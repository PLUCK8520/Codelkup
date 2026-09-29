package com.codelkup.common.constant;

/**
 * Redis key 前缀常量。
 *
 * <p>统一在此定义，避免各处硬编码字符串导致 key 冲突或难以排查。
 * 命名规范：{@code codelkup:<模块>:<语义>:<标识>}
 *
 * @author 刘
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 全局前缀 */
    public static final String PREFIX = "codelkup:";

    // ===== 计数（Sprint 3 核心） =====

    /** 帖子点赞数：codelkup:count:post:like:{postId} */
    public static final String POST_LIKE_COUNT = PREFIX + "count:post:like:";

    /** 帖子评论数：codelkup:count:post:comment:{postId} */
    public static final String POST_COMMENT_COUNT = PREFIX + "count:post:comment:";

    /** 帖子浏览数：codelkup:count:post:view:{postId} */
    public static final String POST_VIEW_COUNT = PREFIX + "count:post:view:";

    /** 帖子收藏数：codelkup:count:post:collect:{postId} */
    public static final String POST_COLLECT_COUNT = PREFIX + "count:post:collect:";

    /** 评论点赞数：codelkup:count:comment:like:{commentId} */
    public static final String COMMENT_LIKE_COUNT = PREFIX + "count:comment:like:";

    // ===== 认证 =====

    /** Refresh Token 白名单：codelkup:auth:refresh:{userId} */
    public static final String REFRESH_TOKEN = PREFIX + "auth:refresh:";

    /** JWT 黑名单（登出后失效）：codelkup:auth:blacklist:{jti} */
    public static final String TOKEN_BLACKLIST = PREFIX + "auth:blacklist:";

    /**
     * 用户级"撤销水位"（L-3 修复）：改密时写入该用户当前的 epoch 秒数，
     * 过滤器拒绝一切 issuedAt 早于水位的 accessToken —— 补上"jti 黑名单只管单枚"的缺口。
     */
    public static final String TOKEN_REVOKE_BEFORE = PREFIX + "auth:revoked_before:";

    /** 登录失败计数：codelkup:auth:login_fail:{ip}:{email} */
    public static final String LOGIN_FAIL_COUNT = PREFIX + "auth:login_fail:";

    /**
     * 登录失败计数（纯 IP 维度）：codelkup:auth:login_fail_ip:{ip}
     *
     * <p>与上一个 key 的区别是"不绑定邮箱"：专门用来发现"每次换一个邮箱、
     * 每个邮箱只试 4 次"的分片式口令喷洒 —— 那种打法永远不会触发
     * 「IP + 邮箱」维度的锁定。
     */
    public static final String LOGIN_FAIL_BY_IP = PREFIX + "auth:login_fail_ip:";

    // ===== 浏览去重（Sprint 2） =====

    /**
     * 浏览去重标记：codelkup:view:dedup:{postId}:{viewerKey}
     *
     * <p>同一访客在 TTL 窗口内重复刷新详情页，只计一次浏览数。
     * 没有这层去重，浏览数随手刷新就能刷上去，热度分也就失去意义。
     */
    public static final String VIEW_DEDUP = PREFIX + "view:dedup:";


    // ===== SSE 实时通道（V0.3 M3-1）=====

    /**
     * SSE 一次性 ticket：codelkup:sse:ticket:{ticket}
     *
     * <p>为什么需要它：浏览器 {@code EventSource} 无法携带 Authorization 头，
     * 而把 accessToken 直接放进 query 会落进 nginx / 浏览器历史 / 访问日志
     * （ADR-8）。所以先用登录态换一张 60 秒、只能消费一次的短期票据，
     * 由 {@code GETDEL} 原子地完成"读取即作废"。
     */
    public static final String SSE_TICKET = PREFIX + "sse:ticket:";

    // ===== AI（V0.3 M3-6）=====

    /**
     * AI 问答缓存：codelkup:ai:cache:{问题指纹}
     *
     * <p>只缓存"真正调用过 LLM 的答案"。无 key / 超预算这类降级结果<b>不缓存</b>：
     * 它们本来就没花钱，缓存只会让"后来配上了 key"的部署在 TTL 内一直拿到旧降级文案。
     */
    public static final String AI_ANSWER_CACHE = PREFIX + "ai:cache:";

    /**
     * 长帖 TL;DR 缓存：codelkup:ai:summary:{postId}:{正文哈希}
     *
     * <p>键里带正文哈希而不是"只按 postId"：帖子被编辑后哈希变化，
     * 缓存自然失效 —— 否则会拿旧摘要去解释新正文（比没有摘要更糟）。
     */
    public static final String AI_SUMMARY_CACHE = PREFIX + "ai:summary:";

    /**
     * 全站日预算计数器：codelkup:ai:budget:{yyyy-MM-dd}
     *
     * <p>按自然日切分，过期时间给两天（跨天自然废弃，无需清理任务）。
     * 这是"成本即攻击面"的第三道闸门（前两道：用户限流、问题指纹缓存）。
     */
    public static final String AI_DAILY_BUDGET = PREFIX + "ai:budget:";

    // ===== 限流 =====

    /** 接口限流：codelkup:rate:{uri}:{userIdOrIp} */
    public static final String RATE_LIMIT = PREFIX + "rate:";
}
