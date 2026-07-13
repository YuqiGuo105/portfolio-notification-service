package site.yuqi.notifications.dto;

import java.util.List;

public record AdminSubscriberListResponse(
        List<AdminSubscriberItem> items,
        long total,
        int limit,
        int offset
) {}
