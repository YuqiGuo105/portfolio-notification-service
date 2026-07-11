package site.yuqi.notifications.dto;

public record UnsubscribeVerificationResponse(
        String verificationId,
        String destination,
        long expiresInSeconds,
        String message
) {}
