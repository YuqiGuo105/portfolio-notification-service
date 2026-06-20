package site.yuqi.notifications.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import site.yuqi.notifications.domain.Subscriber;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class SubscriberRepository {

    private final JdbcTemplate jdbc;

    /**
     * Insert by email; if email exists, update both token hashes and bump status to ACTIVE.
     * Returns the (newly created or existing) subscriber id.
     */
    public UUID upsertByEmail(String email, String subscriberTokenHash, String unsubscribeTokenHash) {
        // Try update first; if no row, insert.
        Integer updated = jdbc.update(
                "update public.subscribers " +
                        "   set subscriber_token_hash = ?, " +
                        "       unsubscribe_token_hash = coalesce(?, unsubscribe_token_hash), " +
                        "       status = 'ACTIVE' " +
                        " where email = ?",
                subscriberTokenHash, unsubscribeTokenHash, email);
        if (updated != null && updated > 0) {
            return jdbc.queryForObject(
                    "select id from public.subscribers where email = ?",
                    (rs, n) -> (UUID) rs.getObject("id"),
                    email);
        }
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into public.subscribers " +
                        "  (id, email, status, subscriber_token_hash, unsubscribe_token_hash) " +
                        "values (?, ?, 'ACTIVE', ?, ?)",
                id, email, subscriberTokenHash, unsubscribeTokenHash);
        return id;
    }

    public Optional<Subscriber> findById(UUID id) {
        return jdbc.query(
                "select id, email, status, subscriber_token_hash, unsubscribe_token_hash, " +
                        "       created_at, updated_at " +
                        "  from public.subscribers where id = ?",
                ps -> ps.setObject(1, id),
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.<Subscriber>empty());
    }

    public Optional<Subscriber> findByUnsubscribeTokenHash(String hash) {
        return jdbc.query(
                "select id, email, status, subscriber_token_hash, unsubscribe_token_hash, " +
                        "       created_at, updated_at " +
                        "  from public.subscribers where unsubscribe_token_hash = ?",
                ps -> ps.setString(1, hash),
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.<Subscriber>empty());
    }

    public void setStatus(UUID id, String status) {
        jdbc.update("update public.subscribers set status = ? where id = ?", status, id);
    }

    private static Subscriber map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Subscriber(
                (UUID) rs.getObject("id"),
                rs.getString("email"),
                rs.getString("status"),
                rs.getString("subscriber_token_hash"),
                rs.getString("unsubscribe_token_hash"),
                toOdt(rs.getTimestamp("created_at")),
                toOdt(rs.getTimestamp("updated_at"))
        );
    }

    private static OffsetDateTime toOdt(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }
}
