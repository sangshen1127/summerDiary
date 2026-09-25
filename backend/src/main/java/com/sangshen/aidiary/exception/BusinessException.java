package com.sangshen.aidiary.exception;

import com.sangshen.aidiary.common.ErrorCode;

/**
 * 业务异常。凡是「能预期、要按错误码返回给前端」的情况都抛这个。
 *
 * <p>使用方式：
 * <pre>
 * // 查不到 / 不属于当前用户 —— 都用 NOT_FOUND，不泄露资源是否存在
 * Diary diary = diaryMapper.selectByIdAndUserId(id, userId);
 * if (diary == null) {
 *     throw new BusinessException(ErrorCode.NOT_FOUND);
 * }
 *
 * // 需要更具体的提示
 * throw new BusinessException(ErrorCode.BAD_REQUEST, "密码长度需为 8-64 位");
 * </pre>
 *
 * <p><b>注意</b>：message 会直接返回给前端，不要放 SQL、类名、堆栈等技术细节。
 * 真正需要排查的信息用 {@code log.warn} 记，但要确认不含日记正文和密钥
 * （见开发文档 §5.4）。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** 需要保留底层原因时用（不会把 cause 的 message 返回给前端）。 */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
