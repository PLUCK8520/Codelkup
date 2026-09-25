package com.codelkup.module.notification.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知列表项。
 *
 * <p>刻意<b>不返回跳转链接</b>：链接是表现层的事（前端路由结构变了，
 * 后端不该跟着改）。这里只给足拼链接所需的事实 —— {@code type} 决定图标与措辞，
 * {@code targetType} + {@code targetId} 决定跳去哪。
 *
 * @author 刘
 */
@Data
@Schema(description = "通知列表项")
public class NotificationVO {

    @Schema(description = "通知ID")
    private Long id;

    @Schema(description = "1评论 2回复 3点赞 4关注 5采纳 6系统")
    private Integer type;

    @Schema(description = "触发者ID（系统通知为 null）")
    private Long senderId;

    @Schema(description = "触发者昵称")
    private String senderNickname;

    @Schema(description = "触发者头像")
    private String senderAvatar;

    @Schema(description = "目标类型：1帖子 2评论")
    private Integer targetType;

    @Schema(description = "目标ID")
    private Long targetId;

    @Schema(description = "快照文案（纯文本，前端负责渲染与转义）")
    private String content;

    @Schema(description = "0未读 1已读")
    private Integer isRead;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
