package com.codelkup.common.exception;

import com.codelkup.common.result.ErrorCode;
import com.codelkup.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
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
 * <p>设计原则：
 * <ol>
 *   <li>业务异常 → 原样返回业务码，日志记 WARN</li>
 *   <li>参数校验异常 → 返回具体字段错误，方便前端定位</li>
 *   <li>未知异常 → 统一 500，日志记 ERROR 并保留堆栈，<b>不向前端暴露内部细节</b></li>
 * </ol>
 *
 * @author 刘
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常 —— 预期内，记录 WARN 即可 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("业务异常 | uri={} | code={} | msg={}",
                request.getRequestURI(), e.getErrorCode().getCode(), e.getMessage());
        return Result.error(e.getErrorCode(), e.getMessage());
    }

    /** 参数校验失败（@RequestBody + @Valid） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败 | {}", detail);
        return Result.error(ErrorCode.PARAM_ERROR, detail);
    }

    /** 参数绑定失败（表单 / query 参数） */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBind(BindException e) {
        String detail = e.getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("参数绑定失败 | {}", detail);
        return Result.error(ErrorCode.PARAM_ERROR, detail);
    }

    private String formatFieldError(FieldError fe) {
        return fe.getField() + ": " + fe.getDefaultMessage();
    }

    /** 缺少必要参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少参数 | {}", e.getParameterName());
        return Result.error(ErrorCode.PARAM_MISSING, "缺少参数：" + e.getParameterName());
    }

    /** 参数类型不匹配 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型错误 | name={} | value={}", e.getName(), e.getValue());
        return Result.error(ErrorCode.PARAM_FORMAT_ERROR,
                "参数 " + e.getName() + " 类型不正确");
    }

    /** 请求体不可读（JSON 格式错误等） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败 | {}", e.getMessage());
        return Result.error(ErrorCode.PARAM_FORMAT_ERROR, "请求体格式不正确");
    }

    /** 请求方法不支持 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持 | {}", e.getMessage());
        return Result.error(ErrorCode.PARAM_ERROR, "请求方法不支持：" + e.getMethod());
    }

    /** 404 —— 需配置 spring.mvc.throw-exception-if-no-handler-found 才会触发 */
    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandler(NoHandlerFoundException e) {
        log.warn("接口不存在 | {}", e.getRequestURL());
        return Result.error(ErrorCode.PARAM_ERROR, "接口不存在");
    }

    /**
     * 唯一约束冲突 —— 点赞防重的核心兜底。
     *
     * <p>注：正常业务流中点赞防重依赖 {@code t_user_action} 的唯一索引，
     * 冲突时应由 Service 层捕获并转为"已点赞"这一幂等结果。
     * 走到这里说明有未捕获的重复写入，属于需要排查的情况。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一约束冲突 | {}", e.getMessage());
        return Result.error(ErrorCode.SYSTEM_ERROR, "操作重复，请刷新后重试");
    }

    /** Spring Security 认证异常 */
    @ExceptionHandler(AuthenticationException.class)
    public Result<Void> handleAuthentication(AuthenticationException e) {
        log.warn("认证失败 | {}", e.getMessage());
        return Result.error(ErrorCode.UNAUTHORIZED);
    }

    /** Spring Security 授权异常 */
    @ExceptionHandler(AccessDeniedException.class)
    public Result<Void> handleAccessDenied(AccessDeniedException e) {
        log.warn("授权失败 | {}", e.getMessage());
        return Result.error(ErrorCode.FORBIDDEN);
    }

    /**
     * 兜底 —— 未知异常。
     *
     * <p>⚠️ 关键：完整堆栈只进日志，响应体不含任何内部信息（类名 / SQL / 堆栈），
     * 避免信息泄露。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnknown(Exception e, HttpServletRequest request) {
        log.error("未捕获异常 | uri={} | method={}",
                request.getRequestURI(), request.getMethod(), e);
        return Result.error(ErrorCode.SYSTEM_ERROR);
    }
}
