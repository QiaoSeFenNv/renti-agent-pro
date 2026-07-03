package com.renti.agent.modules.agent.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.renti.agent.infrastructure.client.AgentServiceClient;
import com.renti.agent.infrastructure.client.DeepSeekClient;
import com.renti.agent.infrastructure.persistence.entity.PropertyChatMessageEntity;
import com.renti.agent.infrastructure.persistence.entity.PropertyChatSessionEntity;
import com.renti.agent.infrastructure.persistence.repository.PropertyChatMessageRepository;
import com.renti.agent.infrastructure.persistence.repository.PropertyChatSessionRepository;
import com.renti.agent.modules.admin.application.UserInteractionService;
import com.renti.agent.modules.listing.application.ListingQueryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 房源问答会话：持久化 + 调 Python /agent/property-chat 生成回答。
 * 行为与响应结构对齐旧 property_chat_* payload（camelCase）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PropertyChatService {

    private static final String ENDPOINT = "/api/agent/property-chat";

    private final PropertyChatSessionRepository sessionRepository;
    private final PropertyChatMessageRepository messageRepository;
    private final AgentServiceClient agentServiceClient;
    private final DeepSeekClient deepSeekClient;
    private final ListingQueryService listingQueryService;
    private final UserInteractionService userInteractionService;

    @Transactional(readOnly = true)
    public Map<String, Object> sessionsPayload(Long userId, String listingId) {
        var cleanListingId = AgentPayloads.text(listingId);
        var sessions = sessionRepository
                .findTop50ByUserIdAndListingIdOrderByUpdatedAtDescIdDesc(userId, cleanListingId)
                .stream()
                .map(this::sessionPayload)
                .toList();
        return Map.of("ok", true, "listingId", cleanListingId, "sessions", sessions, "total", sessions.size());
    }

    @Transactional
    public Map<String, Object> createSession(Long userId, Map<String, Object> payload) {
        var listingId = AgentPayloads.text(payload.get("listingId"));
        if (listingId.isEmpty()) {
            return Map.of("ok", false, "code", "listing_id_required", "summary", "请提供 listingId。");
        }
        var session = new PropertyChatSessionEntity();
        session.setUserId(userId);
        session.setListingId(listingId);
        session.setTitle(AgentPayloads.text(payload.get("title"), "新的房源问答"));
        session.setModelProfile(AgentPayloads.text(payload.get("modelProfile"), "balanced"));
        var snapshot = AgentPayloads.mapValue(payload.get("property"));
        if (snapshot.isEmpty()) {
            snapshot = listingQueryService.findActive(listingId).map(AgentPayloads::listingMap).orElse(Map.of());
        }
        session.setPropertySnapshot(new LinkedHashMap<>(snapshot));
        sessionRepository.save(session);
        return Map.of("ok", true, "session", sessionPayload(session), "summary", "已创建新的房源问答会话。");
    }

    @Transactional
    public Map<String, Object> sendMessage(Long userId, long sessionId, Map<String, Object> payload) {
        long started = System.currentTimeMillis();
        var session = sessionRepository.findByIdAndUserId(sessionId, userId).orElse(null);
        if (session == null) {
            return Map.of("ok", false, "code", "not_found", "summary", "房源问答会话不存在，或不属于当前用户。");
        }
        var question = AgentPayloads.text(payload.get("message"), AgentPayloads.text(payload.get("question")));
        if (question.isEmpty()) {
            return Map.of("ok", false, "code", "message_required", "summary", "请输入要询问的问题。");
        }

        var listingId = AgentPayloads.text(payload.get("listingId"), session.getListingId());
        var listing = listingQueryService.findActive(listingId)
                .map(AgentPayloads::listingMap)
                .orElseGet(() -> new LinkedHashMap<>(session.getPropertySnapshot()));
        var previousMessages = messageRepository.findTop120BySessionIdOrderByCreatedAtAscIdAsc(session.getId());
        var history = previousMessages.stream()
                .skip(Math.max(0, previousMessages.size() - 10))
                .map(message -> Map.<String, Object>of("role", message.getRole(), "content", message.getContent()))
                .toList();

        var userMessage = saveMessage(session, "user", question, List.of(), List.of());

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userId", userId);
        request.put("listingId", listingId);
        request.put("listing", listing);
        request.put("history", history);
        request.put("message", question);
        request.put("modelProfile", AgentPayloads.text(payload.get("modelProfile"), session.getModelProfile()));

        var answerResult = generateAnswer(request, listing, question);
        var assistantMessage = saveMessage(
                session,
                "assistant",
                AgentPayloads.text(answerResult.get("content"), "抱歉，本次回答生成失败，请稍后重试。"),
                AgentPayloads.mapList(answerResult.get("citations")),
                AgentPayloads.mapList(answerResult.get("toolTrace")));

        if (previousMessages.isEmpty()) {
            session.setTitle(chatTitle(question));
        }
        session.setUpdatedAt(OffsetDateTime.now());
        sessionRepository.save(session);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("session", sessionPayload(session));
        result.put("userMessage", messagePayload(userMessage));
        result.put("assistantMessage", messagePayload(assistantMessage));
        result.put("answer", assistantMessage.getContent());
        result.put("citations", assistantMessage.getCitations());
        result.put("toolTrace", assistantMessage.getToolTrace());

        userInteractionService.record(userId, ENDPOINT, request, result, System.currentTimeMillis() - started);
        return result;
    }

    @Transactional
    public Map<String, Object> clearSessions(Long userId, String listingId) {
        var cleanListingId = AgentPayloads.text(listingId);
        var sessions = sessionRepository.findByUserIdAndListingId(userId, cleanListingId);
        if (!sessions.isEmpty()) {
            messageRepository.deleteBySessionIdIn(sessions.stream().map(PropertyChatSessionEntity::getId).toList());
            sessionRepository.deleteAll(sessions);
        }
        return Map.of(
                "ok", true,
                "removed", sessions.size(),
                "listingId", cleanListingId,
                "summary", "已清空 " + sessions.size() + " 个房源问答会话。");
    }

    /** Python 服务 → DeepSeek 直连 → 规则文案 的三级降级。 */
    private Map<String, Object> generateAnswer(Map<String, Object> request, Map<String, Object> listing, String question) {
        try {
            var result = agentServiceClient.propertyChat(request);
            if (Boolean.TRUE.equals(result.get("ok")) && !AgentPayloads.text(result.get("content")).isEmpty()) {
                return result;
            }
            log.info("Agent 服务 property-chat 返回失败：{}", result.get("summary"));
        } catch (Exception e) {
            log.warn("Agent 服务 property-chat 调用异常：{}", e.getMessage());
        }

        if (deepSeekClient.isConfigured()) {
            try {
                var answer = deepSeekClient.chat(List.of(
                        DeepSeekClient.ChatMessage.system("""
                                你是房源详情问答助手，只能基于给定房源 JSON 回答用户问题。
                                禁止编造联系方式、可租状态或价格承诺；字段缺失要说明待核验。只输出回答文本。
                                房源数据：""" + listing),
                        DeepSeekClient.ChatMessage.user(question)), 0.3);
                return Map.of(
                        "content", answer,
                        "citations", defaultCitations(listing),
                        "toolTrace", List.of(Map.of(
                                "tool", "property_chat_fallback",
                                "status", "llm_direct",
                                "summary", "Agent 服务不可用，已用 DeepSeek 直连回答。")));
            } catch (Exception e) {
                log.warn("DeepSeek 直连问答失败，使用规则文案：{}", e.getMessage());
            }
        }

        var answer = "AI 服务暂不可用。基于当前房源记录：租金 " + AgentPayloads.text(listing.get("rentPrice"), "待补充")
                + " 元/月，户型 " + AgentPayloads.text(listing.get("layout"), "待补充")
                + "，最近地铁 " + AgentPayloads.text(listing.get("nearestMetro"), "待补充")
                + "。建议核验来源链接与可租状态，稍后可重试提问。";
        return Map.of(
                "content", answer,
                "citations", defaultCitations(listing),
                "toolTrace", List.of(Map.of(
                        "tool", "property_chat_fallback",
                        "status", "rules_fallback",
                        "summary", "Agent 服务与 DeepSeek 均不可用，已返回规则文案。")));
    }

    private List<Map<String, Object>> defaultCitations(Map<String, Object> listing) {
        return List.of(
                Map.of("label", "租金", "value", AgentPayloads.text(listing.get("rentPrice"), "待补充")),
                Map.of("label", "户型", "value", AgentPayloads.text(listing.get("layout"), "待补充")),
                Map.of("label", "数据来源", "value", AgentPayloads.text(listing.get("source"), "待补充")));
    }

    private PropertyChatMessageEntity saveMessage(
            PropertyChatSessionEntity session,
            String role,
            String content,
            List<Map<String, Object>> citations,
            List<Map<String, Object>> toolTrace) {
        var message = new PropertyChatMessageEntity();
        message.setSessionId(session.getId());
        message.setUserId(session.getUserId());
        message.setRole(role);
        message.setContent(content.length() > 5000 ? content.substring(0, 5000) : content);
        message.setCitations(new ArrayList<>(citations));
        message.setToolTrace(new ArrayList<>(toolTrace));
        return messageRepository.save(message);
    }

    private Map<String, Object> sessionPayload(PropertyChatSessionEntity session) {
        var messages = messageRepository.findTop120BySessionIdOrderByCreatedAtAscIdAsc(session.getId())
                .stream()
                .map(this::messagePayload)
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", String.valueOf(session.getId()));
        payload.put("listingId", session.getListingId());
        payload.put("title", session.getTitle());
        payload.put("modelProfile", session.getModelProfile());
        payload.put("propertySnapshot", session.getPropertySnapshot());
        payload.put("createdAt", session.getCreatedAt());
        payload.put("updatedAt", session.getUpdatedAt());
        payload.put("messages", messages);
        return payload;
    }

    private Map<String, Object> messagePayload(PropertyChatMessageEntity message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", String.valueOf(message.getId()));
        payload.put("role", message.getRole());
        payload.put("content", message.getContent());
        payload.put("citations", message.getCitations());
        payload.put("toolTrace", message.getToolTrace());
        payload.put("createdAt", message.getCreatedAt());
        return payload;
    }

    private String chatTitle(String question) {
        var text = question.replaceAll("\\s+", " ").trim();
        return text.length() > 24 ? text.substring(0, 24) + "..." : text;
    }
}
