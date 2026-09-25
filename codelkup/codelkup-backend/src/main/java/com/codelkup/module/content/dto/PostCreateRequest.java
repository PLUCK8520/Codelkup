package com.codelkup.module.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 发帖请求。
 *
 * <p>校验规则（对应 DEV-DOC §13.4）：
 * <ul>
 *   <li>title —— 必填、4~120 字（太短的标题讨论价值低）</li>
 *   <li>content —— 必填、10~50000 字（下限避免"如题"式空帖）</li>
 *   <li>boardId —— 必填，Service 层额外校验板块存在</li>
 *   <li>tags —— 可选，最多 5 个，单个 ≤ 20 字</li>
 * </ul>
 *
 * @author 刘
 */
@Data
@Schema(description = "发帖请求")
public class PostCreateRequest {

    @Schema(description = "标题", example = "Java 21 虚拟线程在 IO 密集场景下的实测", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "标题不能为空")
    @Size(min = 4, max = 120, message = "标题长度需为 4~120 个字符")
    private String title;

    @Schema(description = "正文（Markdown 原文）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "正文不能为空")
    @Size(min = 10, max = 50000, message = "正文长度需为 10~50000 个字符")
    private String content;

    @Schema(description = "板块ID", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "请选择板块")
    private Long boardId;

    @Schema(description = "帖子类型：0普通帖 1提问帖", example = "0")
    private Integer type;

    @Schema(description = "标签名列表（不存在会自动创建）", example = "[\"Java\", \"并发\"]")
    @Size(max = 5, message = "最多只能添加 5 个标签")
    private List<@NotBlank(message = "标签名不能为空") @Size(max = 20, message = "标签名不能超过 20 个字符") String> tags;
}
