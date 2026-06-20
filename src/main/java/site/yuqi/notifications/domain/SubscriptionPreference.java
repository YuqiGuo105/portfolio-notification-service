package site.yuqi.notifications.domain;

import java.util.UUID;

public record SubscriptionPreference(
        UUID subscriberId,
        String topic,
        boolean emailEnabled,
        boolean webEnabled
) {}
