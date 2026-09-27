package com.codelkup.module.ai.service;

import com.codelkup.common.constant.RedisKeys;
import com.codelkup.module.ai.entity.AiAnswerLog;
import com.codelkup.module.ai.mapper.AiAnswerLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 调用的"后勤"：日预算、结果缓存、调用日志（M3-6 抽出，M3-7 起被两个服务共用）。
 *
 * <h2>为什么把三件事放在一起</h2>
 * 它们服务于同一个目的 —— 让每一次<b>花钱的调用</b>都被限额、可缓存、可复盘。
 * 各写一份的代价不是"多几行代码"，而是**口径会漂**：
 * 预算计数一旦在两个服务里各写一遍，早晚会出现一个改了一个没改
 * （比如"先 INCR 再比较"的取舍只改了一边），到时候总预算就悄悄翻倍。
 *
 * <h2>三类失败的处理口径（与限流/计数一致）</h2>
 * <ul>
 *   <li><b>Redis 不可用</b>：预算放行、缓存当作未命中 —— AI 可用性不该被缓存层拖死；</li>
 *   <li><b>缓存读写/序列化异常</b>：当作未命中、写入失败仅记 WARN（不影响本次回答）；</li>
 *   <li><b>日志写入失败</b>：仅记 WARN —— 复盘数据缺失远好过问答失败。</li>
 * </ul>
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCallGuard {

    /** 表格列宽（VARCHAR(255)），写库前防御性截断 */
    private static final int MAX_CITATION_IDS_LENGTH = 255;

    private final StringRedisTemplate stringRedisTemplate;
    private final AiAnswerLogMapper aiAnswerLogMapper;
    private final ObjectMapper objectMapper;

    /** 指标注册表（M3-10）：AI 调用结果分布 */
    private final MeterRegistry meterRegistry;

    /** 全站日预算（次/天） */
    @Value("${codelkup.ai.daily-budget:2000}")
    private int dailyBudget;

    /** AI 结果缓存时长（小时）—— 问答与摘要共用一个 TTL 口径 */
    @Value("${codelkup.ai.cache-ttl-hours:6}")
    private long cacheTtlHours;

    // ==================== 日预算 ====================

    /**
     * 消耗一次全站日预算。
     *
     * <p>先 INCR 再比较（多算一次的代价远小于"先查后写"在并发下超发）；
     * 调用失败的那次也照样计数 —— 不为此做"退款"，那会让计数逻辑复杂一档，
     * 而预算本来就是保守值。命中缓存的请求<b>不该</b>调用本方法（它确实没花钱）。
     */
    public boolean consumeBudget() {
        String key = RedisKeys.AI_DAILY_BUDGET + LocalDate.now();
        try {
            Long used = stringRedisTemplate.opsForValue().increment(key);
            if (used != null) {
                // ⚠️ 无条件续 TTL（第三轮审计）：原实现只在 used==1 时设过期 —— 若那一次 expire
                //    失败（或键由更早版本创建），这个按天键就**永久**留在 Redis 里，逐日累积。
                //    每次续 2 天：跨天自然废弃，不需要清理任务；重复设置是幂等的。
                stringRedisTemplate.expire(key, Duration.ofDays(2));
            }
            if (used != null && used > dailyBudget) {
                log.warn("AI 日预算已用尽 | 已用={} | 预算={}/天", used, dailyBudget);
            }
            return used == null || used <= dailyBudget;
        } catch (Exception e) {
            log.warn("AI 日预算计数失败，本次放行 | err={}", e.getMessage());
            return true;
        }
    }

    // ==================== 结果缓存 ====================

    /** 读缓存；任何异常都当作"未命中"（让这次调用正常走完） */
    public <T> T readCache(String key, Class<T> type) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            return json == null ? null : objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("AI 缓存读取失败（按未命中处理）| err={}", e.getMessage());
            return null;
        }
    }

    /**
     * 写缓存。
     *
     * <p>调用方必须保证<b>只缓存真正花过钱的结果</b>：降级结果没花钱，
     * 缓存它们只会让"后来配上 key"的部署在 TTL 内一直拿到旧降级文案。
     */
    public void writeCache(String key, Object value) {
        try {
            stringRedisTemplate.opsForValue()
                    .set(key, objectMapper.writeValueAsString(value), Duration.ofHours(cacheTtlHours));
        } catch (Exception e) {
            log.warn("AI 缓存写入失败（不影响本次结果）| err={}", e.getMessage());
        }
    }

    // ==================== 调用日志 ====================

    /**
     * 一次 AI 调用的日志条目。
     *
     * @param fingerprint 请求指纹（问答=问题指纹，摘要=正文哈希）—— <b>不记录原文</b>
     * @param outputLength 输出字符数（答案 / 摘要）
     */
    public record LogEntry(Long userId, String fingerprint, List<Long> citationIds, int outputLength,
                           Integer totalTokens, boolean cacheHit, boolean degraded,
                           String degradeReason, long startedMs) {
    }

    /** 写一行日志（失败只记 WARN，绝不抛出） */
    public void record(LogEntry entry) {
        // 指标（M3-10）：AI 结果分布 —— result=ok|cache_hit|degraded，reason 为降级原因。
        // ⚠️ 放在 try 之前：日志写库失败不该让指标也丢（指标反映的是"调用发生了什么"，
        //    而日志反映的是"我们有没有记下来"，两件事）
        meterRegistry.counter("codelkup.ai.calls",
                "result", entry.cacheHit() ? "cache_hit" : (entry.degraded() ? "degraded" : "ok"),
                "reason", entry.degradeReason() == null ? "none" : entry.degradeReason()).increment();
        try {
            AiAnswerLog row = new AiAnswerLog();
            row.setUserId(entry.userId());
            row.setQuestionHash(entry.fingerprint());
            row.setCitationIds(citationIds(entry.citationIds()));
            row.setAnswerLength(entry.outputLength());
            row.setDurationMs((int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - entry.startedMs()));
            row.setTotalTokens(entry.totalTokens());
            row.setCacheHit(entry.cacheHit() ? 1 : 0);
            row.setDegraded(entry.degraded() ? 1 : 0);
            row.setDegradeReason(entry.degradeReason());
            aiAnswerLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("AI 调用日志写入失败（不影响结果）| fingerprint={} | err={}",
                    entry.fingerprint(), e.getMessage());
        }
    }

    private String citationIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        String joined = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        return joined.length() <= MAX_CITATION_IDS_LENGTH
                ? joined : joined.substring(0, MAX_CITATION_IDS_LENGTH);
    }

    /**
     * SHA-256 十六进制摘要 —— 指纹的唯一实现（问答的"问题指纹"与摘要的"正文哈希"共用）。
     *
     * <p>⚠️ 它只用于缓存键与去重，<b>不承担安全职责</b>（不需要抗碰撞强度）；
     * 选 SHA-256 只是为了避免"MD5 不安全"这类无意义的评审噪音。
     * 归一化（大小写、空白）由调用方决定：问题要归一化（同问只付一次费），
     * 正文<b>不</b>归一化（差一个字符就是另一篇内容，摘要必须区分）。
     */
    public static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然支持 SHA-256；真发生了也不能静默返回"每次不同的指纹"（那会让缓存永远打不中）
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}