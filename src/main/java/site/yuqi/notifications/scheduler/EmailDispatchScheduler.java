package site.yuqi.notifications.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import site.yuqi.notifications.service.EmailDispatchService;

@Component
@RequiredArgsConstructor
public class EmailDispatchScheduler {

    private final EmailDispatchService dispatcher;

    @Scheduled(
            fixedDelayString = "${portfolio.email.dispatch.interval-ms:15000}",
            initialDelayString = "${portfolio.email.dispatch.initial-delay-ms:10000}"
    )
    public void run() {
        dispatcher.dispatchOnce();
    }
}
