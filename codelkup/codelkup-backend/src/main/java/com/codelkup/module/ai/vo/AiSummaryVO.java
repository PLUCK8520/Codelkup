package com.codelkup.module.ai.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 长帖 TL;DR 结果（M3-7）。
 *
 * <p>与 {@code AiAnswerVO} 分开而不是共用：摘要没有引用（它依据的是帖子本身），
 * 共用一个 VO 会多出永远为 null 的字段，前端也要多写一个"这个字段何时有值"的判断。
 *
 * @author 刘
 */
@Data
@Schema(description = "长帖 TL;DR 结果")
public class AiSummaryVO {

    @Schema(description = "帖子ID", example = "20103")
    private Long postId;

    @Schema(description = "摘要纯文本（3~5 条要点，不带 Markdown 强调符）；降级时为 null")
    private String summary;

    @Schema(description = "是否降级（未启用 / 超预算 / 上游失败 / 无正文）")
    private boolean degraded;

    @Schema(description = "降级原因：NO_CONTENT / NO_KEY / DISABLED / BUDGET / TIMEOUT / UPSTREAM_ERROR / EMPTY_RESPONSE；正常为 null")
    private String degradeReason;

    public static AiSummaryVO ok(Long postId, String summary) {
        AiSummaryVO vo = new AiSummaryVO();
        vo.setPostId(postId);
        vo.setSummary(summary);
        return vo;
    }

    public static AiSummaryVO degraded(Long postId, String reason) {
        AiSummaryVO vo = new AiSummaryVO();
        vo.setPostId(postId);
        vo.setDegraded(true);
        vo.setDegradeReason(reason);
        return vo;
    }
}