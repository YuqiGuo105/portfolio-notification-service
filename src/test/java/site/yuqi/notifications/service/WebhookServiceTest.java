package site.yuqi.notifications.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import site.yuqi.notifications.dto.WebhookSubscriptionRequest;
import site.yuqi.notifications.repository.WebhookRepository;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WebhookServiceTest {
    private WebhookRepository repository;
    private WebhookService service;

    @BeforeEach
    void setUp() {
        repository = mock(WebhookRepository.class);
        service = new WebhookService(repository);
        ReflectionTestUtils.setField(service, "signingKey", "test-signing-key");
    }

    @Test
    void createsPublicHttpsSubscriptionAndReturnsSecretOnce() {
        var item = service.create(new WebhookSubscriptionRequest(
                "https://8.8.8.8/hooks/portfolio", Set.of("PUBLICATION_FAILED"), "Codex"));
        assertThat(item.signingSecret()).isNotBlank();
        verify(repository).create(item.id(), item.callbackUrl(), item.eventTypes(), "Codex");
    }

    @Test
    void rejectsPrivateAndUnsupportedCallbacks() {
        assertThatThrownBy(() -> service.create(new WebhookSubscriptionRequest(
                "https://127.0.0.1/hook", Set.of("PUBLICATION_COMPLETED"), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new WebhookSubscriptionRequest(
                "http://8.8.8.8/hook", Set.of("PUBLICATION_COMPLETED"), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
