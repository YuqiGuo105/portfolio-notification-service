package site.yuqi.notifications.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import site.yuqi.notifications.service.ContentEventProcessor;
import site.yuqi.notifications.service.ContentEventProcessor.Outcome;

@Component
@RequiredArgsConstructor
@Slf4j
public class ContentEventConsumer {

    private final ContentEventProcessor processor;
    private final DlqProducer dlq;

    @KafkaListener(
            topics = {
                    "${portfolio.kafka.notification.article-updates-topic:content.notification.article-updates.v1}",
                    "${portfolio.kafka.notification.feature-updates-topic:content.notification.feature-updates.v1}",
                    "${portfolio.kafka.notification.job-updates-topic:content.notification.job-updates.v1}"
            },
            groupId = "${spring.kafka.consumer.group-id:portfolio-notification-consumer-group}",
            autoStartup = "${portfolio.kafka.consumer-enabled:true}"
    )
    public void onMessage(ConsumerRecord<String, String> rec, Acknowledgment ack) {
        String value = rec.value();
        String topic = rec.topic();
        int partition = rec.partition();
        long offset = rec.offset();

        log.info("{\"event\":\"consume\",\"topic\":\"{}\",\"partition\":{},\"offset\":{}}",
                topic, partition, offset);

        Outcome outcome;
        try {
            outcome = processor.process(value, topic, partition, String.valueOf(offset));
        } catch (Exception unexpected) {
            // Unexpected throwable from processor (shouldn't happen — processor catches and returns Outcome)
            log.error("{\"event\":\"processor_threw\",\"offset\":{},\"err\":\"{}\"}",
                    offset, unexpected.getMessage());
            outcome = Outcome.RETRY;
        }

        switch (outcome) {
            case DONE:
                ack.acknowledge();
                break;
            case DLQ:
                dlq.publish(rec.key(), value == null ? "" : value,
                        "invalid event at " + topic + "-" + partition + "@" + offset);
                ack.acknowledge();
                break;
            case RETRY:
            default:
                // Do not ack; container will redeliver after the next poll (or after a rebalance).
                // We sleep briefly to avoid a tight retry loop during sustained DB outages.
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                break;
        }
    }
}
