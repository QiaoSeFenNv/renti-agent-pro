package com.renti.agent.modules.subscription.application;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.renti.agent.infrastructure.persistence.entity.SubscriberEntity;
import com.renti.agent.infrastructure.persistence.entity.SubscriptionEventEntity;
import com.renti.agent.infrastructure.persistence.entity.SubscriptionTokenEntity;
import com.renti.agent.infrastructure.persistence.repository.SubscriberRepository;
import com.renti.agent.infrastructure.persistence.repository.SubscriptionEventRepository;
import com.renti.agent.infrastructure.persistence.repository.SubscriptionTokenRepository;
import com.renti.agent.modules.auth.application.EmailOutboxService;
import com.renti.agent.modules.auth.application.PasswordService;
import com.renti.agent.modules.subscription.application.dto.SubscribeRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 邮箱订阅（double opt-in）：订阅申请、确认、退订与统计。
 * 行为对齐旧版 services/subscriptions.py。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+$");
    private static final Pattern SOURCE_INVALID_CHARS = Pattern.compile("[^A-Za-z0-9_\\-:.]");
    private static final long CONFIRM_TOKEN_TTL_DAYS = 7;
    private static final long UNSUBSCRIBE_TOKEN_TTL_DAYS = 365;
    private static final String PURPOSE_CONFIRM = "confirm";
    private static final String PURPOSE_UNSUBSCRIBE = "unsubscribe";

    private final SubscriberRepository subscriberRepository;
    private final SubscriptionTokenRepository subscriptionTokenRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final PasswordService passwordService;
    private final EmailOutboxService emailOutboxService;

    /** POST /api/home/subscribe */
    @Transactional
    public Map<String, Object> subscribe(SubscribeRequest request) {
        var email = normalizeEmail(request == null ? null : request.email());
        var source = normalizeSource(request == null ? null : request.source());

        if (!EMAIL_PATTERN.matcher(email).matches()) {
            var body = new LinkedHashMap<String, Object>();
            body.put("ok", false);
            body.put("code", "invalid_email");
            body.put("summary", "请输入有效邮箱地址。");
            body.put("fieldErrors", Map.of("email", "请输入有效邮箱地址。"));
            return body;
        }

        var now = OffsetDateTime.now();
        var subscriber = subscriberRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            var created = new SubscriberEntity();
            created.setEmail(email);
            created.setStatus("pending");
            created.setSourceFirst(source);
            return created;
        });
        var status = "confirmed".equals(subscriber.getStatus()) ? "confirmed" : "pending";
        subscriber.setStatus(status);
        subscriber.setSourceLast(source);
        incrementSourceCount(subscriber, source);
        if ("pending".equals(status)) {
            subscriber.setUnsubscribedAt(null);
        }
        subscriber.setUpdatedAt(now);
        subscriber = subscriberRepository.save(subscriber);

        var confirmToken = passwordService.generateToken();
        var unsubscribeToken = passwordService.generateToken();
        saveToken(subscriber.getId(), PURPOSE_CONFIRM, confirmToken, now.plusDays(CONFIRM_TOKEN_TTL_DAYS));
        saveToken(subscriber.getId(), PURPOSE_UNSUBSCRIBE, unsubscribeToken, now.plusDays(UNSUBSCRIBE_TOKEN_TTL_DAYS));
        saveEvent(subscriber.getId(), source, "request");

        var confirmUrl = subscriptionUrl(PURPOSE_CONFIRM, confirmToken);
        var unsubscribeUrl = subscriptionUrl(PURPOSE_UNSUBSCRIBE, unsubscribeToken);
        emailOutboxService.send(
                email,
                "subscription_double_opt_in",
                "确认订阅 RentAI 城市租房报告",
                "请点击确认订阅：%s\n如需退订：%s".formatted(confirmUrl, unsubscribeUrl));
        log.info("subscription request email={} source={} status={}", email, source, status);

        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("status", status);
        body.put("email", email);
        body.put("summary", "请查收确认邮件并完成订阅确认。确认前不会推送产品动态。");
        body.put("devConfirmUrl", confirmUrl);
        body.put("devUnsubscribeUrl", unsubscribeUrl);
        return body;
    }

    /** GET /api/home/subscribe/confirm?token= */
    @Transactional
    public Map<String, Object> confirm(String token) {
        var value = token == null ? "" : token.strip();
        if (value.isEmpty()) {
            return error("missing_token", "缺少确认链接 token。");
        }
        var tokenRow = validToken(value, PURPOSE_CONFIRM);
        if (tokenRow == null) {
            return error("invalid_or_expired_token", "确认链接无效或已过期。");
        }
        var subscriber = subscriberRepository.findById(tokenRow.getSubscriberId()).orElse(null);
        if (subscriber == null) {
            return error("invalid_or_expired_token", "确认链接无效或已过期。");
        }

        tokenRow.setUsedAt(OffsetDateTime.now());
        subscriptionTokenRepository.save(tokenRow);
        subscriber.setStatus("confirmed");
        if (subscriber.getConfirmedAt() == null) {
            subscriber.setConfirmedAt(OffsetDateTime.now());
        }
        subscriber.setUnsubscribedAt(null);
        subscriber.setUpdatedAt(OffsetDateTime.now());
        subscriberRepository.save(subscriber);
        saveEvent(subscriber.getId(), sourceOrUnknown(subscriber), PURPOSE_CONFIRM);

        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("status", subscriber.getStatus());
        body.put("email", subscriber.getEmail());
        body.put("summary", "订阅已确认，后续会接收 RentAI 城市租房报告和产品动态。");
        return body;
    }

    /** GET /api/home/subscribe/unsubscribe?token= */
    @Transactional
    public Map<String, Object> unsubscribe(String token) {
        var value = token == null ? "" : token.strip();
        if (value.isEmpty()) {
            return error("missing_token", "缺少退订链接 token。");
        }
        var tokenRow = validToken(value, PURPOSE_UNSUBSCRIBE);
        if (tokenRow == null) {
            return error("invalid_or_expired_token", "退订链接无效或已过期。");
        }
        var subscriber = subscriberRepository.findById(tokenRow.getSubscriberId()).orElse(null);
        if (subscriber == null) {
            return error("invalid_or_expired_token", "退订链接无效或已过期。");
        }

        tokenRow.setUsedAt(OffsetDateTime.now());
        subscriptionTokenRepository.save(tokenRow);
        subscriber.setStatus("unsubscribed");
        subscriber.setUnsubscribedAt(OffsetDateTime.now());
        subscriber.setUpdatedAt(OffsetDateTime.now());
        subscriberRepository.save(subscriber);
        saveEvent(subscriber.getId(), sourceOrUnknown(subscriber), PURPOSE_UNSUBSCRIBE);

        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("status", subscriber.getStatus());
        body.put("email", subscriber.getEmail());
        body.put("summary", "已退订 RentAI 更新邮件。");
        return body;
    }

    /** GET /api/home/subscribe/stats */
    @Transactional(readOnly = true)
    public Map<String, Object> statsPayload() {
        var byStatus = new LinkedHashMap<String, Long>();
        subscriberRepository.countByStatusGrouped()
                .forEach(row -> byStatus.put(row.getStatus(), row.getCount()));
        var bySource = new LinkedHashMap<String, Long>();
        subscriptionEventRepository.countRequestsBySource()
                .forEach(row -> bySource.put(row.getSource(), row.getCount()));

        var stats = new LinkedHashMap<String, Object>();
        stats.put("byStatus", byStatus);
        stats.put("bySource", bySource);
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("stats", stats);
        return body;
    }

    // ------------------------------------------------------------------ helpers

    private SubscriptionTokenEntity validToken(String token, String purpose) {
        return subscriptionTokenRepository
                .findFirstByTokenHashAndPurposeAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                        tokenHash(purpose, token), purpose, OffsetDateTime.now())
                .orElse(null);
    }

    private void saveToken(Long subscriberId, String purpose, String token, OffsetDateTime expiresAt) {
        var entity = new SubscriptionTokenEntity();
        entity.setSubscriberId(subscriberId);
        entity.setPurpose(purpose);
        entity.setTokenHash(tokenHash(purpose, token));
        entity.setExpiresAt(expiresAt);
        subscriptionTokenRepository.save(entity);
    }

    private void saveEvent(Long subscriberId, String source, String eventType) {
        var event = new SubscriptionEventEntity();
        event.setSubscriberId(subscriberId);
        event.setSource(source);
        event.setEventType(eventType);
        subscriptionEventRepository.save(event);
    }

    private void incrementSourceCount(SubscriberEntity subscriber, String source) {
        var counts = new LinkedHashMap<>(subscriber.getSourceCounts() == null
                ? Map.<String, Object>of() : subscriber.getSourceCounts());
        var current = counts.get(source) instanceof Number number ? number.intValue() : 0;
        counts.put(source, current + 1);
        subscriber.setSourceCounts(counts);
    }

    private String sourceOrUnknown(SubscriberEntity subscriber) {
        var source = subscriber.getSourceLast();
        return source == null || source.isBlank() ? "unknown" : source;
    }

    private String tokenHash(String purpose, String token) {
        return passwordService.sha256Hex("subscription:" + purpose + ":" + token);
    }

    private String subscriptionUrl(String purpose, String token) {
        return "/api/home/subscribe/" + purpose + "?token=" + token;
    }

    private Map<String, Object> error(String code, String summary) {
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", false);
        body.put("code", code);
        body.put("summary", summary);
        return body;
    }

    private String normalizeEmail(String value) {
        return (value == null ? "" : value).strip().toLowerCase();
    }

    private String normalizeSource(String value) {
        var raw = (value == null || value.isBlank()) ? "home" : value.strip();
        var cleaned = SOURCE_INVALID_CHARS.matcher(raw).replaceAll("_");
        if (cleaned.length() > 64) {
            cleaned = cleaned.substring(0, 64);
        }
        return cleaned.isEmpty() ? "home" : cleaned;
    }
}
