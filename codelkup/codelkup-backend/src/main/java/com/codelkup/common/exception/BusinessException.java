package com.codelkup.common.exception;

import com.codelkup.common.result.ErrorCode;
import lombok.Getter;

/**
 * 业务异常。
 *
 * <p>Service 层抛出，由 {@link GlobalExceptionHandler} 统一转为响应。
 * 与编程错误（NullPointerException 等）区分：本类表示"预期内的业务规则拒绝"。
 *
 * @author 刘
 */
@Getter
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

    /** 快捷构造：资源不存在类 */
    public static BusinessException notFound(ErrorCode errorCode) {
        return new BusinessException(errorCode);
    }

    /** 快捷构造：冲突类（重复操作等） */
    public static BusinessException conflict(ErrorCode errorCode) {
        return new BusinessException(errorCode);
    }

    /** 快捷构造：无权限 */
    public static BusinessException forbidden(ErrorCode errorCode) {
        return new BusinessException(errorCode);
    }
}
