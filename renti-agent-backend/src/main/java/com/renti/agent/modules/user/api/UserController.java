package com.renti.agent.modules.user.api;

import java.util.Map;

import com.renti.agent.common.annotation.CurrentUser;
import com.renti.agent.modules.auth.application.UserPrincipal;
import com.renti.agent.modules.notification.application.NotificationService;
import com.renti.agent.modules.user.application.SearchHistoryService;
import com.renti.agent.modules.user.application.UserSettingsService;
import com.renti.agent.modules.user.application.UserWorkspaceService;
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
 * 用户工作台接口：设置/收藏/搜索历史/导入房源/公告。路径受用户会话保护。
 */
@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserSettingsService userSettingsService;
    private final UserWorkspaceService userWorkspaceService;
    private final SearchHistoryService searchHistoryService;
    private final NotificationService notificationService;

    // ---------------------------------------------------------------- settings

    @GetMapping("/settings")
    public Map<String, Object> getSettings(@CurrentUser UserPrincipal user) {
        return userSettingsService.settingsPayload(user.id());
    }

    @PutMapping("/settings")
    public Map<String, Object> updateSettings(@CurrentUser UserPrincipal user,
                                              @RequestBody(required = false) Map<String, Object> payload) {
        var result = userSettingsService.updateSettings(user.id(), payload);
        log.info("user settings update userId={}", user.id());
        return result;
    }

    // --------------------------------------------------------------- favorites

    @GetMapping("/favorites")
    public Map<String, Object> listFavorites(@CurrentUser UserPrincipal user) {
        return userWorkspaceService.listFavoritesPayload(user.id());
    }

    @PostMapping("/favorites")
    public Map<String, Object> saveFavorite(@CurrentUser UserPrincipal user,
                                            @RequestBody(required = false) Map<String, Object> payload) {
        var result = userWorkspaceService.saveFavoritePayload(user.id(), payload);
        log.info("user favorite save userId={} ok={}", user.id(), result.get("ok"));
        return result;
    }

    @DeleteMapping("/favorites/{listingId}")
    public Map<String, Object> deleteFavorite(@CurrentUser UserPrincipal user,
                                              @PathVariable String listingId) {
        var result = userWorkspaceService.deleteFavoritePayload(user.id(), listingId);
        log.info("user favorite delete userId={} listingId={} removed={}", user.id(), listingId,
                result.get("removed"));
        return result;
    }

    // ----------------------------------------------------------------- history

    @GetMapping("/history")
    public Map<String, Object> listHistory(@CurrentUser UserPrincipal user) {
        return searchHistoryService.listPayload(user.id());
    }

    @DeleteMapping("/history")
    public Map<String, Object> clearHistory(@CurrentUser UserPrincipal user) {
        var result = searchHistoryService.clearPayload(user.id());
        log.info("user history clear userId={} removed={}", user.id(), result.get("removed"));
        return result;
    }

    // -------------------------------------------------------- imported listings

    @GetMapping("/imported-listings")
    public Map<String, Object> listImported(@CurrentUser UserPrincipal user,
                                            @RequestParam(defaultValue = "上海") String city) {
        return userWorkspaceService.listImportedPayload(user.id(), city);
    }

    @PostMapping("/imported-listings")
    public Map<String, Object> saveImported(@CurrentUser UserPrincipal user,
                                            @RequestBody(required = false) Map<String, Object> payload) {
        var result = userWorkspaceService.saveImportedPayload(user.id(), payload);
        log.info("user imported-listing save userId={} ok={}", user.id(), result.get("ok"));
        return result;
    }

    @DeleteMapping("/imported-listings")
    public Map<String, Object> clearImported(@CurrentUser UserPrincipal user,
                                             @RequestParam(defaultValue = "上海") String city) {
        var result = userWorkspaceService.clearImportedPayload(user.id(), city);
        log.info("user imported-listings clear userId={} city={} removed={}", user.id(),
                result.get("city"), result.get("removed"));
        return result;
    }

    // ------------------------------------------------------------ notifications

    @GetMapping("/notifications")
    public Map<String, Object> listNotifications(@CurrentUser UserPrincipal user) {
        return notificationService.userListPayload(user.id());
    }

    @PostMapping("/notifications/read-all")
    public Map<String, Object> markAllNotificationsRead(@CurrentUser UserPrincipal user) {
        return notificationService.markAllReadPayload(user.id());
    }

    @PostMapping("/notifications/{id}/read")
    public Map<String, Object> markNotificationRead(@CurrentUser UserPrincipal user, @PathVariable Long id) {
        return notificationService.markReadPayload(user.id(), id);
    }
}
