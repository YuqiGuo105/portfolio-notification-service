package site.yuqi.notifications.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminNotificationItem(
        UUID id,
        String topic,
        String title,
        String body,
        String url,
        OffsetDateTime createdAt,
        long recipientCount,
        long sentCount,
        long failedCount,
        long pendingCount
) {}
