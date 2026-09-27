package com.codelkup.module.ai.config;

/**
 * AI 客户端配置（M3-4）。
 *
 * <p>做成不可变记录而不是让 {@code DeepSeekClient} 直接读 {@code @Value}：
 * 单测可以在**不起 Spring 容器**的前提下构造任意配置（含"没 key"与
 * "指向上游 stub"的形态）—— 这正是 M3-4 要求钉死的两条路径。
 *
 * @param enabled     总开关（false = 即使配了 key 也不调用）
 * @param apiKey      API key；空字符串 = 未启用（<b>代码里绝不留默认密钥</b>，只能环境变量注入）
 * @param baseUrl     OpenAI 兼容端点根地址（默认 DeepSeek）
 * @param model       模型名
 * @param timeoutMs   连接/读取超时（毫秒）
 * @param maxTokens   单次回答上限的默认值（请求可覆盖）
 * @param temperature 采样温度的默认值（请求可覆盖）
 * @author 刘
 */
public record AiSettings(
        boolean enabled,
        String apiKey,
        String baseUrl,
        String model,
        long timeoutMs,
        int maxTokens,
        double temperature
) {
}