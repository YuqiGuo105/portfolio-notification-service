package site.yuqi.notifications.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ConfirmUnsubscribeRequest(
        @NotBlank String verificationId,
        @NotBlank @Pattern(regexp = "\\d{6}") String verificationCode
) {}
