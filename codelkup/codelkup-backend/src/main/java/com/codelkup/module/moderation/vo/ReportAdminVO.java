package com.codelkup.module.moderation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审核队列行（管理员视图）。
 *
 * <p>队列的价值在于"不用离开页面就能判断该不该删"，所以一行里必须同时给出
 * <b>三样证据</b>：
 * <ol>
 *   <li><b>目标摘要</b>（{@code targetTitle / targetExcerpt}）—— 审核对象本身长什么样</li>
 *   <li><b>被举报次数</b>（{@code reportedCount}）—— 一次投诉与十次投诉不是同一件事</li>
 *   <li><b>举报人 + 原因 + 说明</b> —— 投诉的具体依据</li>
 * </ol>
 * 缺了任一项，管理员都得另开页面核对，队列就退化成"通知列表"。
 *
 * <p>⚠️ <b>不返回任何举报人的邮箱 / 被举报人的邮箱</b>：审核只需要昵称定位到人，
 * 邮箱属于账号信息，与"这条内容是否违规"无关。
 *
 * @author 刘
 */
@Data
@Schema(description = "审核队列行")
public class ReportAdminVO {

    @Schema(description = "举报记录ID")
    private Long id;

    @Schema(description = "被举报对象类型：1帖子 2评论")
    private Integer targetType;

    @Schema(description = "被举报对象ID")
    private Long targetId;

    @Schema(description = "评论所属帖子ID（帖子举报为 null）—— 前端据此拼跳转链接")
    private Long targetPostId;

    @Schema(description = "帖子标题（帖子举报）；评论举报为 null")
    private String targetTitle;

    @Schema(description = "目标摘要：帖子取 summary / 评论取正文截断")
    private String targetExcerpt;

    @Schema(description = "被举报内容作者ID")
    private Long targetAuthorId;

    @Schema(description = "被举报内容作者昵称")
    private String targetAuthorNickname;

    @Schema(description = "目标是否仍存在：已被删除时为 false，前端显示「内容已删除」")
    private Boolean targetExists;

    @Schema(description = "同一目标被举报的总次数（含已处理的举报）")
    private Integer reportedCount;

    @Schema(description = "举报人ID")
    private Long reporterId;

    @Schema(description = "举报人昵称")
    private String reporterNickname;

    @Schema(description = "举报原因：SPAM / ABUSE / ILLEGAL / OTHER")
    private String reason;

    @Schema(description = "举报补充说明")
    private String detail;

    @Schema(description = "处理状态：0待处理 1已驳回 2已删除内容")
    private Integer status;

    @Schema(description = "举报时间")
    private LocalDateTime createTime;

    @Schema(description = "处理人ID（未处理为 null）")
    private Long handledBy;

    @Schema(description = "处理人昵称")
    private String handledByName;

    @Schema(description = "处理时间")
    private LocalDateTime handledAt;

    @Schema(description = "处理备注")
    private String handleNote;
}