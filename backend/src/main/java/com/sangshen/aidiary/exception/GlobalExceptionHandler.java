package com.sangshen.aidiary.exception;

import com.sangshen.aidiary.common.ErrorCode;
import com.sangshen.aidiary.common.Result;
import com.sangshen.aidiary.common.crypto.DiaryDecryptionException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>职责：把任何异常翻译成统一响应体 {@link Result}，<b>并同步设置正确的 HTTP 状态码</b>。
 * 契约见开发文档 §4.1 / §4.2。
 *
 * <p><b>两条纪律</b>：
 * <ol>
 *   <li>返回给前端的 message 必须是用户能看懂的文案，不含 SQL、类名、堆栈。</li>
 *   <li>日志里绝不能出现日记正文、密码、Cookie、API Key、完整 Prompt（开发文档 §5.4）。
 *       所以这里记录的是「异常类型 + 请求路径」，不记录请求体。</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常 —— 最常走的分支。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException ex, HttpServletRequest request) {
        ErrorCode errorCode = ex.getErrorCode();
        // 4xx 是客户端问题，不值得 error 级别告警；记 debug 保留排查线索
        log.debug("业务异常 code={} path={} msg={}", errorCode.getCode(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(Result.fail(errorCode, ex.getMessage()));
    }

    /**
     * {@code @Valid} 校验失败（@RequestBody 场景）。
     *
     * <p>把所有字段错误拼成一句话，前端可以直接展示。
     * <b>只回显字段名和校验注解里的文案</b>，不回显用户输入的值 ——
     * 否则可能把密码、正文写进响应体和日志。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException ex,
                                                         HttpServletRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + defaultMessage(fe))
                .distinct()
                .collect(Collectors.joining("; "));
        log.debug("参数校验失败 path={} detail={}", request.getRequestURI(), detail);
        return ResponseEntity.status(ErrorCode.BAD_REQUEST.getHttpStatus())
                .body(Result.fail(ErrorCode.BAD_REQUEST, detail.isEmpty() ? ErrorCode.BAD_REQUEST.getMessage() : detail));
    }

    /** 表单/查询参数绑定校验失败。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBind(BindException ex, HttpServletRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + defaultMessage(fe))
                .distinct()
                .collect(Collectors.joining("; "));
        log.debug("参数绑定失败 path={} detail={}", request.getRequestURI(), detail);
        return ResponseEntity.status(ErrorCode.BAD_REQUEST.getHttpStatus())
                .body(Result.fail(ErrorCode.BAD_REQUEST, detail.isEmpty() ? ErrorCode.BAD_REQUEST.getMessage() : detail));
    }

    /** 必填查询参数缺失，例如 {@code GET /api/diaries} 不带 page。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return badRequest("缺少必要参数：" + ex.getParameterName());
    }

    /** 参数类型不对，例如 {@code ?page=abc}。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest("参数格式有误：" + ex.getName());
    }

    /** 请求体不是合法 JSON。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException ex,
                                                         HttpServletRequest request) {
        log.debug("请求体无法解析 path={}", request.getRequestURI());
        return badRequest("请求体格式有误，应为合法 JSON");
    }

    /**
     * 请求方法不支持，例如对只读接口发 POST。
     *
     * <p>返回 HTTP 405 + 业务码 40501。
     * <b>刻意不用 400</b>：语义不同 —— 400 是「请求内容有问题」，
     * 405 是「这个地址不接受这个方法」。把两者混在一起，
     * 前端无法区分「我参数写错了」和「我调用方式错了」。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus())
                .body(Result.fail(ErrorCode.METHOD_NOT_ALLOWED,
                        "该接口不支持 " + ex.getMethod() + " 方法"));
    }

    /** 无匹配路由。spring.mvc.throw-exception-if-no-handler-found=true 时才会走到这里。 */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<Void>> handleNoHandler(NoHandlerFoundException ex) {
        log.debug("路由不存在 path={}", ex.getRequestURL());
        return ResponseEntity.status(ErrorCode.NOT_FOUND.getHttpStatus())
                .body(Result.fail(ErrorCode.NOT_FOUND));
    }

    /**
     * 数据库唯一约束冲突的兜底。
     *
     * <p>正常情况下 Service 层会主动捕获它并转成更友好的业务异常
     * （例如注册接口把重名转成 {@code 40901}）。这里是<b>防御性兜底</b> ——
     * 万一某个新接口漏了处理，也应返回可理解的 40901 而不是 50001。
     *
     * <p><b>刻意不把原始 message 返回给前端</b>：它会包含表名和索引名
     * （如 {@code Duplicate entry 'x' for key 'user.uk_user_username'}），
     * 属于内部结构信息。日志也只记路径，便于定位是哪个接口漏了处理。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicateKey(DuplicateKeyException ex,
                                                           HttpServletRequest request) {
        log.warn("唯一约束冲突未被 Service 层处理 path={}", request.getRequestURI());
        return ResponseEntity.status(ErrorCode.CONFLICT.getHttpStatus())
                .body(Result.fail(ErrorCode.CONFLICT, "数据已存在，请勿重复提交"));
    }

    /**
     * 日记正文解密失败 —— 服务端数据/配置问题，不是客户端问题。
     *
     * <p>用 {@code warn} 而不是 {@code debug}（对比上面的
     * {@link #handleBusiness}）：4xx 是用户输错了参数，不值得看；
     * 解密失败则<b>必然需要人介入</b>，可能的两种原因都很严重：
     * <ul>
     *   <li>密钥换过 / 配错了 —— 所有历史日记都读不出来</li>
     *   <li>密文被篡改 —— 数据完整性事件</li>
     * </ul>
     *
     * <p><b>刻意不记录 {@code ex} 本身（不打堆栈）</b>：堆栈来自 JCE 内部，
     * 虽然不含密钥，但把"记日志"和"记密文相关对象"绑在一起是危险的写法 ——
     * 将来有人改了异常携带的字段，日志红线就被无声地突破了。
     * 这里只记类型；需要堆栈时靠 {@code ex.getCause()} 在本地调试时打印。
     *
     * <p>返回给前端的是 {@link ErrorCode#INTERNAL_ERROR} 的通用文案，
     * <b>不回显</b>具体原因 —— 区分"密钥不对"和"数据被改"等于给攻击者反馈信号。
     */
    @ExceptionHandler(DiaryDecryptionException.class)
    public ResponseEntity<Result<Void>> handleDiaryDecryption(DiaryDecryptionException ex,
                                                             HttpServletRequest request) {
        log.warn("日记正文解密失败 path={} type={}", request.getRequestURI(), ex.getClass().getSimpleName());
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(Result.fail(ErrorCode.INTERNAL_ERROR));
    }

    /**
     * 兜底 —— 未预期的异常。
     *
     * <p>这里<b>必须</b>记 error 日志（含堆栈），否则线上问题无从排查；
     * 但返回给前端的是通用文案，不泄露内部细节。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("未预期异常 path={} type={}", request.getRequestURI(), ex.getClass().getName(), ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(Result.fail(ErrorCode.INTERNAL_ERROR));
    }

    private static String defaultMessage(FieldError fieldError) {
        String msg = fieldError.getDefaultMessage();
        return msg == null ? "取值不合法" : msg;
    }

    private static ResponseEntity<Result<Void>> badRequest(String message) {
        return ResponseEntity.status(ErrorCode.BAD_REQUEST.getHttpStatus())
                .body(Result.fail(ErrorCode.BAD_REQUEST, message));
    }
}
