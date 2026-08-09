package site.yuqi.notifications.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import site.yuqi.notifications.dto.AdminNotificationItem;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class NotificationRepository {

    private final JdbcTemplate jdbc;

    public UUID insert(UUID eventAuditId, String topic, String title, String body, String url,
                       String traceId, String correlationId, String causationId,
                       String sourceType, String sourceId, Integer sourceVersion) {
        if (!isPostgres()) {
            List<UUID> existing = jdbc.query(
                    "select id from public.notifications where event_audit_id = ?",
                    (rs, rowNum) -> (UUID) rs.getObject(1), eventAuditId);
            if (!existing.isEmpty()) return existing.getFirst();
            UUID id = UUID.randomUUID();
            jdbc.update(
                    "insert into public.notifications (id, event_audit_id, topic, title, body, url, " +
                            "trace_id, correlation_id, causation_id, source_type, source_id, source_version) " +
                            "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, eventAuditId, topic, title, body, url, traceId, correlationId, causationId,
                    sourceType, sourceId, sourceVersion);
            return id;
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into public.notifications (id, event_audit_id, topic, title, body, url,
                                                   trace_id, correlation_id, causation_id,
                                                   source_type, source_id, source_version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (event_audit_id) where event_audit_id is not null
                do update set event_audit_id = excluded.event_audit_id,
                              trace_id = excluded.trace_id,
                              correlation_id = excluded.correlation_id,
                              causation_id = excluded.causation_id
                """,
                id, eventAuditId, topic, title, body, url, traceId, correlationId, causationId,
                sourceType, sourceId, sourceVersion);
        return jdbc.queryForObject(
                "select id from public.notifications where event_audit_id = ?",
                (rs, rowNum) -> (UUID) rs.getObject(1), eventAuditId);
    }

    private boolean isPostgres() {
        Boolean result = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql"));
        return Boolean.TRUE.equals(result);
    }

    public List<AdminNotificationItem> listForAdmin(int limit, int offset) {
        return jdbc.query("""
                        select n.id, n.topic, n.title, n.body, n.url, n.created_at,
                               count(r.id) as recipient_count,
                               coalesce(sum(case when r.status in ('SENT', 'READ') then 1 else 0 end), 0) as sent_count,
                               coalesce(sum(case when r.status = 'FAILED' then 1 else 0 end), 0) as failed_count,
                               coalesce(sum(case when r.status = 'PENDING' then 1 else 0 end), 0) as pending_count
                          from public.notifications n
                          left join public.notification_recipients r on r.notification_id = n.id
                         group by n.id, n.topic, n.title, n.body, n.url, n.created_at
                         order by n.created_at desc
                         limit ? offset ?
                        """,
                (rs, rowNum) -> new AdminNotificationItem(
                        (UUID) rs.getObject("id"),
                        rs.getString("topic"),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("url"),
                        toOdt(rs.getTimestamp("created_at")),
                        rs.getLong("recipient_count"),
                        rs.getLong("sent_count"),
                        rs.getLong("failed_count"),
                        rs.getLong("pending_count")),
                limit, offset);
    }

    public long countForAdmin() {
        Long count = jdbc.queryForObject("select count(*) from public.notifications", Long.class);
        return count == null ? 0 : count;
    }

    private static OffsetDateTime toOdt(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }
}
