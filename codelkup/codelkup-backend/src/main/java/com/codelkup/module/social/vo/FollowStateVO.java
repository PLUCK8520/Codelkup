package com.codelkup.module.social.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 某个用户的关注状态 + 关注关系计数。
 *
 * <p>把"我是否关注了 TA"和"TA 的粉丝/关注数"放在一个响应里：
 * 用户主页加载时需要同时展示这两项，分两个接口会让前端多一次往返，
 * 而它们本来就来自同一次查询上下文。
 *
 * @author 刘
 */
@Data
@Schema(description = "关注状态与计数")
public class FollowStateVO {

    /**
     * 我是否已关注 TA。
     *
     * <p>{@code null} 表示<b>未登录，无从判断</b>；{@code false} 表示登录了但没关注。
     * 两者对前端的意义完全不同（前者点击应跳登录，后者才是关注动作），
     * 所以必须用 {@code @JsonInclude(ALWAYS)} 保住这个 null —— 全局 non_null 策略
     * 会把它整个删掉。（S3 踩过同一个坑，这里是同一规矩的延续。）
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "当前登录用户是否已关注该用户（未登录为 null）")
    private Boolean following;

    @Schema(description = "该用户的粉丝数")
    private Integer followerCount;

    @Schema(description = "该用户关注的人数")
    private Integer followeeCount;

    public static FollowStateVO of(Boolean following, int followerCount, int followeeCount) {
        FollowStateVO vo = new FollowStateVO();
        vo.setFollowing(following);
        vo.setFollowerCount(followerCount);
        vo.setFolloweeCount(followeeCount);
        return vo;
    }
}
