package com.codelkup.module.moderation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codelkup.module.moderation.entity.Report;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 举报 Mapper。
 *
 * <p>队列的分页 / 状态过滤 / 倒序全部由 {@code BaseMapper.selectPage} + Wrapper 完成，
 * 唯一需要手写的是"这批目标各被举报了几次"—— 逐条 {@code COUNT} 会变成 N+1，
 * 一条 {@code GROUP BY} 就能出全页的数。
 *
 * @author 刘
 */
@Mapper
public interface ReportMapper extends BaseMapper<Report> {

    /**
     * 统计一批目标各自被举报的总次数（含已处理的举报）。
     *
     * <p>返回 {@code List<Map>} 而非专用 VO：键就是 SQL 里的列标签
     * （{@code target_id} / {@code cnt}），调用方按字面量取值 ——
     * 不会踩"别名与驼峰约定不一致导致静默 null"的坑
     * （本项目已踩过一次，见 t_post 的 @Results 教训）。
     *
     * <p>为什么不限定 {@code status}：审核员需要看到"这条内容一共被几个人投诉过"，
     * 只看待处理数会漏掉"被多次投诉又被多次驳回"的信号。
     *
     * @param targetType 1帖子 2评论
     * @param ids        目标 ID 列表（调用方保证非空）
     */
    @Select("<script>" +
            "SELECT target_id, COUNT(*) AS cnt FROM t_report " +
            "WHERE target_type = #{targetType} AND target_id IN " +
            "<foreach collection='ids' item='tid' open='(' separator=',' close=')'>#{tid}</foreach> " +
            "GROUP BY target_id" +
            "</script>")
    List<Map<String, Object>> countByTargets(@Param("targetType") int targetType,
                                             @Param("ids") List<Long> ids);
}