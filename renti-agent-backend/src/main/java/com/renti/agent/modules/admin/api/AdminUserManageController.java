package com.renti.agent.modules.admin.api;

import java.util.Map;

import com.renti.agent.common.annotation.CurrentAdmin;
import com.renti.agent.modules.admin.application.AdminUserManageService;
import com.renti.agent.modules.auth.application.AdminPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端用户管理。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserManageController {

    private final AdminUserManageService adminUserManageService;

    @GetMapping
    public Map<String, Object> users(@CurrentAdmin AdminPrincipal admin,
                                     @RequestParam(required = false) Integer limit) {
        return adminUserManageService.usersPayload(limit);
    }

    @GetMapping("/{id}")
    public Map<String, Object> userDetail(@CurrentAdmin AdminPrincipal admin, @PathVariable long id) {
        return adminUserManageService.userDetailPayload(id);
    }

    @PutMapping("/{id}/settings")
    public Map<String, Object> updateSettings(@CurrentAdmin AdminPrincipal admin, @PathVariable long id,
                                              @RequestBody(required = false) Map<String, Object> payload) {
        return adminUserManageService.updateSettingsPayload(id, payload);
    }

    @PutMapping("/{id}/config")
    public Map<String, Object> updateConfig(@CurrentAdmin AdminPrincipal admin, @PathVariable long id,
                                            @RequestBody(required = false) Map<String, Object> payload) {
        return adminUserManageService.updateConfigPayload(id, payload);
    }

    @DeleteMapping("/{id}/config")
    public Map<String, Object> resetConfig(@CurrentAdmin AdminPrincipal admin, @PathVariable long id) {
        return adminUserManageService.resetConfigPayload(id);
    }

    @PostMapping("/{id}/password")
    public Map<String, Object> resetPassword(@CurrentAdmin AdminPrincipal admin, @PathVariable long id,
                                             @RequestBody(required = false) Map<String, Object> payload) {
        return adminUserManageService.resetPasswordPayload(id, payload);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteUser(@CurrentAdmin AdminPrincipal admin, @PathVariable long id) {
        log.info("Admin {} deleting user {}", admin.username(), id);
        return adminUserManageService.deleteUserPayload(id);
    }
}
