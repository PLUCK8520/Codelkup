package com.codelkup.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codelkup.module.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户 Mapper。
 *
 * @author 刘
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 按邮箱查询用户（含已逻辑删除的过滤由 MyBatis-Plus 自动处理）。
     *
     * <p>注册唯一性校验、登录凭证查找均依赖此方法。
     * 走 {@code uk_email} 唯一索引，无需额外索引。
     */
    @Select("SELECT * FROM t_user WHERE email = #{email} AND deleted = 0 LIMIT 1")
    User selectByEmail(@Param("email") String email);

    /**
     * 判断邮箱是否已被占用。
     *
     * <p>用 {@code COUNT} 而非查全字段：注册校验只需存在性，减少回表开销。
     */
    @Select("SELECT COUNT(*) FROM t_user WHERE email = #{email} AND deleted = 0")
    int countByEmail(@Param("email") String email);
}
