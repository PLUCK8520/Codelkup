package com.codelkup.common.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页结果封装。
 *
 * @param <T> 列表元素类型
 * @author 刘
 */
@Data
@Schema(description = "分页结果")
public class PageResult<T> implements Serializable {

    @Schema(description = "当前页码，从 1 开始", example = "1")
    private long page;

    @Schema(description = "每页条数", example = "20")
    private long size;

    @Schema(description = "总记录数", example = "135")
    private long total;

    @Schema(description = "总页数", example = "7")
    private long pages;

    @Schema(description = "数据列表")
    private List<T> records;

    public PageResult(long page, long size, long total, List<T> records) {
        this.page = page;
        this.size = size;
        this.total = total;
        this.pages = size == 0 ? 0 : (total + size - 1) / size;
        this.records = records;
    }

    public static <T> PageResult<T> of(long page, long size, long total, List<T> records) {
        return new PageResult<>(page, size, total, records);
    }

    public static <T> PageResult<T> empty(long page, long size) {
        return new PageResult<>(page, size, 0, List.of());
    }
}
