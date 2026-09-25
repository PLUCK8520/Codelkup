package com.codelkup.module.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关注关系。
 *
 * <p>与 {@code t_user_action} 共享同一个设计原则：
 * <b>幂等由唯一索引保证，而不是业务代码"先查再写"</b>。
 * 并发下"查到不存在 → 插入"之间存在竞态窗口，两个请求可能都通过检查然后都插入；
 * 唯一索引是原子操作，不存在这个窗口。代价是插入可能抛
 * {@code DuplicateKeyException}，捕获后按"已关注"幂等返回即可。
 *
 * @author 刘
 */
@Data
@TableName("t_user_follow")
@Schema(description = "关注关系")
public class UserFollow {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "关注者ID（动作发起方）")
    private Long followerId;

    @Schema(description = "被关注者ID（动作对象）")
    private Long followeeId;

    @Schema(description = "关注时间")
    private LocalDateTime createTime;
}
