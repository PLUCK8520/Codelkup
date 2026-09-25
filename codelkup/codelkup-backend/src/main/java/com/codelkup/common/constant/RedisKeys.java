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

    // ===== 缓存 =====

    /** 帖子详情缓存：codelkup:cache:post:{postId} */
    public static final String POST_DETAIL_CACHE = PREFIX + "cache:post:";

    /** 渲染后 HTML 缓存：codelkup:cache:post:html:{postId}（修订 D4：不落库，缓存到 Redis） */
    public static final String POST_HTML_CACHE = PREFIX + "cache:post:html:";

    // ===== 认证 =====

    /** Refresh Token 白名单：codelkup:auth:refresh:{userId} */
    public static final String REFRESH_TOKEN = PREFIX + "auth:refresh:";

    /** JWT 黑名单（登出后失效）：codelkup:auth:blacklist:{jti} */
    public static final String TOKEN_BLACKLIST = PREFIX + "auth:blacklist:";

    /** 登录失败计数：codelkup:auth:login_fail:{ip}:{email} */
    public static final String LOGIN_FAIL_COUNT = PREFIX + "auth:login_fail:";

    // ===== 浏览去重（Sprint 2） =====

    /**
     * 浏览去重标记：codelkup:view:dedup:{postId}:{viewerKey}
     *
     * <p>同一访客在 TTL 窗口内重复刷新详情页，只计一次浏览数。
     * 没有这层去重，浏览数随手刷新就能刷上去，热度分也就失去意义。
     */
    public static final String VIEW_DEDUP = PREFIX + "view:dedup:";


    // ===== 限流 =====

    /** 接口限流：codelkup:rate:{uri}:{userIdOrIp} */
    public static final String RATE_LIMIT = PREFIX + "rate:";

    // ===== Feed 流（V0.2） =====

    /** 用户收件箱：codelkup:feed:inbox:{userId} */
    public static final String FEED_INBOX = PREFIX + "feed:inbox:";

    // ===== 排行榜（V0.2） =====

    /** 热门帖 ZSet：codelkup:rank:post:hot */
    public static final String HOT_POST_RANK = PREFIX + "rank:post:hot";
}
