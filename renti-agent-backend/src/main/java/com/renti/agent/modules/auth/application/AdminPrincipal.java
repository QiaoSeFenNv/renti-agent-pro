package com.renti.agent.modules.auth.application;

/** 已登录管理员信息 */
public record AdminPrincipal(Long id, String username, String displayName) {
}
