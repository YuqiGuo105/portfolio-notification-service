package site.yuqi.notifications.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminDeliveryItem(
        UUID recipientId,
        UUID notificationId,
        String channel,
        String status,
        int retryCount,
        OffsetDateTime nextRetryAt,
        OffsetDateTime sentAt,
        String lastError,
        String topic,
        String title,
        String url,
        OffsetDateTime createdAt) {
}
