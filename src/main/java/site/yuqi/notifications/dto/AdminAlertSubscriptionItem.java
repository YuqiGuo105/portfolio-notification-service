package site.yuqi.notifications.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminAlertSubscriptionItem(
        UUID subscriberId,
        String email,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
