package site.yuqi.notifications.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import site.yuqi.notifications.service.WebhookService;

@Component
@RequiredArgsConstructor
public class WebhookDispatchScheduler {
    private final WebhookService service;

    @Scheduled(fixedDelayString = "${portfolio.webhooks.dispatch-interval-ms:30000}")
    public void dispatch() { service.dispatchDue(); }
}
