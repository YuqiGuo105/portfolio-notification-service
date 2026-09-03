package site.yuqi.notifications.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record WebhookSubscriptionRequest(
        @NotBlank @Size(max = 2048) String callbackUrl,
        @NotEmpty Set<String> eventTypes,
        @Size(max = 255) String description) {}
