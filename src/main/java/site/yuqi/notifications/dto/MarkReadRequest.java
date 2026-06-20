package site.yuqi.notifications.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record MarkReadRequest(
        @NotNull UUID subscriberId,
        @NotBlank String subscriberToken
) {}
