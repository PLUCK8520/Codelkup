package com.codelkup.module.content.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 标签实体，对应表 {@code t_tag}。
 *
 * <p>{@code uk_name} 使用 {@code utf8mb4_unicode_ci} 排序规则（大小写不敏感），
 * 因此 "Java" 与 "java" 在库层面就是同一个标签，不会分裂成两条记录。
 *
 * @author 刘
 */
@Data
@TableName("t_tag")
public class Tag implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标签名（保留首次创建时的大小写） */
    private String name;

    /** 被引用次数 */
    private Integer useCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
