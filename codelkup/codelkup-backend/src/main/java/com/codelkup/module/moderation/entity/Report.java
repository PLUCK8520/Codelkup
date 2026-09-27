package com.codelkup.module.moderation.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 举报记录实体，对应表 {@code t_report}（V11 / M5）。
 *
 * <p><b>没有逻辑删除字段</b>：举报记录是审计材料，处理完也不删 ——
 * "这条内容当初是谁以什么理由要求删除的"必须在事后仍可查。
 * 队列靠 {@code status} 过滤，而不是靠删数据来"清理"。
 *
 * <p>{@code status} 的三个取值刻意<b>不复用</b>帖子的状态码：
 * 那是内容的状态（待审/已发布/已删/已封禁），这是<b>举报单</b>的处理状态，
 * 两套语义混用会让"status=2 到底是内容被删还是举报被驳回"变成需要记忆的约定。
 *
 * @author 刘
 */
@Data
@TableName("t_report")
public class Report implements Serializable {

    /** 被举报对象类型：帖子 */
    public static final int TARGET_POST = 1;
    /** 被举报对象类型：评论 */
    public static final int TARGET_COMMENT = 2;

    /** 处理状态：待处理 */
    public static final int STATUS_PENDING = 0;
    /** 处理状态：已驳回（保留内容） */
    public static final int STATUS_DISMISSED = 1;
    /** 处理状态：已删除内容 */
    public static final int STATUS_DELETED_TARGET = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 举报人 */
    private Long reporterId;

    /** 1帖子 2评论 */
    private Integer targetType;

    private Long targetId;

    /** 原因枚举：SPAM / ABUSE / ILLEGAL / OTHER（存字符串而非序号，便于日后增删枚举） */
    private String reason;

    /** 补充说明（可选，用户填写，≤200 字） */
    private String detail;

    /** 0待处理 1已驳回 2已删除内容 */
    private Integer status;

    private Long handledBy;

    private LocalDateTime handledAt;

    /** 处理备注（管理员填写；同目标兄弟单自动关闭时写系统说明） */
    private String handleNote;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}