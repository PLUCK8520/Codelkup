package com.codelkup.common.result;

import lombok.Getter;

/**
 * 业务错误码。
 *
 * <p>编码规则：5 位数字，按 HTTP 语义分段
 * <ul>
 *   <li>400xx — 请求参数 / 客户端错误</li>
 *   <li>401xx — 未认证</li>
 *   <li>403xx — 无权限</li>
 *   <li>404xx — 资源不存在</li>
 *   <li>409xx — 冲突（重复操作、唯一约束）</li>
 *   <li>429xx — 限流</li>
 *   <li>500xx — 服务端错误</li>
 * </ul>
 *
 * @author 刘
 */
@Getter
public enum ErrorCode {

    // ===== 成功 =====
    SUCCESS(0, "success"),

    // ===== 400xx 客户端错误 =====
    PARAM_ERROR(40001, "参数错误"),
    PARAM_MISSING(40002, "缺少必要参数"),
    PARAM_FORMAT_ERROR(40003, "参数格式不正确"),
    FILE_TYPE_NOT_ALLOWED(40004, "文件类型不支持"),
    FILE_TOO_LARGE(40005, "文件过大"),
    UPLOAD_QUOTA_EXCEEDED(40006, "上传数量已达上限"),

    // ===== 401xx 认证 =====
    UNAUTHORIZED(40101, "未登录或登录已过期"),
    TOKEN_INVALID(40102, "登录凭证无效"),
    TOKEN_EXPIRED(40103, "登录凭证已过期"),
    BAD_CREDENTIALS(40104, "邮箱或密码错误"),
    ACCOUNT_DISABLED(40105, "账号已被封禁"),
    ACCOUNT_MUTED(40106, "账号已被禁言"),

    // ===== 403xx 权限 =====
    FORBIDDEN(40301, "无权限执行此操作"),
    NOT_RESOURCE_OWNER(40302, "只能操作自己的内容"),
    ROLE_NOT_ENOUGH(40303, "权限不足"),

    // ===== 404xx 资源不存在 =====
    USER_NOT_FOUND(40401, "用户不存在"),
    POST_NOT_FOUND(40402, "帖子不存在"),
    COMMENT_NOT_FOUND(40403, "评论不存在"),
    BOARD_NOT_FOUND(40404, "板块不存在"),
    TAG_NOT_FOUND(40405, "标签不存在"),
    NOTIFICATION_NOT_FOUND(40406, "通知不存在"),

    // ===== 409xx 冲突 =====
    EMAIL_ALREADY_EXISTS(40901, "该邮箱已被注册"),
    ALREADY_LIKED(40902, "已经点过赞了"),
    NOT_LIKED(40903, "尚未点赞"),
    ALREADY_COLLECTED(40904, "已经收藏过了"),
    NOT_COLLECTED(40905, "尚未收藏"),
    ALREADY_FOLLOWING(40906, "已经关注了"),
    NOT_FOLLOWING(40907, "尚未关注"),
    CANNOT_FOLLOW_SELF(40908, "不能关注自己"),

    // ===== 429xx 限流 =====
    TOO_MANY_REQUESTS(42901, "操作过于频繁，请稍后再试"),
    LOGIN_ATTEMPT_EXCEEDED(42902, "登录失败次数过多，请 15 分钟后再试"),

    // ===== 500xx 服务端 =====
    SYSTEM_ERROR(50001, "系统繁忙，请稍后再试"),
    DB_ERROR(50002, "数据操作失败"),
    CACHE_ERROR(50003, "缓存服务异常");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
