package com.codelkup.module.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 相似问题提示请求（M3-5）。
 *
 * <p>输入就是"正在写的标题"（编辑器里实时去抖后发来），所以字段名叫 title。
 * 校验只拦空值与超长：<b>太短的输入不报错</b>（那是用户还没打完，不是错误），
 * 由 Service 对"不足 2 字"直接返回空列表 —— 见 SimilarQuestionService 的说明。
 *
 * @author 刘
 */
@Data
@Schema(description = "相似问题提示请求")
public class SimilarQuestionRequest {

    @Schema(description = "正在写的标题（越完整结果越准）",
            example = "虚拟线程在 IO 密集场景的实测",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "标题不能为空")
    @Size(max = 120, message = "标题不能超过 120 个字符")
    private String title;
}