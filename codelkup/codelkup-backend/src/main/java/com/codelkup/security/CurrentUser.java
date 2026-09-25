package com.codelkup.security;

import java.lang.annotation.*;

/**
 * 注入当前登录用户。
 *
 * <p>用法：
 * <pre>{@code
 * // 只需要 id
 * public Result<?> create(@CurrentUser Long userId) { ... }
 *
 * // 需要完整用户实体
 * public Result<?> profile(@CurrentUser User user) { ... }
 * }</pre>
 *
 * @author 刘
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {

    /**
     * 是否必须登录。
     *
     * <p>{@code false}（默认）时未登录注入 null，由业务代码自行判断；
     * {@code true} 时未登录直接抛 401 —— 用于"必然要求登录"的接口。
     */
    boolean required() default false;
}
