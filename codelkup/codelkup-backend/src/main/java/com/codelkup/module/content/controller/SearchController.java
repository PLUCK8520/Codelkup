package com.codelkup.module.content.controller;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.common.result.PageResult;
import com.codelkup.common.result.Result;
import com.codelkup.module.content.dto.PostSearchRequest;
import com.codelkup.module.content.service.SearchService;
import com.codelkup.module.content.vo.PostListVO;
import com.codelkup.module.user.entity.User;
import com.codelkup.security.CurrentUser;
import com.codelkup.security.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>⚠️ 2026-09-28 第五轮安全审计补限流：搜索是全站唯一"**匿名可达且可能触发
 * 全表 LIKE**"的接口 —— 单字符查询必然降级 LIKE（{@code LIKE '%…%'} 扫描
 * mediumtext 万行级 content），2~6 码点短词的"全文 0 条兜底"也会补一次全表扫；
 * 此前它是没有桶的（其余 17 个写/读接口都有），等于一条匿名 DoS 路径
 * （TASKS 备注 55 已记录、备注 57 的兜底又放大了触发面，故补齐）。
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
    private final RateLimitService rateLimitService;

    /** 登录用户搜索限流：默认 300 次/小时/用户（与"相似问题提示"同档） */
    @Value("${codelkup.rate-limit.search-per-hour:300}")
    private int searchPerHour;

    /** 匿名搜索限流：默认 60 次/小时/IP —— 匿名无成本，额度收得更紧 */
    @Value("${codelkup.rate-limit.search-ip-per-hour:60}")
    private int searchIpPerHour;

    @Operation(summary = "搜索帖子",
            description = "标题 + 正文全文检索（MySQL ngram）。默认按相关性排序；"
                    + "单字查询自动降级为 LIKE；关键词中的 BOOLEAN 操作符会被剥离；"
                    + "匿名按 IP、登录按用户限流（默认 60 / 300 次每小时）")
    @GetMapping("/posts")
    public Result<PageResult<PostListVO>> searchPosts(@Valid PostSearchRequest request,
                                                      @CurrentUser User currentUser,
                                                      HttpServletRequest httpRequest) {
        // 双维度桶：匿名按 IP（getRemoteAddr 不可伪造——可信边界口径与注册/登录一致，
        // 见 AuthController.login 的注释）；登录按用户（NAT 出口下不互相误伤）。
        boolean allowed = currentUser == null
                ? rateLimitService.tryAcquire("search_ip", httpRequest.getRemoteAddr(), searchIpPerHour, 60)
                : rateLimitService.tryAcquire("search", "u:" + currentUser.getId(), searchPerHour, 60);
        if (!allowed) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    currentUser == null ? "搜索过于频繁，请稍后再试" : "搜索过于频繁，请 1 小时后再试");
        }

        Long viewerId = currentUser == null ? null : currentUser.getId();
        return Result.success(searchService.search(request, viewerId));
    }
}