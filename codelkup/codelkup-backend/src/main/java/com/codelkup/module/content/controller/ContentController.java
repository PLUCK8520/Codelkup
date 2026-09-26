package com.codelkup.module.content.controller;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.common.result.PageResult;
import com.codelkup.common.result.Result;
import com.codelkup.module.content.dto.PostCreateRequest;
import com.codelkup.module.content.dto.PostQueryRequest;
import com.codelkup.module.content.dto.PostUpdateRequest;
import com.codelkup.module.content.service.BoardService;
import com.codelkup.module.content.service.PostService;
import com.codelkup.module.content.service.TagService;
import com.codelkup.module.content.vo.BoardVO;
import com.codelkup.module.content.vo.PostDetailVO;
import com.codelkup.module.content.vo.PostListVO;
import com.codelkup.module.content.vo.TagVO;import com.codelkup.module.user.entity.User;
import com.codelkup.security.CurrentUser;
import com.codelkup.security.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 内容核心接口：板块 / 标签 / 帖子。
 *
 * <p>权限划分（对应 SecurityConfig 白名单）：
 * <ul>
 *   <li>GET 类接口（列表、详情、板块、标签）—— 游客可读</li>
 *   <li>写操作（发帖、编辑、删除）—— {@code @CurrentUser(required = true)} 强制登录</li>
 * </ul>
 *
 * @author 刘
 */
@Slf4j
@Validated
@Tag(name = "内容", description = "板块 / 标签 / 帖子")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ContentController {

    private final PostService postService;
    private final BoardService boardService;
    private final TagService tagService;
    private final RateLimitService rateLimitService;

    /** 发帖限流：默认 20 帖/小时/用户 */
    @Value("${codelkup.rate-limit.post-per-hour:20}")
    private int postPerHour;

    // ==================== 板块 ====================

    @Operation(summary = "板块列表", description = "返回全部正常板块，按 sortOrder 升序")
    @GetMapping("/boards")
    public Result<List<BoardVO>> listBoards() {
        return Result.success(boardService.listEnabled());
    }

    // ==================== 标签 ====================

    @Operation(summary = "热门标签榜", description = "按引用次数倒序，默认 20 条")
    @GetMapping("/tags/hot")
    public Result<List<TagVO>> listHotTags(@RequestParam(required = false) Integer limit) {
        return Result.success(tagService.listHot(limit));
    }

    // ==================== 帖子：读 ====================

    @Operation(summary = "帖子列表", description = "支持板块 / 标签 / 作者 / 类型筛选与三种排序，分页返回；登录后附带 liked/collected 状态")
    @GetMapping("/posts")
    public Result<PageResult<PostListVO>> listPosts(@Valid PostQueryRequest query,
                                                    @CurrentUser User currentUser) {
        Long viewerId = currentUser == null ? null : currentUser.getId();
        return Result.success(postService.list(query, viewerId));
    }

    @Operation(summary = "帖子详情", description = "返回 Markdown 原文（前端渲染 + 消毒）；带浏览计去重")
    @GetMapping("/posts/{id}")
    public Result<PostDetailVO> getPost(@PathVariable Long id,
                                        @CurrentUser User currentUser,
                                        HttpServletRequest request) {
        Long viewerId = currentUser == null ? null : currentUser.getId();
        // 游客按 IP 去重浏览数。IP 由容器层保证不可伪造
        // （application.yml: server.forward-headers-strategy=native + remoteip.internal-proxies，
        //  仅当直连对端是可信代理时才采信 X-Forwarded-For），
        // 所以这里直接取 getRemoteAddr()，不再手写解析请求头。
        String viewerKey = currentUser != null
                ? "u:" + currentUser.getId()
                : "ip:" + request.getRemoteAddr();
        return Result.success(postService.detail(id, viewerId, viewerKey));
    }

    // ==================== 我的收藏 ====================

    /**
     * 我的收藏（分页）。
     *
     * <p>路径落在 {@code /api/users/me} 命名空间（资源语义是"我这个人的收藏列表"），
     * 处理器却放在内容控制器 —— 因为返回的是<b>一页帖子</b>，
     * 与列表/搜索/关注流共用同一套装配（标签、实时计数、互动态）。
     * 放进 interaction 模块会形成"interaction → content"的反向依赖，
     * 而 content 侧已经依赖 interaction（PostListAssembler 要用计数与互动状态），
     * 包依赖成环比路径归类不整齐的代价大得多。
     *
     * <p>装配时把当前用户同时当 viewer，所以每条记录的 {@code collected} 都是 true，
     * 前端卡片可以直接显示"已收藏"态。
     */
    @Operation(summary = "我的收藏", description = "需登录；按收藏时间倒序分页，含帖子摘要与作者")
    @GetMapping("/users/me/collections")
    public Result<PageResult<PostListVO>> listMyCollections(
            @CurrentUser(required = true) Long userId,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码最小为 1") Integer page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页最少 1 条")
            @Max(value = 50, message = "每页最多 50 条") Integer size) {
        return Result.success(postService.listCollected(userId, page, size));
    }

    // ==================== 帖子：写 ====================

    @Operation(summary = "发帖", description = "正文以 Markdown 原文存储；摘要由后端生成。按用户限流（默认 20 帖/小时）")
    @PostMapping("/posts")
    public Result<PostDetailVO> createPost(@Valid @RequestBody PostCreateRequest body,
                                           @CurrentUser(required = true) Long userId) {
        // 内容创作类接口的限流按**用户**计（不是 IP）：创作必然登录，
        // 按用户才能精确到人；按 IP 会让 NAT 后的多人互相误伤。
        if (!rateLimitService.tryAcquire("post", "u:" + userId, postPerHour, 60)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
        return Result.success(postService.create(body, userId));
    }

    @Operation(summary = "编辑帖子", description = "字段可选，只更新传入项；仅作者本人或管理员")
    @PutMapping("/posts/{id}")
    public Result<PostDetailVO> updatePost(@PathVariable Long id,
                                           @Valid @RequestBody PostUpdateRequest body,
                                           @CurrentUser(required = true) User currentUser) {
        boolean isAdmin = isAdmin(currentUser);
        return Result.success(postService.update(id, body, currentUser.getId(), isAdmin));
    }

    @Operation(summary = "删除帖子", description = "逻辑删除；仅作者本人或管理员")
    @DeleteMapping("/posts/{id}")
    public Result<Void> deletePost(@PathVariable Long id,
                                   @CurrentUser(required = true) User currentUser) {
        postService.delete(id, currentUser.getId(), isAdmin(currentUser));
        return Result.success();
    }

    @Operation(summary = "管理员置顶/加精", description = "仅管理员可用")
    @PostMapping("/posts/{id}/feature")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> featurePost(@PathVariable Long id,
                                    @RequestParam(required = false) Boolean top,
                                    @RequestParam(required = false) Boolean featured) {
        postService.setFlags(id, top, featured);
        return Result.success();
    }

    // ==================== 私有工具 ====================

    /** role == 2 即管理员 */
    private boolean isAdmin(User user) {
        return user != null && user.getRole() != null && user.getRole() == 2;
    }
}
