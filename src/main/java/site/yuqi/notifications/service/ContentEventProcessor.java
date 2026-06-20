package site.yuqi.notifications.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.yuqi.notifications.domain.SubscriptionPreference;
import site.yuqi.notifications.dto.ContentEvent;
import site.yuqi.notifications.repository.ContentEventAuditRepository;
import site.yuqi.notifications.repository.ContentEventAuditRepository.AuditSummary;
import site.yuqi.notifications.repository.NotificationRecipientRepository;
import site.yuqi.notifications.repository.NotificationRepository;
import site.yuqi.notifications.repository.SubscriptionPreferenceRepository;

import java.util.List;
import java.util.UUID;

@Service
public class ContentEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(ContentEventProcessor.class);

    private final ContentEventAuditRepository auditRepo;
    private final SubscriptionPreferenceRepository prefRepo;
    private final NotificationRepository notificationRepo;
    private final NotificationRecipientRepository recipientRepo;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @Autowired
    public ContentEventProcessor(ContentEventAuditRepository auditRepo,
                                 SubscriptionPreferenceRepository prefRepo,
                                 NotificationRepository notificationRepo,
                                 NotificationRecipientRepository recipientRepo,
                                 ObjectMapper objectMapper,
                                 @Value("${portfolio.base-url:https://www.yuqi.site}") String baseUrl) {
        this.auditRepo = auditRepo;
        this.prefRepo = prefRepo;
        this.notificationRepo = notificationRepo;
        this.recipientRepo = recipientRepo;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    public enum Outcome {
        /** Event was processed successfully (or already DONE). Commit offset. */
        DONE,
        /** Event is permanently invalid. Send to DLQ and commit offset. */
        DLQ,
        /** Transient failure (DB unavailable, etc). Do NOT commit offset; let Kafka redeliver. */
        RETRY
    }

    /**
     * Process a single Kafka payload (raw JSON). The Kafka listener uses the return value
     * to decide whether to commit the offset.
     */
    @Transactional
    public Outcome process(String rawJson, String kafkaTopic, Integer partition, String offset) {
        if (rawJson == null || rawJson.isBlank()) {
            log.warn("{\"event\":\"empty_payload\",\"kafkaTopic\":\"{}\",\"offset\":\"{}\"}", kafkaTopic, offset);
            return Outcome.DLQ;
        }

        ContentEvent event;
        try {
            event = objectMapper.readValue(rawJson, ContentEvent.class);
        } catch (JsonProcessingException e) {
            log.warn("{\"event\":\"parse_failed\",\"kafkaTopic\":\"{}\",\"offset\":\"{}\",\"err\":\"{}\"}",
                    kafkaTopic, offset, e.getOriginalMessage());
            return Outcome.DLQ;
        }

        if (!event.isValid()) {
            log.warn("{\"event\":\"invalid_event\",\"kafkaTopic\":\"{}\",\"offset\":\"{}\",\"eventId\":\"{}\"}",
                    kafkaTopic, offset, event.eventId());
            return Outcome.DLQ;
        }

        // Idempotency check
        AuditSummary existing = auditRepo.findByIdempotencyKey(event.idempotencyKey()).orElse(null);
        if (existing != null && "DONE".equals(existing.status())) {
            log.info("{\"event\":\"skip_already_done\",\"idempotencyKey\":\"{}\"}", event.idempotencyKey());
            return Outcome.DONE;
        }

        UUID auditId;
        try {
            auditId = (existing != null)
                    ? existing.id()
                    : auditRepo.insertProcessing(
                            kafkaTopic, partition, offset,
                            event.eventId(), event.eventType(), event.topic(),
                            event.sourceType(), event.sourceId(),
                            event.title(), event.summary(), event.url(),
                            rawJson, event.idempotencyKey());
        } catch (Exception dbErr) {
            log.error("{\"event\":\"audit_insert_failed\",\"idempotencyKey\":\"{}\",\"err\":\"{}\"}",
                    event.idempotencyKey(), dbErr.getMessage());
            return Outcome.RETRY;
        }

        try {
            fanOut(event, auditId);
            auditRepo.markDone(auditId);
            log.info("{\"event\":\"processed\",\"idempotencyKey\":\"{}\",\"auditId\":\"{}\"}",
                    event.idempotencyKey(), auditId);
            return Outcome.DONE;
        } catch (Exception fanErr) {
            log.error("{\"event\":\"fanout_failed\",\"auditId\":\"{}\",\"err\":\"{}\"}",
                    auditId, fanErr.getMessage());
            auditRepo.markFailed(auditId, fanErr.getMessage());
            return Outcome.RETRY;
        }
    }

    private void fanOut(ContentEvent event, UUID auditId) {
        List<SubscriptionPreference> prefs = prefRepo.findActiveForTopic(event.topic());
        if (prefs.isEmpty()) {
            log.info("{\"event\":\"no_subscribers\",\"topic\":\"{}\"}", event.topic());
            return;
        }

        String absoluteUrl = toAbsoluteUrl(event.url());
        UUID notificationId = notificationRepo.insert(
                auditId, event.topic(), event.title(), event.summary(), absoluteUrl);

        int web = 0, em = 0, dupes = 0;
        for (SubscriptionPreference p : prefs) {
            if (p.webEnabled()) {
                String key = notificationId + ":" + p.subscriberId() + ":WEB";
                if (recipientRepo.insertIfAbsent(notificationId, p.subscriberId(), "WEB", key)) web++; else dupes++;
            }
            if (p.emailEnabled()) {
                String key = notificationId + ":" + p.subscriberId() + ":EMAIL";
                if (recipientRepo.insertIfAbsent(notificationId, p.subscriberId(), "EMAIL", key)) em++; else dupes++;
            }
        }
        log.info("{\"event\":\"fanout_done\",\"notificationId\":\"{}\",\"web\":{},\"email\":{},\"dupes\":{}}",
                notificationId, web, em, dupes);
    }

    private String toAbsoluteUrl(String url) {
        if (url == null || url.isBlank()) return null;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        return base + (url.startsWith("/") ? url : "/" + url);
    }
}
