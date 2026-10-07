package site.yuqi.notifications.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import nl.martijndwars.webpush.Utils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import site.yuqi.notifications.exception.UnauthorizedException;
import site.yuqi.notifications.repository.WebPushRepository;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WebPushService {
    private final SubscriptionService subscriptions;
    private final WebPushRepository repository;
    private final WebPushTransport transport;
    private final ObjectMapper mapper;
    public Map<String, Object> config() { return Map.of("enabled", transport.enabled(), "publicKey", transport.publicKey()); }
    public UUID register(UUID subscriber, String token, String endpoint, String p256dh, String auth) {
        if (!"ACTIVE".equals(subscriptions.verify(subscriber, token).status())) throw new UnauthorizedException("subscriber not active");
        if (!repository.hasWebPreference(subscriber)) throw new IllegalArgumentException("Select website notifications in your subscription preferences first");
        if (!transport.enabled()) throw new IllegalArgumentException("Browser push is not configured");
        WebPushTransport.requireEndpoint(endpoint);
        try {
            byte[] key = Base64.getUrlDecoder().decode(p256dh);
            if (key.length != 65 || key[0] != 4 || Base64.getUrlDecoder().decode(auth).length != 16) throw new Exception();
            Utils.loadPublicKey(p256dh);
        } catch (Exception e) { throw new IllegalArgumentException("Invalid push encryption keys"); }
        return repository.register(subscriber, endpoint, p256dh, auth);
    }
    public boolean remove(UUID subscriber, String token, String endpoint) {
        subscriptions.verify(subscriber, token);
        return repository.remove(subscriber, endpoint);
    }
    public boolean active(UUID subscriber, String token, String endpoint) {
        return "ACTIVE".equals(subscriptions.verify(subscriber, token).status())
                && repository.hasWebPreference(subscriber) && repository.active(subscriber, endpoint);
    }
    @Scheduled(fixedDelayString = "${portfolio.push.interval-ms:30000}", initialDelayString = "${portfolio.push.initial-delay-ms:30000}")
    public void dispatchDue() {
        dispatchOnce(20);
    }
    public int dispatchOnce(int limit) {
        if (!transport.enabled()) return 0;
        int attempted = 0;
        for (UUID id : repository.due().stream().limit(Math.max(0, Math.min(limit, 20))).toList()) {
            var d = repository.claim(id);
            if (d == null) continue;
            if (!d.eligible()) { repository.finish(d, "SKIPPED", null); continue; }
            attempted++;
            try {
                // Public previews only; no subscriber identifiers or private admin alerts.
                byte[] payload = mapper.writeValueAsBytes(Map.of("title", shorten(d.title(), 100), "body", shorten(d.body(), 180),
                        "url", d.url() == null ? "/" : d.url(), "tag", d.notificationId().toString()));
                int status = transport.send(d.endpoint(), d.p256dh(), d.auth(), payload);
                if (status == 404 || status == 410) repository.expire(d);
                String outcome = status >= 200 && status < 300 ? "SENT"
                        : status == 404 || status == 410 ? "SKIPPED"
                        : (status == 429 || status >= 500) && d.attempt() < 5 ? "RETRY" : "FAILED";
                repository.finish(d, outcome, status);
            } catch (Exception e) {
                // A timeout may follow acceptance; uncertain deliveries are not blindly resent.
                repository.finish(d, "UNKNOWN", null);
                if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); return attempted; }
            }
        }
        return attempted;
    }
    private static String shorten(String value, int max) { return value == null ? "" : value.substring(0, Math.min(max, value.length())); }
}
