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

    // ==================== 提问统计（只读，V0.3 / M2-14） ====================
    //
    // 只出现在公开主页（GET /api/users/{id}）。CurrentUserVO 继承本类但不会填这几个
    // 字段（它服务的是顶栏身份展示，不需要这些数字），因此"查看自己"时它们为 null ——
    // 前端对 null 显示「—」，而不是 0，避免把"没数据"和"真的是 0"混为一谈。

    @Schema(description = "提问数（已发布的提问帖）")
    private Integer askedCount;

    @Schema(description = "已采纳数")
    private Integer acceptedCount;

    /** 采纳率 = 已采纳数 / 提问数（0~1 四位小数）；一次都没提问过时为 null */
    @Schema(description = "采纳率（0~1）；未提问过为 null", example = "0.75")
    private Double acceptRate;

    // ⚠️ 注意：此处不得添加 email 字段。
    //    邮箱属于个人隐私，仅在"查看自己的资料"场景下通过单独接口返回，
    //    避免在公开主页接口中泄露他人邮箱。
}
