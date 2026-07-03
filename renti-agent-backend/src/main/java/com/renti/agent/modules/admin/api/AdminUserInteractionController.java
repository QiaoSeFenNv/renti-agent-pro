package com.renti.agent.modules.admin.api;

import java.util.Map;

import com.renti.agent.common.annotation.CurrentAdmin;
import com.renti.agent.modules.admin.application.UserInteractionService;
import com.renti.agent.modules.auth.application.AdminPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户交互记录管理端查询。
 */
@RestController
@RequestMapping("/api/admin/user-interactions")
@RequiredArgsConstructor
public class AdminUserInteractionController {

    private final UserInteractionService userInteractionService;

    @GetMapping
    public Map<String, Object> interactions(@CurrentAdmin AdminPrincipal admin,
                                            @RequestParam(required = false) Integer limit,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Long userId,
                                            @RequestParam(defaultValue = "") String endpoint,
                                            @RequestParam(defaultValue = "") String query) {
        return userInteractionService.listPayload(limit, page, userId, endpoint, query);
    }

    @GetMapping("/{id}")
    public Map<String, Object> interactionDetail(@CurrentAdmin AdminPrincipal admin, @PathVariable long id) {
        return userInteractionService.detailPayload(id);
    }
}
