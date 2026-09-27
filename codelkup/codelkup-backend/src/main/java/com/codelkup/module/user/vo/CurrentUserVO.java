package com.codelkup.module.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本人资料视图 —— 独立于公开的 {@link UserVO}：多出邮箱与 role。
 *
 * <p>这两类字段都只在"本人"场景返回：{@code role} 用于前端管理入口（L-2 修复：
 * 公开主页不再暴露角色，避免管理员被遍历枚举）；{@code email} 属于隐私。
 * "一个 VO 走天下"正是这两类泄露的共同来源，所以保持两个独立类型。
 *
 * @author 刘
 */
@Data
@Schema(description = "本人资料（含邮箱）")
public class CurrentUserVO {

    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "邮箱（仅本人可见）")
    private String email;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像URL")
    private String avatar;

    @Schema(description = "个人简介")
    private String bio;

    @Schema(description = "技术标签")
    private String techTags;

    @Schema(description = "角色：0普通 1版主 2管理员")
    private Integer role;

    @Schema(description = "账号状态：0正常 1禁言 2封禁")
    private Integer status;

    @Schema(description = "声望值")
    private Integer reputation;

    @Schema(description = "等级")
    private Integer level;

    @Schema(description = "注册时间")
    private LocalDateTime createTime;

    @Schema(description = "最后活跃时间")
    private LocalDateTime lastActive;
}
