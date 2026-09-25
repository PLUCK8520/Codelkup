package com.codelkup.module.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户信息视图对象。
 *
 * <p>⚠️ <b>安全要点</b>：本类**刻意不包含 password 字段**。
 * 对外返回的用户数据一律经此 VO，绝不直接返回 {@code User} 实体，
 * 从类型层面杜绝密码泄露（比"记得 setPassword(null)"可靠）。
 *
 * @author 刘
 */
@Data
@Schema(description = "用户信息")
public class UserVO {

    @Schema(description = "用户ID")
    private Long id;

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

    @Schema(description = "声望值")
    private Integer reputation;

    @Schema(description = "等级")
    private Integer level;

    @Schema(description = "注册时间")
    private LocalDateTime createTime;

    @Schema(description = "最后活跃时间")
    private LocalDateTime lastActive;

    // ⚠️ 注意：此处不得添加 email 字段。
    //    邮箱属于个人隐私，仅在"查看自己的资料"场景下通过单独接口返回，
    //    避免在公开主页接口中泄露他人邮箱。
}
