package site.yuqi.notifications.service;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import site.yuqi.notifications.domain.NotificationRecipientRow;
import site.yuqi.notifications.repository.NotificationRecipientRepository;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class EmailDispatchService {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatchService.class);

    private final NotificationRecipientRepository recipientRepo;
    private final JdbcTemplate jdbc;
    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final int batchSize;
    private final int maxRetry;

    @Autowired
    public EmailDispatchService(NotificationRecipientRepository recipientRepo,
                                JdbcTemplate jdbc,
                                JavaMailSender mailSender,
                                @Value("${portfolio.email.from:noreply@yuqi.site}") String fromAddress,
                                @Value("${portfolio.email.dispatch.batch-size:20}") int batchSize,
                                @Value("${portfolio.email.dispatch.max-retry:5}") int maxRetry) {
        this.recipientRepo = recipientRepo;
        this.jdbc = jdbc;
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.batchSize = batchSize;
        this.maxRetry = maxRetry;
    }

    /**
     * Run a single dispatch pass. Returns the number of rows attempted.
     */
    public int dispatchOnce() {
        List<NotificationRecipientRow> claimed;
        try {
            claimed = recipientRepo.claimEmailBatch(batchSize, maxRetry);
        } catch (DataAccessException dbErr) {
            log.error("{\"event\":\"claim_failed\",\"err\":\"{}\"}", dbErr.getMessage());
            return 0;
        }
        if (claimed.isEmpty()) return 0;

        log.info("{\"event\":\"dispatch_batch\",\"size\":{}}", claimed.size());

        int attempted = 0;
        for (NotificationRecipientRow row : claimed) {
            attempted++;
            try {
                dispatchOne(row);
            } catch (Exception e) {
                log.error("{\"event\":\"dispatch_unexpected_error\",\"recipientId\":\"{}\",\"err\":\"{}\"}",
                        row.id(), e.getMessage());
                recipientRepo.markFailed(row.id(), e.getMessage(), nextBackoff(row.retryCount()));
            }
        }
        return attempted;
    }

    private void dispatchOne(NotificationRecipientRow row) {
        // 1. Check subscriber status & fetch email
        Map<String, Object> subRow;
        try {
            subRow = jdbc.queryForMap(
                    "select email, status from public.subscribers where id = ?",
                    row.subscriberId());
        } catch (DataAccessException e) {
            recipientRepo.markFailed(row.id(), "subscriber lookup failed: " + e.getMessage(),
                    nextBackoff(row.retryCount()));
            return;
        }

        String status = (String) subRow.get("status");
        String email = (String) subRow.get("email");

        if (!"ACTIVE".equals(status)) {
            recipientRepo.markSkipped(row.id(), "subscriber status=" + status);
            log.info("{\"event\":\"skip_inactive\",\"recipientId\":\"{}\",\"subscriberStatus\":\"{}\"}",
                    row.id(), status);
            return;
        }

        // 2. Send
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(email);
            helper.setSubject("[Yuqi.site] " + safe(row.notificationTitle()));
            helper.setText(buildBody(row), false);
            mailSender.send(mime);

            recipientRepo.markSent(row.id());
            log.info("{\"event\":\"email_sent\",\"recipientId\":\"{}\",\"to\":\"{}\"}", row.id(), email);
        } catch (MailException | jakarta.mail.MessagingException e) {
            int backoff = nextBackoff(row.retryCount());
            recipientRepo.markFailed(row.id(), e.getMessage(), backoff);
            log.warn("{\"event\":\"email_failed\",\"recipientId\":\"{}\",\"backoffSec\":{},\"err\":\"{}\"}",
                    row.id(), backoff, e.getMessage());
        }
    }

    static int nextBackoff(int currentRetryCount) {
        // 60s, 120s, 240s, 480s, 960s (cap)
        int seconds = 60 * (int) Math.pow(2, Math.min(currentRetryCount, 4));
        return Math.min(seconds, 60 * 60);
    }

    private String buildBody(NotificationRecipientRow row) {
        StringBuilder sb = new StringBuilder();
        sb.append(safe(row.notificationTitle())).append("\n\n");
        if (row.notificationBody() != null && !row.notificationBody().isBlank()) {
            sb.append(row.notificationBody()).append("\n\n");
        }
        if (row.notificationUrl() != null && !row.notificationUrl().isBlank()) {
            sb.append("Read more: ").append(row.notificationUrl()).append("\n\n");
        }
        sb.append("—\nYou are receiving this email because you subscribed to ")
                .append(row.notificationTopic()).append(" on yuqi.site.\n");
        return sb.toString();
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
