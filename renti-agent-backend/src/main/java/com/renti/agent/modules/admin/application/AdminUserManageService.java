package com.renti.agent.modules.admin.application;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.renti.agent.infrastructure.persistence.entity.SearchHistoryEntity;
import com.renti.agent.infrastructure.persistence.entity.UserEntity;
import com.renti.agent.infrastructure.persistence.entity.UserSettingsEntity;
import com.renti.agent.infrastructure.persistence.entity.UserWorkspaceConfigEntity;
import com.renti.agent.infrastructure.persistence.repository.SearchHistoryRepository;
import com.renti.agent.infrastructure.persistence.repository.UserRepository;
import com.renti.agent.infrastructure.persistence.repository.UserSessionRepository;
import com.renti.agent.infrastructure.persistence.repository.UserSettingsRepository;
import com.renti.agent.infrastructure.persistence.repository.UserWorkspaceConfigRepository;
import com.renti.agent.modules.auth.application.PasswordService;
import com.renti.agent.modules.user.application.UserSettingsService;
import com.renti.agent.modules.user.application.UserWorkspaceService;
import com.renti.agent.modules.user.application.WorkspaceConfigService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.renti.agent.modules.admin.application.ObservabilitySupport.asMap;
import static com.renti.agent.modules.admin.application.ObservabilitySupport.boundedInt;

