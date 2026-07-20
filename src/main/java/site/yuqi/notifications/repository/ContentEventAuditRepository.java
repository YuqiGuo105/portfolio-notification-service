package site.yuqi.notifications.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ContentEventAuditRepository {

    private final JdbcTemplate jdbc;

    public static record AuditSummary(UUID id, String status, int retryCount) {}

    public Optional<AuditSummary> findByIdempotencyKey(String key) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "select id, status, retry_count from public.content_event_audit " +
                            " where idempotency_key = ?",
                    (rs, n) -> new AuditSummary(
                            (UUID) rs.getObject("id"),
                            rs.getString("status"),
                            rs.getInt("retry_count")),
                    key));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Insert a PROCESSING audit row. If the row already exists (race), do nothing
     * and return the existing id.
     */
    public UUID insertProcessing(
            String kafkaTopic, Integer kafkaPartition, String kafkaOffset,
            String eventId, String eventType, String topic,
            String sourceType, String sourceId,
            String title, String summary, String url,
            String payloadJson, String idempotencyKey) {

        UUID id = UUID.randomUUID();
        if (!isPostgres()) {
            Optional<AuditSummary> existing = findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) return existing.get().id();
            jdbc.update(
                    "insert into public.content_event_audit " +
                            "(id, kafka_topic, kafka_partition, kafka_offset, event_id, event_type, topic, " +
                            "source_type, source_id, title, summary, url, payload, status, idempotency_key) " +
                            "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSING', ?)",
                    id, kafkaTopic, kafkaPartition, kafkaOffset, eventId, eventType, topic,
                    sourceType, sourceId, title, summary, url, payloadJson, idempotencyKey);
            return id;
        }
        jdbc.update("""
                insert into public.content_event_audit
                    (id, kafka_topic, kafka_partition, kafka_offset, event_id, event_type, topic,
                     source_type, source_id, title, summary, url, payload, status, idempotency_key)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSING', ?)
                on conflict (idempotency_key) do nothing
                """,
                id, kafkaTopic, kafkaPartition, kafkaOffset,
                eventId, eventType, topic, sourceType, sourceId,
                title, summary, url, payloadJson, idempotencyKey);
        return findByIdempotencyKey(idempotencyKey)
                .map(AuditSummary::id)
                .orElseThrow(() -> new IllegalStateException("idempotency row disappeared"));
    }

    private boolean isPostgres() {
        Boolean result = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql"));
        return Boolean.TRUE.equals(result);
    }

    public void markDone(UUID id) {
        jdbc.update(
                "update public.content_event_audit " +
                        "   set status = 'DONE', processed_at = now(), last_error = null " +
                        " where id = ?",
                id);
    }

    public void markFailed(UUID id, String error) {
        jdbc.update(
                "update public.content_event_audit " +
                        "   set status = 'FAILED', retry_count = retry_count + 1, last_error = ? " +
                        " where id = ?",
                truncate(error, 2000), id);
    }

    public void markDlq(UUID id, String error) {
        jdbc.update(
                "update public.content_event_audit " +
                        "   set status = 'DLQ', last_error = ?, processed_at = now() " +
                        " where id = ?",
                truncate(error, 2000), id);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
