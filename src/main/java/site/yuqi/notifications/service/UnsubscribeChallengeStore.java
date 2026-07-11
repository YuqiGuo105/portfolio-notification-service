package site.yuqi.notifications.service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface UnsubscribeChallengeStore {

    void save(String verificationId, UUID subscriberId, String emailHash,
              String codeHash, int maxAttempts, Duration ttl);

    Optional<UUID> findSubscriberId(String verificationId);

    VerificationResult verifyAndConsume(String verificationId, String codeHash);

    boolean allowRequest(String emailHash, int limit, Duration window);

    void delete(String verificationId);

    enum VerificationResult {
        VERIFIED,
        INVALID,
        EXPIRED,
        LOCKED,
        ALREADY_USED
    }
}
