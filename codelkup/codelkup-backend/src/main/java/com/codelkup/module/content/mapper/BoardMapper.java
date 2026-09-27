package com.codelkup.module.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codelkup.module.content.entity.Board;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

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

    /**
     * 正常板块数 + 帖子数总和（对应 /api/stats）。
     *
     * <p>{@code post_total} 的口径与板块卡片上各 {@code post_count} 之和完全一致 ——
     * 页面里"共 N 篇讨论"与卡片数字必须是同一套账，否则用户把卡片加一遍就对不上。
     *
     * <p>一条 SQL 出两列：两次查询之间若有板块上下线，会出现"板块数变了、总数没变"
     * 的跨时刻快照（与 countQuestionStats 的考量一致）。
     */
    @Select("SELECT COUNT(*) AS board_count, COALESCE(SUM(post_count), 0) AS post_total "
            + "FROM t_board WHERE status = 0")
    Map<String, Object> countEnabledStats();
}
