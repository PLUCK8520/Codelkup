package com.codelkup.module.social.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 关注/粉丝列表里的一个用户。
 *
 * @author 刘
 */
@Data
@Schema(description = "关注/粉丝列表项")
public class FollowUserVO {

    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像URL")
    private String avatar;

    @Schema(description = "个人简介")
    private String bio;

    /**
     * 当前登录用户是否已关注 TA。
     *
     * <p>⚠️ {@code @JsonInclude(ALWAYS)} 不能省 —— 沿用 S3 定下的规矩：
     * 全局配置是 {@code default-property-inclusion: non_null}，
     * 语义化的 null 字段（null=未登录"不知道" / false=登录了"没关注"）
     * 必须显式保留，否则游客拿到的列表项里根本没有 following 这个键，
     * 前端无法区分"键不存在"和"值为 null"。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "当前登录用户是否已关注（未登录为 null）")
    private Boolean following;
}
