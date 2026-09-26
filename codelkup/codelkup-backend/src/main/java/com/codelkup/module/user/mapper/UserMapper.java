package com.codelkup.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codelkup.module.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户 Mapper。
 *
 * @author 刘
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 按邮箱查询用户（含已逻辑删除的过滤由 MyBatis-Plus 自动处理）。
     *
     * <p>注册唯一性校验、登录凭证查找均依赖此方法。
     * 走 {@code uk_email} 唯一索引，无需额外索引。
     */
    @Select("SELECT * FROM t_user WHERE email = #{email} AND deleted = 0 LIMIT 1")
    User selectByEmail(@Param("email") String email);

    /**
     * 判断邮箱是否已被占用。
     *
     * <p>用 {@code COUNT} 而非查全字段：注册校验只需存在性，减少回表开销。
     */
    @Select("SELECT COUNT(*) FROM t_user WHERE email = #{email} AND deleted = 0")
    int countByEmail(@Param("email") String email);

    // ==================== 声望与等级（V0.3） ====================

    /**
     * 原子增加声望。
     *
     * <p>为什么不用"查出声望 → 加 delta → updateById"：并发下会丢更新
     * （两个点赞同时读到 10，各自写回 12，实际应为 14）。
     * SQL 自增由数据库保证原子性，且 {@code GREATEST(..., 0)} 夹住下界 ——
     * 取消点赞在声望很低时可能算出负数，负数声望没有意义。
     */
    @Update("UPDATE t_user SET reputation = GREATEST(COALESCE(reputation, 0) + #{delta}, 0) "
            + "WHERE id = #{userId}")
    int addReputation(@Param("userId") Long userId, @Param("delta") int delta);

    /** 读回自增后的声望 —— 等级必须基于数据库里的真实值推导，而不是本地猜的值 */
    @Select("SELECT reputation FROM t_user WHERE id = #{userId}")
    Integer selectReputation(@Param("userId") Long userId);

    /** 同步等级（纯派生字段，允许滞后一拍；下次结算会再修正） */
    @Update("UPDATE t_user SET level = #{level} WHERE id = #{userId}")
    int updateLevel(@Param("userId") Long userId, @Param("level") int level);
}
