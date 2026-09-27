package com.codelkup.module.content.service;

import com.codelkup.module.content.vo.SiteStatsVO;

/**
 * 站点汇总统计。
 *
 * <p>落在 content 模块：统计的全部实体（板块 / 标签 / 提问）都属于内容域，
 * 数字口径也与内容侧的列表查询同源（{@code deleted = 0 AND status = 1}）——
 * 放在别处就得反向依赖 content 的 Mapper。
 *
 * @author 刘
 */
public interface SiteStatsService {

    /**
     * 全站汇总统计（游客可读）。
     *
     * <p>四个计数出自三条 SQL、三张表，实现里用只读事务包住，
     * 保证它们来自同一时刻的快照（详见实现类注释）。
     */
    SiteStatsVO overview();
}