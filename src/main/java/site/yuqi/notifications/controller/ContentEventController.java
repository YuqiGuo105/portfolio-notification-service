package site.yuqi.notifications.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.notifications.dto.ContentEvent;
import site.yuqi.notifications.dto.ContentEventRequest;
import site.yuqi.notifications.service.ContentEventProcessor;
import site.yuqi.notifications.service.ContentEventProcessor.Outcome;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP entry-point for triggering content-event fan-out without going through Kafka.
 *
 * <p>This endpoint is designed to be called by the Next.js admin panel after publishing
 * a new article / feature / job update. The Vercel API route injects {@code X-Internal-Token}
 * before forwarding here, so the browser never handles the secret.
 *
 * <p>Internally the request is turned into a {@link ContentEvent} and passed directly to
 * {@link ContentEventProcessor#process} — the same logic the Kafka consumer uses, including
 * idempotency checking and fan-out to all matching subscribers.
 */
@RestController
@RequestMapping("/api/content-events")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Content Events", description = "Trigger subscriber fan-out for new articles / features / jobs")
public class ContentEventController {

    private final ContentEventProcessor processor;
    private final ObjectMapper objectMapper;

    @PostMapping
    @Operation(
            summary = "Publish a content event (HTTP trigger)",
            description = """
                    Publishes a content event that fans out notifications to all subscribers
                    who opted in to the matching topic + channel combination.

                    **eventType** must be one of:
                    - `ARTICLE_PUBLISHED`
                    - `ARTICLE_UPDATED`
                    - `FEATURE_RELEASED`
                    - `JOB_POSITION_UPDATED`

                    **topic** must be one of:
                    - `ARTICLE_UPDATES`
                    - `FEATURE_UPDATES`
                    - `JOB_UPDATES`

                    If `idempotencyKey` is omitted a UUID is generated; supply a stable key
                    (e.g. `article-<slug>-published`) so retries do not create duplicate notifications.
                    """,
            security = @SecurityRequirement(name = "X-Internal-Token"),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "New article", value = """
                                            {
                                              "eventType": "ARTICLE_PUBLISHED",
                                              "topic": "ARTICLE_UPDATES",
                                              "title": "Building a Spring Boot notification service",
                                              "summary": "From Kafka to email — a complete walkthrough.",
                                              "url": "https://www.yuqi.site/blogs/spring-notification",
                                              "sourceType": "ARTICLE",
                                              "sourceId": "spring-notification",
                                              "idempotencyKey": "article-spring-notification-published"
                                            }
                                            """),
                                    @ExampleObject(name = "Feature release", value = """
                                            {
                                              "eventType": "FEATURE_RELEASED",
                                              "topic": "FEATURE_UPDATES",
                                              "title": "Notification bell is live",
                                              "summary": "Subscribers now get real-time web + email notifications.",
                                              "url": "https://www.yuqi.site",
                                              "idempotencyKey": "feature-notification-bell-v1"
                                            }
                                            """),
                                    @ExampleObject(name = "Job update", value = """
                                            {
                                              "eventType": "JOB_POSITION_UPDATED",
                                              "topic": "JOB_UPDATES",
                                              "title": "Open to new opportunities",
                                              "summary": "Looking for senior / staff engineering roles.",
                                              "url": "https://www.yuqi.site/cv",
                                              "idempotencyKey": "job-open-to-opportunities-2026"
                                            }
                                            """)
                            }
                    )
            ),
            responses = {
                    @ApiResponse(responseCode = "200", description = "Event processed (DONE or already processed)"),
                    @ApiResponse(responseCode = "202", description = "Event queued for retry (transient DB error)"),
                    @ApiResponse(responseCode = "400", description = "Invalid request body"),
                    @ApiResponse(responseCode = "422", description = "Event permanently invalid (sent to DLQ)"),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid X-Internal-Token"),
                    @ApiResponse(responseCode = "503", description = "Service not configured (internal token unset)")
            }
    )
    public ResponseEntity<Map<String, Object>> publish(
            @Valid @RequestBody ContentEventRequest req) {

        String idempotencyKey = req.resolvedIdempotencyKey();
        String eventId = UUID.randomUUID().toString();

        // Build a ContentEvent matching the same schema the Kafka consumer uses
        ContentEvent event = new ContentEvent(
                eventId,
                req.eventType(),
                req.topic(),
                req.resolvedSourceType(),
                req.sourceId(),
                req.title(),
                req.summary(),
                req.url(),
                OffsetDateTime.now(),
                idempotencyKey,
                req.metadata()
        );

        String rawJson;
        try {
            rawJson = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("{\"event\":\"serialize_failed\",\"idempotencyKey\":\"{}\",\"err\":\"{}\"}", idempotencyKey, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "serialize_failed", "message", e.getMessage()));
        }

        log.info("{\"event\":\"http_content_event\",\"idempotencyKey\":\"{}\",\"eventType\":\"{}\",\"topic\":\"{}\"}",
                idempotencyKey, req.eventType(), req.topic());

        // Reuse exactly the same logic as the Kafka consumer (idempotency + fan-out)
        Outcome outcome = processor.process(rawJson, "http-trigger", null, idempotencyKey);

        return switch (outcome) {
            case DONE -> ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "outcome", "DONE",
                    "idempotencyKey", idempotencyKey,
                    "eventId", eventId
            ));
            case DLQ -> ResponseEntity.unprocessableEntity().body(Map.of(
                    "status", "error",
                    "outcome", "DLQ",
                    "message", "Event failed validation and was rejected."
            ));
            case RETRY -> ResponseEntity.accepted().body(Map.of(
                    "status", "retry",
                    "outcome", "RETRY",
                    "message", "Transient error; safe to retry."
            ));
        };
    }
}
