package com.codelkup.module.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 草稿保存请求（upsert）。
 *
 * <h2>为什么校验比 PostCreateRequest 松</h2>
 * 草稿天然是"写了一半的东西"：可能只有标题、可能只有正文、可能还没选板块 ——
 * 所以除 scope 外全部可空。这里只保留三类硬约束：
 * <ul>
 *   <li><b>长度上限与发帖对齐</b>（标题 120 / 正文 50000 / 标签 5 个）——
 *       超限的内容即使存进草稿也永远发不出去，不如在入口就说清楚</li>
 *   <li><b>scope 格式</b>：只接受 {@code new} 与 {@code edit:<数字>}，
 *       与前端 useDraft 的 scope 命名一致（同一个字符串就是两侧的"草稿身份"）</li>
 *   <li><b>boardId 为正数</b>：不校验板块是否存在 —— 草稿允许引用一个后续会被
 *       改掉的板块，真正的存在性校验在发帖时做</li>
 * </ul>
 *
 * @author 刘
 */
@Data
@Schema(description = "草稿保存请求")
public class DraftSaveRequest {

    @Schema(description = "草稿归属：new=新建稿；edit:<postId>=某帖的编辑稿",
            example = "new", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "草稿归属不能为空")
    @Size(max = 64, message = "草稿归属过长")
    @Pattern(regexp = "new|edit:\\d+", message = "草稿归属格式不正确")
    private String scope;

    @Schema(description = "标题（草稿可为空）", example = "Java 21 虚拟线程实测")
    @Size(max = 120, message = "标题不能超过 120 个字符")
    private String title;

    @Schema(description = "正文 Markdown 原文（草稿可为空）")
    @Size(max = 50000, message = "正文不能超过 50000 个字符")
    private String content;

    @Schema(description = "所选板块（草稿可为空）", example = "2")
    @Positive(message = "板块ID不合法")
    private Long boardId;

    @Schema(description = "类型：0普通帖 1提问帖", example = "0")
    @Min(value = 0, message = "类型不合法")
    @Max(value = 1, message = "类型不合法")
    private Integer type;

    @Schema(description = "标签名列表，最多 5 个（与发帖口径一致）", example = "[\"Java\", \"并发\"]")
    @Size(max = 5, message = "最多只能添加 5 个标签")
    private List<@NotBlank(message = "标签名不能为空")
                 @Size(max = 20, message = "标签名不能超过 20 个字符") String> tags;
}