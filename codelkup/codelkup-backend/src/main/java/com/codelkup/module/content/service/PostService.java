package com.codelkup.module.content.service;

import com.codelkup.common.result.PageResult;
import com.codelkup.module.content.dto.PostCreateRequest;
import com.codelkup.module.content.dto.PostQueryRequest;
import com.codelkup.module.content.dto.PostUpdateRequest;
import com.codelkup.module.content.vo.PostDetailVO;
import com.codelkup.module.content.vo.PostListVO;

/**
 * 帖子服务。
 *
 * @author 刘
 */
public interface PostService {

    /** 帖子列表（分页 + 板块/标签/作者筛选 + 排序） */
    PageResult<PostListVO> list(PostQueryRequest query);

    /**
     * 帖子列表（带登录态）。
     *
     * <p>{@code viewerId} 用于给每条帖子标记 {@code liked} / {@code collected}，
     * 未登录传 null 时这两个字段保持 null ——
     * 前端据此区分「未登录」与「已登录但未点赞」，前者点击应跳登录页。
     */
    PageResult<PostListVO> list(PostQueryRequest query, Long viewerId);

    /**
     * 我的收藏（分页，按收藏时间倒序）。
     *
     * <p>数据源是 {@code t_user_action}（target_type=帖子, action_type=收藏），
     * 已被删除或下架的帖子会被过滤掉。
     *
     * @param userId 收藏的归属者（即当前登录用户）
     */
    PageResult<PostListVO> listCollected(Long userId, int page, int size);

    /**
     * 帖子详情。
     *
     * @param id       帖子ID
     * @param viewerId 当前登录用户ID（未登录传 null）—— 用于判断 canEdit
     * @param viewerKey 浏览去重标识（登录用户用 "u:{id}"，游客用 "ip:{ip}"）
     */
    PostDetailVO detail(Long id, Long viewerId, String viewerKey);

    /** 发帖 */
    PostDetailVO create(PostCreateRequest request, Long authorId);

    /** 编辑（仅作者或管理员） */
    PostDetailVO update(Long id, PostUpdateRequest request, Long operatorId, boolean isAdmin);

    /** 删除（逻辑删除，仅作者或管理员） */
    void delete(Long id, Long operatorId, boolean isAdmin);

    /** 管理员置顶 / 加精（传 null 表示该维度不修改） */
    void setFlags(Long id, Boolean top, Boolean featured);
}
