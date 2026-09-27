package com.codelkup.module.ai.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * RAG 问答结果（M3-6）。
 *
 * <p>三个字段构成前端的全部渲染依据：{@code answer} 是<b>纯文本</b>（不走 v-html），
 * {@code citations} 由后端给出结构化 {@code postId} 生成站内链接，
 * {@code degraded} 决定是否显示"AI 暂不可用 / 仅检索结果"的提示。
 *
 * @author 刘
 */
@Data
@Schema(description = "RAG 问答结果")
public class AiAnswerVO {

    @Schema(description = "答案纯文本。降级时是可读的提示语，不是报错")
    private String answer;

    @Schema(description = "引用列表（降级时为检索到但未被引用的片段；无依据时为空数组）")
    private List<CitationVO> citations;

    @Schema(description = "是否降级（未启用 / 超预算 / 上游失败 / 无依据）")
    private boolean degraded;

    @Schema(description = "降级原因：NO_EVIDENCE / NO_KEY / DISABLED / BUDGET / TIMEOUT / UPSTREAM_ERROR / EMPTY_RESPONSE；正常为 null")
    private String degradeReason;

    public static AiAnswerVO ok(String answer, List<CitationVO> citations) {
        AiAnswerVO vo = new AiAnswerVO();
        vo.setAnswer(answer);
        vo.setCitations(citations);
        vo.setDegraded(false);
        return vo;
    }

    public static AiAnswerVO degraded(String reason, String answer, List<CitationVO> citations) {
        AiAnswerVO vo = new AiAnswerVO();
        vo.setAnswer(answer);
        vo.setCitations(citations);
        vo.setDegraded(true);
        vo.setDegradeReason(reason);
        return vo;
    }
}