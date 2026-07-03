package com.renti.agent.modules.admin.api;

import java.util.Map;

import com.renti.agent.common.annotation.CurrentAdmin;
import com.renti.agent.modules.admin.application.AdminOverviewService;
import com.renti.agent.modules.auth.application.AdminPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台总览统计。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminOverviewController {

    private final AdminOverviewService adminOverviewService;

    @GetMapping("/overview")
    public Map<String, Object> overview(@CurrentAdmin AdminPrincipal admin) {
        return adminOverviewService.overviewPayload();
    }
}
