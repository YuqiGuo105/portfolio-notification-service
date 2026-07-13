package site.yuqi.notifications.dto;

import java.util.List;

public record AdminNotificationListResponse(
        List<AdminNotificationItem> items,
        long total,
        int limit,
        int offset
) {}
