package site.yuqi.notifications.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.yuqi.notifications.domain.Channel;
import site.yuqi.notifications.domain.Topic;
import site.yuqi.notifications.dto.SubscribeRequest;
import site.yuqi.notifications.dto.SubscribeResponse;
import site.yuqi.notifications.repository.SubscriptionConfirmationRepository;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SubscriptionConfirmationService {
    private final SubscriptionConfirmationRepository confirmations;
    private final SubscriptionService subscriptions;
    private final TokenService tokens;
    private final JavaMailSender mail;
    private final String from;

    public SubscriptionConfirmationService(SubscriptionConfirmationRepository confirmations,
            SubscriptionService subscriptions, TokenService tokens, JavaMailSender mail,
            @Value("${portfolio.email.from:noreply@yuqi.site}") String from) {
        this.confirmations = confirmations;
        this.subscriptions = subscriptions;
        this.tokens = tokens;
        this.mail = mail;
        this.from = from;
    }

    @Transactional
    public Map<String, String> request(SubscribeRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        List<String> topics = request.topics().stream().map(Topic::fromString)
                .filter(java.util.Objects::nonNull).map(Enum::name).distinct().toList();
        List<String> channels = request.channels().stream().map(Channel::fromString)
                .filter(java.util.Objects::nonNull).map(Enum::name).distinct().toList();
        if (topics.isEmpty() || channels.isEmpty()) throw new IllegalArgumentException("Select valid topics and channels.");
        String token = tokens.generateToken();
        if (confirmations.request(email, tokens.hash("subscription-confirm:" + token), topics, channels)) {
            try {
                MimeMessage message = mail.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
                helper.setFrom(from);
                helper.setTo(email);
                helper.setSubject("Confirm your yuqi.site subscription");
                // Fragment tokens are not sent in HTTP URLs or Referer headers.
                helper.setText("Confirm your subscription by opening this link and selecting Confirm subscription:\n\n"
                        + "https://www.yuqi.site/subscriptions/confirm#token=" + token
                        + "\n\nThis link expires in 30 minutes. If you did not request it, ignore this email."
                        + " Your existing subscription and preferences have not changed.");
                mail.send(message);
            } catch (MessagingException | MailException e) {
                throw new IllegalStateException("Unable to send confirmation email.");
            }
        }
        return Map.of("status", "CONFIRMATION_REQUIRED", "message",
                "Check your email for a confirmation link. Your subscription will not change until you confirm.");
    }

    @Transactional
    public SubscribeResponse confirm(String token) {
        if (token == null || !token.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Confirmation link is invalid or expired.");
        }
        var pending = confirmations.consume(tokens.hash("subscription-confirm:" + token))
                .orElseThrow(() -> new IllegalArgumentException("Confirmation link is invalid or expired."));
        return subscriptions.subscribe(new SubscribeRequest(pending.email(), pending.topics(), pending.channels()));
    }
}
