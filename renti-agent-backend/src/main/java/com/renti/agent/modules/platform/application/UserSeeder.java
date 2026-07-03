package com.renti.agent.modules.platform.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.renti.agent.infrastructure.persistence.entity.UserEntity;
import com.renti.agent.infrastructure.persistence.entity.UserPreferenceEntity;
import com.renti.agent.infrastructure.persistence.repository.UserPreferenceRepository;
import com.renti.agent.infrastructure.persistence.repository.UserRepository;
import com.renti.agent.modules.auth.application.PasswordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 旧系统用户迁移：首次启动时导入 seed/users.json（保留 PBKDF2 哈希，
 * 老用户可用原密码登录，登录成功后自动升级为 bcrypt）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSeeder {

    private final UserRepository userRepository;
    private final UserPreferenceRepository userPreferenceRepository;
    private final ObjectMapper objectMapper;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        if (userRepository.count() > 0) {
            return;
        }
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(
                    new ClassPathResource("seed/users.json").getInputStream(),
                    new TypeReference<>() {
                    });
            for (Map<String, Object> row : rows) {
                var user = new UserEntity();
                user.setEmail(str(row, "email"));
                user.setNickname(str(row, "display_name"));
                user.setPasswordHash(str(row, "password_hash"));
                user.setPasswordSalt(str(row, "password_salt"));
                user.setPasswordIterations(intOrNull(row, "password_iterations"));
                user.setPasswordAlgo(PasswordService.ALGO_PBKDF2);
                user.setEmailVerified(Boolean.TRUE.equals(row.get("email_verified")));
                user.setBudgetMin(intOrNull(row, "budget_min"));
                user.setBudgetMax(intOrNull(row, "budget_max"));
                user.setCommuteTarget(str(row, "commute_target"));
                user.setCommuteMinutes(intOrNull(row, "commute_minutes"));
                user.setFavoriteAreas(str(row, "favorite_areas"));
                user.setCreatedAt(timeOrNow(row, "created_at"));
                user.setUpdatedAt(timeOrNow(row, "updated_at"));
                var saved = userRepository.save(user);
                seedPreference(saved.getId(), row);
            }
            log.info("Seeded {} users from legacy database", rows.size());
        } catch (Exception exception) {
            log.error("Failed to seed users", exception);
        }
    }

    /** 旧库偏好列 → 用户偏好表（favorite_areas 为 JSON 数组文本） */
    private void seedPreference(Long userId, Map<String, Object> row) {
        try {
            var preference = new UserPreferenceEntity();
            preference.setUserId(userId);
            preference.setBudgetMin(intOrNull(row, "budget_min"));
            preference.setBudgetMax(intOrNull(row, "budget_max"));
            preference.setCommuteTarget(str(row, "commute_target"));
            preference.setCommuteMinutes(intOrNull(row, "commute_minutes"));
            var areasJson = str(row, "favorite_areas");
            if (areasJson != null && !areasJson.isBlank()) {
                List<String> areas = objectMapper.readValue(areasJson, new TypeReference<>() {
                });
                preference.setFavoriteAreas(areas);
            }
            userPreferenceRepository.save(preference);
        } catch (Exception exception) {
            log.warn("Failed to seed preference for user {}: {}", userId, exception.getMessage());
        }
    }

    private String str(Map<String, Object> row, String key) {
        var value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Integer intOrNull(Map<String, Object> row, String key) {
        return row.get(key) instanceof Number number ? number.intValue() : null;
    }

    private OffsetDateTime timeOrNow(Map<String, Object> row, String key) {
        try {
            var value = str(row, key);
            return value == null ? OffsetDateTime.now() : OffsetDateTime.parse(value);
        } catch (Exception exception) {
            return OffsetDateTime.now();
        }
    }
}
