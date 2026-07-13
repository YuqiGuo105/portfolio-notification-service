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

    public UUID insert(UUID eventAuditId, String topic, String title, String body, String url) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into public.notifications (id, event_audit_id, topic, title, body, url) " +
                        "values (?, ?, ?, ?, ?, ?)",
                id, eventAuditId, topic, title, body, url);
        return id;
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
