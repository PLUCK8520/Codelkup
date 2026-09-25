package com.codelkup.module.content.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 帖子-标签关联实体，对应表 {@code t_post_tag}。
 *
 * <p>本表<b>无独立主键之外的业务字段</b>，也不做逻辑删除 ——
 * 取消标签关联时直接物理删除（关联记录无审计价值，且唯一索引会拦住重复插入）。
 *
 * @author 刘
 */
@Data
@TableName("t_post_tag")
public class PostTag implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long postId;

    private Long tagId;

    private LocalDateTime createTime;
}
