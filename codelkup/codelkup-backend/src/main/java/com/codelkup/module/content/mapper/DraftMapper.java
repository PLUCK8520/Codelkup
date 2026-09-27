package com.codelkup.module.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codelkup.module.content.entity.Draft;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 草稿 Mapper。
 *
 * @author 刘
 */
@Mapper
public interface DraftMapper extends BaseMapper<Draft> {

    /**
     * 保存草稿（upsert）—— 不存在则插入，存在（同 user_id + scope）则覆盖。
     *
     * <h2>为什么用 ON DUPLICATE KEY UPDATE 而不是"先 SELECT 再 INSERT/UPDATE"</h2>
     * 后者在并发下会插入两条同 scope 记录并撞唯一键（自动保存是周期性的，
     * 同一用户的两次保存相隔 15 秒，正常不会并发；但"网络重试 + 用户在另一台设备上
     * 同时编辑"这类组合是可能撞上的）。交给数据库的唯一索引判定，不需要应用层加锁。
     *
     * <p>⚠️ 时间列显式写 {@code NOW()} 而不是交给 MyMetaObjectHandler 自动填充：
     * 本方法走的是手写 SQL（MP 的自动填充只对它自己生成的 insert/update 生效）。
     * {@code update_time} 由数据库给出还有一个好处 —— 它是"服务端时间"，
     * 与响应里的 {@code updateTimeEpoch} 同源，前端拿它与本地草稿比较新旧时
     * 不会受调用方设备时钟的影响。
     *
     * <p>⚠️ {@code VALUES(col)} 在 MySQL 8.0.20+ 被标记为 deprecated（还能用），
     * 换成 {@code AS new} 别名写法要求 8.0.19+；本项目 dev 与 compose 都是 8.0，
     * 但为了兼容更老的 8.0.x 小版本，这里保留 {@code VALUES()} 写法。
     */
    @Insert("""
            INSERT INTO t_draft (user_id, scope, title, content, board_id, type, tags, create_time, update_time)
            VALUES (#{userId}, #{scope}, #{title}, #{content}, #{boardId}, #{type}, #{tags}, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                title = VALUES(title),
                content = VALUES(content),
                board_id = VALUES(board_id),
                type = VALUES(type),
                tags = VALUES(tags),
                update_time = NOW()
            """)
    int upsert(Draft draft);
}