package site.yuqi.notifications.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SubscribeRequest(
        @Email @NotBlank @Size(max = 254) String email,
        @NotEmpty @Size(max = 3) List<@NotBlank @Size(max = 40) String> topics,
        @NotEmpty @Size(max = 2) List<@NotBlank @Size(max = 16) String> channels
) {}
