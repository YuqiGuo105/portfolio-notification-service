package site.yuqi.notifications.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import site.yuqi.notifications.service.WebPushService;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/push")
@RequiredArgsConstructor
public class WebPushController {
    private final WebPushService service;
    @GetMapping("/config")
    public Map<String, Object> config() { return service.config(); }
    @PostMapping("/subscriptions")
    public Map<String, Object> subscribe(@Valid @RequestBody Registration request) {
        var s = request.subscription();
        return Map.of("id", service.register(request.subscriberId(), request.subscriberToken(), s.endpoint(), s.keys().p256dh(), s.keys().auth()), "status", "ACTIVE");
    }
    @DeleteMapping("/subscriptions")
    public Map<String, Object> remove(@Valid @RequestBody Removal request) {
        return Map.of("removed", service.remove(request.subscriberId(), request.subscriberToken(), request.endpoint()));
    }
    @PostMapping("/status")
    public Map<String, Object> status(@Valid @RequestBody Removal request) {
        return Map.of("active", service.active(request.subscriberId(), request.subscriberToken(), request.endpoint()));
    }
    public record Keys(@NotBlank @Size(max=128) String p256dh, @NotBlank @Size(max=64) String auth) {}
    public record Device(@NotBlank @Size(max=2048) String endpoint, @NotNull @Valid Keys keys) {}
    public record Registration(@NotNull UUID subscriberId, @NotBlank @Size(max=256) String subscriberToken, @NotNull @Valid Device subscription) {}
    public record Removal(@NotNull UUID subscriberId, @NotBlank @Size(max=256) String subscriberToken, @NotBlank @Size(max=2048) String endpoint) {}
}
