package com.codelkup.module.notification.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 某一类型的未读通知数。
 *
 * <p>给通知中心的分组角标用：一条 {@code GROUP BY} 拿到全部类型的未读数，
 * 而不是每个 tab 各发一次 COUNT —— 后者不只是多几次往返，还会随类型数量线性变差。
 *
 * <p><b>只包含"有未读"的类型</b>：没有未读的类型不出现在列表里，调用方按 0 处理。
 * 让数据库决定哪些类型有数据，比固定返回 6 个恒为 0 的项更省、也不会有"类型新增了
 * 但这里没跟上"的维护点。
 *
 * @author 刘
 */
@Data
@Schema(description = "按类型的未读数")
public class NotificationTypeUnreadVO {

    @Schema(description = "1评论 2回复 3点赞 4关注 5采纳 6系统")
    private Integer type;

    @Schema(description = "该类型的未读条数")
    private Integer count;
}