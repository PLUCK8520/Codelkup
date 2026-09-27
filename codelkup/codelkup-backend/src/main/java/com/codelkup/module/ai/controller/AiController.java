package com.codelkup.module.ai.controller;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.common.result.Result;
import com.codelkup.module.ai.dto.AiAskRequest;
import com.codelkup.module.ai.dto.SimilarQuestionRequest;
import com.codelkup.module.ai.service.AiAnswerService;
import com.codelkup.module.ai.service.AiSummaryService;
import com.codelkup.module.ai.service.SimilarQuestionService;
import com.codelkup.module.ai.vo.AiAnswerVO;
import com.codelkup.module.ai.vo.AiSummaryVO;
import com.codelkup.module.ai.vo.SimilarQuestionVO;
import com.codelkup.security.CurrentUser;
import com.codelkup.security.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 能力接口（M3-5 起）。
 *
 * <h2>为什么是 POST 而不是 GET</h2>
 * 输入是"用户正在写的标题"——它属于用户内容：
 * <ol>
 *   <li>放 query 会落进 nginx 访问日志与浏览器历史（与 SSE 用 ticket 的判据同源）；
 *   <li>这个接口是边写边问的高频调用，GET 还容易被中间层按 URL 缓存，
 *       让"改一个字却拿到上一版结果"变成难查的怪现象。</li>
 * </ol>
 *
 * <h2>限流口径</h2>
 * 按<b>用户</b>计（提示必然发生在登录后的编辑器里）。默认 300 次/小时：
 * 按去抖后的输入节拍估，一次编辑会话约 20~50 次调用（标题逐字 + 停顿重算），
 * 300 给足余量，同时把滥用上限压住。
 *
 * @author 刘
 */
@Slf4j
@Validated
@Tag(name = "AI", description = "AI 能力（相似问题提示；后续：TL;DR / RAG 问答）")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final SimilarQuestionService similarQuestionService;
    private final AiAnswerService aiAnswerService;
    private final AiSummaryService aiSummaryService;
    private final RateLimitService rateLimitService;

    /** 相似问题提示限流：默认 300 次/小时/用户 */
    @Value("${codelkup.rate-limit.similar-per-hour:300}")
    private int similarPerHour;

    /**
     * AI 生成类调用限流：默认 10 次/小时/用户（ALIGNMENT 口径）。
     *
     * <p>⚠️ <b>问答与 TL;DR 共用这一个桶</b>：两者都真花钱，各给一份额度
     * 等于把每人的上限悄悄翻倍。"AI 生成类调用按用户 10/小时"才是成本闸门的原意。
     * 三重闸门的第一道就是它 —— 第二道是结果缓存（同一问题/同一帖不重复计费），
     * 第三道是全站日预算。
     */
    @Value("${codelkup.rate-limit.ai-per-hour:10}")
    private int aiPerHour;

    @Operation(summary = "相似问题提示",
            description = "需登录；输入正在写的标题，返回站内最像的 3 条讨论（复用检索 + 标题前缀重排，"
                    + "不调用 LLM，毫秒级返回）。不足 2 字返回空列表；按用户限流（默认 300 次/小时）")
    @PostMapping("/similar-questions")
    public Result<List<SimilarQuestionVO>> similarQuestions(@Valid @RequestBody SimilarQuestionRequest body,
                                                            @CurrentUser(required = true) Long userId) {
        if (!rateLimitService.tryAcquire("similar", "u:" + userId, similarPerHour, 60)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
        return Result.success(similarQuestionService.find(body.getTitle(), userId));
    }

    @Operation(summary = "RAG 问答",
            description = "需登录；检索站内 top-k → LLM 依据片段作答并标注引用；未配置密钥 / 超预算 / 上游失败时"
                    + "降级为「仅检索结果 + 可读提示」（code 仍为 0，前端据 degraded 字段展示）。"
                    + "同一问题 6 小时内命中缓存不重复计费；按用户限流（默认 10 次/小时）+ 全站日预算")
    @PostMapping("/ask")
    public Result<AiAnswerVO> ask(@Valid @RequestBody AiAskRequest body,
                                  @CurrentUser(required = true) Long userId) {
        if (!rateLimitService.tryAcquire("ai", "u:" + userId, aiPerHour, 60)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "AI 调用次数已达上限，请 1 小时后再试");
        }
        return Result.success(aiAnswerService.ask(body.getQuestion(), userId));
    }

    @Operation(summary = "长帖 TL;DR",
            description = "需登录；把帖子正文剥离 Markdown 并截断后交给 LLM 生成 3~5 条要点。"
                    + "按正文哈希缓存（帖子被编辑则自动失效）；与问答共用用户限流与全站日预算；"
                    + "未配置密钥 / 超预算 / 上游失败时 degraded=true 且 summary 为 null（前端据此隐藏或提示）")
    @PostMapping("/summarize/{postId}")
    public Result<AiSummaryVO> summarize(@PathVariable Long postId,
                                         @CurrentUser(required = true) Long userId) {
        if (!rateLimitService.tryAcquire("ai", "u:" + userId, aiPerHour, 60)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "AI 调用次数已达上限，请 1 小时后再试");
        }
        return Result.success(aiSummaryService.summarize(postId, userId));
    }
}