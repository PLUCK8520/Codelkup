package com.codelkup.module.content.service;

import com.codelkup.module.content.mapper.PostMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 热度分计算服务。
 *
 * <h2>为什么要有它</h2>
 * 列表页的 {@code hot} 排序是 {@code ORDER BY p.is_top DESC, p.score DESC, p.create_time DESC}，
 * 而 {@code t_post.score} 此前是<b>死字段</b> —— 建表后只在发帖时写 0，之后再无更新。
 * 结果：热度排序与"最新"完全等价（全 0 加时间 tie-breaker），
 * 页面上那个「热门」入口点下去没有任何区别。
 *
 * <h2>为什么是"定时全量重算"而不是"实时计算"</h2>
 * <ol>
 *   <li><b>实时算做不到走索引</b>：把公式写进 ORDER BY 就必然 filesort，
 *       与 V5~V7 辛苦建立的"只读 LIMIT 行数"优化直接冲突。</li>
 *   <li><b>增量维护很别扭</b>：时间衰减是"随当前时间连续变化"的量，
 *       增量更新只记"当时的分"，每次读取还要按时间补偿 —— 复杂度高且无法解释。</li>
 *   <li><b>全量重算足够便宜</b>：一条 UPDATE（见 {@link PostMapper#recalcScores}），
 *       1 万帖量级 &lt; 1 秒，每 5 分钟跑一次可以忽略不计。</li>
 * </ol>
 *
 * <h2>失败与降级</h2>
 * 重算失败<b>不清零、不抛异常</b>：保留上一轮的分数（哪怕偏旧），只记 ERROR 日志。
 * 排序短暂不新鲜，远好过"因为定时任务炸了导致热门列表整体为空"。
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreService {

    private final PostMapper postMapper;

    /** 总开关：压测/排障时可以先关掉，避免定时任务干扰观察 */
    @Value("${codelkup.score.enabled:true}")
    private boolean enabled = true;

    /** 点赞权重 */
    @Value("${codelkup.score.like-weight:2}")
    private int likeWeight = 2;

    /** 收藏权重 */
    @Value("${codelkup.score.collect-weight:3}")
    private int collectWeight = 3;

    /** 评论权重（最高：写评论的成本与社区贡献最大） */
    @Value("${codelkup.score.comment-weight:4}")
    private int commentWeight = 4;

    /**
     * 时间衰减系数 λ（单位：1/小时）。
     *
     * <p>半衰期 = ln2 / λ；默认 λ=0.02 → 约 34.7 小时，即"一天半前的热度折半"，
     * 与论坛"热点存活约一到两天"的直觉一致。
     */
    @Value("${codelkup.score.decay-lambda:0.02}")
    private double decayLambda = 0.02;

    /** 执行间隔（毫秒），仅用于启动日志与 @Scheduled 占位符保持一致 */
    @Value("${codelkup.score.interval-ms:300000}")
    private long intervalMs = 300_000L;

    @PostConstruct
    void logConfig() {
        log.info("热度分任务配置 | enabled={} | 间隔={}ms | λ={}（半衰期≈{}小时） | 权重 赞/藏/评={}/{}/{}",
                enabled, intervalMs, decayLambda, String.format("%.1f", Math.log(2) / decayLambda),
                likeWeight, collectWeight, commentWeight);
    }

    /**
     * 定时入口：启动后延迟首跑（默认 30s，避开应用启动期的资源竞争），之后按间隔重复。
     *
     * <p>两个时间都可配（{@code codelkup.score.initial-delay-ms} / {@code interval-ms}）——
     * 验收脚本会把它们调到秒级，好在一次脚本运行内看到分数变化。
     */
    @Scheduled(initialDelayString = "${codelkup.score.initial-delay-ms:30000}",
            fixedDelayString = "${codelkup.score.interval-ms:300000}")
    public void recalcScheduled() {
        if (!enabled) {
            return;
        }
        recalcOnce();
    }

    /**
     * 重算一次，返回受影响行数（失败返回 -1）。
     *
     * <p>独立成 public 方法而不是把逻辑塞进定时方法：定时入口只负责"要不要跑"，
     * 重算本身可以被单测直接驱动，将来要加管理端手动触发也不必复制逻辑。
     */
    public int recalcOnce() {
        long start = System.currentTimeMillis();
        try {
            int affected = postMapper.recalcScores(likeWeight, collectWeight, commentWeight, decayLambda);
            log.info("热度分重算完成 | 影响行数={} | 耗时={}ms",
                    affected, System.currentTimeMillis() - start);
            return affected;
        } catch (Exception e) {
            // 保留上一轮分数：排序暂时不新鲜好过列表整体异常
            log.error("热度分重算失败，保留上次分数 | 耗时={}ms",
                    System.currentTimeMillis() - start, e);
            return -1;
        }
    }
}