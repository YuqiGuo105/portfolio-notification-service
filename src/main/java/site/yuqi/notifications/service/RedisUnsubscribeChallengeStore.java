package site.yuqi.notifications.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RedisUnsubscribeChallengeStore implements UnsubscribeChallengeStore {

    private static final String CHALLENGE_PREFIX = "notification:unsubscribe:challenge:";
    private static final String RATE_PREFIX = "notification:unsubscribe:rate:";

    private static final DefaultRedisScript<Long> VERIFY_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
            if redis.call('HGET', KEYS[1], 'consumed') == '1' then return -2 end
            local attempts = tonumber(redis.call('HGET', KEYS[1], 'attempts') or '0')
            local maxAttempts = tonumber(redis.call('HGET', KEYS[1], 'maxAttempts') or '1')
            if attempts >= maxAttempts then return -3 end
            if redis.call('HGET', KEYS[1], 'codeHash') ~= ARGV[1] then
              attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
              if attempts >= maxAttempts then return -3 end
              return 0
            end
            redis.call('HSET', KEYS[1], 'consumed', '1')
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RATE_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            if current > tonumber(ARGV[2]) then return 0 end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    @Override
    public void save(String verificationId, UUID subscriberId, String emailHash,
                     String codeHash, int maxAttempts, Duration ttl) {
        String key = challengeKey(verificationId);
        redis.opsForHash().putAll(key, Map.of(
                "subscriberId", subscriberId.toString(),
                "emailHash", emailHash,
                "codeHash", codeHash,
                "attempts", "0",
                "maxAttempts", String.valueOf(maxAttempts),
                "consumed", "0"));
        redis.expire(key, ttl);
    }

    @Override
    public Optional<UUID> findSubscriberId(String verificationId) {
        Object value = redis.opsForHash().get(challengeKey(verificationId), "subscriberId");
        if (value == null) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(String.valueOf(value)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public VerificationResult verifyAndConsume(String verificationId, String codeHash) {
        Long value = redis.execute(VERIFY_SCRIPT, java.util.List.of(challengeKey(verificationId)), codeHash);
        if (value == null || value == -1) return VerificationResult.EXPIRED;
        if (value == -2) return VerificationResult.ALREADY_USED;
        if (value == -3) return VerificationResult.LOCKED;
        if (value == 1) return VerificationResult.VERIFIED;
        return VerificationResult.INVALID;
    }

    @Override
    public boolean allowRequest(String emailHash, int limit, Duration window) {
        Long allowed = redis.execute(RATE_SCRIPT, java.util.List.of(RATE_PREFIX + emailHash),
                String.valueOf(window.toSeconds()), String.valueOf(limit));
        return Long.valueOf(1L).equals(allowed);
    }

    @Override
    public void delete(String verificationId) {
        redis.delete(challengeKey(verificationId));
    }

    private String challengeKey(String verificationId) {
        return CHALLENGE_PREFIX + verificationId;
    }
}
