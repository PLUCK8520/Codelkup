package com.codelkup.module.content.controller;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.common.result.Result;
import com.codelkup.module.content.dto.DraftSaveRequest;
import com.codelkup.module.content.service.DraftService;
import com.codelkup.module.content.vo.DraftSummaryVO;
import com.codelkup.module.content.vo.DraftVO;
import com.codelkup.security.CurrentUser;
import com.codelkup.security.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 服务端草稿箱（M4-15）。
 *
 * <h2>为什么要有服务端草稿（M2-10 的 localStorage 不够吗）</h2>
 * 本地草稿解决的是"同一台设备防误关"；服务端草稿补的是本地做不到的两件事：
 * 换设备/换浏览器后接着写、清掉浏览器数据后内容还在。
 * 两者是<b>叠层</b>而不是替代：localStorage 仍是离线兜底（也是唯一在未登录时
 * 可用的路径），服务端行是跨设备副本。
 *
 * <h2>接口形态的三个决定</h2>
 * <ol>
 *   <li><b>按 scope 而不是按 id 寻址</b>：前端 useDraft 的草稿身份本来就是
 *       {@code new} / {@code edit:<postId>} 这个字符串，服务端沿用同一身份，
 *       编辑器进来只需一次 GET 就能取到"我这台设备正在编辑的那份"。
 *       用自增 id 会强迫前端先拉列表再挑，多一次请求还多一层对账逻辑。</li>
 *   <li><b>保存是 PUT + upsert</b>：自动保存会周期性重复提交同一份草稿，
 *       "不存在则建、存在则覆盖"是唯一不需要前端判断"要不要先建"的语义。</li>
 *   <li><b>GET 单条在没有草稿时返回 data=null</b>：编辑器首次进入必然没有草稿，
 *       这是常态不是错误；用 404 会让前端把"第一次写"当异常处理。</li>
 * </ol>
 *
 * <p>权限：全部接口都要求登录，且 Service 层所有查询都带 {@code user_id} ——
 * 草稿只有"本人可见"这一条权限规则（SecurityConfig 里未列入白名单，
 * 自动落到 {@code anyRequest().authenticated()}）。
 *
 * @author 刘
 */
@Slf4j
@Validated
@Tag(name = "草稿", description = "服务端草稿箱（编辑器跨设备续写）")
@RestController
@RequestMapping("/api/drafts")
@RequiredArgsConstructor
public class DraftController {

    /** scope 合法格式（与前端 useDraft 的 scope 命名、DraftSaveRequest 的校验一致） */
    private static final String SCOPE_PATTERN = "new|edit:\\d+";

    private final DraftService draftService;
    private final RateLimitService rateLimitService;

    /** 草稿保存限流：默认 240 次/小时/用户 */
    @Value("${codelkup.rate-limit.draft-per-hour:240}")
    private int draftPerHour;

    @Operation(summary = "草稿箱列表",
            description = "需登录；当前用户的全部草稿摘要（不含正文，带纯文本预览），按更新时间倒序")
    @GetMapping
    public Result<List<DraftSummaryVO>> list(@CurrentUser(required = true) Long userId) {
        return Result.success(draftService.listSummaries(userId));
    }

    @Operation(summary = "读取一条草稿",
            description = "需登录；按 scope（new / edit:<postId>）取完整草稿，没有草稿时 data 为 null")
    @GetMapping("/{scope}")
    public Result<DraftVO> get(
            @PathVariable @Pattern(regexp = SCOPE_PATTERN, message = "草稿归属格式不正确") String scope,
            @CurrentUser(required = true) Long userId) {
        return Result.success(draftService.get(userId, scope));
    }

    /**
     * 保存草稿（upsert）。
     *
     * <p>限流按<b>用户</b>计（与发帖/评论/上传同一口径）：自动保存是登录后的行为，
     * 按用户计才能精确到人，也不会因 NAT 后的其他人互相误伤。
     * 前端以 15 秒为节流窗口自动保存，240 次/小时留有约 1/3 余量。
     * 超限时前端<b>静默降级</b>为只写本地草稿 —— 服务器忙不该让编辑器不可用。
     */
    @Operation(summary = "保存草稿",
            description = "需登录；同 scope 覆盖保存（upsert），返回库中真实落下的一条（含服务端保存时间）；按用户限流（默认 240 次/小时）")
    @PutMapping
    public Result<DraftVO> save(@Valid @RequestBody DraftSaveRequest body,
                                @CurrentUser(required = true) Long userId) {
        if (!rateLimitService.tryAcquire("draft", "u:" + userId, draftPerHour, 60)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
        return Result.success(draftService.save(userId, body));
    }

    @Operation(summary = "删除草稿",
            description = "需登录；按 scope 删除，幂等（草稿不存在也返回成功）")
    @DeleteMapping("/{scope}")
    public Result<Void> delete(
            @PathVariable @Pattern(regexp = SCOPE_PATTERN, message = "草稿归属格式不正确") String scope,
            @CurrentUser(required = true) Long userId) {
        draftService.delete(userId, scope);
        return Result.success();
    }
}