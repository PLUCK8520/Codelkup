package com.codelkup.module.interaction.service;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.module.content.entity.Post;
import com.codelkup.module.content.mapper.PostMapper;
import com.codelkup.module.interaction.entity.Comment;
import com.codelkup.module.interaction.entity.UserAction;
import com.codelkup.module.interaction.event.CountChangedEvent;
import com.codelkup.module.interaction.mapper.CommentMapper;
import com.codelkup.module.interaction.mapper.UserActionMapper;
import com.codelkup.module.interaction.vo.ActionResultVO;
import com.codelkup.module.notification.event.NotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户互动服务 —— 点赞 / 收藏。
 *
 * <h2>防重的核心：靠数据库唯一索引，不靠业务判断</h2>
 * 常见写法是"先 SELECT 看有没有点过赞，没有就 INSERT"。这在并发下是错的：
 * <pre>
 * 线程A: SELECT → 不存在
 * 线程B: SELECT → 不存在        ← 竞态窗口
 * 线程A: INSERT → 成功
 * 线程B: INSERT → 成功          ← 重复点赞，计数 +2
 * </pre>
 * 加锁能解决但要引入锁开销，且分布式下还得用分布式锁。
 *
 * <p>正确做法是把幂等保证交给数据库：
 * <pre>
 * INSERT INTO t_user_action ...   ← uk_user_target_action 唯一索引
 * 成功 → 首次点赞，计数 +1
 * 抛 DuplicateKeyException → 已经点过，幂等返回，计数不动
 * </pre>
 * 唯一索引的检查与插入在同一原子操作内完成，不存在竞态窗口。
 * <b>这是本 Sprint 最值得讲的技术点之一。</b>
 *
 * <h2>为什么重复点赞返回成功而不是报错</h2>
 * 前端可能因为网络重试、用户连点而重复提交。此时返回"你已经点过赞了"的
 * 错误提示是糟糕的体验 —— 用户意图（点赞）已经达成。所以幂等返回成功态，
 * 让前端把按钮置为"已点赞"。真正的错误只留给"帖子不存在"这类。
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserActionService {

    private final UserActionMapper userActionMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final CounterService counterService;
    private final ApplicationEventPublisher eventPublisher;

    // ==================== 帖子点赞 ====================

    /**
     * 点赞帖子。
     *
     * <p>三步：写明细（唯一索引防重）→ 更新 Redis 计数 → 发事件异步落库。
     *
     * @return 操作后状态与最新计数
     */
    @Transactional(rollbackFor = Exception.class)
    public ActionResultVO likePost(Long postId, Long userId) {
        Post post = requirePublishedPost(postId);

        boolean firstTime = insertActionIgnoringDuplicate(
                userId, UserAction.TARGET_POST, postId, UserAction.ACTION_LIKE);

        if (firstTime) {
            counterService.addPostCount("like", postId, 1);
            eventPublisher.publishEvent(CountChangedEvent.postLike(postId, 1));
            // Sprint 4：给作者发通知。只在"首次点赞"时发 —— 重复点击（幂等路径）
            // 不该再骚扰对方一次；取消点赞也不发（否则取消就通知，观感像刷屏）。
            eventPublisher.publishEvent(
                    NotificationEvent.likePost(post.getAuthorId(), userId, postId));
            log.info("点赞成功 | postId={} | userId={}", postId, userId);
        } else {
            log.debug("重复点赞，幂等返回 | postId={} | userId={}", postId, userId);
        }

        return ActionResultVO.of(true, counterService.getPostCount("like", postId));
    }

    /**
     * 取消点赞。
     *
     * <p>与点赞对称：删掉明细行，{@code affected > 0} 说明确实删掉了（之前点过），
     * 才减计数。若本来就没点过，直接幂等返回 —— 不做"未点赞"报错，
     * 理由同上：用户意图（取消）已达成。
     */
    @Transactional(rollbackFor = Exception.class)
    public ActionResultVO unlikePost(Long postId, Long userId) {
        requirePublishedPost(postId);

        int deleted = userActionMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserAction>()
                        .eq(UserAction::getUserId, userId)
                        .eq(UserAction::getTargetType, UserAction.TARGET_POST)
                        .eq(UserAction::getTargetId, postId)
                        .eq(UserAction::getActionType, UserAction.ACTION_LIKE));

        if (deleted > 0) {
            counterService.addPostCount("like", postId, -1);
            eventPublisher.publishEvent(CountChangedEvent.postLike(postId, -1));
            log.info("取消点赞 | postId={} | userId={}", postId, userId);
        }

        return ActionResultVO.of(false, counterService.getPostCount("like", postId));
    }

    // ==================== 帖子收藏 ====================

    @Transactional(rollbackFor = Exception.class)
    public ActionResultVO collectPost(Long postId, Long userId) {
        requirePublishedPost(postId);

        boolean firstTime = insertActionIgnoringDuplicate(
                userId, UserAction.TARGET_POST, postId, UserAction.ACTION_COLLECT);

        if (firstTime) {
            counterService.addPostCount("collect", postId, 1);
            eventPublisher.publishEvent(CountChangedEvent.postCollect(postId, 1));
            log.info("收藏成功 | postId={} | userId={}", postId, userId);
        }

        return ActionResultVO.of(true, counterService.getPostCount("collect", postId));
    }

    @Transactional(rollbackFor = Exception.class)
    public ActionResultVO uncollectPost(Long postId, Long userId) {
        requirePublishedPost(postId);

        int deleted = userActionMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserAction>()
                        .eq(UserAction::getUserId, userId)
                        .eq(UserAction::getTargetType, UserAction.TARGET_POST)
                        .eq(UserAction::getTargetId, postId)
                        .eq(UserAction::getActionType, UserAction.ACTION_COLLECT));

        if (deleted > 0) {
            counterService.addPostCount("collect", postId, -1);
            eventPublisher.publishEvent(CountChangedEvent.postCollect(postId, -1));
            log.info("取消收藏 | postId={} | userId={}", postId, userId);
        }

        return ActionResultVO.of(false, counterService.getPostCount("collect", postId));
    }

    // ==================== 评论点赞 ====================

    @Transactional(rollbackFor = Exception.class)
    public ActionResultVO likeComment(Long commentId, Long userId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }

        boolean firstTime = insertActionIgnoringDuplicate(
                userId, UserAction.TARGET_COMMENT, commentId, UserAction.ACTION_LIKE);

        if (firstTime) {
            counterService.addCommentLikeCount(commentId, 1);
            eventPublisher.publishEvent(CountChangedEvent.commentLike(commentId, 1));
            // Sprint 4：通知<b>评论作者</b>（不是帖子作者）。
            // 目标指向帖子：通知点进去要看到讨论上下文，评论没有独立详情页。
            eventPublisher.publishEvent(
                    NotificationEvent.likeComment(comment.getAuthorId(), userId, comment.getPostId()));
        }

        return ActionResultVO.of(true, counterService.getCommentLikeCount(commentId));
    }

    @Transactional(rollbackFor = Exception.class)
    public ActionResultVO unlikeComment(Long commentId, Long userId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }

        int deleted = userActionMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserAction>()
                        .eq(UserAction::getUserId, userId)
                        .eq(UserAction::getTargetType, UserAction.TARGET_COMMENT)
                        .eq(UserAction::getTargetId, commentId)
                        .eq(UserAction::getActionType, UserAction.ACTION_LIKE));

        if (deleted > 0) {
            counterService.addCommentLikeCount(commentId, -1);
            eventPublisher.publishEvent(CountChangedEvent.commentLike(commentId, -1));
        }

        return ActionResultVO.of(false, counterService.getCommentLikeCount(commentId));
    }

    // ==================== 查询：给详情页 / 列表页标记状态 ====================

    /** 某用户是否点过赞（单目标） */
    public boolean hasLiked(Long userId, int targetType, Long targetId) {
        if (userId == null) {
            return false;
        }
        return userActionMapper.exists(userId, targetType, targetId, UserAction.ACTION_LIKE) > 0;
    }

    /** 某用户是否收藏过（单目标） */
    public boolean hasCollected(Long userId, Long postId) {
        if (userId == null) {
            return false;
        }
        return userActionMapper.exists(
                userId, UserAction.TARGET_POST, postId, UserAction.ACTION_COLLECT) > 0;
    }

    /**
     * 批量取"我点赞过的一批目标"—— 列表页避免 N+1。
     *
     * <p>返回 Set 而非 List：调用方只需判断"在不在里面"，
     * Set 的 {@code contains} 是 O(1)，List 是 O(n)。一页 20 条时差别不大，
     * 但这是零成本的正确选择。
     */
    public Set<Long> likedTargetIds(Long userId, int targetType, Collection<Long> targetIds) {
        if (userId == null || targetIds == null || targetIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(userActionMapper.selectActedTargetIds(
                userId, targetType, UserAction.ACTION_LIKE, List.copyOf(targetIds)));
    }

    /** 批量取"我收藏过的一批帖子" */
    public Set<Long> collectedPostIds(Long userId, Collection<Long> postIds) {
        if (userId == null || postIds == null || postIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(userActionMapper.selectActedTargetIds(
                userId, UserAction.TARGET_POST, UserAction.ACTION_COLLECT, List.copyOf(postIds)));
    }

    // ==================== 私有工具 ====================

    /**
     * 插入互动记录，命中唯一索引时返回 false。
     *
     * <p>把 try-catch 收在这一个方法里，是为了让调用方读起来是
     * "尝试记录，首次为真"这种业务语义，而不是被异常处理细节淹没。
     *
     * <p>⚠️ 这里捕获的是 {@link DuplicateKeyException}，它由 Spring 的
     * 异常转换机制从 MySQL 的 1062 错误映射而来 —— 所以必须让异常
     * <b>冒泡到 Spring 这一层</b>才能被正确转换。如果在这里换成
     * 捕获 SQLIntegrityConstraintViolationException 就需要额外处理驱动差异。
     */
    private boolean insertActionIgnoringDuplicate(Long userId, int targetType,
                                                 Long targetId, int actionType) {
        try {
            userActionMapper.insert(UserAction.of(userId, targetType, targetId, actionType));
            return true;
        } catch (DuplicateKeyException e) {
            // uk_user_target_action 拦住了重复 —— 预期路径，不是错误
            return false;
        }
    }

    /** 校验帖子存在且已发布 */
    /**
     * 校验帖子存在且已发布。
     *
     * <p>返回帖子对象而不只是抛异常：调用方（点赞）需要 {@code authorId}
     * 才能发通知。让每个调用方自己再查一次是白白的重复查询 ——
     * 这里已经查出来了，直接返回即可。
     */
    private Post requirePublishedPost(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null || (post.getStatus() != null && post.getStatus() != Post.STATUS_PUBLISHED)) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        return post;
    }

    /** 供 Service 内部或其它模块使用的批量状态查询（预留） */
    public Map<Long, Boolean> batchLikedStatus(Long userId, int targetType, Collection<Long> ids) {
        Set<Long> liked = likedTargetIds(userId, targetType, ids);
        return ids.stream().collect(java.util.stream.Collectors.toMap(
                id -> id, liked::contains));
    }
}
