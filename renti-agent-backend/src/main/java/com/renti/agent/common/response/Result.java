package com.renti.agent.common.response;

/**
 * 统一 API 响应结构。
 *
 * @param code    业务码，0 表示成功
 * @param message 提示信息
 * @param data    业务数据
 * @param <T>     数据类型
 */
public record Result<T>(int code, String message, T data) {

    private static final int SUCCESS_CODE = 0;

    public static <T> Result<T> ok(T data) {
        return new Result<>(SUCCESS_CODE, "ok", data);
    }

    public static <T> Result<T> ok() {
        return new Result<>(SUCCESS_CODE, "ok", null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }
}
