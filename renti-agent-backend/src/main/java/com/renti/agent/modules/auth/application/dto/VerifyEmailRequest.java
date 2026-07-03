package com.renti.agent.modules.auth.application.dto;

/** 邮箱验证码校验请求 */
public record VerifyEmailRequest(String email, String code) {
}
