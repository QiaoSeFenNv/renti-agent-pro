package com.renti.agent.modules.auth.application.dto;

/** 会话查询请求（POST /api/auth/session 支持 body 传 token） */
public record SessionRequest(String token, String sessionToken) {

    public String anyToken() {
        if (token != null && !token.isBlank()) {
            return token;
        }
        return sessionToken;
    }
}
