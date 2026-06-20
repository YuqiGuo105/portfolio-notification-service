package site.yuqi.notifications.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import site.yuqi.notifications.NotificationApplication;
import site.yuqi.notifications.dto.ContentEvent;
import site.yuqi.notifications.dto.NotificationListResponse;
import site.yuqi.notifications.dto.SubscribeRequest;
import site.yuqi.notifications.dto.SubscribeResponse;
import site.yuqi.notifications.dto.UpdatePreferencesRequest;
import site.yuqi.notifications.exception.UnauthorizedException;
import site.yuqi.notifications.service.ContentEventProcessor;
import site.yuqi.notifications.service.ContentEventProcessor.Outcome;
import site.yuqi.notifications.service.NotificationService;
import site.yuqi.notifications.service.SubscriptionService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = NotificationApplication.class)
@ActiveProfiles("test")
@Import(NotificationFlowTest.TestBeans.class)
class NotificationFlowTest {

    @Autowired private SubscriptionService subscriptionService;
    @Autowired private ContentEventProcessor processor;
    @Autowired private NotificationService notificationService;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("delete from public.notification_recipients");
        jdbc.update("delete from public.notifications");
        jdbc.update("delete from public.content_event_audit");
        jdbc.update("delete from public.subscription_preferences");
        jdbc.update("delete from public.subscribers");
    }

    // ---------- Subscribe / verify / preferences ----------

    @Test
    void subscribeReturnsTokensAndPersistsHashes() {
        SubscribeRequest req = new SubscribeRequest(
                "  Alice@Example.COM ",
                List.of("ARTICLE_UPDATES", "FEATURE_UPDATES", "junk"),
                List.of("WEB", "EMAIL"));

        SubscribeResponse resp = subscriptionService.subscribe(req);

        assertNotNull(resp.subscriberId());
        assertEquals(64, resp.subscriberToken().length());
        assertEquals(64, resp.unsubscribeToken().length());
        assertEquals(List.of("ARTICLE_UPDATES", "FEATURE_UPDATES"), resp.topics());

        Map<String, Object> row = jdbc.queryForMap(
                "select email, status, subscriber_token_hash from public.subscribers where id = ?",
                resp.subscriberId());
        assertEquals("alice@example.com", row.get("email"));
        assertEquals("ACTIVE", row.get("status"));
        // hash stored, raw token never persisted
        assertNotEquals(resp.subscriberToken(), row.get("subscriber_token_hash"));
    }

    @Test
    void subscribeRejectsBadEmail() {
        assertThrows(IllegalArgumentException.class, () -> subscriptionService.subscribe(
                new SubscribeRequest("not-an-email", List.of("ARTICLE_UPDATES"), List.of("WEB"))));
    }

    @Test
    void subscribeRejectsAllUnknownTopics() {
        assertThrows(IllegalArgumentException.class, () -> subscriptionService.subscribe(
                new SubscribeRequest("a@b.co", List.of("BAD_TOPIC"), List.of("WEB"))));
    }

    @Test
    void updatePreferencesRequiresValidToken() {
        SubscribeResponse s = subscribe();
        UpdatePreferencesRequest bad = new UpdatePreferencesRequest(
                s.subscriberId(), "wrong-token",
                List.of(new UpdatePreferencesRequest.PreferenceItem("ARTICLE_UPDATES", false, false)));
        assertThrows(UnauthorizedException.class, () -> subscriptionService.updatePreferences(bad));
    }

    @Test
    void updatePreferencesAcceptsValidToken() {
        SubscribeResponse s = subscribe();
        UpdatePreferencesRequest req = new UpdatePreferencesRequest(
                s.subscriberId(), s.subscriberToken(),
                List.of(new UpdatePreferencesRequest.PreferenceItem("ARTICLE_UPDATES", false, true)));
        subscriptionService.updatePreferences(req);

        Map<String, Object> p = jdbc.queryForMap(
                "select email_enabled, web_enabled from public.subscription_preferences " +
                        " where subscriber_id = ? and topic = 'ARTICLE_UPDATES'",
                s.subscriberId());
        assertEquals(Boolean.FALSE, p.get("email_enabled"));
        assertEquals(Boolean.TRUE, p.get("web_enabled"));
    }

    @Test
    void unsubscribeFlipsStatusAndSkipsPendingEmails() {
        SubscribeResponse s = subscribe();
        // Manually create a pending EMAIL recipient for this subscriber
        UUID notifId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        jdbc.update("insert into public.notifications (id, topic, title) values (?, 'ARTICLE_UPDATES', 't')",
                notifId);
        jdbc.update("insert into public.notification_recipients " +
                        " (id, notification_id, subscriber_id, channel, status, idempotency_key) " +
                        " values (?, ?, ?, 'EMAIL', 'PENDING', ?)",
                recipientId, notifId, s.subscriberId(), notifId + ":" + s.subscriberId() + ":EMAIL");

        boolean ok = subscriptionService.unsubscribeByToken(s.unsubscribeToken());
        assertTrue(ok);

        String status = jdbc.queryForObject(
                "select status from public.subscribers where id = ?", String.class, s.subscriberId());
        assertEquals("UNSUBSCRIBED", status);

        String recStatus = jdbc.queryForObject(
                "select status from public.notification_recipients where id = ?", String.class, recipientId);
        assertEquals("SKIPPED", recStatus);
    }

    @Test
    void unsubscribeWithUnknownTokenReturnsFalse() {
        assertFalse(subscriptionService.unsubscribeByToken("nope"));
    }

    // ---------- End-to-end: process Kafka event -> fan-out -> list -> mark read ----------

    @Test
    void processValidEventFansOutToSubscribers() throws Exception {
        SubscribeResponse a = subscribe("alice@example.com", List.of("ARTICLE_UPDATES"), List.of("WEB", "EMAIL"));
        SubscribeResponse b = subscribe("bob@example.com", List.of("ARTICLE_UPDATES"), List.of("WEB"));
        // Carol subscribed but only to FEATURE_UPDATES, should NOT receive
        subscribe("carol@example.com", List.of("FEATURE_UPDATES"), List.of("WEB", "EMAIL"));

        String json = mapper.writeValueAsString(new ContentEvent(
                "evt_1", "ARTICLE_PUBLISHED", "ARTICLE_UPDATES",
                "BLOG", "blog_1", "New article", "summary",
                "/blog-single/blog_1", OffsetDateTime.now(),
                "ARTICLE_PUBLISHED:blog_1:v1", Map.of()));

        Outcome outcome = processor.process(json, "portfolio.content-events", 0, "100");
        assertEquals(Outcome.DONE, outcome);

        Integer recipientCount = jdbc.queryForObject(
                "select count(*) from public.notification_recipients", Integer.class);
        // Alice WEB+EMAIL = 2, Bob WEB = 1, Carol = 0  → 3
        assertEquals(3, recipientCount);

        Integer notifCount = jdbc.queryForObject(
                "select count(*) from public.notifications", Integer.class);
        assertEquals(1, notifCount);

        String auditStatus = jdbc.queryForObject(
                "select status from public.content_event_audit where idempotency_key = ?",
                String.class, "ARTICLE_PUBLISHED:blog_1:v1");
        assertEquals("DONE", auditStatus);

        // List for Alice
        NotificationListResponse list =
                notificationService.listForSubscriber(a.subscriberId(), a.subscriberToken(), false);
        assertEquals(1, list.items().size());
        assertEquals(1, list.unreadCount());
        assertEquals("New article", list.items().get(0).title());
        assertTrue(list.items().get(0).url().endsWith("/blog-single/blog_1"));

        // Mark Alice's WEB notification read
        UUID recipientId = list.items().get(0).recipientId();
        boolean updated = notificationService.markRead(recipientId, a.subscriberId(), a.subscriberToken());
        assertTrue(updated);

        NotificationListResponse afterRead =
                notificationService.listForSubscriber(a.subscriberId(), a.subscriberToken(), true);
        assertEquals(0, afterRead.items().size(), "unread filter should hide READ items");

        // Bob got only WEB
        NotificationListResponse bobList =
                notificationService.listForSubscriber(b.subscriberId(), b.subscriberToken(), false);
        assertEquals(1, bobList.items().size());
    }

    @Test
    void processIdempotentOnRepeatedDelivery() throws Exception {
        subscribe("alice@example.com", List.of("ARTICLE_UPDATES"), List.of("WEB", "EMAIL"));

        String json = mapper.writeValueAsString(new ContentEvent(
                "evt_2", "ARTICLE_PUBLISHED", "ARTICLE_UPDATES",
                "BLOG", "blog_2", "Article 2", null,
                "/blog-single/blog_2", null,
                "ARTICLE_PUBLISHED:blog_2:v1", null));

        processor.process(json, "t", 0, "1");
        processor.process(json, "t", 0, "1"); // redelivery

        Integer auditCount = jdbc.queryForObject(
                "select count(*) from public.content_event_audit where idempotency_key = 'ARTICLE_PUBLISHED:blog_2:v1'",
                Integer.class);
        assertEquals(1, auditCount);

        Integer recipientCount = jdbc.queryForObject(
                "select count(*) from public.notification_recipients", Integer.class);
        assertEquals(2, recipientCount, "WEB+EMAIL once, not duplicated");
    }

    @Test
    void processInvalidEventReturnsDlq() {
        Outcome outcome = processor.process("{not json", "t", 0, "1");
        assertEquals(Outcome.DLQ, outcome);
    }

    @Test
    void processEventMissingRequiredFieldsReturnsDlq() throws Exception {
        String json = mapper.writeValueAsString(new ContentEvent(
                null, "BAD_TYPE", "ARTICLE_UPDATES",
                null, null, "title", null, null, null, "k", null));
        Outcome outcome = processor.process(json, "t", 0, "1");
        assertEquals(Outcome.DLQ, outcome);
    }

    // ---------- helpers ----------

    private SubscribeResponse subscribe() {
        return subscribe("alice+" + UUID.randomUUID() + "@example.com",
                List.of("ARTICLE_UPDATES", "FEATURE_UPDATES"),
                List.of("WEB", "EMAIL"));
    }

    private SubscribeResponse subscribe(String email, List<String> topics, List<String> channels) {
        return subscriptionService.subscribe(new SubscribeRequest(email, topics, channels));
    }

    /**
     * Stub JavaMailSender so the email scheduler bean wires up; we never call dispatchOnce
     * in this test, so no SMTP traffic happens.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class TestBeans {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        org.springframework.mail.javamail.JavaMailSender javaMailSender() {
            return new org.springframework.mail.javamail.JavaMailSenderImpl();
        }
    }
}
