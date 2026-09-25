package com.codelkup.module.content.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 作者简要信息。
 *
 * <p>列表页每行都要展示作者，但只需要昵称和头像。
 * 单独定义极简 VO 而非复用 {@code UserVO} —— 后者含 bio / techTags / reputation 等
 * 列表页用不到的字段，一页 20 条会白白多传几十个字段。
 *
 * <p>⚠️ 同样不含 email：公开列表绝不能泄露邮箱。
 *
 * @author 刘
 */
@Data
@Schema(description = "作者简要信息")
public class UserBriefVO {

    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像URL")
    private String avatar;
}
