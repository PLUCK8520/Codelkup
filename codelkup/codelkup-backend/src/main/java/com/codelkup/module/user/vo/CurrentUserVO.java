package com.codelkup.module.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本人资料视图 —— 比 {@link UserVO} 多出邮箱字段。
 *
 * <p>仅用于"查看/编辑自己的资料"场景。公开主页接口用 {@link UserVO}，
 * 不暴露邮箱。这个拆分是为了避免"一个 VO 走天下"导致邮箱被意外公开。
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
