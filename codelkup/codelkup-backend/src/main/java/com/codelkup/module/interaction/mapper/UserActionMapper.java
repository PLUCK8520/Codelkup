package com.codelkup.module.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codelkup.module.interaction.entity.UserAction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 互动记录 Mapper。
 *
 * <p>这里<b>只有查询</b>，没有自定义写入 —— 写入一律走
 * {@code BaseMapper.insert()}，好让 {@code uk_user_target_action}
 * 唯一索引抛出的 {@code DuplicateKeyException} 成为唯一的冲突信号。
 * 如果这里再包一层"先查再插"，反而把幂等保证从数据库挪回了有竞态的代码里。
 *
 * @author 刘
 */
@Mapper
public interface UserActionMapper extends BaseMapper<UserAction> {

    /**
     * 统计某目标的点赞/收藏数 —— <b>计数重建脚本的核心查询</b>。
     *
     * <p>命中 {@code idx_target(target_type, target_id, action_type)}，
     * 是覆盖索引扫描，不需要回表。
     */
    @Select("SELECT COUNT(*) FROM t_user_action "
            + "WHERE target_type = #{targetType} AND target_id = #{targetId} "
            + "AND action_type = #{actionType}")
    int countByTarget(@Param("targetType") int targetType,
                      @Param("targetId") Long targetId,
                      @Param("actionType") int actionType);

    /**
     * 批量统计一批帖子的点赞数 —— 避免重建脚本逐帖查询造成的 N+1。
     *
     * <p>返回 {@code [{target_id, cnt}]}，调用方自行组装成 Map。
     */
    @Select("""
            <script>
            SELECT target_id AS targetId, COUNT(*) AS cnt
            FROM t_user_action
            WHERE target_type = 1 AND action_type = #{actionType}
              AND target_id IN
              <foreach collection='postIds' item='pid' open='(' separator=',' close=')'>#{pid}</foreach>
            GROUP BY target_id
            </script>
            """)
    List<Map<String, Object>> countByTargets(@Param("postIds") List<Long> postIds,
                                            @Param("actionType") int actionType);

    /**
     * 判断某用户对某目标是否有某动作。
     *
     * <p>详情页要返回 {@code liked} / {@code collected}，列表页要批量标记 ——
     * 单条查询走这里，批量走 {@link #selectActedTargetIds}。
     */
    @Select("SELECT COUNT(*) FROM t_user_action "
            + "WHERE user_id = #{userId} AND target_type = #{targetType} "
            + "AND target_id = #{targetId} AND action_type = #{actionType}")
    int exists(@Param("userId") Long userId,
               @Param("targetType") int targetType,
               @Param("targetId") Long targetId,
               @Param("actionType") int actionType);

    /**
     * 批量取"某用户在一批目标里已执行某动作的 targetId 集合"。
     *
     * <p>列表页要给每条帖子标"我是否点过赞"，逐条查会 N+1，
     * 一次 IN 查询 + 转 Set 即可。
     */
    @Select("""
            <script>
            SELECT target_id FROM t_user_action
            WHERE user_id = #{userId} AND target_type = #{targetType}
              AND action_type = #{actionType}
              AND target_id IN
              <foreach collection='targetIds' item='tid' open='(' separator=',' close=')'>#{tid}</foreach>
            </script>
            """)
    List<Long> selectActedTargetIds(@Param("userId") Long userId,
                                    @Param("targetType") int targetType,
                                    @Param("actionType") int actionType,
                                    @Param("targetIds") List<Long> targetIds);

    /** 某用户点赞过的帖子 ID（分页在 Service 层用 PageHelper 或手动切分，MVP 先全量） */
    @Select("SELECT target_id FROM t_user_action "
            + "WHERE user_id = #{userId} AND target_type = 1 AND action_type = 1 "
            + "ORDER BY create_time DESC")
    List<Long> selectLikedPostIds(@Param("userId") Long userId);
}
