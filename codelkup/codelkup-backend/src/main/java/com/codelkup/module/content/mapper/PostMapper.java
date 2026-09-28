package com.codelkup.module.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.codelkup.module.content.entity.Post;
import com.codelkup.module.content.vo.PostListVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 帖子 Mapper。
 *
 * <p>列表查询需要同时取作者昵称/头像与板块名，用 JOIN 一次取回，
 * 避免"查 N 条帖子再循环查作者"的 N+1 问题。
 *
 * @author 刘
 */
@Mapper
public interface PostMapper extends BaseMapper<Post> {

    /**
     * 帖子列表（分页）。
     *
     * <p>排序字段白名单在 Service 层校验后以枚举传入，此处只接受 {@code orderBy}
     * 已受控的片段 —— 不直接拼接用户输入，避免 SQL 注入。
     *
     * <p>JOIN 条件带上 {@code p.deleted = 0}：逻辑删除由 MyBatis-Plus 自动加只对
     * 主表（BaseMapper 生成的方法）生效，自定义 SQL 必须手写。
     */
    @Select("""
            <script>
            SELECT p.id, p.title, p.summary, p.author_id, p.board_id, p.type, p.is_top, p.is_featured,
                   p.view_count, p.like_count, p.comment_count, p.collect_count, p.score, p.last_active,
                   p.create_time,
                   u.nickname AS author_nickname, u.avatar AS author_avatar,
                   b.name AS board_name, b.slug AS board_slug
            FROM t_post p
            LEFT JOIN t_user  u ON u.id = p.author_id
            LEFT JOIN t_board b ON b.id = p.board_id
            WHERE p.deleted = 0 AND p.status = 1
            <if test="boardId != null">        AND p.board_id = #{boardId}        </if>
            <if test="authorId != null">       AND p.author_id = #{authorId}       </if>
            <if test="type != null">           AND p.type = #{type}               </if>
            <if test="solved != null">
                <choose>
                    <when test="solved">AND p.accepted_comment_id IS NOT NULL</when>
                    <otherwise>AND p.accepted_comment_id IS NULL</otherwise>
                </choose>
            </if>
            <if test="tagId != null">
                AND EXISTS (SELECT 1 FROM t_post_tag pt WHERE pt.post_id = p.id AND pt.tag_id = #{tagId})
            </if>
            ORDER BY ${orderBy}
            </script>
            """)
    IPage<PostListVO> selectPostPage(IPage<PostListVO> page,
                                     @Param("boardId") Long boardId,
                                     @Param("authorId") Long authorId,
                                     @Param("tagId") Long tagId,
                                     @Param("type") Integer type,
                                     @Param("solved") Boolean solved,
                                     @Param("orderBy") String orderBy);

    /** 详情：帖子 + 作者 + 板块，一次取回 */
    @Select("""
            SELECT p.*, u.nickname AS author_nickname, u.avatar AS author_avatar,
                   b.name AS board_name, b.slug AS board_slug
            FROM t_post p
            LEFT JOIN t_user  u ON u.id = p.author_id
            LEFT JOIN t_board b ON b.id = p.board_id
            WHERE p.id = #{id} AND p.deleted = 0
            """)
    com.codelkup.module.content.vo.PostDetailVO selectDetailById(@Param("id") Long id);

    /**
     * 浏览数 +1。
     *
     * <p>用原子自增而非"读出+1再写"，避免并发下丢失计数。
     * S3 会在此基础上接入 Redis 计数，此处保留 MySQL 侧的可重建基线。
     */
    @Update("UPDATE t_post SET view_count = view_count + 1 WHERE id = #{id} AND deleted = 0")
    int incrementViewCount(@Param("id") Long id);

    // ==================== 计数基线列（Sprint 3） ====================
    //
    // 这三个方法只由 CountEventListener 异步调用，把 Redis 的实时增量
    // 「记账」到 MySQL。它们是**基线**而非实时值。
    //
    // ⚠️ 全部用 GREATEST(..., 0) 夹住下界：MySQL 列是 INT UNSIGNED 语义上应为非负，
    //    若出现"先取消再点赞"的乱序（事件异步执行，顺序不保证），
    //    DECR 可能把值打到负数。夹到 0 至少保证展示层面不出现负数；
    //    真实偏差由重建脚本发现并修正。

