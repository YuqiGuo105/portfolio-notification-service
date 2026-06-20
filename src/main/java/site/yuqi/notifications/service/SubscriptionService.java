package site.yuqi.notifications.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.yuqi.notifications.domain.Channel;
import site.yuqi.notifications.domain.Subscriber;
import site.yuqi.notifications.domain.Topic;
import site.yuqi.notifications.dto.SubscribeRequest;
import site.yuqi.notifications.dto.SubscribeResponse;
import site.yuqi.notifications.dto.UpdatePreferencesRequest;
import site.yuqi.notifications.exception.NotFoundException;
import site.yuqi.notifications.exception.UnauthorizedException;
import site.yuqi.notifications.repository.NotificationRecipientRepository;
import site.yuqi.notifications.repository.SubscriberRepository;
import site.yuqi.notifications.repository.SubscriptionPreferenceRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SubscriptionService {

    private final SubscriberRepository subscriberRepo;
    private final SubscriptionPreferenceRepository prefRepo;
    private final NotificationRecipientRepository recipientRepo;
    private final TokenService tokens;

    @Autowired
    public SubscriptionService(SubscriberRepository subscriberRepo,
                               SubscriptionPreferenceRepository prefRepo,
                               NotificationRecipientRepository recipientRepo,
                               TokenService tokens) {
        this.subscriberRepo = subscriberRepo;
        this.prefRepo = prefRepo;
        this.recipientRepo = recipientRepo;
        this.tokens = tokens;
    }

    @Transactional
    public SubscribeResponse subscribe(SubscribeRequest req) {
        String email = normalizeEmail(req.email());

        List<String> topics = normalizeTopics(req.topics());
        List<String> channels = normalizeChannels(req.channels());
        if (topics.isEmpty()) throw new IllegalArgumentException("topics: at least one valid topic required");
        if (channels.isEmpty()) throw new IllegalArgumentException("channels: at least one valid channel required");

        String subscriberToken = tokens.generateToken();
        String unsubscribeToken = tokens.generateToken();

        UUID subscriberId = subscriberRepo.upsertByEmail(
                email, tokens.hash(subscriberToken), tokens.hash(unsubscribeToken));

        boolean emailEnabled = channels.contains(Channel.EMAIL.name());
        boolean webEnabled = channels.contains(Channel.WEB.name());

        for (String topic : topics) {
            prefRepo.upsert(subscriberId, topic, emailEnabled, webEnabled);
        }

        return new SubscribeResponse(subscriberId, subscriberToken, unsubscribeToken, topics, channels);
    }

    @Transactional
    public void updatePreferences(UpdatePreferencesRequest req) {
        Subscriber s = verify(req.subscriberId(), req.subscriberToken());
        if (!"ACTIVE".equals(s.status())) {
            throw new UnauthorizedException("subscriber not active");
        }
        for (UpdatePreferencesRequest.PreferenceItem p : req.preferences()) {
            Topic topic = Topic.fromString(p.topic());
            if (topic == null) throw new IllegalArgumentException("unknown topic: " + p.topic());
            prefRepo.upsert(s.id(), topic.name(), p.emailEnabled(), p.webEnabled());
        }
    }

    @Transactional
    public boolean unsubscribeByToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return false;
        String hash = tokens.hash(rawToken);
        Subscriber s = subscriberRepo.findByUnsubscribeTokenHash(hash).orElse(null);
        if (s == null) return false;
        subscriberRepo.setStatus(s.id(), "UNSUBSCRIBED");
        recipientRepo.markPendingEmailSkippedForSubscriber(s.id());
        return true;
    }

    public Subscriber verify(UUID subscriberId, String rawToken) {
        if (subscriberId == null) throw new UnauthorizedException("missing subscriberId");
        Subscriber s = subscriberRepo.findById(subscriberId)
                .orElseThrow(() -> new NotFoundException("subscriber not found"));
        if (!tokens.tokenMatches(rawToken, s.subscriberTokenHash())) {
            throw new UnauthorizedException("invalid subscriber token");
        }
        return s;
    }

    // ---------- helpers ----------

    private static String normalizeEmail(String email) {
        if (email == null) throw new IllegalArgumentException("email required");
        String e = email.trim().toLowerCase();
        if (e.isEmpty() || !e.contains("@") || e.length() > 254) {
            throw new IllegalArgumentException("invalid email");
        }
        return e;
    }

    private static List<String> normalizeTopics(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String r : raw) {
            Topic t = Topic.fromString(r);
            if (t != null && !out.contains(t.name())) out.add(t.name());
        }
        return out;
    }

    private static List<String> normalizeChannels(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String r : raw) {
            Channel c = Channel.fromString(r);
            if (c != null && !out.contains(c.name())) out.add(c.name());
        }
        return out;
    }
}
