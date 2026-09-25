package com.codelkup.module.user.controller;

import com.codelkup.common.result.Result;
import com.codelkup.module.user.dto.ProfileUpdateRequest;
import com.codelkup.module.user.service.FileStorageService;
import com.codelkup.module.user.service.UserService;
import com.codelkup.module.user.vo.CurrentUserVO;
import com.codelkup.module.user.vo.UserVO;
import com.codelkup.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final FileStorageService fileStorageService;

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

    @Operation(summary = "上传头像", description = "jpg/png/webp，≤2MB。校验扩展名白名单 + 文件头魔数")
    @PostMapping("/me/avatar")
    public Result<CurrentUserVO> uploadAvatar(@RequestParam("file") MultipartFile file,
                                              @CurrentUser(required = true) Long userId) {
        String url = fileStorageService.saveAvatar(file, userId);
        return Result.success(userService.updateAvatar(userId, url));
    }
}
