package com.codelkup.module.interaction.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 计数重建报告。
 *
 * <p>刻意返回"重建前后的值"而不只是成功/失败，有两个用途：
 * <ol>
 *   <li><b>可核对</b> —— 运维时能直接看到修正了什么，而不是盲信"成功"</li>
 *   <li><b>可取证</b> —— 压测或故障演练后，
 *       {@code driftCount} 就是"Redis 丢失造成了多少偏差"的量化证据，
 *       可以直接写进简历/答辩材料</li>
 * </ol>
 *
 * @author 刘
 */
@Data
@Schema(description = "计数重建报告")
public class RebuildReportVO {

    @Schema(description = "重建范围：post:{id} 或 all")
    private String scope;

    @Schema(description = "是否成功")
    private Boolean success;

    @Schema(description = "说明信息")
    private String message;

    @Schema(description = "重建前 MySQL 点赞数")
    private Integer beforeLike;

    @Schema(description = "重建前 MySQL 收藏数")
    private Integer beforeCollect;

    @Schema(description = "重建前 MySQL 评论数")
    private Integer beforeComment;

    @Schema(description = "重建后点赞数（真值）")
    private Integer afterLike;

    @Schema(description = "重建后收藏数（真值）")
    private Integer afterCollect;

    @Schema(description = "重建后评论数（真值）")
    private Integer afterComment;

    @Schema(description = "本次修正的偏差总量（各维度绝对差之和）")
    private Integer driftCount;

    @Schema(description = "全量重建：扫描的帖子总数")
    private Integer totalPosts;

    @Schema(description = "全量重建：被修正的帖子数")
    private Integer fixedPosts;
}
