package com.codelkup.module.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codelkup.module.content.entity.Tag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 标签 Mapper。
 *
 * @author 刘
 */
@Mapper
public interface TagMapper extends BaseMapper<Tag> {

    /**
     * 按名称批量查询（大小写不敏感，依赖列的 unicode_ci 排序规则）。
     *
     * <p>发帖时用于「已存在的标签直接复用，不存在的新建」，避免逐条查询。
     */
    @Select("<script>" +
            "SELECT * FROM t_tag WHERE name IN " +
            "<foreach collection='names' item='n' open='(' separator=',' close=')'>#{n}</foreach>" +
            "</script>")
    List<Tag> selectByNames(@Param("names") List<String> names);

    /** 热门标签榜（按引用次数倒序） */
    @Select("SELECT * FROM t_tag WHERE use_count > 0 ORDER BY use_count DESC, id ASC LIMIT #{limit}")
    List<Tag> selectHot(@Param("limit") int limit);

    /** 引用次数 +delta，同 BoardMapper.addPostCount 的原子性考量 */
    @Update("UPDATE t_tag SET use_count = GREATEST(use_count + #{delta}, 0) WHERE id = #{tagId}")
    int addUseCount(@Param("tagId") Long tagId, @Param("delta") int delta);

    /**
     * 标签总数（对应 /api/stats）。
     *
     * <p>与 {@link #selectHot(int)} 区分：热门榜有 LIMIT，长度不是全量；
     * 页面要显示"共 X 个标签"时用本方法，否则上限一旦调小，
     * 数字会跟着"变少"（把"只展示了 50 个"说成"一共就 50 个"）。
     */
    @Select("SELECT COUNT(*) FROM t_tag")
    int countAll();
}
