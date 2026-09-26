package com.codelkup.module.user.controller;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.common.result.Result;
import com.codelkup.module.upload.service.ImageStorageService;
import com.codelkup.module.user.dto.PasswordChangeRequest;
import com.codelkup.module.user.dto.ProfileUpdateRequest;
import com.codelkup.module.user.service.UserService;
import com.codelkup.module.user.vo.CurrentUserVO;
import com.codelkup.module.user.vo.UserVO;
import com.codelkup.security.CurrentUser;
import com.codelkup.security.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户资料接口。
 *
 * <p>⚠️ <b>隐私边界</b>：
 * <ul>
 *   <li>{@code GET /api/users/{id}} —— 公开接口，返回 {@link UserVO}，<b>不含邮箱</b>。
 *       任何人（含未登录游客）可访问他人主页。</li>
 *   <li>{@code GET /api/users/me} —— 需登录，返回 {@link CurrentUserVO}，<b>含邮箱</b>。
 *       邮箱只对本人可见，通过独立的 VO 类型在编译期隔离。</li>
 * </ul>
 *
 * @author 刘
 */
@Slf4j
@Tag(name = "用户", description = "用户主页与个人资料")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final ImageStorageService imageStorageService;
    private final RateLimitService rateLimitService;

    /** 上传限流（与上传模块同一口径）：默认 30 次/小时/用户 */
    @Value("${codelkup.rate-limit.upload-per-hour:30}")
    private int uploadPerHour;

    @Operation(summary = "查看他人主页", description = "公开接口，返回信息不含邮箱")
    @GetMapping("/{id}")
    public Result<UserVO> getProfile(@PathVariable Long id) {
        return Result.success(userService.getPublicProfile(id));
    }

    @Operation(summary = "查看本人资料", description = "需登录，返回信息含邮箱")
    @GetMapping("/me")
    public Result<CurrentUserVO> getMe(@CurrentUser(required = true) Long userId) {
        return Result.success(userService.getCurrentUserProfile(userId));
    }

    @Operation(summary = "修改本人资料", description = "字段可选；不支持改邮箱与密码（需走独立流程）")
    @PutMapping("/me")
    public Result<CurrentUserVO> updateMe(@Valid @RequestBody ProfileUpdateRequest body,
                                          @CurrentUser(required = true) Long userId) {
        return Result.success(userService.updateProfile(userId, body));
    }

    /**
     * 上传头像。
     *
     * <p>顺序刻意是"先查旧值 → 存新文件 → 更新资料 → 删旧文件"：
     * 任何一步失败，用户的头像字段都指向一个<b>存在</b>的文件。
     * 反过来（先删旧文件再存新的）会在上传失败时把用户头像弄丢，
     * 那是不可恢复的体验事故。
     *
     * <p>删除只对"本服务托管的文件"生效（URL 前缀 + 路径必须在存储根内），
     * 历史遗留的外站头像 URL 会被安全跳过。
     */
    @Operation(summary = "上传头像", description = "jpg/png/webp，≤2MB。校验扩展名白名单 + 文件头魔数；替换后清理旧文件；与正文配图共用上传限流桶")
    @PostMapping("/me/avatar")
    public Result<CurrentUserVO> uploadAvatar(@RequestParam("file") MultipartFile file,
                                              @CurrentUser(required = true) Long userId) {
        // 与 /api/uploads/images 共用 upload 桶：两者都是"往磁盘写文件"，
        // 合起来计一个总量才能真实约束磁盘增长（分开计等于给了两倍额度）
        if (!rateLimitService.tryAcquire("upload", "u:" + userId, uploadPerHour, 60)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }

        String oldAvatar = userService.getCurrentUserProfile(userId).getAvatar();

        String url = imageStorageService.saveAvatar(file, userId);
        CurrentUserVO updated = userService.updateAvatar(userId, url);

        imageStorageService.deleteManaged(oldAvatar);
        return Result.success(updated);
    }

    @Operation(summary = "修改密码",
            description = "验原密码 → 换新密码 → 清 refresh 白名单并拉黑当前 accessToken（需重新登录）")
    @PutMapping("/me/password")
    public Result<Void> changePassword(@Valid @RequestBody PasswordChangeRequest body,
                                       @CurrentUser(required = true) Long userId,
                                       @RequestHeader(value = "Authorization", required = false) String authorization) {
        // 把当前 accessToken 一并交给 Service：改密后它必须立刻失效（详见 UserServiceImpl 的说明）
        userService.changePassword(userId, body, extractBearer(authorization));
        return Result.success();
    }

    // ==================== 私有工具 ====================

    /** 从 "Bearer xxx" 中剥离前缀；格式不对返回 null（与 AuthController.logout 同一约定） */
    private String extractBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring(7).trim();
    }
}
