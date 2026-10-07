package site.yuqi.notifications.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import site.yuqi.notifications.NotificationApplication;
import site.yuqi.notifications.dto.SubscribeRequest;
import site.yuqi.notifications.dto.SubscribeResponse;
import site.yuqi.notifications.repository.WebPushRepository;
import site.yuqi.notifications.service.*;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = NotificationApplication.class, properties = "portfolio.push.initial-delay-ms=3600000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebPushFlowTest {
    @Autowired SubscriptionService subscriptions;
    @Autowired WebPushService push;
    @Autowired WebPushRepository repository;
    @Autowired ContentEventProcessor processor;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @MockBean WebPushTransport transport;
    SubscribeResponse owner;
    String key;
    final String auth = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
    final String endpoint = "https://fcm.googleapis.com/fcm/send/test-only";

    @BeforeEach void setup() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:"));
        }
        jdbc.update("delete from browser_push_deliveries");
        jdbc.update("delete from browser_push_subscriptions");
        jdbc.update("delete from notification_recipients");
        jdbc.update("delete from notifications");
        jdbc.update("delete from content_event_audit");
        jdbc.update("delete from subscribers");
        Security.addProvider(new BouncyCastleProvider());
        var generator = KeyPairGenerator.getInstance("EC", "BC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        key = Base64.getUrlEncoder().withoutPadding().encodeToString(Utils.encode((ECPublicKey) generator.generateKeyPair().getPublic()));
        when(transport.enabled()).thenReturn(true);
        when(transport.publicKey()).thenReturn(key);
        when(transport.send(anyString(), anyString(), anyString(), any())).thenReturn(201);
        owner = subscriptions.subscribe(new SubscribeRequest("push@example.test", List.of("ARTICLE_UPDATES"), List.of("WEB")));
    }

    @Test void subscribePublicationDeliveryAndRemoval() throws Exception {
        var body = Map.of("subscriberId", owner.subscriberId(), "subscriberToken", owner.subscriberToken(),
                "subscription", Map.of("endpoint", endpoint, "keys", Map.of("p256dh", key, "auth", auth)));
        mvc.perform(post("/api/push/subscriptions").contentType("application/json").content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isUnauthorized());
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/push/subscriptions").header("X-Internal-Token", "test-internal-token")
                    .contentType("application/json").content(mapper.writeValueAsBytes(body)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        }
        assertEquals(1, count("browser_push_subscriptions"));
        publish("one", "SUBSCRIBERS", "ARTICLE_UPDATES");
        publish("one", "SUBSCRIBERS", "ARTICLE_UPDATES");
        assertEquals(1, count("browser_push_deliveries"));
        push.dispatchDue(); push.dispatchDue();
        assertEquals("SENT", deliveryStatus());
        verify(transport, times(1)).send(eq(endpoint), eq(key), eq(auth), argThat(payload -> {
            String text = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
            return text.contains("New article") && !text.contains("push@example.test") && !text.contains(owner.subscriberToken());
        }));
        assertTrue(push.remove(owner.subscriberId(), owner.subscriberToken(), endpoint));
        assertFalse(push.active(owner.subscriberId(), owner.subscriberToken(), endpoint));
    }

    @Test void ownershipValidationProviderAllowlistAndQuotas() {
        register();
        assertThrows(Exception.class, () -> push.register(owner.subscriberId(), "wrong", endpoint, key, auth));
        var other = subscriptions.subscribe(new SubscribeRequest("other@example.test", List.of("ARTICLE_UPDATES"), List.of("WEB")));
        assertFalse(push.remove(other.subscriberId(), other.subscriberToken(), endpoint));
        assertFalse(push.active(other.subscriberId(), other.subscriberToken(), endpoint));
        for (String bad : List.of("http://fcm.googleapis.com/x", "https://127.0.0.1/x", "https://fcm.googleapis.com.evil.example/x", "https://web.push.apple.com:444/x", "https://user@fcm.googleapis.com/x")) {
            assertThrows(IllegalArgumentException.class, () -> push.register(owner.subscriberId(), owner.subscriberToken(), bad, key, auth));
        }
        assertThrows(IllegalArgumentException.class, () -> push.register(owner.subscriberId(), owner.subscriberToken(), endpoint, "broken", auth));
        for (int i = 1; i < 5; i++) push.register(owner.subscriberId(), owner.subscriberToken(), endpoint + i, key, auth);
        assertThrows(IllegalArgumentException.class, () -> push.register(owner.subscriberId(), owner.subscriberToken(), endpoint + "six", key, auth));
    }

    @Test void suppressesUnmatchedAndPrivateEventsAndUnsubscribedDevices() throws Exception {
        register();
        publish("feature", "SUBSCRIBERS", "FEATURE_UPDATES");
        publish("none", "NONE", "ARTICLE_UPDATES");
        publish("private", "ADMINS_ONLY", "ARTICLE_UPDATES");
        assertEquals(0, count("browser_push_deliveries"));
        publish("public", "SUBSCRIBERS", "ARTICLE_UPDATES");
        subscriptions.unsubscribeByToken(owner.unsubscribeToken());
        push.dispatchDue();
        assertEquals("SKIPPED", deliveryStatus());
        verify(transport, never()).send(anyString(), anyString(), anyString(), any());
    }

    @Test void emailOnlySubscribersCannotEnablePushAndDisabledWebPreferencesSuppressQueuedDelivery() throws Exception {
        var emailOnly = subscriptions.subscribe(new SubscribeRequest("email-only@example.test", List.of("ARTICLE_UPDATES"), List.of("EMAIL")));
        assertThrows(IllegalArgumentException.class, () -> push.register(emailOnly.subscriberId(), emailOnly.subscriberToken(), endpoint, key, auth));
        register(); publish("one", "SUBSCRIBERS", "ARTICLE_UPDATES");
        jdbc.update("update subscription_preferences set web_enabled=false where subscriber_id=?", owner.subscriberId());
        assertFalse(push.active(owner.subscriberId(), owner.subscriberToken(), endpoint));
        push.dispatchDue();
        assertEquals("SKIPPED", deliveryStatus());
        verify(transport, never()).send(anyString(), anyString(), anyString(), any());
    }

    @Test void expiredDeviceIsDisabledAndNotReenqueued() throws Exception {
        register(); publish("one", "SUBSCRIBERS", "ARTICLE_UPDATES");
        when(transport.send(anyString(), anyString(), anyString(), any())).thenReturn(410);
        push.dispatchDue();
        assertEquals("SKIPPED", deliveryStatus());
        assertFalse(push.active(owner.subscriberId(), owner.subscriberToken(), endpoint));
        publish("two", "SUBSCRIBERS", "ARTICLE_UPDATES");
        assertEquals(1, count("browser_push_deliveries"));
    }

    @Test void transientRejectionIsBoundedAndUnknownAcceptanceIsNotRetried() throws Exception {
        register(); publish("one", "SUBSCRIBERS", "ARTICLE_UPDATES");
        when(transport.send(anyString(), anyString(), anyString(), any())).thenReturn(503);
        push.dispatchDue(); assertEquals("RETRY", deliveryStatus());
        push.dispatchDue(); verify(transport, times(1)).send(anyString(), anyString(), anyString(), any());
        jdbc.update("update browser_push_deliveries set available_at=current_timestamp");
        when(transport.send(anyString(), anyString(), anyString(), any())).thenThrow(new java.io.IOException("uncertain"));
        push.dispatchDue(); assertEquals("UNKNOWN", deliveryStatus());
        push.dispatchDue(); verify(transport, times(2)).send(anyString(), anyString(), anyString(), any());
    }

    @Test void claimsAreExclusiveAndCrashedSendsBecomeUnknown() throws Exception {
        register(); publish("one", "SUBSCRIBERS", "ARTICLE_UPDATES");
        UUID id = repository.due().get(0);
        assertNotNull(repository.claim(id));
        assertNull(repository.claim(id));
        jdbc.update("update browser_push_deliveries set available_at=?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)));
        assertTrue(repository.due().isEmpty()); assertEquals("UNKNOWN", deliveryStatus());
    }

    @Test void requestScopedRecoveryDrainsPushWithoutAnActiveBrowserOrBackgroundCpu() throws Exception {
        register(); publish("one", "SUBSCRIBERS", "ARTICLE_UPDATES");
        publish("two", "SUBSCRIBERS", "ARTICLE_UPDATES");
        assertEquals(1, push.dispatchOnce(1));
        assertEquals(1, jdbc.queryForObject("select count(*) from browser_push_deliveries where status='PENDING'", Integer.class));
        mvc.perform(post("/api/internal/workers/drain").param("maxWaitMs", "1000"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/internal/workers/drain").param("maxWaitMs", "1000")
                        .header("X-Internal-Token", "test-internal-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pushAttempts").value(1));
        assertEquals(2, jdbc.queryForObject("select count(*) from browser_push_deliveries where status='SENT'", Integer.class));
    }

    void register() { push.register(owner.subscriberId(), owner.subscriberToken(), endpoint, key, auth); }
    int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }
    String deliveryStatus() { return jdbc.queryForObject("select status from browser_push_deliveries", String.class); }
    void publish(String id, String audience, String topic) throws Exception {
        String json = mapper.writeValueAsString(Map.of("eventId", id, "idempotencyKey", id,
                "eventType", "ARTICLE_PUBLISHED", "topic", topic, "sourceType", "BLOG", "sourceId", id,
                "title", "New article", "summary", "Public preview", "url", "/blog-single/" + id, "audience", audience));
        assertEquals(ContentEventProcessor.Outcome.DONE, processor.process(json, "test", 0, "1"));
    }
}
