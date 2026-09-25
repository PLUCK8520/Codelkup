package com.codelkup.module.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codelkup.module.content.entity.Board;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 板块 Mapper。
 *
 * @author 刘
 */
@Mapper
public interface BoardMapper extends BaseMapper<Board> {

    /** 正常状态的板块列表，按 sort_order 升序 */
    @Select("SELECT * FROM t_board WHERE status = 0 ORDER BY sort_order ASC, id ASC")
    List<Board> selectEnabled();

    /**
     * 帖子数 +delta。
     *
     * <p>用 {@code post_count = post_count + delta} 而非"先查再写"，
     * 避免并发下的丢失更新（读-改-写不是原子的）。
     * 并且用 {@code GREATEST(..., 0)} 兜底，防止计数被减成负数。
     */
    @Update("UPDATE t_board SET post_count = GREATEST(post_count + #{delta}, 0) WHERE id = #{boardId}")
    int addPostCount(@Param("boardId") Long boardId, @Param("delta") int delta);
}
