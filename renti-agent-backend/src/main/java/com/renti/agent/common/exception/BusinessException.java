package com.renti.agent.common.exception;

/**
 * 业务异常，携带 HTTP 状态与业务码，由全局异常处理器统一转换为标准响应。
 */
public class BusinessException extends RuntimeException {

    private final int httpStatus;
    private final String code;

    public BusinessException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(400, "bad_request", message);
    }

    public static BusinessException badRequest(String code, String message) {
        return new BusinessException(400, code, message);
    }

    public static BusinessException unauthorized(String code, String message) {
        return new BusinessException(401, code, message);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException(403, "forbidden", message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(404, "not_found", message);
    }

    public static BusinessException upstream(String message) {
        return new BusinessException(502, "upstream_error", message);
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }
}
