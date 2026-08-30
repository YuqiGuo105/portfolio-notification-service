package site.yuqi.notifications.dto;

import java.util.List;

public record AdminDeliveryListResponse(
        List<AdminDeliveryItem> items,
        long total,
        int limit) {
}
