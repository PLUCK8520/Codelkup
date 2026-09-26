package com.codelkup.module.upload.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 上传结果。
 *
 * <p>刻意只回一个 {@code url}，不回文件大小/路径/原始文件名 ——
 * 客户端需要的只有"把它插到哪里"这一件事，多回的字段将来都会变成兼容负担。
 *
 * @author 刘
 */
@Data
@AllArgsConstructor
@Schema(description = "上传结果")
public class UploadResultVO {

    @Schema(description = "文件访问地址（相对路径，直接可放进 Markdown 或 img src）",
            example = "/uploads/posts/12/2026/09/6f1e....webp")
    private String url;
}