package site.yuqi.notifications.operations;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperationEvent(String eventId, String eventType, int schemaVersion, Instant occurredAt,
                             String environment, String traceId, String spanId, String runId,
                             String correlationId, String causationId, String idempotencyKey,
                             Actor actor, Subject subject, String sourceService, String status,
                             int attempt, Long durationMs, Map<String, Object> attributes) {
    public record Actor(String type, String id) { }
    public record Subject(String type, String id, Integer version) { }
}
