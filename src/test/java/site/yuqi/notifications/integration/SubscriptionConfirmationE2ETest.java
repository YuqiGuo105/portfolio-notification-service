package site.yuqi.notifications.integration;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import site.yuqi.notifications.NotificationApplication;
import site.yuqi.notifications.dto.SubscribeRequest;
import site.yuqi.notifications.dto.SubscribeResponse;
import site.yuqi.notifications.service.SubscriptionService;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP, Flyway/PostgreSQL transactions and SMTP; no external mail delivery. */
@SpringBootTest(classes = NotificationApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class SubscriptionConfirmationE2ETest {
    static GreenMail mailbox;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubscriptionService subscriptions;

    @DynamicPropertySource
    static void isolatedServices(DynamicPropertyRegistry properties) {
        String url = System.getenv("TEST_POSTGRES_URL");
        assertTrue(url.matches("jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/portfolio_audit_test"));
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", "test-password"));
        admin.execute("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='anon') THEN CREATE ROLE anon; END IF; IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN CREATE ROLE authenticated; END IF; END $$");
        if (admin.queryForObject("select count(*) from pg_database where datname='portfolio_audit_e2e_test'", Integer.class) == 0) {
            admin.execute("CREATE DATABASE portfolio_audit_e2e_test");
        }
        mailbox = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        mailbox.setUser("owner@example.test", "owner", "mail-test-password");
        mailbox.start();
        properties.add("spring.datasource.url", () -> url.replace("/portfolio_audit_test", "/portfolio_audit_e2e_test"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> "postgres");
        properties.add("spring.datasource.password", () -> "test-password");
        properties.add("spring.sql.init.mode", () -> "never");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.mail.host", () -> "127.0.0.1");
        properties.add("spring.mail.port", () -> mailbox.getSmtp().getPort());
        properties.add("spring.mail.username", () -> "owner");
        properties.add("spring.mail.password", () -> "mail-test-password");
        properties.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
        properties.add("spring.mail.properties.mail.smtp.timeout", () -> "5000");
        properties.add("spring.mail.properties.mail.smtp.connectiontimeout", () -> "5000");
    }

    @AfterAll static void closeMailbox() { if (mailbox != null) mailbox.stop(); }

    @Test void mailboxOwnershipIsRequiredBeforeReactivationAndCredentialRotation() throws Exception {
        jdbc.update("delete from public.subscription_confirmations where email='owner@example.test'");
        var request = new SubscribeRequest("owner@example.test", List.of("ARTICLE_UPDATES"), List.of("EMAIL"));
        var original = subscriptions.subscribe(request);
        assertTrue(subscriptions.unsubscribeByToken(original.unsubscribeToken()));
        String originalHash = jdbc.queryForObject("select subscriber_token_hash from public.subscribers where id=?", String.class, original.subscriberId());

        assertEquals(HttpStatus.UNAUTHORIZED, http.postForEntity("/api/subscriptions", request, Map.class).getStatusCode());
        var pending = post("/api/subscriptions", request, Map.class);
        assertEquals(HttpStatus.ACCEPTED, pending.getStatusCode());
        assertEquals("CONFIRMATION_REQUIRED", pending.getBody().get("status"));
        assertFalse(pending.getBody().containsKey("subscriberToken"));
        assertFalse(pending.getBody().containsKey("unsubscribeToken"));
        assertUnsubscribed(original.subscriberId());
        assertEquals(originalHash, jdbc.queryForObject("select subscriber_token_hash from public.subscribers where id=?", String.class, original.subscriberId()));
        assertTrue(mailbox.waitForIncomingEmail(5000, 1));
        var message = mailbox.getReceivedMessages()[0];
        assertEquals("owner@example.test", message.getAllRecipients()[0].toString());
        var matcher = Pattern.compile("https://www\\.yuqi\\.site/subscriptions/confirm#token=([a-f0-9]{64})").matcher(message.getContent().toString());
        assertTrue(matcher.find());
        String confirmationToken = matcher.group(1);
        assertEquals(HttpStatus.ACCEPTED, post("/api/subscriptions", request, Map.class).getStatusCode());
        assertEquals(1, mailbox.getReceivedMessages().length);
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/subscriptions/confirm", Map.of("token", "0".repeat(64)), Map.class).getStatusCode());
        assertUnsubscribed(original.subscriberId());

        var confirmed = post("/api/subscriptions/confirm", Map.of("token", confirmationToken), SubscribeResponse.class);
        assertEquals(HttpStatus.OK, confirmed.getStatusCode());
        var account = confirmed.getBody();
        assertEquals(original.subscriberId(), account.subscriberId());
        assertNotEquals(original.subscriberToken(), account.subscriberToken());
        assertEquals("ACTIVE", jdbc.queryForObject("select status from public.subscribers where id=?", String.class, account.subscriberId()));
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/subscriptions/confirm", Map.of("token", confirmationToken), Map.class).getStatusCode());
        var unsubscribed = post("/api/subscriptions/unsubscribe", Map.of("token", account.unsubscribeToken()), Map.class);
        assertEquals(Boolean.TRUE, unsubscribed.getBody().get("unsubscribed"));
        assertUnsubscribed(account.subscriberId());
        post("/api/subscriptions", request, Map.class);
        assertUnsubscribed(account.subscriberId());
        assertEquals(1, mailbox.getReceivedMessages().length);
    }

    void assertUnsubscribed(java.util.UUID id) {
        assertEquals("UNSUBSCRIBED", jdbc.queryForObject("select status from public.subscribers where id=?", String.class, id));
    }

    <T> ResponseEntity<T> post(String path, Object body, Class<T> type) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", "test-internal-token");
        return http.postForEntity(path, new HttpEntity<>(body, headers), type);
    }
}
