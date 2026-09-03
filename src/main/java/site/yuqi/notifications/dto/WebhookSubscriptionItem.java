package site.yuqi.notifications.dto;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

public record WebhookSubscriptionItem(
        UUID id, String callbackUrl, Set<String> eventTypes, String description,
        String status, OffsetDateTime createdAt, String signingSecret) {}
