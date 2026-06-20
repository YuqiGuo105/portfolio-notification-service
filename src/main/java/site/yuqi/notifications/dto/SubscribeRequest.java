package site.yuqi.notifications.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record SubscribeRequest(
        @Email @NotBlank String email,
        @NotEmpty List<String> topics,
        @NotEmpty List<String> channels
) {}
