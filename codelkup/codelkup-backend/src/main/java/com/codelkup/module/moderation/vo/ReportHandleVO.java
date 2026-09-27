package com.codelkup.module.moderation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 处理举报结果。
 *
 * @author 刘
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "举报处理结果")
public class ReportHandleVO {

    @Schema(description = "举报记录ID")
    private Long id;

    @Schema(description = "处理后的状态：1已驳回 2已删除内容")
    private Integer status;

    /**
     * 因"同目标内容已删除"被自动关闭的其它待处理举报数。
     *
     * <p>返回它而不是静静吞掉：管理员点一次"删除内容"实际处理掉了几单，
     * 界面必须如实显示（否则队列待处理数从 5 直接变 0，看起来像丢数据）。
     */
    @Schema(description = "随本次删除被自动关闭的同目标举报数")
    private Integer closedSiblings;
}