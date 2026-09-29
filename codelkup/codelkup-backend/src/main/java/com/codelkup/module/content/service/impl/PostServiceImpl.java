package com.codelkup.module.content.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codelkup.common.constant.RedisKeys;
import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.common.result.PageResult;
import com.codelkup.module.content.dto.PostCreateRequest;
import com.codelkup.module.content.dto.PostQueryRequest;
import com.codelkup.module.content.dto.PostUpdateRequest;
import com.codelkup.module.content.entity.Post;
import com.codelkup.module.content.entity.PostTag;
import com.codelkup.module.content.entity.Tag;
import com.codelkup.module.content.mapper.PostMapper;
import com.codelkup.module.content.mapper.PostTagMapper;
import com.codelkup.module.content.service.BoardService;
import com.codelkup.module.content.service.PostListAssembler;
import com.codelkup.module.content.service.PostService;
import com.codelkup.module.content.service.TagService;
import com.codelkup.module.content.util.MarkdownUtils;
import com.codelkup.module.content.vo.PostDetailVO;
import com.codelkup.module.content.vo.PostListVO;
import com.codelkup.module.content.vo.TagVO;
import com.codelkup.module.content.vo.UserBriefVO;
import com.codelkup.module.interaction.entity.UserAction;
import com.codelkup.module.interaction.service.CommentService;
import com.codelkup.module.interaction.service.CounterService;
import com.codelkup.module.interaction.service.UserActionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 帖子服务实现。
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    /** 浏览去重窗口：同一访客 30 分钟内重复打开只计一次 */
    private static final long VIEW_DEDUP_TTL_MINUTES = 30;

    /**
     * 排序字段白名单。
     *
     * <p>{@code orderBy} 最终会以 {@code ${}} 形式拼进 SQL，所以<b>绝不能</b>直接
     * 使用请求参数。改为查表映射：请求里的 sort 字符串 → 固定 SQL 片段，
     * 命中不了就回落 latest。这是防 SQL 注入的关键一道。
     *
     * <p>片段末尾一律补 {@code p.id DESC} 作为兜底键：{@code create_time} 是秒精度、
     * {@code score} 是浮点，并列几乎必然出现；缺了它 MySQL 对并列行的顺序不作保证，
     * 翻页会重复或漏项（与收藏列表那边同一个坑，见 {@code selectCollectedPostPage}）。
     */
    private static final Map<String, String> ORDER_BY_WHITELIST = Map.of(
            "latest", "p.is_top DESC, p.create_time DESC, p.id DESC",
            "hot", "p.is_top DESC, p.score DESC, p.create_time DESC, p.id DESC",
            "active", "p.is_top DESC, p.last_active DESC, p.id DESC"
    );

    private final PostMapper postMapper;
    private final PostTagMapper postTagMapper;
    private final BoardService boardService;
    private final TagService tagService;
    private final StringRedisTemplate stringRedisTemplate;
    private final CounterService counterService;
    private final UserActionService userActionService;
    private final CommentService commentService;
    private final PostListAssembler assembler;

    // ==================== 列表 ====================

    @Override
    public PageResult<PostListVO> list(PostQueryRequest query) {
        return list(query, null);
    }

    @Override
    public PageResult<PostListVO> list(PostQueryRequest query, Long viewerId) {
        String orderBy = ORDER_BY_WHITELIST.getOrDefault(
                query.getSort() == null ? "latest" : query.getSort().toLowerCase(),
                ORDER_BY_WHITELIST.get("latest"));

        Page<PostListVO> page = new Page<>(query.getPage(), query.getSize());

        // "已解决/待解决"只对提问帖有意义：非提问帖传了 solved 一律忽略（当没传），
        // 否则会返回一个"看起来没有数据"的空列表，把调用方引向错误结论。
        Boolean solved = (query.getType() != null && query.getType() == Post.TYPE_QUESTION)
                ? query.getSolved()
                : null;

        IPage<PostListVO> result = postMapper.selectPostPage(
                page, query.getBoardId(), query.getAuthorId(),
                query.getTagId(), query.getType(), solved, orderBy);

        List<PostListVO> records = result.getRecords();

        // 统一装配：补标签 + 覆盖实时计数 + 标记我的互动状态。
        // 抽到 PostListAssembler 是因为搜索接口也要做同样三件事 ——
        // 两处各写一遍必然出现"列表有 tags、搜索没有"这类字段分叉。
        assembler.assemble(records, viewerId);

        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), records);
    }

    // ==================== 我的收藏 ====================

    @Override
    public PageResult<PostListVO> listCollected(Long userId, int page, int size) {
        Page<PostListVO> p = new Page<>(page, size);
        IPage<PostListVO> result = postMapper.selectCollectedPostPage(p, userId);

        // 与列表页/搜索页/关注流共用同一套装配（标签 + 实时计数 + 我的点赞收藏态）。
        // 这里 viewerId 就是收藏的归属者：每条都会标成 collected=true，
        // 前端据此在卡片上显示"已收藏"，也方便用户在列表里直接取消收藏。
        assembler.assemble(result.getRecords(), userId);

        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    // ==================== 详情 ====================

    @Override
    public PostDetailVO detail(Long id, Long viewerId, String viewerKey) {
        PostDetailVO vo = postMapper.selectDetailById(id);
        if (vo == null) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        // 已删/已封禁的帖子对普通访客不可见（作者本人与管理员可查，此处简化：一律 404）
        if (vo.getStatus() != null && vo.getStatus() != Post.STATUS_PUBLISHED) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }

        // ---- 浏览计数（带去重）----
        // 去重标记在 Redis，过期自动清理，无需定时任务
        // 注意：计数失败不能影响详情返回，所以整段 try-catch 兜住
        boolean shouldCount = tryMarkViewed(id, viewerKey);
        if (shouldCount) {
            try {
                postMapper.incrementViewCount(id);
                // 让返回给前端的数字与库内一致
                vo.setViewCount(vo.getViewCount() == null ? 1 : vo.getViewCount() + 1);
            } catch (Exception e) {
                log.warn("浏览计数失败，不影响详情返回 | postId={} | err={}", id, e.getMessage());
            }
        }

        // ---- 组装 ----
        assemble(vo, viewerId);

        return vo;
    }

    /**
     * 读库 + 完整组装（author / tags / canEdit）并按操作者计算 canEdit。
     *
     * <p>发帖与编辑都复用此方法返回详情。<b>不能</b>直接返回
     * {@code postMapper.selectDetailById()} —— 那只是个裸的 SQL 映射结果，
     * author / tags 都还是 null（踩过一次：创建接口返回的 tags 为 null）。
     */
    private PostDetailVO loadAssembled(Long id, Long operatorId) {
        PostDetailVO vo = postMapper.selectDetailById(id);
        if (vo == null) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        assemble(vo, operatorId);
        return vo;
    }

    /** 统一组装：把 JOIN 出来的扁平字段整理成嵌套结构 */
    private void assemble(PostDetailVO vo, Long viewerId) {
        vo.setAuthor(buildAuthor(vo));
        vo.setTags(toTagVOs(postMapper.selectTagsOfPost(vo.getId())));
        vo.setCanEdit(canEdit(vo.getAuthorId(), viewerId));

        // ---- Sprint 3：计数取 Redis 实时值 ----
        // MySQL 里的是"基线"，可能落后于 Redis 的增量（异步落库有延迟）。
        // 用户刚点完赞就刷新页面，必须看到 +1 后的数字。
        vo.setLikeCount(counterService.getPostCount("like", vo.getId()));
        vo.setCollectCount(counterService.getPostCount("collect", vo.getId()));
        vo.setCommentCount(counterService.getPostCount("comment", vo.getId()));

        // ---- Sprint 3：当前用户的互动状态 ----
        // 未登录时保持 null（而非 false）—— 前端据此区分"未登录"与"已登录但没点赞"，
        // 前者点击应该跳登录页，后者才是真正的点赞动作。
        if (viewerId != null) {
            vo.setLiked(userActionService.hasLiked(
                    viewerId, UserAction.TARGET_POST, vo.getId()));
            vo.setCollected(userActionService.hasCollected(viewerId, vo.getId()));
        }
    }

    /**
     * 浏览去重：首次访问返回 true（应计数），窗口内重复返回 false。
     *
     * <p>用 {@code SETNX + EXPIRE} 的原子语义：{@code setIfAbsent} 成功说明是新访客。
     * Redis 不可用时<b>返回 false（不计数）</b>而不是抛异常 ——
     * 宁可少计一次浏览，也不能让详情页打不开。
     */
    private boolean tryMarkViewed(Long postId, String viewerKey) {
        if (viewerKey == null) {
            return false;
        }
        try {
            String key = RedisKeys.VIEW_DEDUP + postId + ":" + viewerKey;
            Boolean firstTime = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, "1", VIEW_DEDUP_TTL_MINUTES, TimeUnit.MINUTES);
            return Boolean.TRUE.equals(firstTime);
        } catch (Exception e) {
            log.warn("浏览去重标记失败，本次不计数 | postId={} | err={}", postId, e.getMessage());
            return false;
        }
    }

    // ==================== 发帖 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PostDetailVO create(PostCreateRequest request, Long authorId) {
        boardService.requireEnabledBoard(request.getBoardId());

        Post post = new Post();
        post.setTitle(request.getTitle().trim());
        post.setContent(request.getContent());
        // 摘要由后端生成，不接受前端传入 —— 否则前端可以塞任意内容进列表页
        post.setSummary(MarkdownUtils.extractSummary(request.getContent()));
        post.setAuthorId(authorId);
        post.setBoardId(request.getBoardId());
        post.setType(normalizeType(request.getType()));
        // 直接发布：个人项目不做先审后发（待审状态保留给后续敏感词场景）
        post.setStatus(Post.STATUS_PUBLISHED);
        post.setIsTop(0);
        post.setIsFeatured(0);
        post.setViewCount(0);
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setCollectCount(0);
        post.setScore(0.0);
        post.setLastActive(LocalDateTime.now());

        postMapper.insert(post);

        bindTags(post.getId(), request.getTags());
        boardService.addPostCount(request.getBoardId(), 1);

        log.info("发帖成功 | postId={} | authorId={} | boardId={}", post.getId(), authorId, request.getBoardId());

        return loadAssembled(post.getId(), authorId);
    }

    // ==================== 编辑 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PostDetailVO update(Long id, PostUpdateRequest request, Long operatorId, boolean isAdmin) {
        Post existing = requirePost(id);

        if (!canEdit(existing.getAuthorId(), operatorId, isAdmin)) {
            throw new BusinessException(ErrorCode.NOT_RESOURCE_OWNER);
        }

        Post update = new Post();
        update.setId(id);

        // 只 set 非 null 字段 —— MyBatis-Plus 的 updateById 默认忽略 null，
        // 天然实现"部分更新"语义，不需要手写动态 SQL
        if (request.getTitle() != null) {
            update.setTitle(request.getTitle().trim());
        }
        if (request.getContent() != null) {
            update.setContent(request.getContent());
            update.setSummary(MarkdownUtils.extractSummary(request.getContent()));
        }
        if (request.getType() != null) {
            update.setType(normalizeType(request.getType()));
        }

        // 换板块：需要同时维护两个板块的计数
        Long oldBoardId = existing.getBoardId();
        if (request.getBoardId() != null && !request.getBoardId().equals(oldBoardId)) {
            boardService.requireEnabledBoard(request.getBoardId());
            update.setBoardId(request.getBoardId());
        }

        postMapper.updateById(update);

        if (update.getBoardId() != null) {
            boardService.addPostCount(oldBoardId, -1);
            boardService.addPostCount(update.getBoardId(), 1);
        }

        // 提问帖 → 普通帖：采纳位不再有意义，必须一并清掉（2026-09-29 纠错轮）。
        // 不清的后果：详情/评论仍显示"已采纳"，且改回提问后能再采纳一次 ——
        // 来回切换类型成了重复结算声望的刷分路径（与"来回改判"同一漏洞面）。
        // 条件更新由 CommentService 内部完成：并发改判产生的新采纳不会被误清。
        if (existing.getType() != null && existing.getType() == Post.TYPE_QUESTION
                && update.getType() != null && update.getType() == Post.TYPE_NORMAL
                && commentService.revokeAcceptance(id, existing.getAcceptedCommentId(),
                        existing.getAuthorId())) {
            log.info("帖子改为普通帖，已清空采纳位 | postId={} | acceptedCommentId={}",
                    id, existing.getAcceptedCommentId());
        }

        // 标签整体替换（先全删再重插）
        if (request.getTags() != null) {
            replaceTags(id, request.getTags());
        }

        log.info("编辑帖子 | postId={} | operatorId={}", id, operatorId);

        return loadAssembled(id, operatorId);
    }

    // ==================== 删除 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long operatorId, boolean isAdmin) {
        Post existing = requirePost(id);

        if (!canEdit(existing.getAuthorId(), operatorId, isAdmin)) {
            throw new BusinessException(ErrorCode.NOT_RESOURCE_OWNER);
        }

        // 逻辑删除，同时把状态标成"已删"——
        // 两个标记都要：deleted 用于全局查询过滤，status 用于列表 SQL 显式过滤
        Post update = new Post();
        update.setId(id);
        update.setStatus(isAdmin && !existing.getAuthorId().equals(operatorId)
                ? Post.STATUS_BANNED : Post.STATUS_DELETED);
        postMapper.updateById(update);
        // ⚠️ 用**影响行数**判定"这次删除是不是真的发生了"：并发双删时只有一次
        //    deleteById 返回 1，另一次返回 0 —— 若不看结果直接往下走，
        //    板块/标签计数会被扣两次（帖子却只少了一篇）。
        if (postMapper.deleteById(id) == 0) {
            log.info("帖子已被并发删除，跳过计数扣减 | postId={}", id);
            return;
        }

        boardService.addPostCount(existing.getBoardId(), -1);

        // 标签引用数同步减少
        for (Tag tag : postMapper.selectTagsOfPost(id)) {
            tagService.addUseCount(tag.getId(), -1);
        }
        // 关联明细一并物理删除（2026-09-29 纠错轮）：t_post_tag 没有 deleted 列，
        // 帖子逻辑删除后这些行仍留在库里 —— 标签详情页的 EXISTS 关联查询、以及
        // "按关联重算标签使用数"这类维护脚本都会继续把已删帖算进去，使用数被推歪。
        // （与编辑时的"整体替换"同一处理：关联是纯明细，无审计价值。）
        postTagMapper.deleteByPostId(id);

        // 清理 Redis 计数 key，避免残留占内存
        // （注意：不删 t_user_action 明细 —— 计数重建脚本仍需要它来算基线，
        //   且把明细留着可以支持"恢复已删帖子"这类未来需求。
        //   数据量是本项目量级的千级，不值得为省这点空间牺牲可重建性。）
        counterService.evictPostCounts(id);

        log.info("删除帖子 | postId={} | operatorId={} | isAdmin={}", id, operatorId, isAdmin);
    }

    // ==================== 置顶 / 加精 ====================

    @Override
    public void setFlags(Long id, Boolean top, Boolean featured) {
        requirePost(id);

        Post update = new Post();
        update.setId(id);
        // 传 null 表示"这一维度不动"，只改用户显式指定的部分
        if (top != null) {
            update.setIsTop(top ? 1 : 0);
        }
        if (featured != null) {
            update.setIsFeatured(featured ? 1 : 0);
        }
        if (update.getIsTop() == null && update.getIsFeatured() == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "请至少指定 top 或 featured 其中一项");
        }
        postMapper.updateById(update);
        log.info("设置帖子标记 | postId={} | top={} | featured={}", id, top, featured);
    }

    // ==================== 私有工具 ====================

    private Post requirePost(Long id) {
        Post post = postMapper.selectById(id);
        if (post == null) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        return post;
    }

    /** 是否可编辑：作者本人 或 管理员 */
    private boolean canEdit(Long authorId, Long operatorId) {
        return canEdit(authorId, operatorId, false);
    }

    private boolean canEdit(Long authorId, Long operatorId, boolean isAdmin) {
        if (isAdmin) {
            return true;
        }
        return authorId != null && authorId.equals(operatorId);
    }

    private Integer normalizeType(Integer type) {
        return (type != null && type == Post.TYPE_QUESTION) ? Post.TYPE_QUESTION : Post.TYPE_NORMAL;
    }

    /** 绑定标签：解析出/新建标签 → 插入关联 → 更新引用数 */
    private void bindTags(Long postId, List<String> rawNames) {
        List<Tag> tags = tagService.resolveTags(rawNames);
        for (Tag tag : tags) {
            PostTag pt = new PostTag();
            pt.setPostId(postId);
            pt.setTagId(tag.getId());
            try {
                postTagMapper.insert(pt);
                tagService.addUseCount(tag.getId(), 1);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // uk_post_tag 唯一索引拦住重复关联 —— 属预期情况，跳过
                log.debug("标签已关联，跳过 | postId={} | tagId={}", postId, tag.getId());
            }
        }
    }

    /** 整体替换标签：先减掉旧的引用数并删除关联，再重新绑定 */
    private void replaceTags(Long postId, List<String> rawNames) {
        for (Tag tag : postMapper.selectTagsOfPost(postId)) {
            tagService.addUseCount(tag.getId(), -1);
        }
        postTagMapper.deleteByPostId(postId);
        bindTags(postId, rawNames);
    }

    private UserBriefVO buildAuthor(PostDetailVO vo) {
        UserBriefVO author = new UserBriefVO();
        author.setId(vo.getAuthorId());
        author.setNickname(vo.getAuthorNickname());
        author.setAvatar(vo.getAuthorAvatar());
        return author;
    }

    private List<TagVO> toTagVOs(List<Tag> tags) {
        List<TagVO> result = new ArrayList<>();
        for (Tag tag : tags) {
            TagVO vo = new TagVO();
            vo.setId(tag.getId());
            vo.setName(tag.getName());
            vo.setUseCount(tag.getUseCount());
            result.add(vo);
        }
        return result;
    }
}
