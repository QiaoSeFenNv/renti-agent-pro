package com.renti.agent.modules.auth.application;

/** 已登录用户信息（拦截器解析后注入 Controller） */
public record UserPrincipal(Long id, String email, String nickname, boolean emailVerified) {
}
