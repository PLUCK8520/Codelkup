package com.codelkup.module.social.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codelkup.common.result.PageResult;
import com.codelkup.module.content.mapper.PostMapper;
import com.codelkup.module.content.service.PostListAssembler;
import com.codelkup.module.content.vo.PostListVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 关注流服务（Sprint 4，修订 D3：<b>拉模式</b>）。
 *
 * <h2>为什么是拉模式而不是推模式</h2>
 * 推模式（发帖时把帖子 ID 写进每个粉丝的收件箱）在粉丝量大时才有价值。
 * 本项目真实粉丝数是<b>两位数</b>，原文档设计的"粉丝 ≥ 1000 切推模式"
 * 这个阈值永远触发不了 —— 写一套跑不到的分支，除了让代码变复杂、让测试变困难，
 * 没有任何收益。
 *
 * <p>拉模式的代价是"关注 500 人时查询变慢"，而关注 500 人在个人项目里
 * 不会发生。即便发生，{@code idx_author(author_id)} 加上分页
 * （{@code LIMIT 20}）也能撑住。V0.2 若真有量，正好有
 * "朴素实现 → 发现瓶颈 → 引入推拉结合"的完整叙事 —— 那才是这段代码
 * 真正的价值所在。
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedService {

    private final FollowService followService;
    private final PostMapper postMapper;
    private final PostListAssembler assembler;

    /**
     * 我关注的人的帖子（时间倒序）。
     *
     * <p>⚠️ 最关键的一行是"关注数为 0 时提前返回"：
     * 新注册用户还没有关注任何人，此时若拿空列表去拼
     * {@code author_id IN ()}，MySQL 会直接报语法错误 ——
     * 表现为"每个新用户点开关注流都是 500"。
     */
    public PageResult<PostListVO> followingFeed(Long userId, int page, int size) {
        List<Long> followeeIds = followService.followeeIds(userId);

        if (followeeIds.isEmpty()) {
            log.debug("关注列表为空，直接返回空流 | userId={}", userId);
            return PageResult.empty(page, size);
        }

        Page<PostListVO> p = new Page<>(page, size);
        IPage<PostListVO> result = postMapper.selectFeedPage(p, followeeIds);

        // 与列表页/搜索页共用同一套装配
        assembler.assemble(result.getRecords(), userId);

        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }
}
