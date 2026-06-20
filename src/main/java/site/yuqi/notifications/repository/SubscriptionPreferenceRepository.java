package site.yuqi.notifications.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import site.yuqi.notifications.domain.SubscriptionPreference;

import java.util.List;
import java.util.UUID;

@Repository
public class SubscriptionPreferenceRepository {

    private final JdbcTemplate jdbc;

    @Autowired
    public SubscriptionPreferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Upsert (subscriber_id, topic) → flags.
     */
    public void upsert(UUID subscriberId, String topic, boolean emailEnabled, boolean webEnabled) {
        Integer updated = jdbc.update(
                "update public.subscription_preferences " +
                        "   set email_enabled = ?, web_enabled = ? " +
                        " where subscriber_id = ? and topic = ?",
                emailEnabled, webEnabled, subscriberId, topic);
        if (updated == null || updated == 0) {
            jdbc.update(
                    "insert into public.subscription_preferences " +
                            "  (id, subscriber_id, topic, email_enabled, web_enabled) " +
                            "values (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), subscriberId, topic, emailEnabled, webEnabled);
        }
    }

    public List<SubscriptionPreference> listBySubscriber(UUID subscriberId) {
        return jdbc.query(
                "select subscriber_id, topic, email_enabled, web_enabled " +
                        "  from public.subscription_preferences where subscriber_id = ?",
                ps -> ps.setObject(1, subscriberId),
                (rs, n) -> new SubscriptionPreference(
                        (UUID) rs.getObject("subscriber_id"),
                        rs.getString("topic"),
                        rs.getBoolean("email_enabled"),
                        rs.getBoolean("web_enabled")
                ));
    }

    /**
     * Find subscribers (ACTIVE) who care about a topic on at least one channel,
     * returning their preference flags for fan-out.
     */
    public List<SubscriptionPreference> findActiveForTopic(String topic) {
        return jdbc.query(
                "select p.subscriber_id, p.topic, p.email_enabled, p.web_enabled " +
                        "  from public.subscription_preferences p " +
                        "  join public.subscribers s on s.id = p.subscriber_id " +
                        " where p.topic = ? " +
                        "   and s.status = 'ACTIVE' " +
                        "   and (p.email_enabled = true or p.web_enabled = true)",
                ps -> ps.setString(1, topic),
                (rs, n) -> new SubscriptionPreference(
                        (UUID) rs.getObject("subscriber_id"),
                        rs.getString("topic"),
                        rs.getBoolean("email_enabled"),
                        rs.getBoolean("web_enabled")
                ));
    }
}
