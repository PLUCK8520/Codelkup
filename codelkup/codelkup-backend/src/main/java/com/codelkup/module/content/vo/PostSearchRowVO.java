package com.codelkup.module.content.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 搜索结果行 —— {@link PostListVO} 的超集，额外带正文。
 *
 * <h2>为什么需要这个类</h2>
 * 生成 snippet（"关键词附近的一段正文"）必须能读到 {@code content}，
 * 但 {@link PostListVO} 刻意不带正文（S2 的核心决策：一页 20 条若带上
 * MEDIUMTEXT，响应体积膨胀两个数量级）。
 *
 * <p>于是把"查询期需要"和"响应期返回"拆开：
 * <ul>
 *   <li>Mapper 查 {@code PostSearchRowVO}（带正文）—— 只活在后端内存里</li>
 *   <li>Service 据正文算出 {@code snippet} 后，<b>丢弃</b>正文，
 *       转成 {@link PostListVO} 返回给前端</li>
 * </ul>
 *
 * <p>⚠️ {@code content} 加了 {@link JsonIgnore} 是<b>双保险</b>：
 * 即使将来有人图省事直接把这个 VO 塞进响应，正文也不会泄露出去。
 * 单靠"记得转换"是不牢靠的约定，注解才是结构性约束。
 *
 * @author 刘
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "搜索结果行（内部使用，不直接返回）")
public class PostSearchRowVO extends PostListVO {

    @JsonIgnore
    @Schema(description = "正文（Markdown 原文，仅用于后端生成 snippet）", hidden = true)
    private String content;
}
