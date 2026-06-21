package site.yuqi.notifications.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Map;
import java.util.UUID;

/**
 * HTTP request body for {@code POST /api/content-events}.
 *
 * <p>The caller (Next.js admin panel) supplies the human-meaningful fields;
 * the controller builds a {@link ContentEvent} from them and delegates to
 * {@link site.yuqi.notifications.service.ContentEventProcessor}.
 *
 * <p>Allowed {@code eventType} values:
 * <ul>
 *   <li>ARTICLE_PUBLISHED</li>
 *   <li>ARTICLE_UPDATED</li>
 *   <li>FEATURE_RELEASED</li>
 *   <li>JOB_POSITION_UPDATED</li>
 * </ul>
 *
 * <p>Allowed {@code topic} values:
 * <ul>
 *   <li>ARTICLE_UPDATES</li>
 *   <li>FEATURE_UPDATES</li>
 *   <li>JOB_UPDATES</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContentEventRequest(

        @NotBlank
        @Pattern(
                regexp = "ARTICLE_PUBLISHED|ARTICLE_UPDATED|FEATURE_RELEASED|JOB_POSITION_UPDATED",
                message = "eventType must be one of: ARTICLE_PUBLISHED, ARTICLE_UPDATED, FEATURE_RELEASED, JOB_POSITION_UPDATED"
        )
        String eventType,

        @NotBlank
        @Pattern(
                regexp = "ARTICLE_UPDATES|FEATURE_UPDATES|JOB_UPDATES",
                message = "topic must be one of: ARTICLE_UPDATES, FEATURE_UPDATES, JOB_UPDATES"
        )
        String topic,

        @NotBlank
        String title,

        String summary,

        /** Absolute URL to the content, e.g. https://www.yuqi.site/blogs/my-post */
        String url,

        /**
         * Optional – caller can supply the source type (e.g. "ARTICLE").
         * Defaults to the prefix of eventType (ARTICLE → ARTICLE, FEATURE → FEATURE, JOB → JOB).
         */
        String sourceType,

        /** Optional – slug or DB id of the source object. */
        String sourceId,

        /**
         * Idempotency key. If omitted, a UUID is generated.
         * Callers should supply a stable key (e.g. {@code article-<slug>-published})
         * so that retries do not produce duplicate notifications.
         */
        String idempotencyKey,

        Map<String, Object> metadata
) {
    /** Derive a source type from the eventType prefix when the caller omits it. */
    public String resolvedSourceType() {
        if (sourceType != null && !sourceType.isBlank()) return sourceType;
        if (eventType == null) return "UNKNOWN";
        if (eventType.startsWith("ARTICLE")) return "ARTICLE";
        if (eventType.startsWith("FEATURE")) return "FEATURE";
        if (eventType.startsWith("JOB")) return "JOB";
        return "UNKNOWN";
    }

    /** Returns the caller-supplied idempotencyKey, or a random UUID if omitted. */
    public String resolvedIdempotencyKey() {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) return idempotencyKey;
        return UUID.randomUUID().toString();
    }
}
