package site.yuqi.notifications.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import site.yuqi.notifications.dto.AdminAlertSubscriptionItem;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AdminAlertSubscriptionRepository {

    private final JdbcTemplate jdbc;

    public List<UUID> findEnabledSubscriberIds() {
        return jdbc.query("""
                select subscriber_id
                  from public.admin_alert_subscriptions
                 where enabled = true
                """, (rs, rowNum) -> (UUID) rs.getObject(1));
    }

    public List<AdminAlertSubscriptionItem> list() {
        return jdbc.query("""
                select a.subscriber_id, s.email, a.enabled, a.created_at, a.updated_at
                  from public.admin_alert_subscriptions a
                  join public.subscribers s on s.id = a.subscriber_id
                 order by lower(s.email)
                """, (rs, rowNum) -> map(rs));
    }

    public AdminAlertSubscriptionItem upsertByEmail(String email, boolean enabled) {
        if (!isPostgres()) {
            List<UUID> subscribers = jdbc.query(
                    "select id from public.subscribers where lower(email) = lower(?)",
                    (rs, rowNum) -> (UUID) rs.getObject(1), email);
            if (subscribers.isEmpty()) return null;
            UUID subscriberId = subscribers.getFirst();
            int updated = jdbc.update(
                    "update public.admin_alert_subscriptions set enabled = ?, updated_at = current_timestamp " +
                            "where subscriber_id = ?", enabled, subscriberId);
            if (updated == 0) {
                jdbc.update("insert into public.admin_alert_subscriptions (subscriber_id, enabled) values (?, ?)",
                        subscriberId, enabled);
            }
            return list().stream().filter(item -> item.subscriberId().equals(subscriberId))
                    .findFirst().orElseThrow();
        }
        List<AdminAlertSubscriptionItem> rows = jdbc.query("""
                insert into public.admin_alert_subscriptions (subscriber_id, enabled)
                select id, ? from public.subscribers where lower(email) = lower(?)
                on conflict (subscriber_id) do update set enabled = excluded.enabled
                returning subscriber_id, enabled, created_at, updated_at
                """, (rs, rowNum) -> new AdminAlertSubscriptionItem(
                        (UUID) rs.getObject("subscriber_id"), email, rs.getBoolean("enabled"),
                        toOdt(rs.getTimestamp("created_at")), toOdt(rs.getTimestamp("updated_at"))),
                enabled, email);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean isPostgres() {
        Boolean result = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql"));
        return Boolean.TRUE.equals(result);
    }

    public boolean isEnabled(UUID subscriberId) {
        Boolean enabled = jdbc.query("""
                select enabled from public.admin_alert_subscriptions where subscriber_id = ?
                """, ps -> ps.setObject(1, subscriberId),
                rs -> rs.next() ? rs.getBoolean(1) : null);
        return Boolean.TRUE.equals(enabled);
    }

    private static AdminAlertSubscriptionItem map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AdminAlertSubscriptionItem(
                (UUID) rs.getObject("subscriber_id"), rs.getString("email"),
                rs.getBoolean("enabled"), toOdt(rs.getTimestamp("created_at")),
                toOdt(rs.getTimestamp("updated_at")));
    }

    private static OffsetDateTime toOdt(Timestamp value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }
}
