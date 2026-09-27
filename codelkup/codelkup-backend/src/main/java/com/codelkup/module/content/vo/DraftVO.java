package com.codelkup.module.content.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 草稿（含正文）—— 编辑器进入时读取、保存后回执。
 *
 * @author 刘
 */
@Data
@Schema(description = "草稿（含正文）")
public class DraftVO {

    @Schema(description = "草稿归属：new / edit:<postId>", example = "new")
    private String scope;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "正文 Markdown 原文")
    private String content;

    @Schema(description = "所选板块（可能为 null）")
    private Long boardId;

    @Schema(description = "0普通帖 1提问帖")
    private Integer type;

    @Schema(description = "标签名列表")
    private List<String> tags;

    @Schema(description = "最后保存时间（服务端时间，与全站时间格式一致）")
    private LocalDateTime updateTime;

    /**
     * 最后保存时间的 epoch 毫秒（与 {@code updateTime} 同一刻）。
     *
     * <p>为什么除了格式化时间还要给这个数：前端要先回答"本地草稿与服务端草稿
     * 哪个更新"才能决定恢复哪一份，而两边的"时间"来源不同 ——
     * 本地是浏览器 {@code Date.now()}，服务端是这里。给 epoch 比让前端解析
     * {@code yyyy-MM-dd HH:mm:ss} 字符串再猜时区稳得多（本服务不返回时区标记，
     * 前端按本地时区解析会在非 +08 环境下整体偏移）。
     */
    @Schema(description = "最后保存时间（epoch 毫秒），供前端比较新旧")
    private Long updateTimeEpoch;
}