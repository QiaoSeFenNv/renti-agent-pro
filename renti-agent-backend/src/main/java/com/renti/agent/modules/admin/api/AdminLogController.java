package com.renti.agent.modules.admin.api;

import java.util.Map;

import com.renti.agent.common.annotation.CurrentAdmin;
import com.renti.agent.modules.admin.application.SystemLogQueryService;
import com.renti.agent.modules.auth.application.AdminPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统日志查询。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminLogController {

    private final SystemLogQueryService systemLogQueryService;

    @GetMapping("/logs")
    public Map<String, Object> logs(@CurrentAdmin AdminPrincipal admin,
                                    @RequestParam(defaultValue = "api") String kind,
                                    @RequestParam(defaultValue = "") String query,
                                    @RequestParam(defaultValue = "") String level,
                                    @RequestParam(required = false) Integer limit,
                                    @RequestParam(required = false) Integer page) {
        return systemLogQueryService.logsPayload(kind, query, level, limit, page);
    }
}
