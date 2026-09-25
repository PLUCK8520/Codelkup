package com.codelkup.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * @author 刘
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 插件链。注意顺序：分页插件需在其他拦截器之前。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 1. 分页
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(100L);      // 单页上限，防"一次拉全表"
        pagination.setOverflow(false);     // 页码越界时返回空而非回到首页
        interceptor.addInnerInterceptor(pagination);

        // 2. 防全表更新/删除（避免误写 UPDATE t_post SET ... 无 where）
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }
}
