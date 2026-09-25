package com.codelkup.module.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知。
 *
 * @author 刘
 */
@Data
@TableName("t_notification")
@Schema(description = "站内通知")
public class Notification {

    // ---- 通知类型 ----
    public static final int TYPE_COMMENT = 1;   // 评论了你的帖子
    public static final int TYPE_REPLY = 2;     // 回复了你的评论
    public static final int TYPE_LIKE = 3;      // 点赞了你的帖子/评论
    public static final int TYPE_FOLLOW = 4;    // 关注了你
    public static final int TYPE_ACCEPT = 5;    // 采纳了你的回答
    public static final int TYPE_SYSTEM = 6;    // 系统通知

    // ---- 目标类型 ----
    public static final int TARGET_POST = 1;
    public static final int TARGET_COMMENT = 2;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "接收者ID")
    private Long receiverId;

    @Schema(description = "触发者ID（系统通知为 null）")
    private Long senderId;

    @Schema(description = "1评论 2回复 3点赞 4关注 5采纳 6系统")
    private Integer type;

    @Schema(description = "目标类型：1帖子 2评论")
    private Integer targetType;

    @Schema(description = "目标ID")
    private Long targetId;

    @Schema(description = "快照文案（纯文本，非 HTML）")
    private String content;

    @Schema(description = "0未读 1已读")
    private Integer isRead;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
