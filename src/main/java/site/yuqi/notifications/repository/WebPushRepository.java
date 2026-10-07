package site.yuqi.notifications.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class WebPushRepository {
    private final JdbcTemplate jdbc;

    public boolean hasWebPreference(UUID subscriber) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from subscription_preferences where subscriber_id=? and web_enabled=true)", Boolean.class, subscriber));
    }

    @Transactional
    public UUID register(UUID subscriber, String endpoint, String key, String auth) {
        // Lock the owner so concurrent registrations cannot bypass the device limit.
        jdbc.queryForObject("select id from subscribers where id=? for update", UUID.class, subscriber);
        List<UUID> existing = jdbc.query("select id from browser_push_subscriptions where endpoint=? and subscriber_id=?",
                (rs, n) -> rs.getObject(1, UUID.class), endpoint, subscriber);
        if (!existing.isEmpty()) {
            jdbc.update("update browser_push_subscriptions set p256dh=?,auth=?,active=true where id=?", key, auth, existing.get(0));
            return existing.get(0);
        }
        Integer count = jdbc.queryForObject("select count(*) from browser_push_subscriptions where subscriber_id=?", Integer.class, subscriber);
        if (count != null && count >= 5) throw new IllegalArgumentException("Maximum five browsers; remove an old browser first");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into browser_push_subscriptions(id,subscriber_id,endpoint,p256dh,auth) values (?,?,?,?,?)", id, subscriber, endpoint, key, auth);
        return id;
    }
    public boolean remove(UUID subscriber, String endpoint) {
        return jdbc.update("delete from browser_push_subscriptions where subscriber_id=? and endpoint=?", subscriber, endpoint) > 0;
    }
    public boolean active(UUID subscriber, String endpoint) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from browser_push_subscriptions where subscriber_id=? and endpoint=? and active=true)", Boolean.class, subscriber, endpoint));
    }
    public void enqueue(UUID notification, UUID subscriber) {
        List<UUID> devices = jdbc.query("select id from browser_push_subscriptions where subscriber_id=? and active=true",
                (rs, n) -> rs.getObject(1, UUID.class), subscriber);
        for (UUID device : devices) {
            jdbc.update("insert into browser_push_deliveries(id,device_id,notification_id) select ?,?,? where not exists " +
                    "(select 1 from browser_push_deliveries where device_id=? and notification_id=?)",
                    UUID.randomUUID(), device, notification, device, notification);
        }
    }
    public List<UUID> due() {
        jdbc.update("update browser_push_deliveries set status='UNKNOWN' where status='SENDING' and available_at<current_timestamp");
        return jdbc.query("select id from browser_push_deliveries where status in ('PENDING','RETRY') and available_at<=current_timestamp " +
                "order by available_at limit 20", (rs, n) -> rs.getObject(1, UUID.class));
    }
    public Delivery claim(UUID id) {
        UUID claim = UUID.randomUUID();
        if (jdbc.update("update browser_push_deliveries set status='SENDING',claim_token=?,attempt=attempt+1,available_at=? " +
                        "where id=? and status in ('PENDING','RETRY') and available_at<=current_timestamp", claim,
                Timestamp.from(Instant.now().plusSeconds(120)), id) != 1) return null;
        return jdbc.query("""
                select d.id,d.device_id,d.claim_token,d.attempt,s.endpoint,s.p256dh,s.auth,
                       n.id as notification_id,n.title,n.body,n.url,
                       (s.active=true and u.status='ACTIVE' and coalesce(p.web_enabled,false)=true) as eligible
                from browser_push_deliveries d join browser_push_subscriptions s on s.id=d.device_id
                join subscribers u on u.id=s.subscriber_id join notifications n on n.id=d.notification_id
                left join subscription_preferences p on p.subscriber_id=s.subscriber_id and p.topic=n.topic
                where d.id=? and d.claim_token=?
                """, (rs, n) -> new Delivery(rs.getObject("id", UUID.class), rs.getObject("device_id", UUID.class),
                rs.getObject("claim_token", UUID.class), rs.getInt("attempt"), rs.getString("endpoint"), rs.getString("p256dh"),
                rs.getString("auth"), rs.getObject("notification_id", UUID.class), rs.getString("title"), rs.getString("body"),
                rs.getString("url"), rs.getBoolean("eligible")), id, claim).stream().findFirst().orElse(null);
    }
    public void finish(Delivery d, String status, Integer responseCode) {
        jdbc.update("update browser_push_deliveries set status=?,response_code=?,sent_at=?,available_at=? where id=? and claim_token=? and status='SENDING'",
                status, responseCode, "SENT".equals(status) ? Timestamp.from(Instant.now()) : null,
                Timestamp.from(Instant.now().plusSeconds(Math.min(3600, 60L << Math.min(d.attempt(), 5)))), d.id(), d.claim());
    }
    public void expire(Delivery d) { jdbc.update("update browser_push_subscriptions set active=false where id=?", d.deviceId()); }
    public record Delivery(UUID id, UUID deviceId, UUID claim, int attempt, String endpoint, String p256dh, String auth,
                           UUID notificationId, String title, String body, String url, boolean eligible) {
        @Override public String toString() { return "PushDelivery[" + id + "]"; }
    }
}
