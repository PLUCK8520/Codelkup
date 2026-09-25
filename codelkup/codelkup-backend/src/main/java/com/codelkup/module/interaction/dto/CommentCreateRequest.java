package com.codelkup.module.interaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发表评论请求。
 *
 * <p>回复由 {@link #parentId} 表达：不传 / 传 0 为一级评论，
 * 传某条评论的 ID 则为"回复该评论"。服务端会把它归一化成根评论 ID
 * （只做两级），前端不需要关心这个细节。
 *
 * @author 刘
 */
@Data
@Schema(description = "发表评论请求")
public class CommentCreateRequest {

    @Schema(description = "评论内容（纯文本）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 1000, message = "评论内容不能超过 1000 字")
    private String content;

    @Schema(description = "父评论ID：不传或 0 表示一级评论；传值表示回复该评论")
    private Long parentId;
}
