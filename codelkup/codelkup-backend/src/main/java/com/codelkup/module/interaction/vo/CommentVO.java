package com.codelkup.module.interaction.vo;

import com.codelkup.module.content.vo.UserBriefVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 评论视图对象（树形）。
 *
 * <p>扁平数据来自 {@code selectByPostId}，Service 层按 {@code parentId} 分组后
 * 把回复塞进 {@link #replies}，形成<b>固定两层</b>的树。
 *
 * <p>⚠️ 不含 {@code authorId} 之外的任何用户隐私字段（email 等）。
 *
 * @author 刘
 */
@Data
@Schema(description = "评论（树形）")
public class CommentVO {

    @Schema(description = "评论ID")
    private Long id;

    @Schema(description = "帖子ID")
    private Long postId;

    @Schema(description = "作者ID")
    private Long authorId;

    @Schema(description = "评论内容（纯文本）")
    private String content;

    @Schema(description = "根评论ID：0=一级评论")
    private Long parentId;

    @Schema(description = "被回复者ID")
    private Long replyUserId;

    @Schema(description = "点赞数（实时值，已合并 Redis 计数）")
    private Integer likeCount;

    @Schema(description = "是否被采纳")
    private Integer isAccepted;

    @Schema(description = "发布时间")
    private LocalDateTime createTime;

    // ---- JOIN 字段 ----

    @Schema(description = "作者昵称")
    private String authorNickname;

    @Schema(description = "作者头像")
    private String authorAvatar;

    @Schema(description = "被回复者昵称（渲染「回复 @某人」）")
    private String replyUserNickname;

    // ---- 组装字段 ----

    @Schema(description = "作者简要信息")
    private UserBriefVO author;

    @Schema(description = "被回复者简要信息")
    private UserBriefVO replyUser;

    @Schema(description = "回复列表（仅一级评论有此字段非空）")
    private List<CommentVO> replies = new ArrayList<>();

    @Schema(description = "当前登录用户是否可删除（作者本人或管理员）")
    private Boolean canDelete;

    @Schema(description = "当前登录用户是否已点赞")
    private Boolean liked;
}
