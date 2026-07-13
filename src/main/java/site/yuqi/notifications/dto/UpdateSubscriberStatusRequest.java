package site.yuqi.notifications.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateSubscriberStatusRequest(@NotBlank String status) {}
