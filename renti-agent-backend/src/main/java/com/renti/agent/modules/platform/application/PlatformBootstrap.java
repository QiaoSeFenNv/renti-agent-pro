package com.renti.agent.modules.platform.application;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.renti.agent.infrastructure.persistence.entity.AdminUserEntity;
import com.renti.agent.infrastructure.persistence.entity.PlatformConfigEntity;
import com.renti.agent.infrastructure.persistence.repository.AdminUserRepository;
import com.renti.agent.infrastructure.persistence.repository.PlatformConfigRepository;
import com.renti.agent.modules.auth.application.PasswordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台引导：首次启动时写入默认管理员与平台配置（来自旧系统迁移的集成配置）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformBootstrap {

    static final String DEFAULT_ADMIN_USERNAME = "admin";
    static final String DEFAULT_ADMIN_PASSWORD = "admin123";

    private final PlatformConfigRepository platformConfigRepository;
    private final AdminUserRepository adminUserRepository;
    private final PasswordService passwordService;
    private final ObjectMapper objectMapper;
    private final IntegrationSettingsService integrationSettingsService;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrap() {
        seedPlatformConfig();
        seedDefaultAdmin();
    }

    private void seedPlatformConfig() {
        try {
            Map<String, Map<String, Object>> seeds = objectMapper.readValue(
                    new ClassPathResource("seed/platform-config.json").getInputStream(),
                    new TypeReference<>() {
                    });
            seeds.forEach((key, value) -> {
                if (platformConfigRepository.existsById(key)) {
                    return;
                }
                var entity = new PlatformConfigEntity();
                entity.setConfigKey(key);
                entity.setConfigJson(value);
                platformConfigRepository.save(entity);
                log.info("Seeded platform config '{}'", key);
            });
            integrationSettingsService.invalidate();
        } catch (Exception exception) {
            log.error("Failed to seed platform config", exception);
        }
    }

    private void seedDefaultAdmin() {
        if (adminUserRepository.count() > 0) {
            return;
        }
        var admin = new AdminUserEntity();
        admin.setUsername(DEFAULT_ADMIN_USERNAME);
        admin.setDisplayName("平台管理员");
        admin.setPasswordHash(passwordService.hash(DEFAULT_ADMIN_PASSWORD));
        admin.setPasswordAlgo(PasswordService.ALGO_BCRYPT);
        adminUserRepository.save(admin);
        log.info("Seeded default admin '{}' (initial password '{}', 请尽快修改)",
                DEFAULT_ADMIN_USERNAME, DEFAULT_ADMIN_PASSWORD);
    }
}
