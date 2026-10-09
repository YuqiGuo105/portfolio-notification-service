package site.yuqi.notifications.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import site.yuqi.notifications.dto.SubscribeRequest;
import site.yuqi.notifications.repository.SubscriptionConfirmationRepository;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubscriptionConfirmationServiceTest {
    final SubscriptionConfirmationRepository repository = mock(SubscriptionConfirmationRepository.class);
    final SubscriptionService subscriptions = mock(SubscriptionService.class);
    final JavaMailSender mail = mock(JavaMailSender.class);
    final TokenService tokens = new TokenService("test-only-pepper");
    final SubscriptionConfirmationService service = new SubscriptionConfirmationService(repository, subscriptions, tokens, mail, "owner@example.test");
    final SubscribeRequest request = new SubscribeRequest("Owner@Example.test", List.of("ARTICLE_UPDATES"), List.of("EMAIL"));

    @Test void anonymousRequestOnlySendsConfirmationAndNeverMintsCredentials() throws Exception {
        when(repository.request(eq("owner@example.test"), anyString(), anyList(), anyList())).thenReturn(true);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        var response = service.request(request);
        assertEquals("CONFIRMATION_REQUIRED", response.get("status"));
        assertEquals(2, response.size());
        verifyNoInteractions(subscriptions);
        ArgumentCaptor<MimeMessage> email = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(email.capture());
        String text = email.getValue().getContent().toString();
        var matcher = java.util.regex.Pattern.compile("#token=([a-f0-9]{64})").matcher(text);
        assertTrue(matcher.find());
        verify(repository).request(eq("owner@example.test"), eq(tokens.hash("subscription-confirm:" + matcher.group(1))), anyList(), anyList());
        assertFalse(response.toString().contains(matcher.group(1)));
    }

    @Test void repeatedRequestsNeitherRotateLinkNorSendAnotherMail() {
        when(repository.request(anyString(), anyString(), anyList(), anyList())).thenReturn(false);
        assertEquals("CONFIRMATION_REQUIRED", service.request(request).get("status"));
        verifyNoInteractions(subscriptions, mail);
    }

    @Test void onlyUnexpiredSingleUseConfirmationActivates() {
        String token = "a".repeat(64);
        var pending = new SubscriptionConfirmationRepository.Pending("owner@example.test", request.topics(), request.channels());
        when(repository.consume(tokens.hash("subscription-confirm:" + token))).thenReturn(Optional.of(pending), Optional.empty());
        service.confirm(token);
        assertThrows(IllegalArgumentException.class, () -> service.confirm(token));
        verify(subscriptions, times(1)).subscribe(new SubscribeRequest(pending.email(), pending.topics(), pending.channels()));
        assertThrows(IllegalArgumentException.class, () -> service.confirm("invalid"));
    }

    @Test void unsubscribeCapabilityCannotManagePreferencesOrSurviveTokenRotation() {
        UUID id = UUID.randomUUID();
        String signature = tokens.emailUnsubscribeToken(id, "current-hash");
        assertTrue(tokens.emailUnsubscribeMatches(signature, id, "current-hash"));
        assertFalse(tokens.emailUnsubscribeMatches(signature, UUID.randomUUID(), "current-hash"));
        assertFalse(tokens.emailUnsubscribeMatches(signature, id, "rotated-hash"));
        assertFalse(tokens.emailUnsubscribeMatches(signature + "0", id, "current-hash"));
        assertFalse(tokens.tokenMatches(signature, "current-hash"));
    }
}
