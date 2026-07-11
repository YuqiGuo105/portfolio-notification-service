package site.yuqi.notifications.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record UnsubscribeVerificationRequest(
        @Email @NotBlank String email
) {}
