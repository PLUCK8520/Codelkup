package com.codelkup.module.ai.client;

import com.codelkup.module.ai.config.AiSettings;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek（OpenAI 兼容端点）客户端实现（M3-4 / ADR-1）—— <b>默认实现</b>。
 *
 * <p>M3-11 起同一接口下还有一个 {@link SpringAiClient}（{@code codelkup.ai.impl=spring-ai} 切换），
 * 本类在 {@code impl=rest}（或未配置）时生效 —— 两个 Bean 都带 {@code @ConditionalOnProperty}，
 * 保证容器里<b>永远只有一个</b> {@link AiClient}。
 *
 * <h2>为什么是 RestClient、为什么自己拼 JSON</h2>
 * ADR-1 已定：默认不引 Spring AI —— 零框架依赖，超时/降级完全可控。
 * 请求体用 {@code Map} 直接拼（而不是定义 DTO）：OpenAI 兼容协议里我们只用
 * 四个字段，为它们建一套 record 只会多出"字段名写错却不报错"的面。
 * 响应则用 {@link JsonNode} 逐字段宽松读取 —— 上游字段缺失时返回降级而不是抛异常。
 *
 * <h2>超时与重试</h2>
 * 连接与读取超时都取 {@code codelkup.ai.timeout-ms}（默认 8 秒，与 M3-9 的
 * P95 目标一致），<b>不设重试</b>：RestClient 默认不重试，这里也不额外加重试器 ——
 * 自动重试等于在失败路径上偷偷翻倍成本，而成本正是 AI 模块最需要盯住的量。
 *
 * <h2>日志边界（隐私）</h2>
 * 只记模型名、耗时、token 数、回答长度与降级原因，<b>绝不记 prompt 与回答正文</b>：
 * 它们包含用户内容（RAG 片段甚至是站内别人的帖子）。
 *
 * @author 刘
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "codelkup.ai.impl", havingValue = "rest", matchIfMissing = true)
public class DeepSeekClient implements AiClient {

    /** 错误响应体进日志前的截断长度（上游报错可能很长） */
    private static final int ERROR_BODY_LOG_LIMIT = 200;

    private final AiSettings settings;
    private final RestClient restClient;

    public DeepSeekClient(AiSettings settings) {
        this.settings = settings;
        this.restClient = buildRestClient(settings);
        // 启动时就把"AI 到底启用没有"打进日志：这是排障第一眼要看的信息，
        // 也避免运维对着"接口返回降级文案"猜半天是配置问题还是上游故障。
        // （只打印是否配置，绝不打印 key 本身）
        if (available()) {
            log.info("AI 客户端已启用 | model={} | baseUrl={} | 超时={}ms",
                    settings.model(), settings.baseUrl(), settings.timeoutMs());
        } else {
            log.info("AI 未启用 | 原因={} | 相关接口将返回降级结果（不影响其它功能）",
                    settings.enabled() ? "未配置 AI_API_KEY" : "AI_ENABLED=false");
        }
    }

