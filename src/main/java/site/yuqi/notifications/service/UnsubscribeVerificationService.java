package site.yuqi.notifications.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import site.yuqi.notifications.domain.Subscriber;
import site.yuqi.notifications.dto.ConfirmUnsubscribeResponse;
import site.yuqi.notifications.dto.UnsubscribeVerificationResponse;
import site.yuqi.notifications.repository.SubscriberRepository;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

@Service
@Slf4j
public class UnsubscribeVerificationService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String GENERIC_MESSAGE =
            "If this address has an active subscription, a verification code has been sent.";

    private final SubscriberRepository subscriberRepo;
    private final SubscriptionService subscriptionService;
    private final UnsubscribeChallengeStore challengeStore;
    private final TokenService tokens;
    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final long ttlSeconds;
    private final int maxAttempts;
    private final int maxRequests;
    private final long rateWindowSeconds;

    public UnsubscribeVerificationService(
            SubscriberRepository subscriberRepo,
            SubscriptionService subscriptionService,
            UnsubscribeChallengeStore challengeStore,
            TokenService tokens,
            JavaMailSender mailSender,
            @Value("${portfolio.email.from:noreply@yuqi.site}") String fromAddress,
            @Value("${portfolio.unsubscribe.verification.ttl-seconds:600}") long ttlSeconds,
            @Value("${portfolio.unsubscribe.verification.max-attempts:5}") int maxAttempts,
            @Value("${portfolio.unsubscribe.verification.max-requests-per-window:3}") int maxRequests,
            @Value("${portfolio.unsubscribe.verification.rate-window-seconds:3600}") long rateWindowSeconds) {
        this.subscriberRepo = subscriberRepo;
        this.subscriptionService = subscriptionService;
        this.challengeStore = challengeStore;
        this.tokens = tokens;
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.ttlSeconds = Math.max(60, ttlSeconds);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.maxRequests = Math.max(1, maxRequests);
        this.rateWindowSeconds = Math.max(60, rateWindowSeconds);
    }

    public UnsubscribeVerificationResponse requestCode(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        String emailHash = tokens.hash("unsubscribe-email:" + email);
        String verificationId = UUID.randomUUID().toString();

        boolean allowed = challengeStore.allowRequest(
                emailHash, maxRequests, Duration.ofSeconds(rateWindowSeconds));
        Subscriber subscriber = allowed
                ? subscriberRepo.findByEmail(email).filter(s -> "ACTIVE".equals(s.status())).orElse(null)
                : null;

        if (subscriber != null) {
            String code = String.format(Locale.ROOT, "%06d", RNG.nextInt(1_000_000));
            String codeHash = codeHash(verificationId, code);
            challengeStore.save(verificationId, subscriber.id(), emailHash, codeHash,
                    maxAttempts, Duration.ofSeconds(ttlSeconds));
            try {
                sendCodeEmail(email, code);
                log.info("unsubscribe verification issued verificationId={} emailHash={}",
                        verificationId, shortHash(emailHash));
            } catch (MailException | MessagingException e) {
                challengeStore.delete(verificationId);
                throw new IllegalStateException("Unable to send verification email.", e);
            }
        }

        return new UnsubscribeVerificationResponse(
                verificationId, maskEmail(email), ttlSeconds, GENERIC_MESSAGE);
    }

    public ConfirmUnsubscribeResponse confirm(String verificationId, String verificationCode) {
        UUID subscriberId = challengeStore.findSubscriberId(verificationId)
                .orElseThrow(() -> new IllegalArgumentException("Verification code is invalid or expired."));

        UnsubscribeChallengeStore.VerificationResult result = challengeStore.verifyAndConsume(
                verificationId, codeHash(verificationId, verificationCode));
        if (result != UnsubscribeChallengeStore.VerificationResult.VERIFIED) {
            throw new IllegalArgumentException(messageFor(result));
        }

        subscriptionService.unsubscribeVerifiedSubscriber(subscriberId, "CHAT_AGENT_EMAIL_OTP");
        log.info("subscriber unsubscribed verificationId={} subscriberId={}", verificationId, subscriberId);
        return new ConfirmUnsubscribeResponse(
                "UNSUBSCRIBED", "The subscription has been unsubscribed successfully.");
    }

    private String codeHash(String verificationId, String code) {
        return tokens.hash("unsubscribe-code:" + verificationId + ":" + code);
    }

    private void sendCodeEmail(String email, String code) throws MessagingException {
        MimeMessage mime = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());
        helper.setFrom(fromAddress);
        helper.setTo(email);
        helper.setSubject("Your yuqi.site unsubscribe verification code");
        helper.setText("""
                You requested to unsubscribe from yuqi.site updates.

                Verification code: %s

                This code expires in %d minutes. If you did not request this, you can ignore this email.
                """.formatted(code, Math.max(1, ttlSeconds / 60)));
        mailSender.send(mime);
    }

    private static String normalizeEmail(String email) {
        if (email == null) throw new IllegalArgumentException("email required");
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 254 || !normalized.contains("@")) {
            throw new IllegalArgumentException("invalid email");
        }
        return normalized;
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        int dot = domain.lastIndexOf('.');
        String domainName = dot > 0 ? domain.substring(0, dot) : domain;
        String suffix = dot > 0 ? domain.substring(dot) : "";
        return local.charAt(0) + "***@" +
                (domainName.isBlank() ? "***" : domainName.charAt(0) + "***") + suffix;
    }

    private static String shortHash(String hash) {
        return hash == null ? "" : hash.substring(0, Math.min(12, hash.length()));
    }

    private static String messageFor(UnsubscribeChallengeStore.VerificationResult result) {
        return switch (result) {
            case LOCKED -> "Too many incorrect attempts. Request a new verification code.";
            case ALREADY_USED -> "This verification code has already been used.";
            case EXPIRED -> "Verification code is invalid or expired.";
            default -> "Verification code is invalid.";
        };
    }
}
