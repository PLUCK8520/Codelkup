package com.codelkup.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codelkup.module.user.entity.User;
import com.codelkup.module.user.vo.RankingUserVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

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

    // ==================== 声望榜（V0.4） ====================

    /**
     * 声望榜 —— 一条只读查询，不落库、不加缓存。
     *
     * <p>三个筛选/排序条件都不是随手写的：
     * <ul>
     *   <li>{@code deleted = 0 AND status = 0}：注销与禁言/封禁账号不上榜。
     *       ⚠️ 手写 SQL **不会**自动带上 MyBatis-Plus 的逻辑删除条件，必须自己写。</li>
     *   <li>{@code reputation > 0}：库里 189 个账号只有 36 个声望大于 0。
     *       不筛的话榜尾会被一串 0 填满 —— 而"0 分并列第 20 名"的榜单没有信息量。</li>
     *   <li>{@code id ASC} 兜底排序：声望相同的人必须**每次返回同一个顺序**。
     *       只按 reputation 排，MySQL 对并列行的顺序不保证，同一个榜刷新两次就可能换位，
     *       看起来像"榜单坏了"。并列时用 id 定序，结果稳定且可复现。</li>
     * </ul>
     *
     * <p>只查榜单需要的 6 列（**不含 email / password**）：直接映射成
     * {@link RankingUserVO}，而不是"查整行实体再手工裁剪"——后者很容易漏字段。
     * 走 {@code idx_reputation}（V1 已建）的倒序扫描，取前 N 名不需要 filesort。
     */
    @Select("""
            SELECT id, nickname, avatar, level, reputation, last_active
            FROM t_user
            WHERE deleted = 0 AND status = 0 AND reputation > 0
            ORDER BY reputation DESC, id ASC
            LIMIT #{limit}
            """)
    List<RankingUserVO> selectTopByReputation(@Param("limit") int limit);
}