package site.yuqi.notifications.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationRecipientRow(
        UUID id,
        UUID notificationId,
        UUID subscriberId,
        String channel,
        String status,
        int retryCount,
        OffsetDateTime nextRetryAt,
        OffsetDateTime sentAt,
        OffsetDateTime readAt,
        String lastError,
        String idempotencyKey,
        // joined notification columns
        String notificationTopic,
        String notificationTitle,
        String notificationBody,
        String notificationUrl,
        OffsetDateTime createdAt
) {}
