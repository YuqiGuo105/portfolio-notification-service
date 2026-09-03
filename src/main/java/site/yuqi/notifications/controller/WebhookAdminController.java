package site.yuqi.notifications.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.notifications.dto.WebhookSubscriptionItem;
import site.yuqi.notifications.dto.WebhookSubscriptionRequest;
import site.yuqi.notifications.service.WebhookService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/webhook-subscriptions")
@RequiredArgsConstructor
public class WebhookAdminController {
    private final WebhookService service;

    @GetMapping
    public List<WebhookSubscriptionItem> list() { return service.list(); }

    @PostMapping
    public WebhookSubscriptionItem create(@Valid @RequestBody WebhookSubscriptionRequest request) {
        return service.create(request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
