package site.yuqi.notifications.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import site.yuqi.notifications.domain.NotificationRecipientRow;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class NotificationRecipientRepository {

    private final JdbcTemplate jdbc;

    /**
     * Insert recipient row; if idempotency_key already exists, treat as no-op.
     * Returns true if inserted, false if it was a duplicate.
     */
    public boolean insertIfAbsent(UUID notificationId, UUID subscriberId,
                                  String channel, String idempotencyKey) {
        try {
            jdbc.update(
                    "insert into public.notification_recipients " +
                            "  (id, notification_id, subscriber_id, channel, status, idempotency_key, next_retry_at) " +
                            "values (?, ?, ?, ?, 'PENDING', ?, ?)",
                    UUID.randomUUID(), notificationId, subscriberId, channel, idempotencyKey,
                    java.sql.Timestamp.from(java.time.Instant.now()));
            return true;
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            return false;
        }
    }

    /**
     * List WEB notifications for a subscriber.
     * If unreadOnly = true, returns only PENDING and SENT (not READ/SKIPPED/FAILED).
     */
    public List<NotificationRecipientRow> listWebForSubscriber(UUID subscriberId, boolean unreadOnly, int limit) {
        String filter = unreadOnly ? "and r.status in ('PENDING','SENT')" : "";
        String sql = "select r.id, r.notification_id, r.subscriber_id, r.channel, r.status, " +
                "       r.retry_count, r.next_retry_at, r.sent_at, r.read_at, r.last_error, " +
                "       r.idempotency_key, r.created_at, " +
                "       n.topic as n_topic, n.title as n_title, n.body as n_body, n.url as n_url " +
                "  from public.notification_recipients r " +
                "  join public.notifications n on n.id = r.notification_id " +
                " where r.subscriber_id = ? and r.channel = 'WEB' " + filter +
                " order by r.created_at desc limit ?";
        return jdbc.query(sql,
                ps -> { ps.setObject(1, subscriberId); ps.setInt(2, limit); },
                (rs, n) -> map(rs));
    }

    public int markRead(UUID recipientId, UUID subscriberId) {
        Integer rows = jdbc.update(
                "update public.notification_recipients " +
                        "   set status = 'READ', read_at = now() " +
                        " where id = ? and subscriber_id = ? and channel = 'WEB' " +
                        "   and status in ('PENDING','SENT')",
                recipientId, subscriberId);
        return rows == null ? 0 : rows;
    }

    public int markPendingEmailSkippedForSubscriber(UUID subscriberId) {
        Integer rows = jdbc.update(
                "update public.notification_recipients " +
                        "   set status = 'SKIPPED', last_error = 'subscriber unsubscribed' " +
                        " where subscriber_id = ? and channel = 'EMAIL' and status in ('PENDING','FAILED')",
                subscriberId);
        return rows == null ? 0 : rows;
    }

    /**
     * Claim a batch of EMAIL rows for dispatch by flipping PENDING/FAILED → PENDING_CLAIMED-like state.
     * For simplicity we keep status = PENDING but bump next_retry_at far into the future so other
     * workers don't pick them up while we send. Returns the claimed rows joined with notification data.
     *
     * NOTE: For multi-worker safety, in production prefer SELECT ... FOR UPDATE SKIP LOCKED inside
     * a transaction. For a single Cloud Run worker this two-step approach is sufficient.
     */
    public List<NotificationRecipientRow> claimEmailBatch(int batchSize, int maxRetry) {
        // 1) Pick candidate ids
        List<UUID> ids = jdbc.query(
                "select id from public.notification_recipients " +
                        " where channel = 'EMAIL' " +
                        "   and status in ('PENDING','FAILED') " +
                        "   and (next_retry_at is null or next_retry_at <= ?) " +
                        "   and retry_count < ? " +
                        " order by created_at asc " +
                        " limit ?",
                ps -> {
                    ps.setTimestamp(1, java.sql.Timestamp.from(java.time.Instant.now()));
                    ps.setInt(2, maxRetry);
                    ps.setInt(3, batchSize);
                },
                (rs, n) -> (UUID) rs.getObject("id"));

        if (ids.isEmpty()) return Collections.emptyList();

        // 2) Push next_retry_at 10 minutes out so they aren't re-claimed if this worker crashes mid-send
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        java.sql.Timestamp future = java.sql.Timestamp.from(
                java.time.Instant.now().plusSeconds(600));
        Object[] params = new Object[ids.size() + 1];
        params[0] = future;
        for (int i = 0; i < ids.size(); i++) params[i + 1] = ids.get(i);

        jdbc.update(
                "update public.notification_recipients " +
                        "   set next_retry_at = ? " +
                        " where id in (" + placeholders + ")",
                params);

        // 3) Return joined rows
        String sql = "select r.id, r.notification_id, r.subscriber_id, r.channel, r.status, " +
                "       r.retry_count, r.next_retry_at, r.sent_at, r.read_at, r.last_error, " +
                "       r.idempotency_key, r.created_at, " +
                "       n.topic as n_topic, n.title as n_title, n.body as n_body, n.url as n_url " +
                "  from public.notification_recipients r " +
                "  join public.notifications n on n.id = r.notification_id " +
                " where r.id in (" + placeholders + ")";
        return jdbc.query(sql, ps -> {
            for (int i = 0; i < ids.size(); i++) ps.setObject(i + 1, ids.get(i));
        }, (rs, n) -> map(rs));
    }

    public void markSent(UUID id) {
        jdbc.update(
                "update public.notification_recipients " +
                        "   set status = 'SENT', sent_at = now(), last_error = null, next_retry_at = null " +
                        " where id = ?",
                id);
    }

    public void markFailed(UUID id, String error, int backoffSeconds) {
        java.sql.Timestamp next = java.sql.Timestamp.from(
                java.time.Instant.now().plusSeconds(backoffSeconds));
        jdbc.update(
                "update public.notification_recipients " +
                        "   set status = 'FAILED', " +
                        "       retry_count = retry_count + 1, " +
                        "       last_error = ?, " +
                        "       next_retry_at = ? " +
                        " where id = ?",
                truncate(error, 2000), next, id);
    }

    public void markSkipped(UUID id, String reason) {
        jdbc.update(
                "update public.notification_recipients " +
                        "   set status = 'SKIPPED', last_error = ? " +
                        " where id = ?",
                truncate(reason, 2000), id);
    }

    private static NotificationRecipientRow map(ResultSet rs) throws SQLException {
        return new NotificationRecipientRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("notification_id"),
                (UUID) rs.getObject("subscriber_id"),
                rs.getString("channel"),
                rs.getString("status"),
                rs.getInt("retry_count"),
                toOdt(rs.getTimestamp("next_retry_at")),
                toOdt(rs.getTimestamp("sent_at")),
                toOdt(rs.getTimestamp("read_at")),
                rs.getString("last_error"),
                rs.getString("idempotency_key"),
                rs.getString("n_topic"),
                rs.getString("n_title"),
                rs.getString("n_body"),
                rs.getString("n_url"),
                toOdt(rs.getTimestamp("created_at"))
        );
    }

    private static OffsetDateTime toOdt(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
