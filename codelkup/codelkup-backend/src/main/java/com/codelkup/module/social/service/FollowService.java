package com.codelkup.module.social.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.common.result.PageResult;
import com.codelkup.module.notification.event.NotificationEvent;
import com.codelkup.module.social.entity.UserFollow;
import com.codelkup.module.social.mapper.UserFollowMapper;
import com.codelkup.module.social.vo.FollowStateVO;
import com.codelkup.module.social.vo.FollowUserVO;
import com.codelkup.module.user.entity.User;
import com.codelkup.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 关注服务（Sprint 4）。
 *
 * <h2>幂等的实现方式与 S3 的点赞完全同构</h2>
 * 靠 {@code uk_follow(follower_id, followee_id)} 唯一索引，而不是
 * "先 exists 再 insert"。后者在并发下有竞态窗口：两个请求同时查到"未关注"，
 * 然后都执行插入，结果是两行重复数据（或一个请求报错）。
 * 唯一索引让数据库来仲裁，第二个插入必然失败，捕获
 * {@link DuplicateKeyException} 后按"已关注"返回即可。
 *
 * <p>⚠️ 一个容易踩的点：在 {@code @Transactional} 方法里捕获
 * {@link DuplicateKeyException} 是<b>安全的（仅限 MySQL）</b>。
 * MySQL 的唯一键冲突只让该条语句失败，事务本身仍然可用；
 * 而 PostgreSQL 会把整个事务标记为 aborted，后续语句全部报错，
 * 那种情况下必须用 savepoint。这里能这么写是因为目标库确定是 MySQL ——
 * 换库时这是需要重新审视的地方之一。
 *
 * <p>之所以要捕获在<b>方法内部</b>：Spring 默认只对传播出事务边界的
 * RuntimeException 回滚。异常在方法内被吃掉就不会触发回滚，
 * 这正是"幂等"想要的 —— 重复关注不该让整个请求失败。
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowService {

    private final UserFollowMapper followMapper;
    private final UserMapper userMapper;
    private final ApplicationEventPublisher eventPublisher;

    // ==================== 写 ====================

    /**
     * 关注。
     *
     * <p>幂等：重复关注不报错，返回与首次相同的结果。
     */
    @Transactional(rollbackFor = Exception.class)
    public FollowStateVO follow(Long targetId, Long userId) {
        checkTarget(targetId, userId);

        UserFollow relation = new UserFollow();
        relation.setFollowerId(userId);
        relation.setFolloweeId(targetId);

        try {
            followMapper.insert(relation);
            // 只有真正新增了关注关系才发通知 —— 重复点击不该再骚扰对方一次
            eventPublisher.publishEvent(NotificationEvent.follow(targetId, userId));
            log.info("关注成功 | followerId={} | followeeId={}", userId, targetId);
        } catch (DuplicateKeyException e) {
            log.debug("重复关注，按幂等处理 | followerId={} | followeeId={}", userId, targetId);
        }

        return state(targetId, userId);
    }

    /**
     * 取消关注。
     *
     * <p>幂等：没关注过时也不报错（DELETE 影响 0 行，天然幂等）。
     */
    @Transactional(rollbackFor = Exception.class)
    public FollowStateVO unfollow(Long targetId, Long userId) {
        checkTarget(targetId, userId);

        int removed = followMapper.deleteFollow(userId, targetId);
        log.debug("取消关注 | followerId={} | followeeId={} | removed={}", userId, targetId, removed);

        return state(targetId, userId);
    }

    // ==================== 读 ====================

    /**
     * 关注状态 + 计数。
     *
     * @param viewerId 当前登录用户；为 null 时 {@code following} 返回 null
     *                 （"未登录，不知道"），而非 false（"登录了但没关注"）
     */
    public FollowStateVO state(Long targetId, Long viewerId) {
        requireUser(targetId);

        Boolean following = null;
        if (viewerId != null) {
            following = followMapper.exists(viewerId, targetId) > 0;
        }
        return FollowStateVO.of(
                following,
                followMapper.countFollowers(targetId),
                followMapper.countFollowees(targetId));
    }

    /** 粉丝列表（谁关注了 TA） */
    public PageResult<FollowUserVO> followers(Long userId, Long viewerId, int page, int size) {
        requireUser(userId);
        Page<FollowUserVO> p = new Page<>(page, size);
        IPage<FollowUserVO> result = followMapper.selectFollowers(p, userId);
        fillFollowingState(result.getRecords(), viewerId);
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    /** 关注列表（TA 关注了谁） */
    public PageResult<FollowUserVO> followees(Long userId, Long viewerId, int page, int size) {
        requireUser(userId);
        Page<FollowUserVO> p = new Page<>(page, size);
        IPage<FollowUserVO> result = followMapper.selectFollowees(p, userId);
        fillFollowingState(result.getRecords(), viewerId);
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    /**
     * 我关注的所有人 ID —— 关注流（拉模式）的第一步。
     *
     * <p>返回列表而不是"直接给查询结果"，是因为调用方（FeedService）需要用
     * 它来决定"关注数为 0 时提前返回"。若在这里返回 IPage，那个边界判断
     * 就只能在 SQL 层做，而 {@code IN ()} 是语法错误。
     */
    public List<Long> followeeIds(Long userId) {
        return followMapper.selectFolloweeIds(userId);
    }

    // ==================== 私有工具 ====================

    /**
     * 批量标记"我关注了列表里的谁"。
     *
     * <p>一次 IN 查询 + 内存 Set 判断。若逐个用户查 exists，
     * 一页 20 条就是 20 次往返 —— 典型的 N+1，且很容易在 review 时被忽略
     * （因为每个查询本身都很快）。
     */
    private void fillFollowingState(List<FollowUserVO> records, Long viewerId) {
        if (records == null || records.isEmpty() || viewerId == null) {
            return;
        }
        List<Long> ids = records.stream().map(FollowUserVO::getId).toList();
        Set<Long> followed = new HashSet<>(followMapper.selectFollowedAmong(viewerId, ids));

        for (FollowUserVO record : records) {
            record.setFollowing(followed.contains(record.getId()));
        }
    }

    /** 校验目标用户存在、且不是自己 */
    private void checkTarget(Long targetId, Long userId) {
        if (targetId == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "缺少目标用户");
        }
        if (targetId.equals(userId)) {
            throw new BusinessException(ErrorCode.CANNOT_FOLLOW_SELF);
        }
        requireUser(targetId);
    }

    /** 目标用户必须存在（已注销的账号不允许被关注） */
    private void requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
    }
}
