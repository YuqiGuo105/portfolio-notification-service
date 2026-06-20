package site.yuqi.notifications.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record UpdatePreferencesRequest(
        @NotNull UUID subscriberId,
        @NotBlank String subscriberToken,
        @NotEmpty List<PreferenceItem> preferences
) {
    public record PreferenceItem(
            @NotBlank String topic,
            boolean emailEnabled,
            boolean webEnabled
    ) {}
}
