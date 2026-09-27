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

    // ⚠️ 此处不得添加 role 字段（L-2 修复，安全审计）：角色属于"谁能操作"的信息，
    //    只在本人接口的 CurrentUserVO 里返回。公开主页若带上 role=2，遍历 id 就能
    //    枚举出全部管理员账号，为撞库 / 社工提供靶子 —— 与下方 email 同一类
    //    的"类型层防泄露"（不是记得 setRole(null)，而是压根没有这个字段）。

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
    // 只出现在公开主页（GET /api/users/{id}）—— 个人主页（含自己）都走这个接口；
    // 顶栏身份展示走独立的 CurrentUserVO，不含这组统计。

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
