package site.yuqi.notifications.dto;

import java.util.List;
import java.util.UUID;

public record SubscribeResponse(
        UUID subscriberId,
        String subscriberToken,
        String unsubscribeToken,
        List<String> topics,
        List<String> channels
) {}
