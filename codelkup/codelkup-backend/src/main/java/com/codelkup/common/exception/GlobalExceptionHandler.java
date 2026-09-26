package com.codelkup.common.exception;

import com.codelkup.common.result.ErrorCode;
import com.codelkup.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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

    /**
     * 方法级参数校验失败（{@code @Validated} 类上的 {@code @Min/@Max} 等注解作用在
     * {@code @RequestParam} 上的场景，如分页的 page/size）。
     *
     * <p>与上一条 {@link MethodArgumentNotValidException} 是<b>两套独立机制</b>：
     * 那个校验请求体（MVC 内置），这个由 AOP 代理在方法进入前校验。
     * 少了这个处理器，{@code ?size=999} 会落到兜底的 50001 ——
     * 明明是调用方传错了参数，却报"服务器开小差了"，前端也无法据此提示。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                // propertyPath 形如 "listMyCollections.size"，只取末段参数名更可读
                .map(v -> {
                    String path = v.getPropertyPath().toString();
                    return path.substring(path.lastIndexOf('.') + 1) + ": " + v.getMessage();
                })
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败（方法级） | {}", detail);
        return Result.error(ErrorCode.PARAM_ERROR, detail);
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

    /**
     * 上传文件超限（Spring 在进入 Controller 之前就抛了，属于容器层拦截）。
     *
     * <p>必须单独处理：否则会落到兜底的 50001 —— 用户传了个大图却看到"服务器错误"，
     * 完全不知道是自己文件太大。业务层（ImageStorageService）另有一道更严格的
     * 尺寸校验，这里只是把"容器层先拦下"的那部分翻译成同一套可读提示。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过容器上限 | {}", e.getMessage());
        return Result.error(ErrorCode.FILE_TOO_LARGE, "文件过大，请压缩后再上传");
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
