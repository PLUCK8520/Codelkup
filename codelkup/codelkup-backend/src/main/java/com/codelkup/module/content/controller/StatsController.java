package com.codelkup.module.content.controller;

import com.codelkup.common.result.Result;
import com.codelkup.module.content.service.SiteStatsService;
import com.codelkup.module.content.vo.SiteStatsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站点汇总统计接口。
 *
 * <p>为什么单独成类而不是塞进 ContentController：它的资源语义是"站点"，
 * 横跨板块 / 标签 / 帖子三类实体 —— 挂在任何一个已有资源下都会让那个
 * 控制器的职责描述变糊（ContentController 的注释只有"板块 / 标签 / 帖子"）。
 *
 * @author 刘
 */
@Tag(name = "站点统计", description = "站级汇总数字（板块 / 标签 / 提问）")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StatsController {

    private final SiteStatsService siteStatsService;

    @Operation(summary = "站点汇总统计",
            description = "游客可读；一次请求返回首页 / 问答 / 标签 / 板块页所需的全部计数，口径恒定、不随调用方筛选变化")
    @GetMapping("/stats")
    public Result<SiteStatsVO> overview() {
        return Result.success(siteStatsService.overview());
    }
}