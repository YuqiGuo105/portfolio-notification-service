package site.yuqi.notifications.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OperationEventPublisher {
    private static final HttpClient HTTP = HttpClient.newBuilder().build();
    private final ObjectMapper objectMapper;

    @Value("${portfolio.operations.ingest-url:}")
    private String ingestUrl;
    @Value("${portfolio.operations.internal-token:}")
    private String internalToken;
    @Value("${portfolio.operations.timeout-ms:750}")
    private long timeoutMs;
    @Value("${spring.application.name:portfolio-notification-service}")
    private String service;
    @Value("${portfolio.environment:production}")
    private String environment;
    @Value("${portfolio.operations.enabled:true}")
    private boolean enabled;

    public void publishAfterCommit(String traceId, String correlationId, String causationId,
                                   String idempotencyKey, String eventType, String status,
                                   String subjectType, String subjectId, Integer subjectVersion,
                                   int attempt, Long durationMs, Map<String, Object> attributes) {
        Runnable publish = () -> publish(traceId, correlationId, causationId, idempotencyKey, eventType,
                status, subjectType, subjectId, subjectVersion, attempt, durationMs, attributes);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else {
            publish.run();
        }
    }

    public void publish(String traceId, String correlationId, String causationId,
                        String idempotencyKey, String eventType, String status,
                        String subjectType, String subjectId, Integer subjectVersion,
                        int attempt, Long durationMs, Map<String, Object> attributes) {
        if (!enabled) return;
        String resolvedCorrelation = nonBlank(correlationId, idempotencyKey);
        OperationEvent event = new OperationEvent(UUID.randomUUID().toString(), eventType, 1, Instant.now(),
                environment, traceId, null, null, resolvedCorrelation, causationId, idempotencyKey,
                new OperationEvent.Actor("SERVICE", service),
                new OperationEvent.Subject(subjectType, subjectId, subjectVersion), service, status,
                Math.max(1, attempt), durationMs, attributes == null ? Map.of() : Map.copyOf(attributes));
        if (ingestUrl == null || ingestUrl.isBlank() || internalToken == null || internalToken.isBlank()) return;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ingestUrl))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(event)))
                    .build();
            HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
                if (error != null || response.statusCode() >= 300) {
                    log.warn("Operation event ingest failed type={} status={}", eventType,
                            error == null ? response.statusCode() : error.getClass().getSimpleName());
                }
            });
        } catch (Exception error) {
            log.warn("Operation event ingest failed type={}: {}", eventType, error.getMessage());
        }
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
