package com.renti.agent.infrastructure.client;

import java.util.List;
import java.util.Map;

import com.renti.agent.common.exception.BusinessException;
import com.renti.agent.modules.platform.application.IntegrationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * DeepSeek Chat 客户端（OpenAI 兼容协议）。
 *
 * <p>配置每次调用时从配置中心读取，管理端修改后即刻生效。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeepSeekClient {

    private final HttpClientFactory httpClientFactory;
    private final IntegrationSettingsService settings;

    /** 一条对话消息 */
    public record ChatMessage(String role, String content) {

        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }

        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }
    }

    /** 是否已配置可用的 API Key */
    public boolean isConfigured() {
        return !settings.llm().apiKey().isBlank();
    }

    /**
     * 同步对话补全，返回首个 choice 的文本内容。
     *
     * @throws BusinessException 上游失败或未配置时抛出（调用方决定是否降级）
     */
    @SuppressWarnings("unchecked")
    public String chat(List<ChatMessage> messages, double temperature) {
        var llm = settings.llm();
        if (llm.apiKey().isBlank()) {
            throw BusinessException.upstream("DeepSeek API Key 未配置。");
        }
        long started = System.currentTimeMillis();
        try {
            var client = httpClientFactory.create(llm.baseUrl(), "", llm.timeoutSeconds());
            Map<String, Object> response = client.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + llm.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", llm.chatModel(),
                            "messages", messages,
                            "temperature", temperature,
                            "stream", false))
                    .retrieve()
                    .body(Map.class);
            var choices = (List<Map<String, Object>>) (response == null ? List.of() : response.getOrDefault("choices", List.of()));
            if (choices.isEmpty()) {
                throw BusinessException.upstream("DeepSeek 返回为空。");
            }
            var message = (Map<String, Object>) choices.getFirst().get("message");
            var content = message == null ? null : String.valueOf(message.get("content"));
            log.info("DeepSeek chat ok, model={}, durationMs={}", llm.chatModel(), System.currentTimeMillis() - started);
            return content == null ? "" : content;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("DeepSeek chat failed after {}ms: {}", System.currentTimeMillis() - started, exception.getMessage());
            throw BusinessException.upstream("DeepSeek 调用失败：" + exception.getMessage());
        }
    }
}
