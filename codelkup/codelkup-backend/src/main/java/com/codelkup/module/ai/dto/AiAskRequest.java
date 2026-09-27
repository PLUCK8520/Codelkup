package com.codelkup.module.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * RAG 问答请求（M3-6）。
 *
 * <p>长度上限 200：这是"一个问题"，不是"一篇文档" —— 超长输入既不会让回答更准
 * （检索按关键词，长文反而稀释命中），还会成倍放大 prompt 成本。真需要长文本
 * 场景（长帖 TL;DR）走 M3-7 的独立接口。
 *
 * @author 刘
 */
@Data
@Schema(description = "RAG 问答请求")
public class AiAskRequest {

    @Schema(description = "用户问题", example = "虚拟线程和平台线程有什么区别？",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "问题不能为空")
    @Size(max = 200, message = "问题不能超过 200 个字符")
    private String question;
}