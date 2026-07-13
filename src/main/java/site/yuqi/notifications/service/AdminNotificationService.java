package site.yuqi.notifications.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.yuqi.notifications.dto.AdminNotificationListResponse;
import site.yuqi.notifications.dto.AdminSubscriberItem;
import site.yuqi.notifications.dto.AdminSubscriberListResponse;
import site.yuqi.notifications.exception.NotFoundException;
import site.yuqi.notifications.repository.NotificationRecipientRepository;
import site.yuqi.notifications.repository.NotificationRepository;
import site.yuqi.notifications.repository.SubscriberRepository;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminNotificationService {

    private static final Set<String> SUBSCRIBER_STATUSES =
            Set.of("ACTIVE", "UNSUBSCRIBED", "BOUNCED");

    private final SubscriberRepository subscriberRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationRecipientRepository recipientRepository;

    public AdminSubscriberListResponse listSubscribers(
            String status, String query, int limit, int offset) {
        String normalizedStatus = normalizeOptionalStatus(status);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, offset);
        return new AdminSubscriberListResponse(
                subscriberRepository.listForAdmin(normalizedStatus, normalizeQuery(query), safeLimit, safeOffset),
                subscriberRepository.countForAdmin(normalizedStatus, normalizeQuery(query)),
                safeLimit,
                safeOffset);
    }

    @Transactional
    public AdminSubscriberItem updateSubscriberStatus(UUID id, String status) {
        String normalizedStatus = requireStatus(status);
        if (subscriberRepository.updateStatusForAdmin(id, normalizedStatus) == 0) {
            throw new NotFoundException("subscriber not found");
        }
        if ("UNSUBSCRIBED".equals(normalizedStatus)) {
            recipientRepository.markPendingEmailSkippedForSubscriber(id);
        }
        return subscriberRepository.findAdminItem(id)
                .orElseThrow(() -> new NotFoundException("subscriber not found"));
    }

    public AdminNotificationListResponse listNotifications(int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, offset);
        return new AdminNotificationListResponse(
                notificationRepository.listForAdmin(safeLimit, safeOffset),
                notificationRepository.countForAdmin(),
                safeLimit,
                safeOffset);
    }

    private static String normalizeOptionalStatus(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) return null;
        return requireStatus(value);
    }

    private static String requireStatus(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!SUBSCRIBER_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported subscriber status");
        }
        return normalized;
    }

    private static String normalizeQuery(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
