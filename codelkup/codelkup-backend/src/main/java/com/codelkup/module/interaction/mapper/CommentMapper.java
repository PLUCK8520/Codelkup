package com.codelkup.module.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codelkup.module.interaction.entity.Comment;
import com.codelkup.module.interaction.vo.CommentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 评论 Mapper。
 *
 * @author 刘
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    /**
     * 取某帖的全部评论（含作者信息），一次查询解决。
     *
     * <p>不分页、不递归：评论量在本项目量级下每帖几十条，一次取回再在
     * 应用层按 {@code parent_id} 分组拼树，比"递归查子评论"少几十次往返。
     * 如果将来单帖评论到万级，再改为「根评论分页 + 按需加载回复」。
     *
     * <p>按 {@code create_time ASC} 排：论坛评论是线性阅读顺序，
     * 最早的在上面才符合"对话"直觉（区别于楼中楼）。
     */
    @Select("""
            SELECT c.id, c.post_id, c.author_id, c.content, c.parent_id, c.reply_user_id,
                   c.like_count, c.is_accepted, c.create_time,
                   u.nickname AS author_nickname, u.avatar AS author_avatar,
                   ru.nickname AS reply_user_nickname
            FROM t_comment c
            LEFT JOIN t_user u  ON u.id = c.author_id
            LEFT JOIN t_user ru ON ru.id = c.reply_user_id
            WHERE c.post_id = #{postId} AND c.deleted = 0
            ORDER BY c.create_time ASC, c.id ASC
            """)
    List<CommentVO> selectByPostId(@Param("postId") Long postId);

    /**
     * 统计某帖的有效评论数。
     *
     * <p>用于计数重建脚本 —— Redis 与 MySQL 基线同时被它覆盖写入。
     * 计数时排除逻辑删除的评论。
     */
    @Select("SELECT COUNT(*) FROM t_comment WHERE post_id = #{postId} AND deleted = 0")
    int countByPostId(@Param("postId") Long postId);

    /** 评论点赞数 +1 / -1（MySQL 基线，原子自增避免丢失计数） */
    @Update("UPDATE t_comment SET like_count = GREATEST(like_count + #{delta}, 0) "
            + "WHERE id = #{commentId} AND deleted = 0")
    int addLikeCount(@Param("commentId") Long commentId, @Param("delta") int delta);

    /** 有哪些帖子存在评论 —— 供全量重建脚本枚举目标 */
    @Select("SELECT DISTINCT post_id FROM t_comment WHERE deleted = 0")
    List<Long> selectDistinctPostIds();
}
