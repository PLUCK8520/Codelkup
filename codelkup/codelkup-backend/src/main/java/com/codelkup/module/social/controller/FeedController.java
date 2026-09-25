package com.codelkup.module.social.controller;

import com.codelkup.common.result.PageResult;
import com.codelkup.common.result.Result;
import com.codelkup.module.content.vo.PostListVO;
import com.codelkup.module.social.service.FeedService;
import com.codelkup.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Feed 流接口（Sprint 4）。
 *
 * <p>当前只有"关注流"。首页的"最新/热门"直接复用
 * {@code GET /api/posts?sort=latest|hot}，不必在这里再包一层 ——
 * 那只是同一个查询的别名，多一层转发除了增加维护面没有收益。
 *
 * @author 刘
 */
@Slf4j
@Validated
@Tag(name = "Feed", description = "关注流")
@RestController
@RequestMapping("/api/feed")
@RequiredArgsConstructor
public class FeedController {

    private final FeedService feedService;

    /**
     * 关注流。
     *
     * <p>虽然 SecurityConfig 把 {@code GET /api/feed/**} 放行了（为将来的公开流预留），
     * 这个接口仍用 {@code @CurrentUser(required = true)} 强制登录 ——
     * "我关注的人"离开登录态没有意义。未登录访问会得到 40101，
     * 而不是一个空列表（后者会让前端误以为"关注的人都没发帖"）。
     */
    @Operation(summary = "关注流", description = "我关注的人的帖子，时间倒序；需登录")
    @GetMapping("/following")
    public Result<PageResult<PostListVO>> following(
            @CurrentUser(required = true) Long userId,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码最小为 1") Integer page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页最少 1 条")
            @Max(value = 50, message = "每页最多 50 条") Integer size) {
        return Result.success(feedService.followingFeed(userId, page, size));
    }
}
