package com.codelkup.module.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 编辑帖子请求。
 *
 * <p>与 {@link PostCreateRequest} 的关键差异：<b>字段全部可选</b>，只更新传入的部分
 * （PATCH 语义）。若强制全量，用户只想改标题却必须重传正文，前端的"部分编辑"就没法做。
 *
 * <p>标签是例外：一旦传入就<b>整体替换</b>（先全删再重插），传空数组表示清空标签。
 *
 * @author 刘
 */
@Data
@Schema(description = "编辑帖子请求（字段可选，只更新传入项）")
public class PostUpdateRequest {

    @Schema(description = "标题")
    @Size(min = 4, max = 120, message = "标题长度需为 4~120 个字符")
    private String title;

    @Schema(description = "正文（Markdown 原文）")
    @Size(min = 10, max = 50000, message = "正文长度需为 10~50000 个字符")
    private String content;

    @Schema(description = "板块ID（换板块）")
    private Long boardId;

    @Schema(description = "帖子类型：0普通帖 1提问帖")
    private Integer type;

    @Schema(description = "标签名列表（传入即整体替换，传空数组表示清空）")
    @Size(max = 5, message = "最多只能添加 5 个标签")
    private List<@Size(max = 20, message = "标签名不能超过 20 个字符") String> tags;

    /**
     * 标题在入口处去除首尾空白（2026-09-29 纠错轮）。
     *
     * <p>与 {@code PostCreateRequest} 同一理由：校验发生在反序列化之后，
     * 不先 trim 的话 `"    "`（4 个空格）能通过 min=4，落库却成了空标题。
     */
    public void setTitle(String title) {
        this.title = title == null ? null : title.trim();
    }
}
