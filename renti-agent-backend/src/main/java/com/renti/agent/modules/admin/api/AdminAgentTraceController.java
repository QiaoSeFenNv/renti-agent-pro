package com.renti.agent.modules.admin.api;

import java.util.Map;

import com.renti.agent.common.annotation.CurrentAdmin;
import com.renti.agent.modules.admin.application.AgentTraceService;
import com.renti.agent.modules.auth.application.AdminPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 执行 trace 管理端查询。
 */
@RestController
@RequestMapping("/api/admin/agent-traces")
@RequiredArgsConstructor
public class AdminAgentTraceController {

    private final AgentTraceService agentTraceService;

    @GetMapping
    public Map<String, Object> traces(@CurrentAdmin AdminPrincipal admin,
                                      @RequestParam(required = false) Integer limit,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Long userId,
                                      @RequestParam(defaultValue = "") String status,
                                      @RequestParam(defaultValue = "") String mode) {
        return agentTraceService.listPayload(limit, page, userId, status, mode);
    }

    @GetMapping("/{id}")
    public Map<String, Object> traceDetail(@CurrentAdmin AdminPrincipal admin, @PathVariable long id) {
        return agentTraceService.detailPayload(id);
    }
}
