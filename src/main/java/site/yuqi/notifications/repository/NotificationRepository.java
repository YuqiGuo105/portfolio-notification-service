package site.yuqi.notifications.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
}
