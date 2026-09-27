package com.codelkup.module.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 声望榜条目。
 *
 * <p>刻意只暴露榜单需要的 6 个字段，<b>不含 email / password / role / status</b>：
 * Mapper 里也是按列查询而不是 {@code SELECT *} —— 一个公开的只读榜，
 * 没有任何理由把整行用户数据捞进内存再裁剪。
 *
 * <p>{@code rank} 由服务端给出而不是让前端用下标算：名次是榜单的**核心数据**，
 * 不是表现层细节；将来若给榜单加分页，名次必须等于"偏移量 + 下标"，
 * 那时前端算就错了，而服务端算天然正确。
 *
 * @author 刘
 */
@Data
@Schema(description = "声望榜条目")
public class RankingUserVO {

    @Schema(description = "名次（从 1 开始）")
    private Integer rank;

    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像")
    private String avatar;

    @Schema(description = "等级（1~5，由声望推导）")
    private Integer level;

    @Schema(description = "声望")
    private Integer reputation;

    @Schema(description = "最后活跃时间")
    private LocalDateTime lastActive;
}