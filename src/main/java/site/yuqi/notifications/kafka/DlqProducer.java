package site.yuqi.notifications.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@Slf4j
public class DlqProducer {

    private final KafkaTemplate<String, String> kafka;
    private final String dlqTopic;

    public DlqProducer(KafkaTemplate<String, String> kafka,
                       @Value("${portfolio.kafka.dlq-topic:portfolio.dlq}") String dlqTopic) {
        this.kafka = kafka;
        this.dlqTopic = dlqTopic;
    }

    /**
     * Best-effort publish to DLQ. If publishing fails we log loudly but do NOT throw —
     * the source message offset will still be committed because the alternative
     * (infinite redelivery of a poison pill) is worse.
     */
    public void publish(String key, String payload, String reason) {
        try {
            kafka.send(dlqTopic, key, payload).get(5, TimeUnit.SECONDS);
            log.warn("{\"event\":\"dlq_published\",\"topic\":\"{}\",\"key\":\"{}\",\"reason\":\"{}\"}",
                    dlqTopic, key, reason);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.error("{\"event\":\"dlq_interrupted\",\"reason\":\"{}\"}", reason);
        } catch (ExecutionException | TimeoutException e) {
            log.error("{\"event\":\"dlq_publish_failed\",\"reason\":\"{}\",\"err\":\"{}\"}",
                    reason, e.getMessage());
        }
    }
}
