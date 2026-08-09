package site.yuqi.notifications.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Kafka-consumed content event.
 *
 * <p>The DTO supports two payload shapes:
 * <ul>
 *   <li>Legacy {@code portfolio.content-events} events, which use
 *       {@code topic} / {@code createdAt} and always carry an explicit
 *       {@code eventType}.</li>
 *   <li>Admin-service {@code content.notification.*.v1} events
 *       ({@code ContentPublishedEvent}), which use {@code notificationTopic} /
 *       {@code occurredAt} and omit {@code eventType} — it is derived from
 *       {@link #sourceType} via {@link #effectiveEventType()}.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContentEvent(
        String eventId,
        String traceId,
        String correlationId,
        String causationId,
        Integer schemaVersion,
        String eventType,
        @JsonAlias({"notificationTopic"}) String topic,
        String sourceType,
        String sourceId,
        Integer sourceVersion,
        String title,
        String summary,
        String url,
        @JsonAlias({"occurredAt"}) OffsetDateTime createdAt,
        String idempotencyKey,
        Map<String, Object> metadata
) {
    /** Backwards-compatible constructor for the legacy HTTP and Kafka payload shape. */
    public ContentEvent(
            String eventId,
            String eventType,
            String topic,
            String sourceType,
            String sourceId,
            String title,
            String summary,
            String url,
            OffsetDateTime createdAt,
            String idempotencyKey,
            Map<String, Object> metadata) {
        this(eventId, null, null, null, 1, eventType, topic, sourceType, sourceId,
                null, title, summary, url, createdAt, idempotencyKey, metadata);
    }

    /**
     * Returns the explicit {@link #eventType} when present, otherwise derives
     * it from {@link #sourceType} for admin-service payloads that omit the
     * field. Returns {@code null} when neither is enough to determine a value.
     */
    public String effectiveEventType() {
        if (notBlank(eventType)) return eventType;
        if (sourceType == null) return null;
        return switch (sourceType) {
            case "BLOG", "LIFE_BLOG" -> "ARTICLE_PUBLISHED";
            case "PROJECT"           -> "FEATURE_RELEASED";
            case "EXPERIENCE"        -> "JOB_POSITION_UPDATED";
            case "ALERT"             -> "ANALYTICS_ALERT_TRIGGERED";
            default                  -> null;
        };
    }

    public boolean isValid() {
        String effective = effectiveEventType();
        return notBlank(effective)
                && notBlank(topic)
                && notBlank(idempotencyKey)
                && notBlank(title)
                && isAllowedEventType(effective)
                && isAllowedTopic(topic);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    public static boolean isAllowedEventType(String eventType) {
        if (eventType == null) return false;
        switch (eventType) {
            case "ARTICLE_PUBLISHED":
            case "ARTICLE_UPDATED":
            case "FEATURE_RELEASED":
            case "JOB_POSITION_UPDATED":
            case "ANALYTICS_ALERT_TRIGGERED":
                return true;
            default:
                return false;
        }
    }

    public static boolean isAllowedTopic(String topic) {
        if (topic == null) return false;
        switch (topic) {
            case "ARTICLE_UPDATES":
            case "FEATURE_UPDATES":
            case "JOB_UPDATES":
            case "ADMIN_ALERTS":
                return true;
            default:
                return false;
        }
    }
}
