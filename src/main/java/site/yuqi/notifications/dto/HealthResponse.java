package site.yuqi.notifications.dto;

import java.util.Map;

public record HealthResponse(
        String status,
        Map<String, Object> details
) {}
