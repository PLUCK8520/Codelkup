package com.codelkup.module.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 问答日志实体，对应表 {@code t_ai_answer_log}（V10 / M3-6）。
 *
 * <p>⚠️ <b>没有"问题原文"字段</b>，只有 {@code questionHash}：问题属于用户内容，
 * 落库等于永久留档；复盘要的"同一问题被问了几次"用指纹即可回答。
 * 写入方（{@code AiAnswerService}）也遵循同一口径：日志里只有指纹与计数，没有正文。
 *
 * @author 刘
 */
@Data
@TableName("t_ai_answer_log")
public class AiAnswerLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 问题指纹（SHA-256 hex） */
    private String questionHash;

    /** 引用到的帖子 ID，逗号分隔（空表示没有引用） */
    private String citationIds;

    private Integer answerLength;

    private Integer durationMs;

    /** 上游返回的总 token 数；降级时为 null */
    private Integer totalTokens;

    private Integer cacheHit;

    private Integer degraded;

    /** 降级原因（正常时为 null） */
    private String degradeReason;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private LocalDateTime createTime;
}