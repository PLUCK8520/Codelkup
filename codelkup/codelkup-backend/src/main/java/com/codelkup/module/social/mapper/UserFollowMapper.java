package com.codelkup.module.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.codelkup.module.social.entity.UserFollow;
import com.codelkup.module.social.vo.FollowUserVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;

import java.util.List;

/**
 * 关注关系 Mapper。
 *
 * <p>与 {@code UserActionMapper} 一样，<b>不提供自定义的插入方法</b> ——
 * 写入统一走 {@code BaseMapper.insert}，让唯一索引成为唯一的冲突信号源。
 * 如果这里再包一层"先查再插"，就等于把已经设计好的原子保障绕开了。
 *
 * @author 刘
 */
@Mapper
public interface UserFollowMapper extends BaseMapper<UserFollow> {

    /** 是否已关注 */
    @Select("SELECT COUNT(*) FROM t_user_follow WHERE follower_id = #{followerId} AND followee_id = #{followeeId}")
    int exists(@Param("followerId") Long followerId, @Param("followeeId") Long followeeId);

    /**
     * 粉丝数（谁关注了我）。
     *
     * <p>实时 {@code COUNT(*)} 而非冗余计数列 —— 本项目的关注关系是<b>千级</b>，
     * 且 {@code idx_followee(followee_id, create_time)} 的等值前缀正好覆盖这个查询。
     * 再引入一套冗余计数，就得连带引入 t_user_action 那样的"重建"问题，
     * 收益为零而复杂度翻倍。这是有意识的取舍，不是偷懒。
     */
    @Select("SELECT COUNT(*) FROM t_user_follow f JOIN t_user u ON u.id = f.follower_id "
            + "WHERE f.followee_id = #{userId} AND u.deleted = 0")
    int countFollowers(@Param("userId") Long userId);

    /** 关注数（我关注了多少人） */
    @Select("SELECT COUNT(*) FROM t_user_follow f JOIN t_user u ON u.id = f.followee_id "
            + "WHERE f.follower_id = #{userId} AND u.deleted = 0")
    int countFollowees(@Param("userId") Long userId);

    /**
     * 我关注的所有人 ID —— 关注流（拉模式）的第一步。
     *
     * <p>⚠️ 只返回 ID 而非完整用户对象：下一步查询要把它放进
     * {@code WHERE author_id IN (...)}。取整行再在内存里映射是白白的浪费。
     */
    @Select("SELECT followee_id FROM t_user_follow f JOIN t_user u ON u.id = f.followee_id "
            + "WHERE f.follower_id = #{userId} AND u.deleted = 0")
    List<Long> selectFolloweeIds(@Param("userId") Long userId);

    /**
     * 批量判断"我给这批人里的谁点过关注"。
     *
     * <p>用于粉丝/关注列表：一页 20 个用户，若逐个查会变成 20 次往返（N+1）。
     * 一次 IN 查询取回我已关注的 ID 集合，再在内存里 {@code contains} 判断。
     */
    @Select("""
            <script>
            SELECT followee_id FROM t_user_follow
            WHERE follower_id = #{followerId}
              AND followee_id IN
              <foreach collection='targetIds' item='tid' open='(' separator=',' close=')'>#{tid}</foreach>
            </script>
            """)
    List<Long> selectFollowedAmong(@Param("followerId") Long followerId,
                                   @Param("targetIds") List<Long> targetIds);

    /**
     * 粉丝列表（谁关注了我）。
     *
     * <p>{@code u.deleted = 0} 必须手写：MyBatis-Plus 的逻辑删除只对
     * {@code BaseMapper} 生成的方法自动生效，自定义 JOIN SQL 不享受该待遇。
     */
    @Select("""
            SELECT u.id, u.nickname, u.avatar, u.bio
            FROM t_user_follow f
            JOIN t_user u ON u.id = f.follower_id
            WHERE f.followee_id = #{userId} AND u.deleted = 0
            ORDER BY f.create_time DESC, f.id DESC
            """)
    IPage<FollowUserVO> selectFollowers(IPage<FollowUserVO> page, @Param("userId") Long userId);

    /** 关注列表（我关注了谁） */
    @Select("""
            SELECT u.id, u.nickname, u.avatar, u.bio
            FROM t_user_follow f
            JOIN t_user u ON u.id = f.followee_id
            WHERE f.follower_id = #{userId} AND u.deleted = 0
            ORDER BY f.create_time DESC, f.id DESC
            """)
    IPage<FollowUserVO> selectFollowees(IPage<FollowUserVO> page, @Param("userId") Long userId);

    /**
     * 取关。
     *
     * <p>用 DELETE 而非逻辑删除：关注关系没有"历史价值"，
     * 留一行 {@code deleted=1} 只会让每次 COUNT 和 EXISTS 都多一个条件。
     * 取消关注语义上就是"这段关系不存在了"，物理删除最贴切。
     */
    @Delete("DELETE FROM t_user_follow WHERE follower_id = #{followerId} AND followee_id = #{followeeId}")
    int deleteFollow(@Param("followerId") Long followerId, @Param("followeeId") Long followeeId);
}
