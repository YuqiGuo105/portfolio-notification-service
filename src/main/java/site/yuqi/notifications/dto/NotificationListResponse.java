package site.yuqi.notifications.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record NotificationListResponse(
        int unreadCount,
        List<Item> items
) {
    public record Item(
            UUID recipientId,
            UUID notificationId,
            String topic,
            String title,
            String body,
            String url,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime readAt
    ) {}
}
