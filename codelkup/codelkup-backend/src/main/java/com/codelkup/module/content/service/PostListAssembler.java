package com.codelkup.module.content.service;

import com.codelkup.module.content.mapper.PostMapper;
import com.codelkup.module.content.vo.PostListVO;
import com.codelkup.module.content.vo.TagVO;
import com.codelkup.module.interaction.entity.UserAction;
import com.codelkup.module.interaction.service.CounterService;
import com.codelkup.module.interaction.service.UserActionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 帖子列表的<b>统一装配器</b>。
 *
 * <h2>为什么单独抽出来</h2>
 * "把一页 {@link PostListVO} 补全到可返回状态"这件事，现在有两个调用方：
 * 常规列表（{@code PostServiceImpl.list}）与搜索结果（{@code SearchService}）。
 *
 * <p>S3 已经吃过一次同类亏：详情页和创建接口各自组装了一次 VO，结果创建接口
 * 返回的 {@code tags} 是 null —— 因为漏了一处。那次修法是让创建接口复用详情查询。
 * 这次在<b>动手写第二个调用方之前</b>就抽出来，属于把教训前置，而不是等它再发生。
 *
 * <p>装配包含三步，顺序无所谓（互不依赖），但<b>缺一不可</b>：
 * <ol>
 *   <li>补标签 —— 一次 IN 查询</li>
 *   <li>覆盖实时计数 —— 从 Redis 批量取</li>
 *   <li>标记当前用户的点赞/收藏状态</li>
 * </ol>
 * 三步都做成批量，任何一步退化成逐条查询都会引入 N+1。
 *
 * @author 刘
 */
@Component
@RequiredArgsConstructor
public class PostListAssembler {

    private final PostMapper postMapper;
    private final CounterService counterService;
    private final UserActionService userActionService;

    /**
     * 把一页列表补全到可返回状态。
     *
     * @param records  查询结果（原地修改）
     * @param viewerId 当前登录用户；为 null（游客）时不查互动状态，字段保持 null
     */
    public void assemble(List<PostListVO> records, Long viewerId) {
        fillTags(records);
        fillLiveCounts(records);
        fillInteractionState(records, viewerId);
    }

    /** 给一页帖子批量补标签 */
    private void fillTags(List<PostListVO> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Long> ids = records.stream().map(PostListVO::getId).toList();

        // 一次查询取回所有帖子的标签，再按 postId 分组 —— 避免 N+1
        List<Map<String, Object>> rows = postMapper.selectTagsByPostIds(ids);
        Map<Long, List<TagVO>> grouped = rows.stream().collect(Collectors.groupingBy(
                r -> ((Number) r.get("post_id")).longValue(),
                Collectors.mapping(r -> {
                    TagVO vo = new TagVO();
                    vo.setId(((Number) r.get("tag_id")).longValue());
                    vo.setName((String) r.get("tag_name"));
                    return vo;
                }, Collectors.toList())
        ));

        for (PostListVO record : records) {
            record.setTags(grouped.getOrDefault(record.getId(), List.of()));
        }
    }

    /**
     * 把列表里的计数替换为 Redis 实时值（Sprint 3）。
     *
     * <p>只对<b>命中了 Redis 的</b>记录覆盖：没命中的保持 SQL 查出的 MySQL 基线。
     * 这里刻意<b>不</b>逐条调用 {@code getPostCount} 去回填 ——
     * 那会退化成 20 次 Redis 往返（虽然有回填收益，但列表页读多写少，
     * 回填交给详情页做就够）。批量读是 N 条 key 一次 multiGet。
     */
    private void fillLiveCounts(List<PostListVO> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Long> ids = records.stream().map(PostListVO::getId).toList();

        Map<Long, Integer> liveLikes = counterService.batchGetPostCount("like", ids);
        Map<Long, Integer> liveCollects = counterService.batchGetPostCount("collect", ids);
        Map<Long, Integer> liveComments = counterService.batchGetPostCount("comment", ids);

        for (PostListVO record : records) {
            Integer like = liveLikes.get(record.getId());
            Integer collect = liveCollects.get(record.getId());
            Integer comment = liveComments.get(record.getId());
            if (like != null) {
                record.setLikeCount(like);
            }
            if (collect != null) {
                record.setCollectCount(collect);
            }
            if (comment != null) {
                record.setCommentCount(comment);
            }
        }
    }

    /**
     * 标记"当前用户点赞/收藏过哪些帖子"（Sprint 3）。
     *
     * <p>一次 IN 查询解决整页；游客时不做任何查询，字段保持 null
     * （null 表示"不知道"，与 false 的"没点过"语义不同）。
     */
    private void fillInteractionState(List<PostListVO> records, Long viewerId) {
        if (records == null || records.isEmpty() || viewerId == null) {
            return;
        }
        List<Long> ids = records.stream().map(PostListVO::getId).toList();

        var likedIds = userActionService.likedTargetIds(
                viewerId, UserAction.TARGET_POST, ids);
        var collectedIds = userActionService.collectedPostIds(viewerId, ids);

        for (PostListVO record : records) {
            record.setLiked(likedIds.contains(record.getId()));
            record.setCollected(collectedIds.contains(record.getId()));
        }
    }
}
