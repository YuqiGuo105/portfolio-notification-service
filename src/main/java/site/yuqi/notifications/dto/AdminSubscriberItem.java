package site.yuqi.notifications.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Safe admin projection. Credential hashes are intentionally excluded. */
public record AdminSubscriberItem(
        UUID id,
        String email,
        String status,
        int emailTopicCount,
        int webTopicCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime unsubscribedAt,
        String unsubscribeSource
) {}
