package com.renti.agent.modules.auth.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.renti.agent.common.config.RentiProperties;
import com.renti.agent.infrastructure.persistence.entity.EmailVerificationEntity;
import com.renti.agent.infrastructure.persistence.entity.UserEntity;
import com.renti.agent.infrastructure.persistence.entity.UserPreferenceEntity;
import com.renti.agent.infrastructure.persistence.repository.EmailVerificationRepository;
import com.renti.agent.infrastructure.persistence.repository.UserPreferenceRepository;
import com.renti.agent.infrastructure.persistence.repository.UserRepository;
import com.renti.agent.infrastructure.persistence.repository.UserSessionRepository;
import com.renti.agent.modules.auth.application.dto.ChangePasswordRequest;
import com.renti.agent.modules.auth.application.dto.LoginRequest;
import com.renti.agent.modules.auth.application.dto.RegisterRequest;
import com.renti.agent.modules.auth.application.dto.VerifyEmailRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证服务：注册（邮箱验证码）、登录（含限流与 pbkdf2 → bcrypt 升级）、
 * 会话查询、改密码、偏好清除。响应结构对齐旧版 services/auth.py（字段 camelCase）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    public static final long EMAIL_CODE_TTL_SECONDS = 15 * 60;
    private static final long RATE_LIMIT_WINDOW_SECONDS = 10 * 60;
    private static final int RATE_LIMIT_MAX_FAILURES = 5;
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+$");
    private static final Pattern VERIFICATION_CODE_PATTERN = Pattern.compile("^\\d{6}$");

    /** 登录失败限流：clientId:email → 窗口内失败时间戳（epoch 秒） */
    private final Map<String, List<Long>> loginFailures = new ConcurrentHashMap<>();

    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final UserPreferenceRepository userPreferenceRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final PasswordService passwordService;
    private final SessionService sessionService;
    private final EmailOutboxService emailOutboxService;
    private final RentiProperties properties;

    /** 登录结果：body 为响应载荷，sessionToken 非空时由 Controller 写 Cookie */
    public record LoginOutcome(Map<String, Object> body, String sessionToken) {
    }

    // ---------------------------------------------------------------- register

    @Transactional
    public Map<String, Object> register(RegisterRequest request) {
        var email = normalizeEmail(request.email());
        var displayName = normalizeDisplayName(request.preferredName(), email);
        var password = orEmpty(request.password());

        var fieldErrors = new LinkedHashMap<String, String>();
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            fieldErrors.put("email", "请输入有效邮箱地址。");
        }
        validatePasswordPolicy(password).ifPresent(message -> fieldErrors.put("password", message));
        if (!fieldErrors.isEmpty()) {
            return error("invalid_input", "请检查注册信息。", fieldErrors);
        }

        var existing = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (existing != null && existing.isEmailVerified()) {
            return error("email_taken", "该邮箱已经注册，请直接登录。", Map.of("email", "该邮箱已经注册。"));
        }

        UserEntity user;
        if (existing != null) {
            existing.setNickname(displayName);
            applyBcryptPassword(existing, password);
            existing.setUpdatedAt(OffsetDateTime.now());
            user = userRepository.save(existing);
        } else {
            user = new UserEntity();
            user.setEmail(email);
            user.setNickname(displayName);
            applyBcryptPassword(user, password);
            user.setEmailVerified(false);
            user = userRepository.save(user);
        }
        ensureDefaultPreferences(user.getId());
        issueVerificationCode(user);

        var body = payload(
                "ok", true,
                "status", existing != null ? "verification_resent" : "verification_required",
                "requiresVerification", true,
                "email", email,
                "summary", "注册信息已保存，请输入邮箱验证码完成验证。");
        body.put("devVerificationCode", latestCodeForDev(user.getId()));
        return body;
    }

    // ------------------------------------------------------------ verify email

    @Transactional
    public Map<String, Object> verifyEmail(VerifyEmailRequest request) {
        var email = normalizeEmail(request.email());
        var code = orEmpty(request.code()).trim();

        var fieldErrors = new LinkedHashMap<String, String>();
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            fieldErrors.put("email", "请输入有效邮箱地址。");
        }
        if (!VERIFICATION_CODE_PATTERN.matcher(code).matches()) {
            fieldErrors.put("code", "请输入 6 位邮箱验证码。");
        }
        if (!fieldErrors.isEmpty()) {
            return error("invalid_input", "请检查邮箱和验证码。", fieldErrors);
        }

        var verification = emailVerificationRepository
                .findFirstByEmailIgnoreCaseAndTokenHashAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                        email, verificationCodeHash(email, code), OffsetDateTime.now())
                .orElse(null);
        if (verification == null) {
            return error("invalid_or_expired_code", "验证码不正确或已过期。", Map.of("code", "验证码不正确或已过期。"));
        }
        var user = userRepository.findById(verification.getUserId()).orElse(null);
        if (user == null) {
            return error("invalid_or_expired_code", "验证码不正确或已过期。", Map.of("code", "验证码不正确或已过期。"));
        }

        verification.setConsumedAt(OffsetDateTime.now());
        emailVerificationRepository.save(verification);
        user.setEmailVerified(true);
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);

        return payload(
                "ok", true,
                "user", publicUser(user),
                "summary", "邮箱验证成功，现在可以登录 RentAI。");
    }

    // ----------------------------------------------------------------- login

    @Transactional
    public LoginOutcome login(LoginRequest request, String clientId) {
        var email = normalizeEmail(request.email());
        var password = orEmpty(request.password());

        var fieldErrors = new LinkedHashMap<String, String>();
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            fieldErrors.put("email", "请输入有效邮箱地址。");
        }
        if (password.length() < 8) {
            fieldErrors.put("password", "密码至少需要 8 位。");
        }
        if (!fieldErrors.isEmpty()) {
            return new LoginOutcome(error("invalid_input", "请检查邮箱和密码格式。", fieldErrors), null);
        }

        var rateKey = clientId + ":" + email;
        var nowEpoch = System.currentTimeMillis() / 1000;
        if (isRateLimited(rateKey, nowEpoch)) {
            return new LoginOutcome(error("rate_limited", "登录尝试过多，请稍后再试。", Map.of()), null);
        }

        var user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user == null || !verifyPassword(password, user)) {
            recordLoginFailure(rateKey, nowEpoch);
            return new LoginOutcome(error("invalid_credentials", "邮箱或密码不正确。", Map.of()), null);
        }

        if (!user.isEmailVerified()) {
            issueVerificationCode(user);
            var body = payload(
                    "ok", false,
                    "code", "email_unverified",
                    "requiresVerification", true,
                    "email", email,
                    "summary", "邮箱尚未验证，已重新生成验证码。",
                    "fieldErrors", Map.of());
            body.put("devVerificationCode", latestCodeForDev(user.getId()));
            return new LoginOutcome(body, null);
        }

        loginFailures.remove(rateKey);
        upgradeLegacyPasswordIfNeeded(user, password);
        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(user);

        var token = sessionService.createUserSession(user.getId(), truncate(clientId, 64));
        var expiresAt = OffsetDateTime.now().plusSeconds(properties.security().sessionTtlSeconds());
        var body = payload(
                "ok", true,
                "expiresAt", expiresAt.toEpochSecond(),
                "user", publicUser(user));
        return new LoginOutcome(body, token);
    }

    // ---------------------------------------------------------------- session

    @Transactional(readOnly = true)
    public Map<String, Object> session(String token) {
        if (token == null || token.isBlank()) {
            return anonymousSession();
        }
        var session = userSessionRepository.findByTokenHash(passwordService.sha256Hex(token))
                .filter(item -> item.getExpiresAt().isAfter(OffsetDateTime.now()))
                .orElse(null);
        if (session == null) {
            return anonymousSession();
        }
        var user = userRepository.findById(session.getUserId())
                .filter(UserEntity::isEmailVerified)
                .filter(item -> "active".equals(item.getStatus()))
                .orElse(null);
        if (user == null) {
            return anonymousSession();
        }
        return payload(
                "authenticated", true,
                "user", publicUser(user),
                "expiresAt", session.getExpiresAt().toEpochSecond());
    }

    @Transactional
    public Map<String, Object> logout(String token) {
        sessionService.revokeUserSession(token);
        return payload("ok", true, "summary", "已退出登录。");
    }

    // ---------------------------------------------------------- change password

    @Transactional
    public Map<String, Object> changePassword(Long userId, ChangePasswordRequest request) {
        var currentPassword = orEmpty(request.currentPassword());
        var newPassword = orEmpty(request.newPassword());
        var confirmPassword = orEmpty(request.confirmPassword());

        var fieldErrors = new LinkedHashMap<String, String>();
        if (currentPassword.length() < 8) {
            fieldErrors.put("currentPassword", "请输入当前密码。");
        }
        validatePasswordPolicy(newPassword).ifPresent(message -> fieldErrors.put("newPassword", message));
        if (!confirmPassword.equals(newPassword)) {
            fieldErrors.put("confirmPassword", "两次输入的新密码不一致。");
        }
        if (!fieldErrors.isEmpty()) {
            return error("invalid_input", "请检查密码填写。", fieldErrors);
        }

        var user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return error("user_not_found", "当前登录账号不存在或已失效。", Map.of());
        }
        if (!verifyPassword(currentPassword, user)) {
            return error("invalid_current_password", "当前密码不正确。", Map.of("currentPassword", "当前密码不正确。"));
        }
        if (verifyPassword(newPassword, user)) {
            return error("same_password", "新密码不能与当前密码相同。", Map.of("newPassword", "请设置一个不同的新密码。"));
        }

        applyBcryptPassword(user, newPassword);
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);
        return payload(
                "ok", true,
                "summary", "密码已更新，请妥善保管新的登录密码。",
                "user", publicUser(user));
    }

    // ------------------------------------------------------------- preferences

    @Transactional
    public Map<String, Object> clearPreferences(Long userId) {
        var user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return error("user_not_found", "当前登录账号不存在或已失效。", Map.of());
        }
        var preference = userPreferenceRepository.findById(userId).orElseGet(() -> {
            var created = new UserPreferenceEntity();
            created.setUserId(userId);
            return created;
        });
        preference.setBudgetMin(null);
        preference.setBudgetMax(null);
        preference.setCommuteTarget(null);
        preference.setCommuteMinutes(null);
        preference.setFavoriteAreas(new ArrayList<>());
        preference.setUpdatedAt(OffsetDateTime.now());
        userPreferenceRepository.save(preference);
        return payload(
                "ok", true,
                "summary", "偏好数据已清除，后续推荐不会继续使用这些默认条件。",
                "user", publicUser(user));
    }

    // ------------------------------------------------------------------ helpers

    /** 旧版 public_user：email/displayName/emailVerified/preferences */
    public Map<String, Object> publicUser(UserEntity user) {
        var preference = userPreferenceRepository.findById(user.getId()).orElse(null);
        var displayName = user.getNickname();
        if (displayName == null || displayName.isBlank()) {
            displayName = user.getEmail().split("@", 2)[0];
        }
        return payload(
                "email", user.getEmail(),
                "displayName", displayName,
                "emailVerified", user.isEmailVerified(),
                "preferences", payload(
                        "budget", budgetLabel(
                                preference == null ? null : preference.getBudgetMin(),
                                preference == null ? null : preference.getBudgetMax()),
                        "commuteTarget", commuteLabel(
                                preference == null ? null : preference.getCommuteTarget(),
                                preference == null ? null : preference.getCommuteMinutes()),
                        "favoriteAreas", preference == null || preference.getFavoriteAreas() == null
                                ? List.of() : List.copyOf(preference.getFavoriteAreas())));
    }

    private void issueVerificationCode(UserEntity user) {
        var pending = emailVerificationRepository.findByUserIdAndConsumedAtIsNull(user.getId());
        pending.forEach(item -> item.setConsumedAt(OffsetDateTime.now()));
        emailVerificationRepository.saveAll(pending);

        var code = passwordService.generateVerificationCode();
        var verification = new EmailVerificationEntity();
        verification.setUserId(user.getId());
        verification.setEmail(user.getEmail());
        verification.setCode(code);
        verification.setTokenHash(verificationCodeHash(user.getEmail(), code));
        verification.setExpiresAt(OffsetDateTime.now().plusSeconds(EMAIL_CODE_TTL_SECONDS));
        emailVerificationRepository.save(verification);

        emailOutboxService.send(
                user.getEmail(),
                "auth_email_verification",
                "RentAI 邮箱验证码",
                "你的 RentAI 邮箱验证码是 %s，15 分钟内有效。".formatted(code));
    }

    /** 开发态回显验证码（旧版 RENTAI_DEV_EMAIL_CODES 默认开启，此处保持同样默认） */
    private String latestCodeForDev(Long userId) {
        return emailVerificationRepository.findByUserIdAndConsumedAtIsNull(userId).stream()
                .reduce((first, second) -> second)
                .map(EmailVerificationEntity::getCode)
                .orElse(null);
    }

    private void ensureDefaultPreferences(Long userId) {
        var preference = userPreferenceRepository.findById(userId).orElseGet(() -> {
            var created = new UserPreferenceEntity();
            created.setUserId(userId);
            return created;
        });
        if (preference.getBudgetMin() == null) {
            preference.setBudgetMin(4500);
        }
        if (preference.getBudgetMax() == null) {
            preference.setBudgetMax(6500);
        }
        if (preference.getCommuteTarget() == null || preference.getCommuteTarget().isBlank()) {
            preference.setCommuteTarget("临港 / 张江");
        }
        if (preference.getCommuteMinutes() == null) {
            preference.setCommuteMinutes(30);
        }
        if (preference.getFavoriteAreas() == null || preference.getFavoriteAreas().isEmpty()) {
            preference.setFavoriteAreas(new ArrayList<>(List.of("临港", "张江", "中山公园")));
        }
        preference.setUpdatedAt(OffsetDateTime.now());
        userPreferenceRepository.save(preference);
    }

    private void applyBcryptPassword(UserEntity user, String rawPassword) {
        user.setPasswordHash(passwordService.hash(rawPassword));
        user.setPasswordAlgo(PasswordService.ALGO_BCRYPT);
        user.setPasswordSalt(null);
        user.setPasswordIterations(null);
    }

    private boolean verifyPassword(String rawPassword, UserEntity user) {
        return passwordService.verify(rawPassword, user.getPasswordAlgo(), user.getPasswordHash(),
                user.getPasswordSalt(), user.getPasswordIterations());
    }

    /** 旧 pbkdf2 用户校验通过后就地升级为 bcrypt 并清空 salt/iterations */
    private void upgradeLegacyPasswordIfNeeded(UserEntity user, String rawPassword) {
        if (PasswordService.ALGO_PBKDF2.equalsIgnoreCase(user.getPasswordAlgo())) {
            applyBcryptPassword(user, rawPassword);
            user.setUpdatedAt(OffsetDateTime.now());
            log.info("Upgraded legacy pbkdf2 password to bcrypt for user {}", user.getId());
        }
    }

    private String verificationCodeHash(String email, String code) {
        return passwordService.sha256Hex("email-verification:" + normalizeEmail(email) + ":" + code);
    }

    /** 旧版密码策略：10-128 位、含字母和数字、无空白字符 */
    private java.util.Optional<String> validatePasswordPolicy(String password) {
        if (password.length() < 10) {
            return java.util.Optional.of("密码至少需要 10 位。");
        }
        if (password.length() > 128) {
            return java.util.Optional.of("密码不能超过 128 位。");
        }
        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            return java.util.Optional.of("密码需要同时包含字母和数字。");
        }
        if (password.matches(".*\\s.*")) {
            return java.util.Optional.of("密码不能包含空白字符。");
        }
        return java.util.Optional.empty();
    }

    private boolean isRateLimited(String key, long nowEpochSeconds) {
        var attempts = pruneFailures(key, nowEpochSeconds);
        return attempts.size() >= RATE_LIMIT_MAX_FAILURES;
    }

    private void recordLoginFailure(String key, long nowEpochSeconds) {
        var attempts = pruneFailures(key, nowEpochSeconds);
        attempts.add(nowEpochSeconds);
        loginFailures.put(key, attempts);
    }

    private List<Long> pruneFailures(String key, long nowEpochSeconds) {
        var attempts = new ArrayList<>(loginFailures.getOrDefault(key, List.of()));
        attempts.removeIf(item -> nowEpochSeconds - item >= RATE_LIMIT_WINDOW_SECONDS);
        loginFailures.put(key, attempts);
        return attempts;
    }

    private String budgetLabel(Integer min, Integer max) {
        if (min != null && min > 0 && max != null && max > 0) {
            return "¥%,d - ¥%,d/月".formatted(min, max);
        }
        if (max != null && max > 0) {
            return "¥%,d/月以内".formatted(max);
        }
        return "尚未设置预算";
    }

    private String commuteLabel(String target, Integer minutes) {
        var targetText = target == null || target.isBlank() ? "尚未设置通勤目标" : target;
        if (minutes != null && minutes > 0) {
            return targetText + " · " + minutes + " 分钟内";
        }
        return targetText;
    }

    private Map<String, Object> anonymousSession() {
        var body = new LinkedHashMap<String, Object>();
        body.put("authenticated", false);
        body.put("user", null);
        body.put("expiresAt", null);
        return body;
    }

    private Map<String, Object> error(String code, String summary, Map<String, String> fieldErrors) {
        return payload(
                "ok", false,
                "code", code,
                "summary", summary,
                "fieldErrors", fieldErrors);
    }

    private String normalizeEmail(String value) {
        return orEmpty(value).trim().toLowerCase();
    }

    private String normalizeDisplayName(String value, String email) {
        var displayName = orEmpty(value).trim();
        if (displayName.isEmpty()) {
            displayName = email.split("@", 2)[0];
        }
        return truncate(displayName, 64);
    }

    private String truncate(String value, int limit) {
        var text = orEmpty(value);
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 构建保持插入顺序、允许 null 值的响应 Map */
    private static LinkedHashMap<String, Object> payload(Object... pairs) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
