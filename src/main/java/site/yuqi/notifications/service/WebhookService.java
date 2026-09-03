package site.yuqi.notifications.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import site.yuqi.notifications.dto.WebhookSubscriptionItem;
import site.yuqi.notifications.dto.WebhookSubscriptionRequest;
import site.yuqi.notifications.repository.WebhookRepository;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WebhookService {
    private static final Set<String> ALLOWED_EVENTS = Set.of(
            "PUBLICATION_COMPLETED", "PUBLICATION_FAILED", "ANALYTICS_ALERT_TRIGGERED", "COMMENT_CREATED");
    private final WebhookRepository repository;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Value("${portfolio.webhooks.signing-key:${portfolio.token.pepper}}")
    private String signingKey;

    public WebhookSubscriptionItem create(WebhookSubscriptionRequest request) {
        URI uri = requirePublicHttps(request.callbackUrl());
        Set<String> eventTypes = request.eventTypes().stream()
                .map(v -> v.trim().toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        if (eventTypes.isEmpty() || !ALLOWED_EVENTS.containsAll(eventTypes)) {
            throw new IllegalArgumentException("eventTypes must use: " + ALLOWED_EVENTS);
        }
        UUID id = UUID.randomUUID();
        repository.create(id, uri.toString(), eventTypes, request.description());
        return new WebhookSubscriptionItem(id, uri.toString(), eventTypes, request.description(), "ACTIVE",
                OffsetDateTime.now(ZoneOffset.UTC), secretFor(id));
    }

    public List<WebhookSubscriptionItem> list() {
        return repository.list();
    }

    public void delete(UUID id) {
        if (!repository.delete(id)) throw new IllegalArgumentException("Webhook subscription not found: " + id);
    }

    public void enqueue(String eventId, String eventType, String payload) {
        repository.enqueue(eventId, eventType, payload);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueuePublicationFailure(String eventId, String sourceId, String error) {
        String payload = "{\"eventId\":\"%s\",\"eventType\":\"PUBLICATION_FAILED\",\"sourceId\":\"%s\",\"error\":\"%s\"}"
                .formatted(json(eventId), json(sourceId), json(error));
        repository.enqueue(eventId, "PUBLICATION_FAILED", payload);
    }

    public int dispatchDue() {
        int sent = 0;
        for (WebhookRepository.Delivery delivery : repository.claimDue(10)) {
            try {
                URI uri = requirePublicHttps(delivery.callbackUrl());
                long timestamp = System.currentTimeMillis() / 1000;
                String signature = sign(delivery.subscriptionId(), timestamp + "." + delivery.payload());
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .header("X-Yuqi-Event", delivery.eventType())
                        .header("X-Yuqi-Delivery", delivery.id().toString())
                        .header("X-Yuqi-Timestamp", String.valueOf(timestamp))
                        .header("X-Yuqi-Signature", "v1=" + signature)
                        .POST(HttpRequest.BodyPublishers.ofString(delivery.payload()))
                        .build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    repository.sent(delivery.id(), response.statusCode());
                    sent++;
                } else {
                    repository.failed(delivery.id(), delivery.attempt(), response.statusCode(), "HTTP " + response.statusCode());
                }
            } catch (Exception e) {
                repository.failed(delivery.id(), delivery.attempt(), null, e.getMessage());
            }
        }
        return sent;
    }

    private URI requirePublicHttps(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("callbackUrl must be a public HTTPS URL without credentials or fragment");
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("callbackUrl must not resolve to a private network");
                }
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("callbackUrl could not be resolved", e);
        }
    }

    private String secretFor(UUID id) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest((signingKey + ":" + id).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String sign(UUID id, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretFor(id).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
