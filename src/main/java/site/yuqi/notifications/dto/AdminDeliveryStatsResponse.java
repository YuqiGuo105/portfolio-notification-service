package site.yuqi.notifications.dto;

import java.time.OffsetDateTime;

public record AdminDeliveryStatsResponse(
        String window,
        String channel,
        OffsetDateTime from,
        OffsetDateTime to,
        long total,
        long pending,
        long sent,
        long failed,
        long read,
        long skipped) {
}