    /** 点赞数基线增减 */
    @Update("UPDATE t_post SET like_count = GREATEST(like_count + #{delta}, 0) "
            + "WHERE id = #{id} AND deleted = 0")
    int addLikeCount(@Param("id") Long id, @Param("delta") int delta);

    /** 收藏数基线增减 */
    @Update("UPDATE t_post SET collect_count = GREATEST(collect_count + #{delta}, 0) "
            + "WHERE id = #{id} AND deleted = 0")
    int addCollectCount(@Param("id") Long id, @Param("delta") int delta);

    /** 评论数基线增减 */
    @Update("UPDATE t_post SET comment_count = GREATEST(comment_count + #{delta}, 0) "
            + "WHERE id = #{id} AND deleted = 0")
    int addCommentCount(@Param("id") Long id, @Param("delta") int delta);

    /**
     * 重建时直接覆盖计数列（不做增量）。
     *
     * <p>与上面的 addXxx 分开是因为语义完全不同：那些是"记账"，
     * 这个是"校准"。混用会出现"重建跑两次、计数翻倍"的灾难。
     */
    @Update("UPDATE t_post SET like_count = #{likeCount}, collect_count = #{collectCount}, "
            + "comment_count = #{commentCount} WHERE id = #{id}")
    int resetCounts(@Param("id") Long id,
                    @Param("likeCount") int likeCount,
                    @Param("collectCount") int collectCount,
                    @Param("commentCount") int commentCount);

    /** 枚举全部未删除帖子 ID —— 供全量重建脚本使用 */
    @Select("SELECT id FROM t_post WHERE deleted = 0")
    List<Long> selectAllIds();

