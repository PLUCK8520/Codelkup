package com.codelkup.module.upload.controller;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.common.result.Result;
import com.codelkup.module.upload.service.ImageStorageService;
import com.codelkup.module.upload.vo.UploadResultVO;
import com.codelkup.security.CurrentUser;
import com.codelkup.security.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传接口。
 *
 * <p>当前只有"通用图片上传"（帖子正文配图用）。头像上传仍在
 * {@code POST /api/users/me/avatar} —— 它返回的是更新后的用户资料
 * （前端要立刻刷新头像），语义与"传一张图拿到 URL"不同，
 * 属于两件事，不合并。
 *
 * <h2>三道闸门（上传接口 = 成本即攻击面）</h2>
 * <ol>
 *   <li><b>限流</b>：按用户 30 次/小时（可配）。按用户而不是按 IP ——
 *       上传必然登录，按用户才能精确到人；按 IP 会让 NAT 后的正常用户互相误伤。</li>
 *   <li><b>配额</b>：单用户文件数上限（见 ImageStorageService）。</li>
 *   <li><b>内容校验</b>：扩展名白名单 + 魔数 + 尺寸上限。</li>
 * </ol>
 *
 * @author 刘
 */
@Slf4j
@Tag(name = "上传", description = "通用图片上传")
@RestController
@RequestMapping("/api/uploads")
@RequiredArgsConstructor
public class UploadController {

    private final ImageStorageService imageStorageService;
    private final RateLimitService rateLimitService;

    /** 上传限流：默认 30 次/小时/用户 */
    @Value("${codelkup.rate-limit.upload-per-hour:30}")
    private int uploadPerHour;

    @Operation(summary = "上传图片",
            description = "帖子正文配图。jpg/png/webp，≤5MB，需登录；按用户限流 + 配额限制，返回 {url}")
    @PostMapping("/images")
    public Result<UploadResultVO> uploadImage(@RequestParam("file") MultipartFile file,
                                              @CurrentUser(required = true) Long userId) {
        // 限流用 "u:{id}" 前缀，与 IP 维度的桶区分开，避免将来两种身份混在同一个 key 里
        if (!rateLimitService.tryAcquire("upload", "u:" + userId, uploadPerHour, 60)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }

        String url = imageStorageService.savePostImage(file, userId);
        log.debug("图片上传成功 | userId={} | url={}", userId, url);
        return Result.success(new UploadResultVO(url));
    }
}