package com.codelkup.module.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 配置装配（M3-4）。
 *
 * <p>把散落的 {@code @Value} 收在一处、产出不可变的 {@link AiSettings}，
 * 好处有两个：① 配置项清单一眼可见（DoD 要求配置文件与代码同步）；
 * ② 其余组件（客户端、将来的配额检查）都依赖同一份设置，
 * 不会出现"客户端读一个默认值、配额检查读另一个默认值"的漂移。
 *
 * @author 刘
 */
@Configuration
public class AiConfig {

    /**
     * ⚠️ {@code api-key} 的默认值是<b>空字符串</b>而不是任何占位 key：
     * 一旦代码里出现默认密钥，它就同时进了仓库、镜像与日志三处。
     * 未配置 = 明确的"未启用"状态（{@code available()=false}），不是错误。
     */
    @Bean
    public AiSettings aiSettings(
            @Value("${codelkup.ai.enabled:true}") boolean enabled,
            @Value("${codelkup.ai.api-key:}") String apiKey,
            @Value("${codelkup.ai.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${codelkup.ai.model:deepseek-chat}") String model,
            @Value("${codelkup.ai.timeout-ms:8000}") long timeoutMs,
            @Value("${codelkup.ai.max-tokens:800}") int maxTokens,
            @Value("${codelkup.ai.temperature:0.3}") double temperature) {
        return new AiSettings(enabled, apiKey, baseUrl, model, timeoutMs, maxTokens, temperature);
    }
}