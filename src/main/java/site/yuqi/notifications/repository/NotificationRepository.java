package site.yuqi.notifications.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class NotificationRepository {

    private final JdbcTemplate jdbc;

    @Autowired
    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID eventAuditId, String topic, String title, String body, String url) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into public.notifications (id, event_audit_id, topic, title, body, url) " +
                        "values (?, ?, ?, ?, ?, ?)",
                id, eventAuditId, topic, title, body, url);
        return id;
    }
}
