package site.yuqi.notifications.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailDispatchServiceBackoffTest {

    @Test
    void backoffGrowsExponentiallyAndCaps() {
        assertEquals(60, EmailDispatchService.nextBackoff(0));
        assertEquals(120, EmailDispatchService.nextBackoff(1));
        assertEquals(240, EmailDispatchService.nextBackoff(2));
        assertEquals(480, EmailDispatchService.nextBackoff(3));
        assertEquals(960, EmailDispatchService.nextBackoff(4));
        // 60 * 2^4 = 960 still capped at 3600
        assertTrue(EmailDispatchService.nextBackoff(10) <= 3600);
    }
}
