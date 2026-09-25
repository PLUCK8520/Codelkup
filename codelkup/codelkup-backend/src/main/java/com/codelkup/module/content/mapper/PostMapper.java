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
}
