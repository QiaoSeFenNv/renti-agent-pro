package com.renti.agent.modules.subscription.api;

import java.util.Map;

import com.renti.agent.modules.subscription.application.SubscriptionService;
import com.renti.agent.modules.subscription.application.dto.SubscribeRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页邮箱订阅（double opt-in），全部为公开端点，与旧版一致。
 */
@Slf4j
@RestController
@RequestMapping("/api/home/subscribe")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @PostMapping
    public Map<String, Object> subscribe(@RequestBody(required = false) SubscribeRequest request) {
        return subscriptionService.subscribe(request);
    }

    @GetMapping("/confirm")
    public Map<String, Object> confirm(@RequestParam(defaultValue = "") String token) {
        var result = subscriptionService.confirm(token);
        log.info("subscription confirm ok={}", result.get("ok"));
        return result;
    }

    @GetMapping("/unsubscribe")
    public Map<String, Object> unsubscribe(@RequestParam(defaultValue = "") String token) {
        var result = subscriptionService.unsubscribe(token);
        log.info("subscription unsubscribe ok={}", result.get("ok"));
        return result;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return subscriptionService.statsPayload();
    }
}
