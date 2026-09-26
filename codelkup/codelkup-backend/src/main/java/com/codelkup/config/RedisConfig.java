package com.codelkup.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置。
 *
 * <p>关键点：默认的 {@code JdkSerializationRedisSerializer} 会把 key 写成二进制乱码，
 * 在 redis-cli 里无法排查。此处统一为：
 * <ul>
 *   <li>key / hashKey → String 序列化（可读）</li>
 *   <li>value / hashValue → JSON 序列化（可读，且支持复杂对象）</li>
 * </ul>
 *
 * @author 刘
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // key 用 String，便于 redis-cli 直接查看
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // value 用 JSON
        Jackson2JsonRedisSerializer<Object> jsonSerializer =
                new Jackson2JsonRedisSerializer<>(buildObjectMapper(), Object.class);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * 专用 ObjectMapper。
     *
     * <p>不使用容器内的 ObjectMapper，避免被 Web 层的配置（如 non_null 策略）影响，
     * 导致缓存反序列化时字段丢失。
     *
     * <p>⚠️ <b>刻意不开启 {@code activateDefaultTyping}（多态默认类型信息）</b>：
     * 那会在 JSON 里写入 {@code @class} 之类的类型字段，反序列化时按名字实例化任意类 ——
     * 只要攻击者能把一段 JSON 写进这些 Redis 键，就构成反序列化 gadget 链（RCE 面）。
     * 本项目该模板只被健康探针写入常量字符串，不开类型信息反而更安全；
     * 代价是反序列化回来的是 Map/List/String 而非原始对象，
     * 若将来真的要用它缓存业务对象，请改用带白名单的
     * {@code BasicPolymorphicTypeValidator} 而不是 LaissezFaireSubTypeValidator。
     */
    private ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}
