package com.codelkup.module.interaction.service;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.module.content.entity.Post;
import com.codelkup.module.content.mapper.PostMapper;
import com.codelkup.module.content.vo.UserBriefVO;
import com.codelkup.module.interaction.dto.CommentCreateRequest;
import com.codelkup.module.interaction.entity.Comment;
import com.codelkup.module.interaction.entity.UserAction;
import com.codelkup.module.interaction.event.CountChangedEvent;
import com.codelkup.module.interaction.mapper.CommentMapper;
import com.codelkup.module.interaction.vo.CommentVO;
import com.codelkup.module.notification.event.NotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 评论服务。
 *
 * <h2>树形结构的实现选择</h2>
 * 只做两级，所以"拼树"不需要递归：
 * <ol>
 *   <li>一次 {@code SELECT} 取回该帖全部评论（含作者、被回复者 JOIN）</li>
 *   <li>在内存按 {@code parentId} 分组</li>
 *   <li>一级评论按时间升序排列，回复挂到各自根评论下</li>
 * </ol>
 * 一次查询 + O(n) 分组，没有递归、没有 N+1。
 *
 * <p><b>为什么不递归</b>：无限嵌套会让"取第 5 层的第 3 条回复"变成
 * 5 次查询，前端渲染也要递归组件；而实际论坛里，深于两级的对话
 * 阅读体验反而更差（人类对话本身就是线性的）。这是有意识的简化，
 * 不是能力不足 —— 面试被问到可以这样回答。
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    /** 评论长度上限。评论是即时讨论，不需要长文（长文应该发帖） */
    private static final int MAX_CONTENT_LENGTH = 1000;

    private final CommentMapper commentMapper;
    private final PostMapper postMapper;
    private final CounterService counterService;
    private final UserActionService userActionService;
    private final ApplicationEventPublisher eventPublisher;

    // ==================== 读取：树形列表 ====================

    /**
     * 取某帖的评论树（两级）。
     *
     * <p>计数以 Redis 为准：{@code selectByPostId} 返回的是 MySQL 基线，
     * 这里用 Redis 的值覆盖它，保证前端看到的是实时数。
     *
     * @param viewerId 当前登录用户（可为 null 表示游客），用于标记 canDelete / liked
     */
    public List<CommentVO> listByPost(Long postId, Long viewerId, boolean isAdmin) {
        requirePost(postId);

        List<CommentVO> flat = commentMapper.selectByPostId(postId);

        if (flat.isEmpty()) {
            return List.of();
        }

        // ---- 用 Redis 实时值覆盖 MySQL 基线 ----
        List<Long> ids = flat.stream().map(CommentVO::getId).toList();
        Map<Long, Integer> liveLikes = counterService.batchGetCommentLikeCount(ids);

        // ---- 批量取"我点赞过的评论"，避免逐条查询 ----
        Set<Long> likedIds = userActionService.likedTargetIds(
                viewerId, UserAction.TARGET_COMMENT, ids);

        // ---- 按 parentId 分组 ----
        // 用 LinkedHashMap 保持插入顺序（SQL 已按 create_time ASC 排序），
        // 这样一级评论的展示顺序就是时间顺序，不需要再排一次
        Map<Long, CommentVO> roots = new LinkedHashMap<>();
        Map<Long, List<CommentVO>> repliesByRoot = new HashMap<>();

        for (CommentVO vo : flat) {
            // 填充实时计数与个人状态
            vo.setLikeCount(liveLikes.getOrDefault(vo.getId(), vo.getLikeCount()));
            vo.setLiked(likedIds.contains(vo.getId()));
            vo.setCanDelete(canDelete(vo.getAuthorId(), viewerId, isAdmin));
            vo.setAuthor(buildAuthor(vo.getAuthorId(), vo.getAuthorNickname(), vo.getAuthorAvatar()));
            if (vo.getReplyUserId() != null) {
                vo.setReplyUser(buildAuthor(vo.getReplyUserId(), vo.getReplyUserNickname(), null));
            }

            long parentId = vo.getParentId() == null ? Comment.ROOT_PARENT_ID : vo.getParentId();
            if (parentId == Comment.ROOT_PARENT_ID) {
                vo.setReplies(new ArrayList<>());
                roots.put(vo.getId(), vo);
            } else {
                repliesByRoot.computeIfAbsent(parentId, k -> new ArrayList<>()).add(vo);
            }
        }

        // ---- 把回复挂到根评论上 ----
        for (Map.Entry<Long, List<CommentVO>> entry : repliesByRoot.entrySet()) {
            CommentVO root = roots.get(entry.getKey());
            if (root != null) {
                root.setReplies(entry.getValue());
            } else {
                // 根评论被删除但回复还在：把回复提升为一级，避免内容凭空消失
                // （评论作者为数据一致性负责，宁可展示出来也不要静默吞掉）
                log.debug("根评论不存在，回复提升为一级 | rootId={}", entry.getKey());
                for (CommentVO orphan : entry.getValue()) {
                    orphan.setParentId(Comment.ROOT_PARENT_ID);
                    roots.put(orphan.getId(), orphan);
                }
            }
        }

        // 被采纳的排最前（提问帖里采纳答案应该一眼可见）
        List<CommentVO> result = new ArrayList<>(roots.values());
        result.sort((a, b) -> {
            boolean aAcc = a.getIsAccepted() != null && a.getIsAccepted() == 1;
            boolean bAcc = b.getIsAccepted() != null && b.getIsAccepted() == 1;
            if (aAcc != bAcc) {
                return aAcc ? -1 : 1;
            }
            return 0; // 其余保持 SQL 的时间序
        });
        return result;
    }

    // ==================== 写入 ====================

    /**
     * 发表评论 / 回复。
     *
     * <p>回复的处理：{@code parentId} 传的是<b>被回复那条评论的 ID</b>，
     * 但存库时要归一化成<b>根评论 ID</b> —— 因为只做两级。
     * 若 A 是一级、B 回复 A、C 回复 B，则 C 的 parentId 存 A 的 ID，
     * replyUserId 存 B 的作者。这样树仍是两层，但"回复谁"的信息保留。
     */
    @Transactional(rollbackFor = Exception.class)
    public CommentVO create(Long postId, CommentCreateRequest request, Long authorId) {
        Post post = requirePost(postId);

        String content = normalizeContent(request.getContent());

        Comment comment = new Comment();
        comment.setPostId(postId);
        comment.setAuthorId(authorId);
        comment.setContent(content);
        comment.setParentId(Comment.ROOT_PARENT_ID);
        comment.setReplyUserId(null);
        comment.setLikeCount(0);
        comment.setIsAccepted(0);

        // ---- 处理回复 ----
        // parent 声明在 if 外：下面发通知时需要知道"被回复的是谁"
        Comment parent = null;
        if (request.getParentId() != null && request.getParentId() != Comment.ROOT_PARENT_ID) {
            parent = commentMapper.selectById(request.getParentId());
            if (parent == null) {
                throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
            }
            if (!parent.getPostId().equals(postId)) {
                // 跨帖子回复：属于客户端错误或恶意构造，必须拦
                throw new BusinessException(ErrorCode.PARAM_ERROR, "被回复的评论不属于该帖子");
            }

            // 归一化到根评论：只做两级
            long rootId = parent.getParentId() == null || parent.getParentId() == Comment.ROOT_PARENT_ID
                    ? parent.getId()
                    : parent.getParentId();
            comment.setParentId(rootId);
            // 回复的展示对象是"被回复的那条评论的作者"（而非根评论作者）
            comment.setReplyUserId(parent.getAuthorId());
        }

        commentMapper.insert(comment);

        // 计数：Redis 增量 + 异步落库
        counterService.addPostCount("comment", postId, 1);
        eventPublisher.publishEvent(CountChangedEvent.postComment(postId, 1));

        // ---- Sprint 4：发通知 ----
        // 规则不是"总是通知楼主"，而是：
        //   回复某条评论 → 通知<b>被回复那条评论</b>的作者
        //   一级评论     → 通知楼主
        // 反例说明必要性：A 评论了楼主的帖子，B 回复 A。此时被回复者是 A，
        // 楼主（如果不是 A）收到通知属于误报 —— 讨论和他无关。
        if (parent != null) {
            eventPublisher.publishEvent(NotificationEvent.reply(
                    parent.getAuthorId(), authorId, postId));
        } else {
            eventPublisher.publishEvent(NotificationEvent.comment(
                    post.getAuthorId(), authorId, postId));
        }

        log.info("发表评论 | commentId={} | postId={} | authorId={} | parentId={}",
                comment.getId(), postId, authorId, comment.getParentId());

        // 返回单条（前端可直接插入列表，不必重新拉全量）
        return loadOne(comment.getId(), authorId);
    }

    /** 删除评论（作者本人或管理员）。逻辑删除，计数同步减少。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long commentId, Long operatorId, boolean isAdmin) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        if (!isAdmin && !comment.getAuthorId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.NOT_RESOURCE_OWNER);
        }

        commentMapper.deleteById(commentId);

        counterService.addPostCount("comment", comment.getPostId(), -1);
        eventPublisher.publishEvent(CountChangedEvent.postComment(comment.getPostId(), -1));

        log.info("删除评论 | commentId={} | postId={} | operatorId={} | isAdmin={}",
                commentId, comment.getPostId(), operatorId, isAdmin);
    }

    /**
     * 采纳评论（仅楼主，仅提问帖）。
     *
     * <p>采纳会先清掉该帖原有的采纳标记，再设置新的 ——
     * 一篇帖子只能有一个被采纳答案。
     */
    @Transactional(rollbackFor = Exception.class)
    public void accept(Long postId, Long commentId, Long operatorId) {
        Post post = requirePost(postId);

        if (!post.getAuthorId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.NOT_RESOURCE_OWNER, "只有楼主可以采纳答案");
        }
        if (post.getType() == null || post.getType() != Post.TYPE_QUESTION) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "只有提问帖可以采纳答案");
        }

        Comment comment = commentMapper.selectById(commentId);
        if (comment == null || !comment.getPostId().equals(postId)) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }

        // 清除旧的采纳（如果有）
        Long oldAcceptedId = post.getAcceptedCommentId();
        if (oldAcceptedId != null && !oldAcceptedId.equals(commentId)) {
            Comment old = commentMapper.selectById(oldAcceptedId);
            if (old != null) {
                old.setIsAccepted(0);
                commentMapper.updateById(old);
            }
        }

        comment.setIsAccepted(1);
        commentMapper.updateById(comment);

        Post update = new Post();
        update.setId(postId);
        update.setAcceptedCommentId(commentId);
        postMapper.updateById(update);

        // Sprint 4：通知被采纳评论的作者。
        // 「自己采纳自己」的情况（楼主回答自己的提问）由通知模块统一拦掉，此处不必判断。
        eventPublisher.publishEvent(NotificationEvent.accept(
                comment.getAuthorId(), operatorId, postId));

        log.info("采纳答案 | postId={} | commentId={} | operatorId={}", postId, commentId, operatorId);
    }

    // ==================== 私有工具 ====================

    private CommentVO loadOne(Long commentId, Long viewerId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        // 复用列表查询按 postId 过滤后取目标条 —— 保证 VO 字段组装逻辑唯一，
        // 不出现"列表有 author 但单条没有"这种分叉（Sprint 2 踩过同类坑）
        List<CommentVO> all = commentMapper.selectByPostId(comment.getPostId());
        CommentVO vo = all.stream()
                .filter(c -> c.getId().equals(commentId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMENT_NOT_FOUND));

        vo.setLikeCount(counterService.getCommentLikeCount(commentId));
        vo.setLiked(userActionService.hasLiked(viewerId, UserAction.TARGET_COMMENT, commentId));
        vo.setCanDelete(comment.getAuthorId().equals(viewerId));
        vo.setAuthor(buildAuthor(vo.getAuthorId(), vo.getAuthorNickname(), vo.getAuthorAvatar()));
        if (vo.getReplyUserId() != null) {
            vo.setReplyUser(buildAuthor(vo.getReplyUserId(), vo.getReplyUserNickname(), null));
        }
        return vo;
    }

    private Post requirePost(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null || (post.getStatus() != null && post.getStatus() != Post.STATUS_PUBLISHED)) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        return post;
    }

    /** 内容校验：去空白、非空、限长 */
    private String normalizeContent(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "评论内容不能为空");
        }
        String content = raw.trim();
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "评论内容不能超过 " + MAX_CONTENT_LENGTH + " 字");
        }
        return content;
    }

    private boolean canDelete(Long authorId, Long viewerId, boolean isAdmin) {
        return isAdmin || (authorId != null && authorId.equals(viewerId));
    }

    private UserBriefVO buildAuthor(Long id, String nickname, String avatar) {
        if (id == null) {
            return null;
        }
        UserBriefVO brief = new UserBriefVO();
        brief.setId(id);
        brief.setNickname(nickname);
        brief.setAvatar(avatar);
        return brief;
    }

    /** 供详情页统计"某帖评论数"（走 Redis 实时值） */
    public int commentCount(Long postId) {
        return counterService.getPostCount("comment", postId);
    }

    /** 批量取某批帖子的评论数（列表页用） */
    public Map<Long, Integer> batchCommentCount(List<Long> postIds) {
        return counterService.batchGetPostCount("comment", postIds);
    }

    /** 把列表按 parentId 分组（暴露给测试或其它模块复用） */
    public Map<Long, List<CommentVO>> groupByParent(List<CommentVO> flat) {
        return flat.stream().collect(Collectors.groupingBy(
                c -> c.getParentId() == null ? Comment.ROOT_PARENT_ID : c.getParentId()));
    }
}
