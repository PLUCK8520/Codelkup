package com.codelkup.module.content.service.impl;

import com.codelkup.module.content.mapper.BoardMapper;
import com.codelkup.module.content.mapper.PostMapper;
import com.codelkup.module.content.mapper.TagMapper;
import com.codelkup.module.content.service.SiteStatsService;
import com.codelkup.module.content.vo.SiteStatsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 站点汇总统计实现。
 *
 * <p><b>为什么整个方法挂 {@code @Transactional(readOnly = true)}</b>：
 * 计数来自三张表、三条 SQL，不放进同一个事务，它们就是**三个时刻**的快照 ——
 * 一次发帖恰好落在中间时，"板块帖子总数"与"提问总数"会互相矛盾。
 * 只读事务给到一致性快照，不加锁、不影响写入。
 *
 * <p><b>为什么不缓存</b>：这些数字的用途就是"当场可信"（问答页显示
 * "还剩多少没解决"、首页显示"多少个待解决"）—— 省几条 COUNT 的收益，
 * 换来"有人发帖后数字继续撒谎"的代价，与项目"指标也不许撒谎"的口径相悖。
 * 三条查询都是扫描/索引上的 COUNT，1 万帖量级实测毫秒级。
 *
 * @author 刘
 */
@Service
@RequiredArgsConstructor
public class SiteStatsServiceImpl implements SiteStatsService {

    private final BoardMapper boardMapper;
    private final TagMapper tagMapper;
    private final PostMapper postMapper;

    @Override
    @Transactional(readOnly = true)
    public SiteStatsVO overview() {
        Map<String, Object> boards = boardMapper.countEnabledStats();
        Map<String, Object> questions = postMapper.countAllQuestionStats();

        SiteStatsVO vo = new SiteStatsVO();
        vo.setBoardCount(asInt(boards.get("board_count")));
        vo.setBoardPostCount(asInt(boards.get("post_total")));
        vo.setTagCount(tagMapper.countAll());
        vo.setQuestionCount(asInt(questions.get("total")));
        vo.setQuestionSolvedCount(asInt(questions.get("solved")));
        vo.setQuestionUnsolvedCount(asInt(questions.get("unsolved")));
        return vo;
    }

    /**
     * JDBC 对 COUNT 返回 Long、对 SUM 返回 BigDecimal —— 统一转 int。
     * 字段在 VO 上用包装类型，这里的 0 只兜"查询结果列缺失"这一种异常，
     * 正常路径下三列必然存在（别名写死在 SQL 里）。
     */
    private static int asInt(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }
}