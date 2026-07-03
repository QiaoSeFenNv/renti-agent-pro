package com.renti.agent.modules.auth.application.dto;

/** 修改密码请求 */
public record ChangePasswordRequest(String currentPassword, String newPassword, String confirmPassword) {
}
