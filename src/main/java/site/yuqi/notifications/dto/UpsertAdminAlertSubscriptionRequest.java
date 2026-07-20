package site.yuqi.notifications.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record UpsertAdminAlertSubscriptionRequest(
        @NotBlank @Email String email,
        Boolean enabled) {
}
