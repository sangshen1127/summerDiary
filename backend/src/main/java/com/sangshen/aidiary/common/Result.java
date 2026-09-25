package com.sangshen.aidiary.common;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 统一响应体。全项目所有接口（含错误）都返回这个结构。
 *
 * <p>契约见开发文档 §4.1：
 * <pre>
 * {
 *   "code": 0,
 *   "message": "success",
 *   "data": {},
 *   "timestamp": 1730000000000
 * }
 * </pre>
 *
 * <p><b>注意</b>：{@code code} 是业务码，与 HTTP 状态码是两套东西。
 * 前端 Axios 拦截器必须检查业务码，不能只看 HTTP 200 —— 见开发文档 §8.3。
 *
 * @param code      业务码，0 表示成功，非 0 见 {@link ErrorCode}
 * @param message   给用户看的提示，不要暴露技术细节
 * @param data      业务数据，无内容时为 null（不省略字段）
 * @param timestamp 毫秒时间戳
 * @param <T>       业务数据类型
 */
@JsonPropertyOrder({"code", "message", "data", "timestamp"})
public record Result<T>(
        int code,
        String message,
        T data,
        long timestamp
) {

    /** 成功，带数据。 */
    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), "success", data, System.currentTimeMillis());
    }

    /** 成功，无数据。用于 DELETE、POST /logout 这类没有返回体的操作。 */
    public static Result<Void> ok() {
        return ok(null);
    }

    /** 失败，按错误码返回。HTTP 状态码由全局异常处理器同步设置。 */
    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null, System.currentTimeMillis());
    }

    /**
     * 失败，自定义提示文案。
     *
     * <p>用于需要更具体说明的场景，例如「密码长度需为 8-64 位」。
     * 但要确保文案里不含 SQL、堆栈、内部类名等技术细节。
     */
    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null, System.currentTimeMillis());
    }
}
