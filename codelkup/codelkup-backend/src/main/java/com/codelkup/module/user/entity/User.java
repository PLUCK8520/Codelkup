package com.codelkup.module.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户实体，对应表 {@code t_user}。
 *
 * @author 刘
 */
@Data
@TableName("t_user")
public class User implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 邮箱 —— 登录凭证，唯一 */
    private String email;

    /** BCrypt 加密后的密码 */
    private String password;

    /** 昵称 —— 仅展示，允许重复 */
    private String nickname;

    private String avatar;

    private String bio;

    /** 技术标签，逗号分隔 */
    private String techTags;

    /** 0普通 1版主 2管理员 */
    private Integer role;

    /** 0正常 1禁言 2封禁 */
    private Integer status;

    private Integer reputation;

    private Integer level;

    private LocalDateTime lastActive;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标记。
     * <p>注意：字段需声明为 {@link TableLogic} 才会被 MyBatis-Plus 自动过滤，
     * 全局配置 {@code logic-delete-field: deleted} 已声明，此处显式标注以增强可读性。
     */
    @TableLogic
    private Integer deleted;
}
