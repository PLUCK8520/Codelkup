package com.codelkup.module.content.controller;

import com.codelkup.common.result.PageResult;
import com.codelkup.common.result.Result;
import com.codelkup.module.content.dto.PostSearchRequest;
import com.codelkup.module.content.service.SearchService;
import com.codelkup.module.content.vo.PostListVO;
import com.codelkup.module.user.entity.User;
import com.codelkup.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索接口（Sprint 4）。
 *
 * <p>单独一个 Controller 而不是塞进 {@code ContentController}：
 * 路径前缀不同（{@code /api/search} vs {@code /api/posts}），
 * 且将来整体切换到 ES 时只需替换这一个类。
 *
 * <p>游客可搜索（免登录）—— 论坛的搜索不该要求先注册，
 * 这也是内容型产品拉新的常规做法。登录用户额外获得 liked/collected 标记。
 *
 * @author 刘
 */
@Slf4j
@Tag(name = "检索", description = "帖子全文检索")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @Operation(summary = "搜索帖子",
            description = "标题 + 正文全文检索（MySQL ngram）。默认按相关性排序；"
                    + "单字查询自动降级为 LIKE；关键词中的 BOOLEAN 操作符会被剥离")
    @GetMapping("/posts")
    public Result<PageResult<PostListVO>> searchPosts(@Valid PostSearchRequest request,
                                                      @CurrentUser User currentUser) {
        Long viewerId = currentUser == null ? null : currentUser.getId();
        return Result.success(searchService.search(request, viewerId));
    }
}
