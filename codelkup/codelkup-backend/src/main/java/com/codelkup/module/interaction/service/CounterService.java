package com.codelkup.module.interaction.service;

import com.codelkup.common.constant.RedisKeys;
import com.codelkup.module.content.mapper.PostMapper;
import com.codelkup.module.interaction.entity.UserAction;
import com.codelkup.module.interaction.mapper.CommentMapper;
import com.codelkup.module.interaction.mapper.UserActionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 计数服务 —— Redis 实时计数 + MySQL 可重建基线。
 *
 * <h2>一致性模型（DEV-DOC 修订 D5）</h2>
 * <blockquote>
 * <b>MySQL 提供可重建的基线，Redis 提供实时增量。</b>
 * 两者是"基线 + 增量"关系，不是"快照 + 权威"关系。
 * </blockquote>
 * 这个表述的差别很关键：如果宣称"Redis 是权威值"，面试官会立刻追问
 * "Redis 挂了计数就永久错了怎么办"——那时没有答案。而"基线 + 增量"模型下，
 * Redis 丢失只是丢掉增量，跑一遍 {@link CounterRebuildService} 就能从
 * {@code t_user_action} 反查真值恢复。
 *
 * <h2>为什么用 Redis 计数而不是直接 UPDATE</h2>
 * <pre>
 * UPDATE t_post SET like_count = like_count + 1 WHERE id = ?
 * </pre>
 * 这是行锁操作，热点帖的点赞会串行化；而 {@code INCR} 是单线程原子操作，
 * 无锁且 O(1)。
 *
 * <h2>降级策略</h2>
 * Redis 不可用时<b>不抛异常、不阻断业务</b>：读操作回落 MySQL 基线值，
 * 写操作只落 MySQL。代价是暂时失去实时性，但点赞本身能成功 ——
 * 对一个论坛来说，"点赞点了没反应"比"计数短暂不准"严重得多。
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CounterService {

    private final StringRedisTemplate redis;
    private final UserActionMapper userActionMapper;
    private final CommentMapper commentMapper;
    private final PostMapper postMapper;

    // ==================== key 构造 ====================

    /**
     * 帖子某个维度的计数 key。
     *
     * <p>统一走这里而不是各处拼字符串，避免拼错前缀导致"计数明明写了却读不到"。
     */
    private String postCountKey(String dimension, Long postId) {
        return switch (dimension) {
            case "like" -> RedisKeys.POST_LIKE_COUNT + postId;
            case "collect" -> RedisKeys.POST_COLLECT_COUNT + postId;
            case "comment" -> RedisKeys.POST_COMMENT_COUNT + postId;
            case "view" -> RedisKeys.POST_VIEW_COUNT + postId;
            default -> throw new IllegalArgumentException("未知计数维度: " + dimension);
        };
    }

    private String commentCountKey(Long commentId) {
        return RedisKeys.COMMENT_LIKE_COUNT + commentId;
    }

    // ==================== 写入：增量 ====================

    /**
     * 帖子计数增减。
     *
     * <p>⚠️ <b>不做负数保护</b>。看起来应该加 {@code if (count < 0) set 0}，
     * 但那样会掩盖真正的 bug（比如重复取消点赞）。宁可让它短暂为负、
     * 由重建脚本发现并修正，也不要静默吞掉异常信号。
     *
     * <h2>惰性基线（为什么不再先取基线）</h2>
     * 旧实现先把"当前 MySQL 基线"读出来、再让 Lua 拿它作初值 INCR —— 但调用点
     * （点赞/收藏/评论）都发生在同一事务里，明细行<b>已可见</b>，所以读到的所谓
     * "基线"其实是"变更后"的值，再 INCR 就多加了 1：<b>首次点赞显示 2</b>。
     *
     * <p>现在改为<b>惰性</b>：热路径只有 1 次 Lua（key 不存在就用本次增量占位
     * 并返回 1），只有真正发生"首次写入"时才回库取一次基线做校准
     * （{@link #repairIfUnchanged}）——正常路径不再为了一个已经存在的 key
     * 多打一次 COUNT 查询。
     *
     * <h2>事务回滚补偿（2026-09-29 纠错轮）</h2>
     * Redis 不是事务资源：事务回滚<b>不会</b>撤销上面的 INCR —— 而 MySQL 基线侧
     * 走 AFTER_COMMIT 事件（回滚时被丢弃）。若计数加完、事务随后回滚（唯一索引
     * 之外的失败、提交时断连等），Redis 永久多/少 1，两边漂移到重建脚本才发现。
     * 这里在提交回调上登记反向增量：<b>回滚则补回，提交则什么都不做</b>（增量已在）。
     * 不在事务中调用时为空操作，行为与旧版完全一致。
     */
    public void addPostCount(String dimension, Long postId, long delta) {
        if (applyPostCountDelta(dimension, postId, delta)) {
            // 只有真的动过 Redis 才登记补偿：失败（Redis 不可用）时本就没改，
            // 回滚再执行反向操作反而会凭空减掉一笔
            registerRollbackCompensation(() -> applyPostCountDelta(dimension, postId, -delta));
        }
    }

    /** 实际执行一次 Redis 增量；返回 false 表示 Redis 不可用、本次未改动任何值 */
    private boolean applyPostCountDelta(String dimension, Long postId, long delta) {
        String key = postCountKey(dimension, postId);
        try {
            Long miss = redis.execute(INCR_OR_MISS_SCRIPT, List.of(key), String.valueOf(delta));
            if (miss != null && miss == 1L) {
                // key 不存在：Lua 已把本次增量写成占位值；这里取"变更后基线"覆盖它。
                // 取变更后值是安全的：明细行在同一事务内可见，countByTarget 已把 delta 算进去
                repairIfUnchanged(key, delta, baselineOf(dimension, postId));
            }
            return true;
        } catch (Exception e) {
            // Redis 不可用：静默降级，MySQL 基线仍会被更新
            log.warn("Redis 计数失败，降级为仅更新 MySQL | key={} | delta={} | err={}",
                    key, delta, e.getMessage());
            return false;
        }
    }

    /**
     * 评论点赞数增减（同惰性基线）。
     *
     * <p>评论的基线列由计数事件<b>异步落库</b>（AFTER_COMMIT），所以事务里读到的
     * {@code t_comment.like_count} 仍是"变更前"值 —— 校准值时要把本次 delta 加上。
     *
     * <p>回滚补偿与 {@link #addPostCount} 同一机制（见其 javadoc）。
     */
    public void addCommentLikeCount(Long commentId, long delta) {
        if (applyCommentLikeDelta(commentId, delta)) {
            registerRollbackCompensation(() -> applyCommentLikeDelta(commentId, -delta));
        }
    }

    /** 实际执行一次评论点赞计数增量；返回 false 表示 Redis 不可用、本次未改动任何值 */
    private boolean applyCommentLikeDelta(Long commentId, long delta) {
        String key = commentCountKey(commentId);
        try {
            Long miss = redis.execute(INCR_OR_MISS_SCRIPT, List.of(key), String.valueOf(delta));
            if (miss != null && miss == 1L) {
                var comment = commentMapper.selectById(commentId);
                int before = comment == null || comment.getLikeCount() == null
                        ? 0 : comment.getLikeCount();
                repairIfUnchanged(key, delta, before + (int) delta);
            }
            return true;
        } catch (Exception e) {
            log.warn("评论点赞计数失败 | commentId={} | err={}", commentId, e.getMessage());
            return false;
        }
    }

    /**
     * 登记"当前事务回滚时的反向补偿"。
     *
     * <p>为什么需要（2026-09-29 纠错轮）：见 {@link #addPostCount} 的说明 ——
     * Redis 增量不受事务管理，回滚后必须人工补回反向增量，否则"基线 + 增量"
     * 模型的两侧永久漂移。
     *
     * <p>用 {@code afterCompletion} 而不是 {@code afterCommit}：只有回滚才执行，
     * 提交路径天然是空操作。无事务上下文时直接返回（无处可挂，也无回滚可言）。
     */
    private void registerRollbackCompensation(Runnable compensation) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    // 补偿内部的 Redis 异常已被 applyXxxDelta 吞掉并记 warn，这里无需再兜
                    compensation.run();
                }
            }
        });
    }

    /**
     * 计数脚本（惰性基线）。
     *
     * <p>key 不存在 → 用本次增量占位写入并返回 1；已存在 → INCRBY 并返回 0。
     * Redis 单线程执行保证"判断存在 + 写入"是原子的：不会出现两个请求都按
     * "首次"处理，也不会出现"一个刚初始化、另一个读到初始化前状态"的交错。
     */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long>
            INCR_OR_MISS_SCRIPT = new org.springframework.data.redis.core.script.DefaultRedisScript<>(
            """
            if redis.call('EXISTS', KEYS[1]) == 0 then
                redis.call('SET', KEYS[1], ARGV[1])
                return 1
            end
            redis.call('INCRBY', KEYS[1], ARGV[1])
            return 0
            """, Long.class);

    /**
     * 基线校准脚本：仅当 key 当前值仍等于占位值时才覆盖为库侧基线（CAS）。
     *
     * <p>若期间有并发写把值改掉了（值 ≠ 占位值），说明那份状态已被别的请求
     * 接手 —— 直接覆盖会把它算进去的增量抹掉。放弃校准、交给重建脚本兜底，
     * 是这种情况下更安全的选择。
     *
     * <p>{@code ARGV[1]}=占位值（本次 delta），{@code ARGV[2]}=库侧基线。
     */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long>
            REPAIR_IF_UNCHANGED_SCRIPT = new org.springframework.data.redis.core.script.DefaultRedisScript<>(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                redis.call('SET', KEYS[1], ARGV[2])
                return 1
            end
            return 0
            """, Long.class);

    /** 取库侧基线覆盖占位值（只在 key 首次被写入、且期间无并发写时生效） */
    private void repairIfUnchanged(String key, long placeholder, int baseline) {
        try {
            redis.execute(REPAIR_IF_UNCHANGED_SCRIPT, List.of(key),
                    String.valueOf(placeholder), String.valueOf(baseline));
        } catch (Exception e) {
            log.warn("计数基线校准失败（由重建脚本兜底）| key={} | err={}", key, e.getMessage());
        }
    }

    // ==================== 读取：Redis 优先，miss 回填 ====================

    /**
     * 读帖子某维度计数。
     *
     * <p>读路径：Redis → 未命中则读 MySQL 基线并<b>回填 Redis</b> → 返回。
     * 回填很关键：否则每个请求都要打一次 MySQL，"缓存"就白加了。
     */
    public int getPostCount(String dimension, Long postId) {
        String key = postCountKey(dimension, postId);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return Integer.parseInt(cached);
            }
            // 未命中：从 MySQL 基线回填
            int baseline = baselineOf(dimension, postId);
            // ⚠️ 必须 SETNX（第三轮审计 C1）：与"首次写入"的计数脚本（见 INCR_OR_MISS_SCRIPT）
            //    并发时，普通 SET 会把刚 INCR 的结果覆盖回旧基线 → Redis 比 MySQL 少 1，
            //    且要等到下次重建才自愈。SETNX 失败说明 key 已被并发方初始化：改读它的现值返回。
            Boolean initialized = redis.opsForValue().setIfAbsent(key, String.valueOf(baseline));
            if (Boolean.FALSE.equals(initialized)) {
                String concurrent = redis.opsForValue().get(key);
                if (concurrent != null) {
                    return Integer.parseInt(concurrent);
                }
            }
            return baseline;
        } catch (Exception e) {
            log.warn("读 Redis 计数失败，回落 MySQL | key={} | err={}", key, e.getMessage());
            return baselineOf(dimension, postId);
        }
    }

    /** 批量读帖子点赞数（列表页用，一次 pipeline 取回，避免 N 次往返） */
    public Map<Long, Integer> batchGetPostCount(String dimension, Collection<Long> postIds) {
        Map<Long, Integer> result = new HashMap<>();
        if (postIds == null || postIds.isEmpty()) {
            return result;
        }
        List<String> keys = postIds.stream().map(id -> postCountKey(dimension, id)).toList();
        try {
            List<String> values = redis.opsForValue().multiGet(keys);
            int i = 0;
            for (Long postId : postIds) {
                String v = values == null ? null : values.get(i++);
                if (v != null) {
                    result.put(postId, Integer.parseInt(v));
                }
            }
        } catch (Exception e) {
            log.warn("批量读计数失败 | dimension={} | err={}", dimension, e.getMessage());
        }
        return result;
    }

    /**
     * 读评论点赞数。
     *
     * <p>与帖子计数同构：Redis 优先，miss 则从 {@code t_comment.like_count} 回填。
     */
    public int getCommentLikeCount(Long commentId) {
        String key = commentCountKey(commentId);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return Integer.parseInt(cached);
            }
            var comment = commentMapper.selectById(commentId);
            int baseline = comment == null || comment.getLikeCount() == null
                    ? 0 : comment.getLikeCount();
            // 同 getPostCount：SETNX 回填，避免覆盖并发 INCR 的结果（第三轮审计 C1）
            Boolean initialized = redis.opsForValue().setIfAbsent(key, String.valueOf(baseline));
            if (Boolean.FALSE.equals(initialized)) {
                String concurrent = redis.opsForValue().get(key);
                if (concurrent != null) {
                    return Integer.parseInt(concurrent);
                }
            }
            return baseline;
        } catch (Exception e) {
            log.warn("读评论点赞数失败，回落 MySQL | commentId={} | err={}", commentId, e.getMessage());
            var comment = commentMapper.selectById(commentId);
            return comment == null || comment.getLikeCount() == null ? 0 : comment.getLikeCount();
        }
    }

    /**
     * 批量读评论点赞数 —— 评论列表页用。
     *
     * <p>复用 {@code multiGet} 的一次往返，避免每条评论查一次 Redis。
     */
    public Map<Long, Integer> batchGetCommentLikeCount(Collection<Long> commentIds) {
        Map<Long, Integer> result = new HashMap<>();
        if (commentIds == null || commentIds.isEmpty()) {
            return result;
        }
        List<String> keys = commentIds.stream().map(this::commentCountKey).toList();
        try {
            List<String> values = redis.opsForValue().multiGet(keys);
            int i = 0;
            for (Long commentId : commentIds) {
                String v = values == null ? null : values.get(i++);
                if (v != null) {
                    result.put(commentId, Integer.parseInt(v));
                }
            }
        } catch (Exception e) {
            log.warn("批量读评论点赞数失败 | err={}", e.getMessage());
        }
        return result;
    }

    // ==================== MySQL 基线 ====================

    /**
     * 从 MySQL 取某维度的基线值。
     *
     * <p>注意浏览数走的是 {@code t_post.view_count}，它没有对应的
     * {@code t_user_action} 记录（浏览去重靠 Redis TTL 标记，不落表），
     * 所以它<b>不参与重建</b>—— 重建只针对有点赞/收藏/评论记录表的维度。
     */
    private int baselineOf(String dimension, Long postId) {
        try {
            return switch (dimension) {
                case "like" -> userActionMapper.countByTarget(
                        UserAction.TARGET_POST, postId, UserAction.ACTION_LIKE);
                case "collect" -> userActionMapper.countByTarget(
                        UserAction.TARGET_POST, postId, UserAction.ACTION_COLLECT);
                case "comment" -> commentMapper.countByPostId(postId);
                case "view" -> {
                    var post = postMapper.selectById(postId);
                    yield post == null || post.getViewCount() == null ? 0 : post.getViewCount();
                }
                default -> 0;
            };
        } catch (Exception e) {
            log.warn("取计数基线失败 | dimension={} | postId={} | err={}", dimension, postId, e.getMessage());
            return 0;
        }
    }

    // ==================== 供重建脚本使用 ====================

    /**
     * 直接覆盖写入 Redis 计数（重建用）。
     *
     * <p>与 {@link #addPostCount} 的区别：本方法是 {@code SET} 覆盖，
     * 用于校准；后者是 {@code INCRBY} 增量，用于正常业务。分开是为了让
     * "重建"这个动作在代码上就一眼可辨，不会被误用在业务路径里。
     */
    public void resetPostCount(String dimension, Long postId, int value) {
        try {
            redis.opsForValue().set(postCountKey(dimension, postId), String.valueOf(value));
        } catch (Exception e) {
            log.warn("重置 Redis 计数失败 | dimension={} | postId={} | err={}",
                    dimension, postId, e.getMessage());
        }
    }

    /** 直接覆盖写入评论点赞计数（重建用） */
    public void resetCommentLikeCount(Long commentId, int value) {
        try {
            redis.opsForValue().set(commentCountKey(commentId), String.valueOf(value));
        } catch (Exception e) {
            log.warn("重置评论计数失败 | commentId={} | err={}", commentId, e.getMessage());
        }
    }

    /** 删除计数 key（帖子删除时清理，避免残留占内存） */
    public void evictPostCounts(Long postId) {
        try {
            redis.delete(List.of(
                    postCountKey("like", postId),
                    postCountKey("collect", postId),
                    postCountKey("comment", postId)));
        } catch (Exception e) {
            log.warn("清理计数 key 失败 | postId={} | err={}", postId, e.getMessage());
        }
    }
}
