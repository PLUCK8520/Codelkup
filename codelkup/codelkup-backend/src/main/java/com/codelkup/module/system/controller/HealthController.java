package com.codelkup.module.system.controller;

import com.codelkup.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查与骨架自检接口。
 *
 * <p>用途：Sprint 0 验证「Web 层 → MySQL → Redis」三层链路是否全部打通。
 * 这是一个临时接口，Sprint 1 起可移入 admin 模块。
 *
 * @author 刘
 */
@Tag(name = "系统", description = "健康检查")
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final RedisTemplate<String, Object> redisTemplate;

    /** 注册开关（公开只读）：前端注册页据此提前显示"暂未开放"，而不是让访客填完表单才被拒 */
    @Value("${codelkup.auth.registration-enabled:true}")
    private boolean registrationEnabled = true;

    @Operation(summary = "公开站点配置", description = "游客可读；目前只有注册开关（前端注册页展示用）")
    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        return Result.success(Map.of("registrationEnabled", registrationEnabled));
    }

    @Operation(summary = "骨架自检", description = "依次探测 MySQL、Redis 连通性")
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("application", "codelkup");
        data.put("time", LocalDateTime.now().toString());

        // ---- MySQL ----
        // ⚠️ 2026-09-29 第五轮审计 #2a：失败时只回 "DOWN"，不回异常类名 ——
        // 此接口匿名可读，异常类名（如 RedisConnectionFailureException）会把
        // 内部组件与故障类型暴露给探测者；运维排障看服务端日志，不靠探针。
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            data.put("mysql", one != null && one == 1 ? "UP" : "UNKNOWN");
        } catch (Exception e) {
            data.put("mysql", "DOWN");
        }

        // ---- Redis ----
        try {
            String key = "codelkup:health:probe";
            // ⚠️ 必须带 TTL（2026-09-29 补）：正常路径下面会 delete，
            // 但只要 get / delete 之间抛异常，一个**没有 TTL 的 key 就会永久留在 Redis**。
            // 而本接口匿名可达（SecurityConfig 里 permitAll），不能留下这种残留面。
            // 5 秒足够完成一次读写往返，异常时也会被自动回收。
            redisTemplate.opsForValue().set(key, "ok", Duration.ofSeconds(5));
            Object v = redisTemplate.opsForValue().get(key);
            redisTemplate.delete(key);
            data.put("redis", "ok".equals(v) ? "UP" : "UNKNOWN");
        } catch (Exception e) {
            data.put("redis", "DOWN");
        }

        return Result.success(data);
    }
}
