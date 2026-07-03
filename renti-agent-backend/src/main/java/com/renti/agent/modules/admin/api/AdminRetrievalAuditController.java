package com.renti.agent.modules.admin.api;

import java.util.Map;

import com.renti.agent.common.annotation.CurrentAdmin;
import com.renti.agent.modules.admin.application.RetrievalAuditService;
import com.renti.agent.modules.auth.application.AdminPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索审计管理端查询与回放。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/retrieval-audits")
@RequiredArgsConstructor
public class AdminRetrievalAuditController {

    private final RetrievalAuditService retrievalAuditService;

    @GetMapping
    public Map<String, Object> audits(@CurrentAdmin AdminPrincipal admin,
                                      @RequestParam(required = false) Integer limit,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Long userId,
                                      @RequestParam(defaultValue = "") String endpoint) {
        return retrievalAuditService.listPayload(limit, page, userId, endpoint);
    }

    @GetMapping("/{id}")
    public Map<String, Object> auditDetail(@CurrentAdmin AdminPrincipal admin, @PathVariable long id) {
        return retrievalAuditService.detailPayload(id);
    }

    @PostMapping("/{id}/replay")
    public Map<String, Object> replay(@CurrentAdmin AdminPrincipal admin, @PathVariable long id) {
        log.info("Admin {} replaying retrieval audit {}", admin.username(), id);
        return retrievalAuditService.replayPayload(id);
    }
}
