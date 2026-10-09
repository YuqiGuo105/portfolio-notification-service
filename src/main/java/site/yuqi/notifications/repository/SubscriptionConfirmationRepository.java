package site.yuqi.notifications.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class SubscriptionConfirmationRepository {
    private final JdbcTemplate jdbc;

    // One pending request per address. Repeated anonymous requests cannot
    // rotate a valid link or send more than one message every 15 minutes.
    public boolean request(String email, String hash, List<String> topics, List<String> channels) {
        return jdbc.update("""
                insert into public.subscription_confirmations(email, token_hash, topics, channels, expires_at)
                values (?, ?, ?, ?, now() + interval '30 minutes')
                on conflict (email) do update set token_hash = excluded.token_hash,
                    topics = excluded.topics, channels = excluded.channels,
                    requested_at = now(), expires_at = excluded.expires_at, consumed_at = null
                where subscription_confirmations.expires_at <= now()
                   or (subscription_confirmations.consumed_at is not null
                       and subscription_confirmations.requested_at <= now() - interval '15 minutes')
                """, email, hash, String.join(",", topics), String.join(",", channels)) == 1;
    }

    // Called in the same transaction as activation. Replay and concurrent
    // confirmation lose the row lock and cannot mint another management token.
    public Optional<Pending> consume(String hash) {
        return jdbc.query("""
                update public.subscription_confirmations set consumed_at = now()
                where token_hash = ? and consumed_at is null and expires_at > now()
                returning email, topics, channels
                """, (rs, n) -> new Pending(rs.getString("email"),
                List.of(rs.getString("topics").split(",")), List.of(rs.getString("channels").split(","))), hash)
                .stream().findFirst();
    }

    public record Pending(String email, List<String> topics, List<String> channels) {}
}
