package site.yuqi.notifications.dto;

import jakarta.validation.constraints.NotBlank;

public record UnsubscribeRequest(
        @NotBlank String token
) {}
