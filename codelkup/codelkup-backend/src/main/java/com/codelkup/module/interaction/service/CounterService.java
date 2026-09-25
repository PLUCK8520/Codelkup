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
     * <p>但如果 key 不存在，{@code DECR} 会从 0 减到 -1 —— 这是错的，
     * 因为真值应该是"MySQL 基线 - 1"而不是 "-1"。所以先用 {@code SETNX}
     * 把 key 初始化成当前基线值，再做增量。用 Lua 保证这两步原子。
     */
    public void addPostCount(String dimension, Long postId, long delta) {
        String key = postCountKey(dimension, postId);
        try {
            redis.execute(INCR_WITH_BASELINE_SCRIPT,
                    List.of(key),
                    String.valueOf(delta),
                    String.valueOf(baselineOf(dimension, postId)));
        } catch (Exception e) {
            // Redis 不可用：静默降级，MySQL 基线仍会被更新
            log.warn("Redis 计数失败，降级为仅更新 MySQL | key={} | delta={} | err={}",
                    key, delta, e.getMessage());
        }
    }

    /** 评论点赞数增减（key 初始化基线从 t_comment.like_count 取） */
    public void addCommentLikeCount(Long commentId, long delta) {
        String key = commentCountKey(commentId);
        try {
            Integer baseline = commentMapper.selectById(commentId) == null
                    ? 0 : commentMapper.selectById(commentId).getLikeCount();
            redis.execute(INCR_WITH_BASELINE_SCRIPT,
                    List.of(key),
                    String.valueOf(delta),
                    String.valueOf(baseline == null ? 0 : baseline));
        } catch (Exception e) {
            log.warn("评论点赞计数失败 | commentId={} | err={}", commentId, e.getMessage());
        }
    }

    /**
     * 计数增减脚本：key 不存在时先以基线初始化，再执行增量。
     *
     * <p>原子性用 Lua 保证 —— 若分成 SETNX + INCR 两条命令，
     * 并发下会出现"两个请求都 SETNX 失败、都去 INCR"以外的更糟情况：
     * A 刚 SETNX 成功、B 的 INCR 就到了，B 读到的是基线但其实是 A 初始化前的状态。
     * 放进一段 Lua 里由 Redis 单线程执行，这些交错都不会发生。
     *
     * <p>{@code ARGV[1]}=增量，{@code ARGV[2]}=基线值。
     */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long>
            INCR_WITH_BASELINE_SCRIPT = new org.springframework.data.redis.core.script.DefaultRedisScript<>(
            """
            if redis.call('EXISTS', KEYS[1]) == 0 then
                redis.call('SET', KEYS[1], ARGV[2])
            end
            return redis.call('INCRBY', KEYS[1], ARGV[1])
            """, Long.class);

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
            redis.opsForValue().set(key, String.valueOf(baseline));
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
            redis.opsForValue().set(key, String.valueOf(baseline));
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
