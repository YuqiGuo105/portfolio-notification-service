package site.yuqi.notifications.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.yuqi.notifications.domain.NotificationRecipientRow;
import site.yuqi.notifications.domain.Subscriber;
import site.yuqi.notifications.dto.NotificationListResponse;
import site.yuqi.notifications.exception.UnauthorizedException;
import site.yuqi.notifications.repository.NotificationRecipientRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int DEFAULT_LIMIT = 50;

    private final SubscriptionService subscriptionService;
    private final NotificationRecipientRepository recipientRepo;

    public NotificationListResponse listForSubscriber(UUID subscriberId, String token, boolean unreadOnly) {
        Subscriber subscriber = subscriptionService.verify(subscriberId, token);
        List<NotificationRecipientRow> rows =
                recipientRepo.listWebForSubscriber(subscriber.id(), unreadOnly, DEFAULT_LIMIT);

        List<NotificationListResponse.Item> items = new ArrayList<>(rows.size());
        int unread = 0;
        for (NotificationRecipientRow r : rows) {
            if (!"READ".equals(r.status()) && !"SKIPPED".equals(r.status())) unread++;
            items.add(new NotificationListResponse.Item(
                    r.id(),
                    r.notificationId(),
                    r.notificationTopic(),
                    r.notificationTitle(),
                    r.notificationBody(),
                    r.notificationUrl(),
                    r.status(),
                    r.createdAt(),
                    r.readAt()
            ));
        }
        return new NotificationListResponse(unread, items);
    }

    @Transactional
    public boolean markRead(UUID recipientId, UUID subscriberId, String token) {
        Subscriber subscriber = subscriptionService.verify(subscriberId, token);
        if (recipientId == null) throw new UnauthorizedException("missing notification id");
        int rows = recipientRepo.markRead(recipientId, subscriber.id());
        return rows > 0;
    }
}
