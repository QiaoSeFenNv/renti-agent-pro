package com.renti.agent.modules.platform.api;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import com.renti.agent.common.annotation.CurrentAdmin;
import com.renti.agent.infrastructure.persistence.entity.PlatformConfigEntity;
import com.renti.agent.infrastructure.persistence.repository.PlatformConfigRepository;
import com.renti.agent.modules.auth.application.AdminPrincipal;
import com.renti.agent.modules.platform.application.SystemIntegrationsConfigService;
import com.renti.agent.modules.user.application.WorkspaceConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台配置中心：工作台配置（key=workspace）与系统集成配置（key=system_integrations）。
 * 响应结构对齐旧 platform_config.py 的 get/update payload 函数。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class PlatformConfigController {

    private final WorkspaceConfigService workspaceConfigService;
    private final SystemIntegrationsConfigService systemIntegrationsConfigService;
    private final PlatformConfigRepository platformConfigRepository;

    /** GET /api/admin/config：工作台配置（modelOptions/listingPageSize） */
    @GetMapping("/config")
    public Map<String, Object> workspaceConfig(@CurrentAdmin AdminPrincipal admin) {
        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.putAll(workspaceConfigService.config());
        return body;
    }

    /** PUT /api/admin/config：合并保存工作台配置 */
    @PutMapping("/config")
    @Transactional
    public Map<String, Object> updateWorkspaceConfig(@CurrentAdmin AdminPrincipal admin,
                                                     @RequestBody(required = false) Map<String, Object> payload) {
        var source = payload == null ? Map.<String, Object>of() : payload;
        var incoming = source.get("config") instanceof Map<?, ?>
                ? SystemIntegrationsConfigService.asMap(source.get("config")) : source;
        var merged = new LinkedHashMap<>(workspaceConfigService.config());
        merged.putAll(incoming);
        var config = workspaceConfigService.normalize(merged);

        var entity = platformConfigRepository.findById(WorkspaceConfigService.PLATFORM_CONFIG_KEY)
                .orElseGet(() -> {
                    var created = new PlatformConfigEntity();
                    created.setConfigKey(WorkspaceConfigService.PLATFORM_CONFIG_KEY);
                    return created;
                });
        entity.setConfigJson(config);
        entity.setUpdatedAt(OffsetDateTime.now());
        platformConfigRepository.save(entity);
        log.info("Admin {} updated workspace platform config", admin.username());

        var body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.putAll(config);
        body.put("summary", "平台配置已保存。");
        return body;
    }

    /** GET /api/admin/system-integrations/config：集成配置（敏感字段脱敏回显） */
    @GetMapping("/system-integrations/config")
    public Map<String, Object> systemIntegrations(@CurrentAdmin AdminPrincipal admin) {
        return systemIntegrationsConfigService.getPayload();
    }

    /** PUT /api/admin/system-integrations/config：分段合并保存 */
    @PutMapping("/system-integrations/config")
    public Map<String, Object> updateSystemIntegrations(@CurrentAdmin AdminPrincipal admin,
                                                        @RequestBody(required = false) Map<String, Object> payload) {
        log.info("Admin {} updated system integrations config", admin.username());
        return systemIntegrationsConfigService.updatePayload(payload);
    }
}