    /**
     * 批量统计"仍可见"的帖子数（第三轮审计 C7 用）。
     *
     * <p>可见性口径与检索完全一致（{@code deleted = 0 AND status = 1}）——
     * AI 答案缓存命中后用它复核引用帖是否还在：缓存里嵌着标题与摘要，
     * 不复核就会让已删除/下架的帖子借缓存（TTL 6 小时）继续"露面"。
     */
    @Select("<script>SELECT COUNT(*) FROM t_post WHERE deleted = 0 AND status = 1 AND id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int countVisibleByIds(@Param("ids") List<Long> ids);

    // ==================== 提问闭环（V0.3 / M2-14） ====================

    /**
     * 某用户的提问统计：提问数 + 已采纳数。
     *
     * <p>一条 SQL 出两列而不是两次 COUNT：两次查询在"两次之间恰好发生采纳"时
     * 会给出自相矛盾的快照（已采纳 &gt; 提问），而一个 SUM 里不可能出现这种偏差。
     *
     * <p>{@code COALESCE} 兜住"一条提问都没有"的情形 —— 那时 {@code SUM} 返回
     * NULL 而不是 0，直接取 intValue 会 NPE。
     *
     * <p>命中 {@code idx_author(author_id)}（V2 已建），按作者取自己的帖子是索引扫描。
     */
    @Select("""
            SELECT COUNT(*) AS asked,
                   COALESCE(SUM(p.accepted_comment_id IS NOT NULL), 0) AS accepted
            FROM t_post p
            WHERE p.deleted = 0 AND p.status = 1 AND p.type = 1 AND p.author_id = #{authorId}
            """)
    Map<String, Object> countQuestionStats(@Param("authorId") Long authorId);

    /**
     * 全站提问统计：总数 + 已解决 + 待解决（对应 /api/stats）。
     *
     * <p>与按作者的 {@link #countQuestionStats(Long)} 同一口径
     * （{@code deleted = 0 AND status = 1 AND type = 1}，已解决 = accepted_comment_id 非空）。
     *
     * <p>为什么一条 SQL 出三列：三个数必须满足 {@code total = solved + unsolved}。
     * 若由三次查询拼出，跨查询时刻的采纳/发帖会让等式不成立（页面渲染出
     * 「共 3 个提问 · 待解决 3 · 已解决 10」这类自相矛盾的数字）。
     *
     * <p>为什么不用"列表接口 + size=1 探针"：那是 3 次 HTTP 请求、3 个时刻的快照，
     * 且"总数"会随调用者的筛选条件变化 —— 本查询恒定口径，与筛选无关。
     */
    @Select("""
            SELECT COUNT(*) AS total,
                   COALESCE(SUM(p.accepted_comment_id IS NOT NULL), 0) AS solved,
                   COALESCE(SUM(p.accepted_comment_id IS NULL), 0) AS unsolved
            FROM t_post p
            WHERE p.deleted = 0 AND p.status = 1 AND p.type = 1
            """)
    Map<String, Object> countAllQuestionStats();

    /**
     * 采纳位的条件更新（CAS）—— 并发改判时只有一个请求能成功。
     *
     * <p>原实现是"读出 accepted_comment_id → 覆盖写"的纯读改写：两个请求同时改判
     * 会双双成功，旧采纳者被收回声望、新采纳者被 +15，各自的结算都跑一遍。
     * 条件更新把"比对 + 写入"压进一条 SQL（由 InnoDB 行锁串行化），
     * 影响行数为 0 即说明期间已被别人改过，调用方据此放弃本次操作。
     *
     * <p>⚠️ <b>拆成"空 / 非空"两条普通 SQL，而不是用 NULL 安全等号 {@code <=>}</b>：
     * {@code MybatisPlusConfig} 注册的 {@code BlockAttackInnerInterceptor} 会用
     * JSQLParser 解析 UPDATE 语句，而它不认识 {@code <=>} —— 实测直接抛
     * {@code MybatisPlusException: Failed to process}（接口返回 50001）。
     * 两条语句与 {@code <=>} 语义完全等价，且解析器认。
     */
    @Update("UPDATE t_post SET accepted_comment_id = #{newId} "
            + "WHERE id = #{postId} AND accepted_comment_id IS NULL AND deleted = 0")
    int casAcceptWhenEmpty(@Param("postId") Long postId, @Param("newId") Long newId);

    /** 采纳位的条件更新（CAS）—— 改判：旧采纳位非空且仍等于读到的那个值 */
    @Update("UPDATE t_post SET accepted_comment_id = #{newId} "
            + "WHERE id = #{postId} AND accepted_comment_id = #{oldId} AND deleted = 0")
    int casAcceptWhenSame(@Param("postId") Long postId, @Param("newId") Long newId,
                          @Param("oldId") Long oldId);

    /**
     * 清空采纳位 —— 被采纳的评论被删除时调用。
     *
     * <p>不清的话帖子会一辈子挂着"已解决"：列表按 {@code accepted_comment_id IS NOT NULL}
     * 判定解决状态，而详情里的采纳标记却指向一条已不存在的评论，楼主也再没有入口改判。
     * 同样用条件更新：仅当采纳位仍是这条评论时才清，避免把并发产生的新采纳误清。
     */
    @Update("UPDATE t_post SET accepted_comment_id = NULL "
            + "WHERE id = #{postId} AND accepted_comment_id = #{commentId} AND deleted = 0")
    int clearAcceptedComment(@Param("postId") Long postId, @Param("commentId") Long commentId);

    // ==================== 热度分（V0.3） ====================

    /**
     * 刷新"最后活跃时间"（评论 / 回复成功时调用）。
     *
     * <p>它不是展示字段而是排序字段：{@code sort=active} 按它排、热度分的衰减也按它算。
     * 此前只有发帖会写它，于是"活跃排序"实际退化成"发布时间排序" ——
     * 一个半年前发的高热帖不会因为今天的讨论重新浮起来，而那正是这个字段的全部意义。
     */
    @Update("UPDATE t_post SET last_active = NOW() WHERE id = #{postId} AND deleted = 0")
    int touchLastActive(@Param("postId") Long postId);

    /**
     * 全量重算热度分 —— <b>一条 SQL</b>，不做逐帖 update。
     *
     * <p>为什么放在 SQL 里算而不是应用层循环：1 万行量级下一条 UPDATE 不到 1 秒，
     * 而"查出全部帖子 → 逐帖算 → 逐帖写"要几十次往返，且中途失败会留下半新半旧的分。
     *
     * <h2>公式</h2>
     * <pre>
     * score = (1 + 2*赞 + 3*藏 + 4*评论) * e^(-λ * 距最后活跃小时数)
     * </pre>
     * 三处设计意图：
     * <ol>
     *   <li><b>权重递增（评论 &gt; 收藏 &gt; 点赞）</b>：写评论的成本最高，对社区的贡献最大；
     *       点赞最廉价，权重最低。常数 1 保证"零互动帖"也有基础分（否则全为 0 时排序退化）。</li>
     *   <li><b>时间衰减</b>：热度是"当下有人讨论"而不是"历史累计"，
     *       没有衰减的话老帖会永久霸榜（这正是排行榜类功能最常见的退化形态）。</li>
     *   <li><b>COALESCE(last_active, create_time)</b>：老数据可能没有 last_active，
     *       缺了会让整个表达式变成 NULL（score 变 NULL，排序时排到最前或最后都很难解释）。</li>
     * </ol>
     *
     * <p>⚠️ 权重与 λ 来自配置（{@code codelkup.score.*}），仍然是 {@code #{}} 参数化绑定 ——
     * 即便将来把它做成"管理端可调"，也不能退化成字符串拼接。
     */
    @Update("""
            UPDATE t_post
               SET score = (1 + #{likeWeight} * like_count
                              + #{collectWeight} * collect_count
                              + #{commentWeight} * comment_count)
                           * EXP(-#{decayLambda} * TIMESTAMPDIFF(HOUR, COALESCE(last_active, create_time), NOW()))
             WHERE deleted = 0 AND status = 1
            """)
    int recalcScores(@Param("likeWeight") int likeWeight,
                     @Param("collectWeight") int collectWeight,
                     @Param("commentWeight") int commentWeight,
                     @Param("decayLambda") double decayLambda);

    /** 批量取多篇帖子的标签（避免逐篇查询） */
    @Select("<script>" +
            "SELECT pt.post_id, t.id AS tag_id, t.name AS tag_name " +
            "FROM t_post_tag pt JOIN t_tag t ON t.id = pt.tag_id " +
            "WHERE pt.post_id IN " +
            "<foreach collection='postIds' item='pid' open='(' separator=',' close=')'>#{pid}</foreach>" +
            "</script>")
    List<java.util.Map<String, Object>> selectTagsByPostIds(@Param("postIds") List<Long> postIds);

    /** 某篇帖子的标签列表 */
    @Select("SELECT t.* FROM t_tag t JOIN t_post_tag pt ON pt.tag_id = t.id " +
            "WHERE pt.post_id = #{postId} ORDER BY t.id ASC")
    List<com.codelkup.module.content.entity.Tag> selectTagsOfPost(@Param("postId") Long postId);

    // ==================== 全文检索（Sprint 4） ====================

    /**
     * 全文检索（分页）。
     *
     * <h2>为什么有 useLike 这个开关</h2>
     * 本机 {@code ngram_token_size = 2}（§8.5 已实测），即索引里的最小单位是<b>双字</b>。
     * 因此单字查询（如"帖"）在 {@code MATCH ... AGAINST} 下<b>必然返回空</b> ——
     * 不是 bug，是分词粒度的直接后果。实测：{@code AGAINST('帖')} → 0 条，
     * 而 {@code title LIKE '%帖%'} → 16 条。
     *
     * <p>于是短查询降级为 LIKE 扫描。这在小数据量下完全可接受，
     * 且比"搜不到任何东西还不给提示"体验好得多。降级仅在 Service 判定
     * 关键词长度 &lt; 2 时启用，正常查询仍走索引。
     *
     * <h2>SELECT 里为什么多算一次 MATCH</h2>
     * {@code relevance} 是排序依据（搜索的意义就在于"最相关的排前面"）。
     * 它与 WHERE 里的 MATCH 是同一个表达式，MySQL 会复用其计算结果，不会重复扫描。
     *
     * <h2>为什么 ESCAPE 用 '!' 而不是反斜杠</h2>
     * 反斜杠在 SQL 字符串字面量里本身就是转义符，写成 {@code ESCAPE '\'} 需要 4 层
     * 转义（Java 文本块 → Java 字符串 → SQL 解析 → LIKE），极易写错且难以 review。
     * 换成一个在正文里几乎不出现的 '!' 作为转义符，可读性提升是实打实的。
     */
    @Select("""
            <script>
            SELECT p.id, p.title, p.summary, p.author_id, p.board_id, p.type, p.is_top, p.is_featured,
                   p.view_count, p.like_count, p.comment_count, p.collect_count, p.score, p.last_active,
                   p.create_time, p.content,
                   u.nickname AS author_nickname, u.avatar AS author_avatar,
                   b.name AS board_name, b.slug AS board_slug,
                   <choose>
                       <when test="useLike">0</when>
                       <otherwise>MATCH(p.title, p.content) AGAINST(#{query} IN BOOLEAN MODE)</otherwise>
                   </choose> AS relevance
            FROM t_post p
            LEFT JOIN t_user  u ON u.id = p.author_id
            LEFT JOIN t_board b ON b.id = p.board_id
            WHERE p.deleted = 0 AND p.status = 1
            <choose>
                <when test="useLike">
                    AND (p.title   LIKE CONCAT('%', #{likeQuery}, '%') ESCAPE '!'
                      OR p.content LIKE CONCAT('%', #{likeQuery}, '%') ESCAPE '!')
                </when>
                <otherwise>
                    AND MATCH(p.title, p.content) AGAINST(#{query} IN BOOLEAN MODE)
                </otherwise>
            </choose>
            <if test="boardId != null">  AND p.board_id = #{boardId}  </if>
            <if test="authorId != null"> AND p.author_id = #{authorId} </if>
            <if test="tagId != null">
                AND EXISTS (SELECT 1 FROM t_post_tag pt WHERE pt.post_id = p.id AND pt.tag_id = #{tagId})
            </if>
            ORDER BY ${orderBy}
            </script>
            """)
    IPage<com.codelkup.module.content.vo.PostSearchRowVO> searchPostPage(
            IPage<com.codelkup.module.content.vo.PostSearchRowVO> page,
            @Param("query") String query,
            @Param("likeQuery") String likeQuery,
            @Param("useLike") boolean useLike,
            @Param("boardId") Long boardId,
            @Param("tagId") Long tagId,
            @Param("authorId") Long authorId,
            @Param("orderBy") String orderBy);

    /**
     * 标题前缀探测（M3-5 相似问题提示用）。
     *
     * <h2>为什么需要一条独立的取数路径</h2>
     * 通用检索的 {@code MATCH(title, content) AGAINST(... IN BOOLEAN MODE)} 是
     * <b>OR 语义</b>（不带 {@code +} 前缀时，命中任一 token 即可）。遇到
     * {@code java} 这类常见词会把候选池整个打满 —— 实测（2026-09-26）：
     * 查 {@code java virtual thread} 时，一篇标题就叫这个的<b>新帖没能进前 20</b>，
     * 于是一堆无关帖子占满了提示位。"标题最像"是提示场景里最有价值的一类信号，
     * 必须有独立路径保证它进得来，而不是寄希望于相关性排序把它排进候选池。
     *
     * <p>{@code ESCAPE '!'} 与检索同一口径：转义由调用方（SearchService）
     * 统一处理，这里不重复实现一套 LIKE 转义规则。
     *
     * <p>只取提示要用到的几列（id / title / summary / board），
     * 不 SELECT 正文 —— 前缀命中的预览直接用 summary，不需要正文参与。
     *
     * @param escapedPrefix 已转义的前缀（非空，由 Service 保证）
     */
    @Select("""
            SELECT p.id, p.title, p.summary, p.author_id, p.board_id,
                   b.name AS board_name, b.slug AS board_slug
            FROM t_post p
            LEFT JOIN t_board b ON b.id = p.board_id
            WHERE p.deleted = 0 AND p.status = 1
              AND p.title LIKE CONCAT(#{escapedPrefix}, '%') ESCAPE '!'
            ORDER BY p.create_time DESC
            LIMIT #{limit}
            """)
    List<com.codelkup.module.content.vo.PostSearchRowVO> selectByTitlePrefix(
            @Param("escapedPrefix") String escapedPrefix,
            @Param("limit") int limit);

    // ==================== 关注流（Sprint 4） ====================

    /**
     * 关注流 —— 拉模式（修订 D3）。
     *
     * <p>由调用方先查出"我关注的人 ID 列表"，再作为 {@code authorIds} 传入。
     * 没有写成子查询 {@code author_id IN (SELECT followee_id FROM t_user_follow ...)}
     * 的原因：Service 需要提前知道"关注列表是否为空"以决定是否直接返回空结果 ——
     * {@code IN ()} 空列表是 SQL 语法错误，而 {@code IN (SELECT ...)} 返回空集时
     * 语法合法但会多一次无效查询。把判断放在 Java 侧，语义更明确。
     *
     * <p>⚠️ 调用方必须保证 {@code authorIds} <b>非空</b>。
     *
     * <p>排序固定为时间倒序，不做白名单：关注流的语义就是"我关注的人最近发了什么"，
     * 按热度或相关性排都是错的。
     */
    @Select("""
            <script>
            SELECT p.id, p.title, p.summary, p.author_id, p.board_id, p.type, p.is_top, p.is_featured,
                   p.view_count, p.like_count, p.comment_count, p.collect_count, p.score, p.last_active,
                   p.create_time,
                   u.nickname AS author_nickname, u.avatar AS author_avatar,
                   b.name AS board_name, b.slug AS board_slug
            FROM t_post p
            LEFT JOIN t_user  u ON u.id = p.author_id
            LEFT JOIN t_board b ON b.id = p.board_id
            WHERE p.deleted = 0 AND p.status = 1
              AND p.author_id IN
              <foreach collection='authorIds' item='aid' open='(' separator=',' close=')'>#{aid}</foreach>
            ORDER BY p.create_time DESC, p.id DESC
            </script>
            """)
    IPage<PostListVO> selectFeedPage(IPage<PostListVO> page, @Param("authorIds") List<Long> authorIds);

    // ==================== 我的收藏（V0.3 / M2-8） ====================

    /**
     * 我的收藏（分页）—— 从 {@code t_user_action} 出发取帖子。
     *
     * <h2>为什么是一条 JOIN，而不是"先查 ID 再查帖子"</h2>
     * 两段式查询要自己处理三件事：把 ID 列表切成当前页、按 ID 二次查询后再把顺序
     * 还原回收藏顺序、总数还要另发一条 COUNT。JOIN 让分页/排序/总数都在数据库
     * 一次完成，也不必在 Java 侧维护"顺序数组 ↔ VO 列表"的一致性。
     *
     * <h2>排序依据是"收藏时间"而非"发布时间"</h2>
     * 用户要找的是"我最近收的那篇"，不是"最近有人发的那篇"。
     * 次级键 {@code ua.id} 用于同一秒内收藏两条时的稳定顺序 ——
     * {@code create_time} 是秒精度，缺了它 MySQL 对并列行的返回顺序不做保证，
     * 翻页会出现重复或漏项。
     *
     * <h2>为什么过滤 deleted / status</h2>
     * 收藏列表给的是"能点开的帖子"。已被删除或下架的帖子留在列表里就是死链，
     * 比少一条更糟。（代价是 total 会随之变化，这符合"列表里只有可见内容"的直觉。）
     *
     * <p>{@code target_type = 1 AND action_type = 2} 为字面量，与
     * {@code UserAction.TARGET_POST / ACTION_COLLECT} 同义 —— 注解里的 SQL
     * 不引入 Java 常量拼接，便于把整条语句复制到客户端里直接 EXPLAIN。
     */
    @Select("""
            SELECT p.id, p.title, p.summary, p.author_id, p.board_id, p.type, p.is_top, p.is_featured,
                   p.view_count, p.like_count, p.comment_count, p.collect_count, p.score, p.last_active,
                   p.create_time,
                   u.nickname AS author_nickname, u.avatar AS author_avatar,
                   b.name AS board_name, b.slug AS board_slug
            FROM t_user_action ua
            JOIN t_post  p ON p.id = ua.target_id AND p.deleted = 0 AND p.status = 1
            LEFT JOIN t_user  u ON u.id = p.author_id
            LEFT JOIN t_board b ON b.id = p.board_id
            WHERE ua.user_id = #{userId} AND ua.target_type = 1 AND ua.action_type = 2
            ORDER BY ua.create_time DESC, ua.id DESC
            """)
    IPage<PostListVO> selectCollectedPostPage(IPage<PostListVO> page, @Param("userId") Long userId);
}
