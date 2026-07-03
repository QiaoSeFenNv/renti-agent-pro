package com.renti.agent.modules.agent.api;

import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.renti.agent.common.annotation.CurrentUser;
import com.renti.agent.modules.agent.application.AgentSearchService;
import com.renti.agent.modules.agent.application.PropertyChatService;
import com.renti.agent.modules.agent.application.PropertyInsightService;
import com.renti.agent.modules.auth.application.UserPrincipal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AI Agent 接入层：rental-search / property-insight / property-chat（契约 §C）。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentSearchService agentSearchService;
    private final PropertyInsightService propertyInsightService;
    private final PropertyChatService propertyChatService;

    @PostMapping("/rental-search")
    public Map<String, Object> rentalSearch(
            @CurrentUser UserPrincipal user,
            @RequestBody(required = false) Map<String, Object> payload) {
        log.info("agent rental-search userId={}", user.id());
        return agentSearchService.rentalSearch(user.id(), payload == null ? Map.of() : payload);
    }

    @PostMapping("/property-insight")
    public Map<String, Object> propertyInsight(
            @CurrentUser UserPrincipal user,
            @RequestBody(required = false) Map<String, Object> payload) {
        log.info("agent property-insight userId={}", user.id());
        return propertyInsightService.insight(user.id(), payload == null ? Map.of() : payload);
    }

    @GetMapping("/property-chat/sessions")
    public Map<String, Object> chatSessions(
            @CurrentUser UserPrincipal user,
            @RequestParam(name = "listingId", required = false, defaultValue = "") String listingId) {
        return propertyChatService.sessionsPayload(user.id(), listingId);
    }

    @PostMapping("/property-chat/sessions")
    public Map<String, Object> createChatSession(
            @CurrentUser UserPrincipal user,
            @RequestBody(required = false) Map<String, Object> payload) {
        return propertyChatService.createSession(user.id(), payload == null ? Map.of() : payload);
    }

    @PostMapping("/property-chat/sessions/{sessionId}/messages")
    public Map<String, Object> sendChatMessage(
            @CurrentUser UserPrincipal user,
            @PathVariable long sessionId,
            @RequestBody(required = false) Map<String, Object> payload) {
        log.info("agent property-chat message userId={} sessionId={}", user.id(), sessionId);
        return propertyChatService.sendMessage(user.id(), sessionId, payload == null ? Map.of() : payload);
    }

    @DeleteMapping("/property-chat/sessions")
    public Map<String, Object> clearChatSessions(
            @CurrentUser UserPrincipal user,
            @RequestParam(name = "listingId", required = false, defaultValue = "") String listingId) {
        return propertyChatService.clearSessions(user.id(), listingId);
    }
}
