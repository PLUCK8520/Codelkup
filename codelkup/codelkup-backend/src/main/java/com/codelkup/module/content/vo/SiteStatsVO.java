package com.codelkup.module.content.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 站点汇总统计（一次请求给出全部"站里有多少东西"的数字）。
 *
 * <p>存在的理由：这些计数原先由前端在各页面"客户端拼装" ——
 * 问答页发 3 个请求凑 3 个数、标签页把热门榜长度当总数（上限 50）、
 * 首页标签数是热门榜长度（上限 20）。于是同一个站点在不同页面上
 * 报出不同口径的数字，且每个数都是"加载那一刻"的快照。
 * 这里给出唯一口径、唯一时刻的服务端聚合。
 *
 * @author 刘
 */
@Data
@Schema(description = "站点汇总统计")
public class SiteStatsVO {

    @Schema(description = "正常板块数")
    private Integer boardCount;

    @Schema(description = "全部板块的帖子数之和（与板块卡片数字同一套账）")
    private Integer boardPostCount;

    @Schema(description = "标签总数（不受热门榜条数上限影响）")
    private Integer tagCount;

    @Schema(description = "提问总数")
    private Integer questionCount;

    @Schema(description = "已解决提问数")
    private Integer questionSolvedCount;

    @Schema(description = "待解决提问数")
    private Integer questionUnsolvedCount;
}