    /**
     * 超时同时作用于连接与读取；由 Boot 的构建器探测可用的底层客户端
     * （JDK HttpClient / Apache / 简单实现），避免与具体实现耦合。
     */
    private static RestClient buildRestClient(AiSettings settings) {
        ClientHttpRequestFactorySettings factorySettings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(settings.timeoutMs()))
                .withReadTimeout(Duration.ofMillis(settings.timeoutMs()));
        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.detect().build(factorySettings);
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(settings.baseUrl())
                .build();
    }

    @Override
    public boolean available() {
        return settings.enabled() && StringUtils.hasText(settings.apiKey());
    }

    @Override
    public AiReply chat(AiChatRequest request) {
        if (!settings.enabled()) {
            return AiReply.degraded(AiFailure.DISABLED);
        }
        if (!StringUtils.hasText(settings.apiKey())) {
            // 默认状态：没配 key。这是"未启用"，不是故障 —— 连请求都不发
            return AiReply.degraded(AiFailure.NO_KEY);
        }

        long started = System.currentTimeMillis();
        try {
            JsonNode root = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + settings.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildBody(request))
                    .retrieve()
                    .body(JsonNode.class);
            return parse(root, started);
        } catch (ResourceAccessException e) {
            // 连接/读取超时与网络中断都落在这里；把"超时"单独辨认出来（它最值得盯）
            boolean timeout = isTimeout(e);
            log.warn("AI 调用失败 | model={} | 耗时={}ms | 原因={} | err={}",
                    settings.model(), System.currentTimeMillis() - started,
                    timeout ? AiFailure.TIMEOUT : AiFailure.UPSTREAM_ERROR, e.getMessage());
            return AiReply.degraded(timeout ? AiFailure.TIMEOUT : AiFailure.UPSTREAM_ERROR);
        } catch (RestClientResponseException e) {
            // 上游 4xx/5xx（401 key 错、429 限流、5xx 抖动……）
            log.warn("AI 上游报错 | model={} | status={} | 耗时={}ms | body={}",
                    settings.model(), e.getStatusCode().value(),
                    System.currentTimeMillis() - started, abbreviate(e.getResponseBodyAsString()));
            return AiReply.degraded(AiFailure.UPSTREAM_ERROR);
        } catch (Exception e) {
            // 兜底：任何意料之外的异常也降级，绝不外抛（接口契约）
            log.warn("AI 调用异常 | model={} | 耗时={}ms | err={}",
                    settings.model(), System.currentTimeMillis() - started, e.getMessage());
            return AiReply.degraded(AiFailure.UPSTREAM_ERROR);
        }
    }

    // ==================== 私有工具 ====================

    /**
     * 组装请求体。
     *
     * <p>{@code stream=false} 显式写出：将来若有人想改流式，必须先在这里改，
     * 而不是靠"上游默认值恰好是非流式"这种隐形假设。
     */
    private Map<String, Object> buildBody(AiChatRequest request) {
        int maxTokens = request.maxTokens() != null ? request.maxTokens() : settings.maxTokens();
        double temperature = request.temperature() != null ? request.temperature() : settings.temperature();
        return Map.of(
                "model", settings.model(),
                "messages", List.of(
                        Map.of("role", "system", "content", request.system() == null ? "" : request.system()),
                        Map.of("role", "user", "content", request.user())),
                "temperature", temperature,
                "max_tokens", maxTokens,
                "stream", false);
    }

    /** 宽松解析：字段缺失/结构不符一律走降级，不抛 */
    private AiReply parse(JsonNode root, long started) {
        JsonNode choices = root == null ? null : root.path("choices");
        String content = null;
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            content = choices.get(0).path("message").path("content").asText(null);
        }
        if (content == null || content.isBlank()) {
            log.warn("AI 返回无可读内容 | model={} | 耗时={}ms", settings.model(),
                    System.currentTimeMillis() - started);
            return AiReply.degraded(AiFailure.EMPTY_RESPONSE);
        }

        JsonNode usage = root.path("usage").path("total_tokens");
        Integer totalTokens = usage.isNumber() ? usage.asInt() : null;
        String model = root.path("model").asText(settings.model());

        log.debug("AI 调用成功 | model={} | tokens={} | 耗时={}ms | 回答长度={}",
                model, totalTokens, System.currentTimeMillis() - started, content.length());
        return AiReply.success(content, model, totalTokens);
    }

    /**
     * 判定是否超时。
     *
     * <p>底层实现不同，异常类型也不同：HttpURLConnection 抛
     * {@link SocketTimeoutException}，JDK HttpClient 抛 {@link HttpTimeoutException}
     * —— 两种都认，否则"超时"会被误记成普通上游错误。
     */
    private boolean isTimeout(ResourceAccessException e) {
        Throwable cause = e.getCause();
        return cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException;
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= ERROR_BODY_LOG_LIMIT ? text : text.substring(0, ERROR_BODY_LOG_LIMIT) + "…";
    }
}