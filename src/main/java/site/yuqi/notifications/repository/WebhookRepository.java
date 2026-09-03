package site.yuqi.notifications.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import site.yuqi.notifications.dto.WebhookSubscriptionItem;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class WebhookRepository {
    private final JdbcTemplate jdbc;

    public void create(UUID id, String callbackUrl, Set<String> eventTypes, String description) {
        jdbc.update("insert into public.mcp_webhook_subscriptions " +
                        "(id, callback_url, event_types, description) values (?, ?, ?, ?)",
                id, callbackUrl, String.join(",", eventTypes), description);
    }

    public List<WebhookSubscriptionItem> list() {
        return jdbc.query("select * from public.mcp_webhook_subscriptions where status <> 'DELETED' order by created_at desc",
                (rs, n) -> new WebhookSubscriptionItem((UUID) rs.getObject("id"), rs.getString("callback_url"),
                        split(rs.getString("event_types")), rs.getString("description"), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class), null));
    }

    public boolean delete(UUID id) {
        return jdbc.update("update public.mcp_webhook_subscriptions set status='DELETED', updated_at=now() " +
                "where id=? and status <> 'DELETED'", id) > 0;
    }

    public void enqueue(String eventId, String eventType, String payload) {
        for (WebhookSubscriptionItem item : list()) {
            if (!"ACTIVE".equals(item.status()) || !item.eventTypes().contains(eventType)) continue;
            try {
                jdbc.update("insert into public.mcp_webhook_deliveries " +
                                "(id, subscription_id, event_id, event_type, payload) values (?, ?, ?, ?, ?)",
                        UUID.randomUUID(), item.id(), eventId, eventType, payload);
            } catch (DuplicateKeyException ignored) {
                // Idempotent event/subscription pair.
            }
        }
    }

    public List<Delivery> claimDue(int limit) {
        List<Delivery> due = jdbc.query("select d.id, d.subscription_id, d.event_id, d.event_type, d.payload, " +
                        "d.attempt, s.callback_url from public.mcp_webhook_deliveries d " +
                        "join public.mcp_webhook_subscriptions s on s.id=d.subscription_id " +
                        "where d.status in ('PENDING','FAILED') and d.next_retry_at <= now() and s.status='ACTIVE' " +
                        "order by d.created_at limit ?",
                (rs, n) -> new Delivery((UUID) rs.getObject("id"), (UUID) rs.getObject("subscription_id"),
                        rs.getString("event_id"), rs.getString("event_type"), rs.getString("payload"),
                        rs.getInt("attempt") + 1, rs.getString("callback_url")), Math.max(1, Math.min(limit, 50)));
        return due.stream().filter(d -> jdbc.update(
                "update public.mcp_webhook_deliveries set status='PROCESSING', attempt=? where id=? and status in ('PENDING','FAILED')",
                d.attempt(), d.id()) == 1).toList();
    }

    public void sent(UUID id, int code) {
        jdbc.update("update public.mcp_webhook_deliveries set status='SENT', response_code=?, delivered_at=now(), last_error=null where id=?",
                code, id);
    }

    public void failed(UUID id, int attempt, Integer code, String error) {
        String status = attempt >= 8 ? "DLQ" : "FAILED";
        Instant retry = Instant.now().plusSeconds(Math.min(3600, 15L * (1L << Math.min(attempt, 7))));
        jdbc.update("update public.mcp_webhook_deliveries set status=?, response_code=?, last_error=?, next_retry_at=? where id=?",
                status, code, error == null ? null : error.substring(0, Math.min(2000, error.length())),
                Timestamp.from(retry), id);
    }

    private static Set<String> split(String value) {
        return value == null || value.isBlank() ? Set.of() : new LinkedHashSet<>(Arrays.asList(value.split(",")));
    }

    public record Delivery(UUID id, UUID subscriptionId, String eventId, String eventType,
                           String payload, int attempt, String callbackUrl) {}
}
