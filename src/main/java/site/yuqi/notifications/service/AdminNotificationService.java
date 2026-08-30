package site.yuqi.notifications.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.yuqi.notifications.dto.AdminNotificationListResponse;
import site.yuqi.notifications.dto.AdminAlertSubscriptionItem;
import site.yuqi.notifications.dto.AdminSubscriberItem;
import site.yuqi.notifications.dto.AdminSubscriberListResponse;
import site.yuqi.notifications.dto.AdminDeliveryItem;
import site.yuqi.notifications.dto.AdminDeliveryListResponse;
import site.yuqi.notifications.dto.AdminDeliveryStatsResponse;
import site.yuqi.notifications.exception.NotFoundException;
import site.yuqi.notifications.repository.NotificationRecipientRepository;
import site.yuqi.notifications.repository.AdminAlertSubscriptionRepository;
import site.yuqi.notifications.repository.NotificationRepository;
import site.yuqi.notifications.repository.SubscriberRepository;

import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminNotificationService {

    private static final Set<String> SUBSCRIBER_STATUSES =
            Set.of("ACTIVE", "UNSUBSCRIBED", "BOUNCED");
    private static final Set<String> DELIVERY_CHANNELS = Set.of("EMAIL", "WEB");
    private static final Set<String> DELIVERY_STATUSES = Set.of("FAILED", "PENDING");
    private static final Map<String, Integer> DELIVERY_WINDOWS =
            Map.of("1h", 1, "24h", 24, "7d", 24 * 7);

    private final SubscriberRepository subscriberRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationRecipientRepository recipientRepository;
    private final AdminAlertSubscriptionRepository adminAlertSubscriptionRepository;

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

    public AdminDeliveryStatsResponse getDeliveryStats(String window, String channel) {
        String normalizedWindow = normalizeWindow(window);
        String normalizedChannel = normalizeDeliveryChannel(channel);
        OffsetDateTime to = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime from = to.minusHours(DELIVERY_WINDOWS.get(normalizedWindow));
        long[] counts = recipientRepository.deliveryStats(from, normalizedChannel);
        return new AdminDeliveryStatsResponse(
                normalizedWindow, normalizedChannel, from, to,
                counts[0], counts[1], counts[2], counts[3], counts[4], counts[5]);
    }

    public AdminDeliveryListResponse listDeliveries(String channel, String status, int limit) {
        String normalizedChannel = normalizeDeliveryChannel(channel);
        String normalizedStatus = normalizeDeliveryStatus(status);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return new AdminDeliveryListResponse(
                recipientRepository.listForAdmin(normalizedChannel, normalizedStatus, safeLimit),
                recipientRepository.countForAdmin(normalizedChannel, normalizedStatus),
                safeLimit);
    }

    @Transactional
    public AdminDeliveryItem retryFailedDelivery(UUID recipientId) {
        if (recipientRepository.retryFailed(recipientId) == 0) {
            throw new NotFoundException("failed delivery not found");
        }
        return recipientRepository.findAdminItem(recipientId)
                .orElseThrow(() -> new NotFoundException("delivery not found"));
    }

    public List<AdminAlertSubscriptionItem> listAdminAlertSubscriptions() {
        return adminAlertSubscriptionRepository.list();
    }

    @Transactional
    public AdminAlertSubscriptionItem upsertAdminAlertSubscription(String email, Boolean enabled) {
        AdminAlertSubscriptionItem item = adminAlertSubscriptionRepository.upsertByEmail(
                email.trim(), enabled == null || enabled);
        if (item == null) {
            throw new NotFoundException("subscriber email not found");
        }
        return item;
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

    private static String normalizeWindow(String value) {
        String normalized = value == null || value.isBlank()
                ? "24h"
                : value.trim().toLowerCase(Locale.ROOT);
        if (!DELIVERY_WINDOWS.containsKey(normalized)) {
            throw new IllegalArgumentException("Unsupported delivery stats window");
        }
        return normalized;
    }

    private static String normalizeDeliveryChannel(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!DELIVERY_CHANNELS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported delivery channel");
        }
        return normalized;
    }

    private static String normalizeDeliveryStatus(String value) {
        String normalized = value == null || value.isBlank()
                ? "FAILED"
                : value.trim().toUpperCase(Locale.ROOT);
        if (!DELIVERY_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported delivery status");
        }
        return normalized;
    }
}
