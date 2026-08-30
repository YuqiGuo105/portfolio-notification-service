package site.yuqi.notifications.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PublicationDeliveryStatusResponse(
        boolean observed,
        String deliveryStatus,
        boolean complete,
        boolean sentSuccessfully,
        UUID eventAuditId,
        UUID notificationId,
        String eventId,
        String idempotencyKey,
        String traceId,
        String correlationId,
        String sourceType,
        String sourceId,
        String eventStatus,
        int eventRetryCount,
        long emailRecipients,
        long pending,
        long sent,
        long failed,
        long read,
        long skipped,
        OffsetDateTime createdAt,
        OffsetDateTime processedAt) {
}
