package com.codelkup.module.interaction.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 互动操作结果（点赞 / 收藏）。
 *
 * <p>不返回空 body：前端需要立刻更新按钮状态与计数，避免再发一次详情请求。
 * 一次性把「操作后的状态」和「最新计数」都给前端，是这个 VO 存在的理由。
 *
 * @author 刘
 */
@Data
@Schema(description = "互动操作结果")
public class ActionResultVO {

    @Schema(description = "操作后的状态：true=已点赞/已收藏，false=已取消")
    private Boolean active;

    @Schema(description = "目标的最新计数（点赞数或收藏数）")
    private Integer count;

    public static ActionResultVO of(boolean active, int count) {
        ActionResultVO vo = new ActionResultVO();
        vo.setActive(active);
        vo.setCount(count);
        return vo;
    }
}
