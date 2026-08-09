package site.yuqi.notifications.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OperationEventPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    @Value("${portfolio.kafka.operations-topic:platform.operation.events.v1}")
    private String topic;
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
        try {
            kafka.send(topic, resolvedCorrelation, objectMapper.writeValueAsString(event))
                    .whenComplete((ignored, error) -> {
                        if (error != null) log.warn("Failed to publish operation event {}: {}", eventType, error.getMessage());
                    });
        } catch (Exception error) {
            log.warn("Failed to serialize operation event {}: {}", eventType, error.getMessage());
        }
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
