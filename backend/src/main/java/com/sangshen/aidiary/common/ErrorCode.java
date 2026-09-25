package com.sangshen.aidiary.common;

import org.springframework.http.HttpStatus;

/**
 * 全项目错误码字典。前后端与 AI 三边共用同一张表。
 *
 * <p>契约见开发文档 §4.2。<b>这张表只增不改</b> —— 改了要同步前端
 * {@code src/api/request.ts} 和 AI 侧的错误处理。
 *
 * <p><b>安全约定</b>：资源不存在与不属于当前用户，统一返回 {@link #NOT_FOUND}，
 * <b>不</b>返回 {@link #FORBIDDEN}。避免攻击者通过错误码差异枚举出
 * 「这个 id 存在但不属于我」。{@link #FORBIDDEN} 保留给与资源存在性无关的
 * 场景（如角色权限不足）。
 */
public enum ErrorCode {

    /** 0 —— 成功。 */
    SUCCESS(0, "success", HttpStatus.OK),

    /** 40001 —— 参数缺失、格式或长度错误。 */
    BAD_REQUEST(40001, "请求参数有误", HttpStatus.BAD_REQUEST),

    /** 40101 —— 未登录、Cookie/Token 无效或已过期。 */
    UNAUTHORIZED(40101, "请先登录", HttpStatus.UNAUTHORIZED),

    /** 40301 —— 已登录但无权访问（角色/权限不足，与资源存在性无关）。 */
    FORBIDDEN(40301, "没有权限执行此操作", HttpStatus.FORBIDDEN),

    /** 40401 —— 资源不存在<b>或不属于当前用户</b>。 */
    NOT_FOUND(40401, "请求的内容不存在", HttpStatus.NOT_FOUND),

    /**
     * 40501 —— 请求方法不被支持（如对只读接口发 POST）。
     *
     * <p>HTTP 用 405 Method Not Allowed 而非 400：语义不同 ——
     * 400 表示「请求内容有问题，改参数」；405 表示「这个地址不支持这个方法，改调用方式」。
     * 区分开对前端和监控排查都有帮助。
     *
     * <p>与 {@link #NOT_FOUND} 的区别：405 说明路径<b>存在</b>但不接受此方法；
     * 404 说明路径或资源不存在。
     *
     * <p>⚠️ 不要把它和 400 混用。Phase 0 曾把方法不支持错写成 400，
     * 是在模块 1-2 的验收测试里被断言抓出来的。
     */
    METHOD_NOT_ALLOWED(40501, "不支持的请求方法", HttpStatus.METHOD_NOT_ALLOWED),

    /** 40901 —— 用户名已存在、幂等键冲突、状态非法。 */
    CONFLICT(40901, "操作冲突，请刷新后重试", HttpStatus.CONFLICT),

    /** 42201 —— AI 返回的 JSON 无法解析或业务状态无法处理。 */
    UNPROCESSABLE(42201, "数据无法处理", HttpStatus.UNPROCESSABLE_ENTITY),

    /**
     * 50011 —— AI 或向量服务失败（模型超时、429、5xx、向量库不可用）。
     *
     * <p>用 502 而非 500：这是「上游依赖失败」，不是本服务内部错误。
     * 前端据此保留用户输入并提供重试按钮。
     */
    AI_SERVICE_ERROR(50011, "AI 服务暂时不可用，请稍后重试", HttpStatus.BAD_GATEWAY),

    /** 50001 —— 未预期的系统错误。 */
    INTERNAL_ERROR(50001, "服务器开小差了，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
