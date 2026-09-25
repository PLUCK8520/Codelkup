package com.codelkup.module.content.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 板块实体，对应表 {@code t_board}。
 *
 * <p>板块是低频变更的字典数据，读多写极少 —— 适合整体缓存（S2 阶段先直查，量级仅十级）。
 *
 * @author 刘
 */
@Data
@TableName("t_board")
public class Board implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** URL 短名，唯一，用于前端路由（如 /b/backend） */
    private String slug;

    private String description;

    private String icon;

    /** 排序值，越小越靠前 */
    private Integer sortOrder;

    /** 帖子数（冗余字段，读多写少） */
    private Integer postCount;

    /** 0正常 1隐藏 */
    private Integer status;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    // ⚠️ 注意：t_board 刻意没有 deleted 字段。
    //    板块是运营配置数据，需要"下架"时用 status=1 隐藏，
    //    而不是逻辑删除 —— 否则历史帖子会指向一个"不存在"的板块。
}
