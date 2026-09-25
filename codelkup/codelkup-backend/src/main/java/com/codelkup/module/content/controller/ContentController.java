package com.codelkup.module.content.controller;

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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
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
@Tag(name = "内容", description = "板块 / 标签 / 帖子")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ContentController {

    private final PostService postService;
    private final BoardService boardService;
    private final TagService tagService;

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
        String viewerKey = currentUser != null
                ? "u:" + currentUser.getId()
                : "ip:" + resolveClientIp(request);
        return Result.success(postService.detail(id, viewerId, viewerKey));
    }

    // ==================== 帖子：写 ====================

    @Operation(summary = "发帖", description = "正文以 Markdown 原文存储；摘要由后端生成")
    @PostMapping("/posts")
    public Result<PostDetailVO> createPost(@Valid @RequestBody PostCreateRequest body,
                                           @CurrentUser(required = true) Long userId) {
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

    /** 取客户端 IP 用于游客浏览去重（与 AuthController 同逻辑，抽出独立工具类会更优雅，此处先内联） */
    private String resolveClientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP"};
        for (String header : headers) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                int comma = value.indexOf(',');
                return (comma > 0 ? value.substring(0, comma) : value).trim();
            }
        }
        return request.getRemoteAddr();
    }
}
