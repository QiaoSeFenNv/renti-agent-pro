package com.renti.agent.modules.auth.application.dto;

/**
 * 注册请求。
 *
 * @param nickname    昵称（前端字段）
 * @param displayName 昵称别名（兼容旧客户端）
 */
public record RegisterRequest(String email, String password, String nickname, String displayName) {

    /** 优先 nickname，回退 displayName */
    public String preferredName() {
        if (nickname != null && !nickname.isBlank()) {
            return nickname;
        }
        return displayName;
    }
}
