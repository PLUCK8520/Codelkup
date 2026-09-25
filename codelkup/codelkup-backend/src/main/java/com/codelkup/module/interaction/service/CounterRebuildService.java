package com.codelkup.module.interaction.service;

import com.codelkup.module.content.mapper.PostMapper;
import com.codelkup.module.interaction.entity.UserAction;
import com.codelkup.module.interaction.mapper.CommentMapper;
import com.codelkup.module.interaction.mapper.UserActionMapper;
import com.codelkup.module.interaction.vo.RebuildReportVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 计数重建服务 —— <b>本 Sprint 最值得在面试里讲的一块</b>。
 *
 * <h2>它解决什么问题</h2>
 * 引入 Redis 计数后，"Redis 里的数字"和"数据库里的真实记录"可能不一致。
 * 不一致的来源至少四种：
 * <ol>
 *   <li>Redis 实例重启 / 被 flush → 增量全部丢失</li>
 *   <li>异步落库失败（MySQL 抖动、连接池耗尽）→ 基线落后于真实值</li>
 *   <li>发布事件后事务回滚 → 不该加的计数被加了</li>
 *   <li>人工误操作 / 代码 bug</li>
 * </ol>
 * 没有重建能力，"最终一致"就是一句空话 —— 面试官问一句
 * 「Redis 挂了计数就永久错了怎么办」，没有这个模块就答不上来。
 *
 * <h2>为什么能从 t_user_action 重建</h2>
 * 因为 {@code t_user_action} 存的是<b>事件明细</b>（谁、对什么、做了什么），
 * 而计数只是它的聚合视图。只要有明细，任何聚合值都能重算。
 * 这是"以不可变的明细为准，派生数据可重建"的思路 ——
 * 和账本与余额的关系一样，余额错了就重新加一遍账本。
 * <b>这也正是那张防重表存在的额外价值</b>：原设计只把它当"防重工具"，
 * 实际上它同时是"计数的事实来源"。
 *
 * <h2>幂等性</h2>
 * 重建全部用 {@code SET}/{@code UPDATE} <b>覆盖</b>，不用增量。
 * 因此重复执行任意次结果都相同 —— 可以放心在定时任务、人工运维、
 * 故障恢复流程里反复调用。
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CounterRebuildService {

    private final UserActionMapper userActionMapper;
    private final CommentMapper commentMapper;
    private final PostMapper postMapper;
    private final CounterService counterService;

    /**
     * 重建<b>单篇</b>帖子的全部计数（点赞 / 收藏 / 评论）。
     *
     * <p>先用 3 次 COUNT 拿到真值，再分别覆盖 Redis 与 MySQL。
     *
     * <p>注意写入顺序：<b>先 MySQL 后 Redis</b>。因为 Redis 是"实时增量"的承接方，
     * 一旦它被校准，后续的 {@code INCR} 就会从这个正确的基线往上加。
     * 反过来若先写 Redis，中间窗口内到达的增量会加在旧值上，又产生偏差。
     *
     * @return 重建报告（含新旧值对照，便于人工核对与压测取证）
     */
    public RebuildReportVO rebuildPost(Long postId) {
        RebuildReportVO report = new RebuildReportVO();
        report.setScope("post:" + postId);

        // ---- 1. 从明细表算真值 ----
        int trueLike = userActionMapper.countByTarget(
                UserAction.TARGET_POST, postId, UserAction.ACTION_LIKE);
        int trueCollect = userActionMapper.countByTarget(
                UserAction.TARGET_POST, postId, UserAction.ACTION_COLLECT);
        int trueComment = commentMapper.countByPostId(postId);

        // ---- 2. 记录重建前的值（用于报告）----
        var post = postMapper.selectById(postId);
        if (post == null) {
            report.setSuccess(false);
            report.setMessage("帖子不存在: " + postId);
            return report;
        }
        report.setBeforeLike(post.getLikeCount());
        report.setBeforeCollect(post.getCollectCount());
        report.setBeforeComment(post.getCommentCount());

        // ---- 3. 覆盖写入 MySQL 基线 ----
        postMapper.resetCounts(postId, trueLike, trueCollect, trueComment);

        // ---- 4. 覆盖写入 Redis ----
        counterService.resetPostCount("like", postId, trueLike);
        counterService.resetPostCount("collect", postId, trueCollect);
        counterService.resetPostCount("comment", postId, trueComment);

        // ---- 5. 报告 ----
        report.setSuccess(true);
        report.setAfterLike(trueLike);
        report.setAfterCollect(trueCollect);
        report.setAfterComment(trueComment);
        report.setDriftCount(
                drift(post.getLikeCount(), trueLike)
                        + drift(post.getCollectCount(), trueCollect)
                        + drift(post.getCommentCount(), trueComment));

        log.info("计数重建完成 | postId={} | like {}→{} | collect {}→{} | comment {}→{}",
                postId, post.getLikeCount(), trueLike,
                post.getCollectCount(), trueCollect,
                post.getCommentCount(), trueComment);
        return report;
    }

    /**
     * 重建<b>全部</b>帖子的计数。
     *
     * <p>用分批 + 一次 IN 查询聚合，而不是逐帖 3 次 COUNT：
     * N 篇帖子若逐帖查询要 3N 次往返，批量后只需常数次。
     * 这也是重建脚本能放心在运维窗口里跑的前提。
     */
    public RebuildReportVO rebuildAll() {
        List<Long> postIds = postMapper.selectAllIds();
        RebuildReportVO report = new RebuildReportVO();
        report.setScope("all");
        report.setTotalPosts(postIds.size());

        if (postIds.isEmpty()) {
            report.setSuccess(true);
            report.setMessage("没有需要重建的帖子");
            return report;
        }

        // ---- 批量取真值 ----
        Map<Long, Integer> likeMap = toCountMap(
                userActionMapper.countByTargets(postIds, UserAction.ACTION_LIKE));
        Map<Long, Integer> collectMap = toCountMap(
                userActionMapper.countByTargets(postIds, UserAction.ACTION_COLLECT));

        int fixed = 0;
        int totalDrift = 0;

        for (Long postId : postIds) {
            var post = postMapper.selectById(postId);
            if (post == null) {
                continue;
            }
            int trueLike = likeMap.getOrDefault(postId, 0);
            int trueCollect = collectMap.getOrDefault(postId, 0);
            int trueComment = commentMapper.countByPostId(postId);

            int d = drift(post.getLikeCount(), trueLike)
                    + drift(post.getCollectCount(), trueCollect)
                    + drift(post.getCommentCount(), trueComment);
            if (d > 0) {
                fixed++;
                totalDrift += d;
                log.info("修正计数偏差 | postId={} | like {}→{} | collect {}→{} | comment {}→{}",
                        postId, post.getLikeCount(), trueLike,
                        post.getCollectCount(), trueCollect,
                        post.getCommentCount(), trueComment);
            }

            postMapper.resetCounts(postId, trueLike, trueCollect, trueComment);
            counterService.resetPostCount("like", postId, trueLike);
            counterService.resetPostCount("collect", postId, trueCollect);
            counterService.resetPostCount("comment", postId, trueComment);
        }

        report.setSuccess(true);
        report.setFixedPosts(fixed);
        report.setDriftCount(totalDrift);
        log.info("全量计数重建完成 | 帖子数={} | 修正={} | 偏差总数={}",
                postIds.size(), fixed, totalDrift);
        return report;
    }

    /** 把 {@code [{targetId, cnt}]} 转成 Map，缺失的按 0 处理 */
    private Map<Long, Integer> toCountMap(List<Map<String, Object>> rows) {
        Map<Long, Integer> map = new HashMap<>();
        if (rows == null) {
            return map;
        }
        for (Map<String, Object> row : rows) {
            Object idObj = row.get("targetId");
            Object cntObj = row.get("cnt");
            if (idObj instanceof Number id && cntObj instanceof Number cnt) {
                map.put(id.longValue(), cnt.intValue());
            }
        }
        return map;
    }

    /** 绝对偏差；null 视为 0 */
    private int drift(Integer actual, int truth) {
        int a = actual == null ? 0 : actual;
        return Math.abs(a - truth);
    }
}
