package site.yuqi.notifications.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ContentEvent(
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
        Map<String, Object> metadata
) {
    public boolean isValid() {
        return notBlank(eventType)
                && notBlank(topic)
                && notBlank(idempotencyKey)
                && notBlank(title)
                && isAllowedEventType(eventType)
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
                return true;
            default:
                return false;
        }
    }
}
