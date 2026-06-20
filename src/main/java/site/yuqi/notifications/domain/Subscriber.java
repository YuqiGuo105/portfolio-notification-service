package site.yuqi.notifications.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Subscriber(
        UUID id,
        String email,
        String status,
        String subscriberTokenHash,
        String unsubscribeTokenHash,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
