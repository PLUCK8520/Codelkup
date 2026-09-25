package com.codelkup.module.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codelkup.module.content.entity.PostTag;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 帖子-标签关联 Mapper。
 *
 * @author 刘
 */
@Mapper
public interface PostTagMapper extends BaseMapper<PostTag> {

    /**
     * 物理删除某帖子的全部标签关联。
     *
     * <p>关联记录无审计价值，直接物理删除（本表也没有 deleted 字段）。
     * 编辑帖子时"先全删再重插"是最简单的幂等做法。
     */
    @Delete("DELETE FROM t_post_tag WHERE post_id = #{postId}")
    int deleteByPostId(@Param("postId") Long postId);
}