/**
 * 管理端用户管理：列表/详情/设置/工作台配置/重置密码/删除。
 * 响应结构对齐旧 services/admin.py 的 admin_users_payload / admin_user_detail_payload 等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserManageService {

    private static final Pattern LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final Pattern WHITESPACE = Pattern.compile("\\s");

    private final UserRepository userRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final UserWorkspaceConfigRepository userWorkspaceConfigRepository;
    private final SearchHistoryRepository searchHistoryRepository;
    private final UserSessionRepository userSessionRepository;
    private final UserWorkspaceService userWorkspaceService;
    private final UserSettingsService userSettingsService;
    private final WorkspaceConfigService workspaceConfigService;
    private final PasswordService passwordService;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    /** GET /api/admin/users?limit= */
    @Transactional(readOnly = true)
    public Map<String, Object> usersPayload(Integer limit) {
        int pageSize = boundedInt(limit, 50, 1, 200);
        var users = userRepository
                .findAll(PageRequest.of(0, pageSize, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();
        var favoriteCounts = groupedCounts("SELECT f.userId, COUNT(f) FROM UserFavoriteEntity f GROUP BY f.userId");
        var historyCounts = groupedCounts("SELECT h.userId, COUNT(h) FROM SearchHistoryEntity h GROUP BY h.userId");

        var rows = users.stream()
                .map(user -> adminUserMap(user,
                        favoriteCounts.getOrDefault(user.getId(), 0L),
                        historyCounts.getOrDefault(user.getId(), 0L)))
                .toList();
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("users", rows);
        body.put("total", rows.size());
        return body;
    }

    /** GET /api/admin/users/{id}：用户 + 工作台配置 + 收藏 + 历史 */
    @Transactional(readOnly = true)
    public Map<String, Object> userDetailPayload(long userId) {
        var user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return error("not_found", "用户不存在。");
        }
        var favoriteCount = jpqlCount(
                "SELECT COUNT(f) FROM UserFavoriteEntity f WHERE f.userId = " + userId);
        var historyCount = searchHistoryRepository.countByUserId(userId);

        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("user", adminUserMap(user, favoriteCount, historyCount));
        body.put("config", configPayload(userId));
        body.put("favorites", userWorkspaceService.listFavoritesPayload(userId).get("favorites"));
        body.put("history", historyRows(userId));
        return body;
    }

    /** PUT /api/admin/users/{id}/settings：对齐旧 update_user_settings_payload */
    @Transactional
    public Map<String, Object> updateSettingsPayload(long userId, Map<String, Object> payload) {
        var config = effectiveConfig(userId);
        var incoming = payload != null && payload.get("settings") instanceof Map<?, ?>
                ? asMap(payload.get("settings"))
                : payload == null ? Map.<String, Object>of() : payload;

        var merged = new LinkedHashMap<>(currentSettings(userId, config));
        merged.putAll(incoming);
        var settings = saveSettings(userId, merged, config);

        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("settings", settings);
        body.put("modelOptions", workspaceConfigService.enabledModelOptions(config));
        body.put("listingPageSizeOptions", workspaceConfigService.listingPageSizeOptions(config));
        body.put("defaultListingPageSize", workspaceConfigService.defaultListingPageSize(config));
        body.put("usesPlatformDefault", !userWorkspaceConfigRepository.existsById(userId));
        body.put("summary", "设置已保存。");
        return body;
    }

    /** PUT /api/admin/users/{id}/config：对齐旧 update_user_workspace_config_payload */
    @Transactional
    public Map<String, Object> updateConfigPayload(long userId, Map<String, Object> payload) {
        var config = workspaceConfigService.normalize(payload == null ? Map.of() : payload);
        var entity = userWorkspaceConfigRepository.findById(userId).orElseGet(() -> {
            var created = new UserWorkspaceConfigEntity();
            created.setUserId(userId);
            return created;
        });
        entity.setConfig(config);
        entity.setUpdatedAt(OffsetDateTime.now());
        userWorkspaceConfigRepository.save(entity);

        var settings = saveSettings(userId, currentSettings(userId, config), config);
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.putAll(config);
        body.put("settings", settings);
        body.put("usesPlatformDefault", false);
        body.put("summary", "用户专属模型配置已保存。");
        return body;
    }

    /** DELETE /api/admin/users/{id}/config：对齐旧 reset_user_workspace_config_payload */
    @Transactional
    public Map<String, Object> resetConfigPayload(long userId) {
        userWorkspaceConfigRepository.deleteById(userId);
        var config = workspaceConfigService.config();
        var settings = saveSettings(userId, currentSettings(userId, config), config);
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.putAll(config);
        body.put("settings", settings);
        body.put("usesPlatformDefault", true);
        body.put("summary", "已恢复为平台默认配置。");
        return body;
    }

    /** POST /api/admin/users/{id}/password：BCrypt 重置并吊销全部会话 */
    @Transactional
    public Map<String, Object> resetPasswordPayload(long userId, Map<String, Object> payload) {
        var source = payload == null ? Map.<String, Object>of() : payload;
        var password = String.valueOf(source.get("password") != null ? source.get("password")
                : source.get("newPassword") != null ? source.get("newPassword") : "");
        var fieldError = passwordPolicyError(password);
        if (fieldError != null) {
            var body = error("invalid_password", "请检查新密码。");
            body.put("fieldErrors", Map.of("password", fieldError));
            return body;
        }
        var user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return error("not_found", "用户不存在。");
        }
        user.setPasswordHash(passwordService.hash(password));
        user.setPasswordAlgo(PasswordService.ALGO_BCRYPT);
        user.setPasswordSalt(null);
        user.setPasswordIterations(null);
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);
        userSessionRepository.deleteByUserId(userId);
        log.info("Admin reset password for user {}", userId);

        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("summary", "用户密码已重置，现有登录态已失效。");
        return body;
    }

    /** DELETE /api/admin/users/{id}：删除账号并级联清理工作台数据 */
    @Transactional
    public Map<String, Object> deleteUserPayload(long userId) {
        var user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return error("not_found", "用户不存在。");
        }
        userSessionRepository.deleteByUserId(userId);
        searchHistoryRepository.deleteByUserId(userId);
        bulkDelete("DELETE FROM UserFavoriteEntity f WHERE f.userId = :userId", userId);
        bulkDelete("DELETE FROM ImportedListingEntity i WHERE i.userId = :userId", userId);
        bulkDelete("DELETE FROM NotificationReadEntity n WHERE n.userId = :userId", userId);
        bulkDelete("DELETE FROM UserPreferenceEntity p WHERE p.userId = :userId", userId);
        userSettingsRepository.deleteById(userId);
        userWorkspaceConfigRepository.deleteById(userId);
        userRepository.delete(user);
        log.info("Admin deleted user {}", userId);

        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("deleted", true);
        body.put("summary", "用户账号已删除，关联工作台数据已清理。");
        return body;
    }

    // ------------------------------------------------------------------ helpers

    /** 对齐旧 _admin_user_from_row */
    private Map<String, Object> adminUserMap(UserEntity user, long favoriteCount, long historyCount) {
        var preferences = new LinkedHashMap<String, Object>();
        preferences.put("budgetMin", user.getBudgetMin());
        preferences.put("budgetMax", user.getBudgetMax());
        preferences.put("commuteTarget", user.getCommuteTarget() == null ? "" : user.getCommuteTarget());
        preferences.put("commuteMinutes", user.getCommuteMinutes());
        preferences.put("favoriteAreas", parseFavoriteAreas(user.getFavoriteAreas()));

        var workspace = new LinkedHashMap<String, Object>();
        workspace.put("favoriteCount", favoriteCount);
        workspace.put("historyCount", historyCount);
        workspace.put("settings", userSettingsRepository.findById(user.getId())
                .map(UserSettingsEntity::getSettings).orElseGet(LinkedHashMap::new));
        workspace.put("usesPlatformDefault", !userWorkspaceConfigRepository.existsById(user.getId()));

        var value = new LinkedHashMap<String, Object>();
        value.put("id", user.getId());
        value.put("email", user.getEmail());
        value.put("displayName", user.getNickname() == null || user.getNickname().isBlank()
                ? user.getEmail() : user.getNickname());
        value.put("emailVerified", user.isEmailVerified());
        value.put("createdAt", user.getCreatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        value.put("updatedAt", user.getUpdatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        value.put("preferences", preferences);
        value.put("workspace", workspace);
        return value;
    }

    /** 对齐旧 get_user_workspace_config_payload */
    private Map<String, Object> configPayload(long userId) {
        var custom = userWorkspaceConfigRepository.findById(userId);
        var config = custom
                .map(entity -> workspaceConfigService.normalize(entity.getConfig()))
                .orElseGet(workspaceConfigService::config);
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.putAll(config);
        body.put("usesPlatformDefault", custom.isEmpty());
        return body;
    }

    /** 用户生效配置：专属配置优先，否则平台配置 */
    private Map<String, Object> effectiveConfig(long userId) {
        return userWorkspaceConfigRepository.findById(userId)
                .map(entity -> workspaceConfigService.normalize(entity.getConfig()))
                .orElseGet(workspaceConfigService::config);
    }

    private Map<String, Object> currentSettings(long userId, Map<String, Object> config) {
        var stored = userSettingsRepository.findById(userId)
                .map(UserSettingsEntity::getSettings)
                .orElseGet(LinkedHashMap::new);
        return userSettingsService.normalize(stored, config);
    }

    private Map<String, Object> saveSettings(long userId, Map<String, Object> values, Map<String, Object> config) {
        var settings = userSettingsService.normalize(values, config);
        var entity = userSettingsRepository.findById(userId).orElseGet(() -> {
            var created = new UserSettingsEntity();
            created.setUserId(userId);
            return created;
        });
        entity.setSettings(settings);
        entity.setUpdatedAt(OffsetDateTime.now());
        userSettingsRepository.save(entity);
        return settings;
    }

    private List<Map<String, Object>> historyRows(long userId) {
        return searchHistoryRepository
                .findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 20))
                .stream()
                .map(this::historyMap)
                .toList();
    }

    /** 对齐旧 _history_from_row */
    private Map<String, Object> historyMap(SearchHistoryEntity entity) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", entity.getId());
        value.put("queryText", entity.getQueryText());
        value.put("source", entity.getSource());
        value.put("centerLabel", entity.getCenterLabel());
        value.put("longitude", entity.getLongitude());
        value.put("latitude", entity.getLatitude());
        value.put("radiusMeters", entity.getRadiusMeters());
        value.put("resultCount", entity.getResultCount());
        value.put("modelProfile", entity.getModelProfile());
        value.put("requestPayload", entity.getRequestPayload());
        value.put("summary", entity.getSummary());
        value.put("createdAt", entity.getCreatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        return value;
    }

    /** 对齐旧 validate_password_policy：>=10 位、<=128 位、含字母和数字、无空白 */
    private String passwordPolicyError(String password) {
        if (password.length() < 10) {
            return "密码至少需要 10 位。";
        }
        if (password.length() > 128) {
            return "密码不能超过 128 位。";
        }
        if (!LETTER.matcher(password).find() || !DIGIT.matcher(password).find()) {
            return "密码需要同时包含字母和数字。";
        }
        if (WHITESPACE.matcher(password).find()) {
            return "密码不能包含空白字符。";
        }
        return null;
    }

    private List<Object> parseFavoriteAreas(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, Object.class));
        } catch (Exception exception) {
            return List.of();
        }
    }

    private Map<Long, Long> groupedCounts(String jpql) {
        var counts = new HashMap<Long, Long>();
        for (Object[] row : entityManager.createQuery(jpql, Object[].class).getResultList()) {
            if (row[0] instanceof Number key && row[1] instanceof Number count) {
                counts.put(key.longValue(), count.longValue());
            }
        }
        return counts;
    }

    private long jpqlCount(String jpql) {
        var result = entityManager.createQuery(jpql, Long.class).getSingleResult();
        return result == null ? 0 : result;
    }

    private void bulkDelete(String jpql, long userId) {
        entityManager.createQuery(jpql).setParameter("userId", userId).executeUpdate();
    }

    private LinkedHashMap<String, Object> error(String code, String summary) {
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", false);
        body.put("code", code);
        body.put("summary", summary);
        body.put("fieldErrors", Map.of());
        return body;
    }
}
