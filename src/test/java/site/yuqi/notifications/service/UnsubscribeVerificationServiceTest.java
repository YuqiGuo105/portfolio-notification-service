package site.yuqi.notifications.service;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import site.yuqi.notifications.domain.Subscriber;
import site.yuqi.notifications.repository.SubscriberRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnsubscribeVerificationServiceTest {

    private SubscriberRepository subscriberRepo;
    private SubscriptionService subscriptionService;
    private UnsubscribeChallengeStore challengeStore;
    private JavaMailSender mailSender;
    private TokenService tokenService;
    private UnsubscribeVerificationService service;

    @BeforeEach
    void setUp() {
        subscriberRepo = mock(SubscriberRepository.class);
        subscriptionService = mock(SubscriptionService.class);
        challengeStore = mock(UnsubscribeChallengeStore.class);
        mailSender = mock(JavaMailSender.class);
        tokenService = new TokenService("test-pepper");
        when(challengeStore.allowRequest(any(), anyInt(), any())).thenReturn(true);
        when(mailSender.createMimeMessage()).thenAnswer(ignored -> new JavaMailSenderImpl().createMimeMessage());
        service = new UnsubscribeVerificationService(
                subscriberRepo, subscriptionService, challengeStore, tokenService, mailSender,
                "test@example.test", 600, 5, 3, 3600);
    }

    @Test
    void requestAndConfirmUsesOtpThenChangesStatusOnly() throws Exception {
        UUID subscriberId = UUID.randomUUID();
        Subscriber subscriber = new Subscriber(
                subscriberId, "alice@example.com", "ACTIVE", "subscriber-hash", "unsub-hash",
                OffsetDateTime.now(), OffsetDateTime.now());
        when(subscriberRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(subscriber));

        AtomicReference<String> storedVerificationId = new AtomicReference<>();
        AtomicReference<String> storedCodeHash = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            storedVerificationId.set(invocation.getArgument(0));
            storedCodeHash.set(invocation.getArgument(3));
            return null;
        }).when(challengeStore).save(any(), eq(subscriberId), any(), any(), eq(5), any());

        var requested = service.requestCode(" Alice@Example.com ");
        assertNotNull(requested.verificationId());
        assertFalse(requested.destination().contains("alice"));

        ArgumentCaptor<MimeMessage> mail = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(mail.capture());
        Matcher matcher = Pattern.compile("Verification code: (\\d{6})")
                .matcher(String.valueOf(mail.getValue().getContent()));
        assertTrue(matcher.find());
        String code = matcher.group(1);

        when(challengeStore.findSubscriberId(requested.verificationId()))
                .thenReturn(Optional.of(subscriberId));
        when(challengeStore.verifyAndConsume(eq(requested.verificationId()), any()))
                .thenAnswer(invocation -> storedCodeHash.get().equals(invocation.getArgument(1))
                        ? UnsubscribeChallengeStore.VerificationResult.VERIFIED
                        : UnsubscribeChallengeStore.VerificationResult.INVALID);

        var confirmed = service.confirm(requested.verificationId(), code);

        assertEquals("UNSUBSCRIBED", confirmed.status());
        assertEquals(requested.verificationId(), storedVerificationId.get());
        verify(subscriptionService).unsubscribeVerifiedSubscriber(subscriberId, "CHAT_AGENT_EMAIL_OTP");
    }

    @Test
    void unknownEmailReturnsGenericResponseWithoutSendingMail() {
        when(subscriberRepo.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        var response = service.requestCode("nobody@example.com");

        assertNotNull(response.verificationId());
        verify(mailSender, never()).send(any(MimeMessage.class));
        verify(challengeStore, never()).save(any(), any(), any(), any(), anyInt(), any());
    }
}
