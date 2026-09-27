package com.codelkup.module.ai.client;

import com.codelkup.module.ai.config.AiSettings;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Spring AI 实现（M3-11）—— {@link AiClient} 的<b>第二实现</b>，用
 * {@code codelkup.ai.impl=spring-ai} 切换，默认仍是自写的
 * {@link DeepSeekClient}（{@code impl=rest}）。
 *
 * <h2>它存在的意义</h2>
 * ADR-1 当初定的是"不引 Spring AI"（理由：零新增依赖、超时与降级完全可控）。
 * M3-11 把这句判断变成<b>可验证的对比</b>：同一个接口下真的挂上框架版，
 * 用同一份配置、同一套验收脚本跑一遍 —— 而不是停在"框架应该也行"的口头结论上。
 * 结论（实测，见 TASKS 备注 32）：两条路都能跑通全部 87 项断言，
 * 差别在于 <b>可控性</b> —— 框架自带重试与观测，而"不重试/成本为王"正是本项目的口径，
 * 于是这里要主动把它<b>关掉</b>（下面三处"必须显式指定"都在干这件事）。
 *
 * <h2>为什么手动 new 而不走 starter 自动配置</h2>
 * <ol>
 *   <li>starter 启动就要求 {@code spring.ai.openai.api-key} 非空，而本项目"没配 key"
 *       是<b>正常状态</b>（{@code available()=false} → 接口降级）；为迁就自动配置塞假 key
 *       等于把"密钥可以没有"这件事写坏。</li>
 *   <li>配置口径是 {@code codelkup.ai.*}：再引入第二套 {@code spring.ai.*} 键，
 *       就会出现"同一个东西两处配置、早晚改一处漏一处"。</li>
 *   <li>手动构建后，两个实现读的是<b>同一份 {@link AiSettings}</b>，
 *       "切换实现"才是一次纯粹的实现替换，而不是换一套配置语义。</li>
 * </ol>
 *
 * <h2>与 {@link DeepSeekClient} 逐条对齐的三处</h2>
 * <ul>
 *   <li><b>不重试</b>：Spring AI 默认 {@code RetryUtils.DEFAULT_RETRY_TEMPLATE} 会重试瞬时错误
 *       （失败路径上偷偷翻倍成本）→ 显式传 {@code maxAttempts=1} 的 RetryTemplate。</li>
 *   <li><b>路径一致</b>：Spring AI 默认打 {@code /v1/chat/completions}，而 {@code base-url}
 *       在本项目里的语义是"根地址 + /chat/completions"→ 显式指定 {@code completionsPath}，
 *       否则同一个 base-url 在两个实现下会打到不同 URL（stub 验收会立刻发现）。</li>
 *   <li><b>超时一致</b>：复用 Boot 的 {@link ClientHttpRequestFactoryBuilder} 按
 *       {@code codelkup.ai.timeout-ms} 设连接/读取超时，与 RestClient 版同一套工厂设置。</li>
 * </ul>
 *
 * <p>观测走自家的 {@code codelkup.ai.calls} 指标（{@code AiCallGuard}），
 * 因此这里把 ObservationRegistry 设为 {@code NOOP} —— 同一件事不留两套口径。
 *
 * @author 刘
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "codelkup.ai.impl", havingValue = "spring-ai")
public class SpringAiClient implements AiClient {

    /** 异常链遍历的深度上限（防止病态的自引用 cause 造成死循环） */
    private static final int MAX_CAUSE_DEPTH = 12;

    private final AiSettings settings;

    /** 未启用（没配 key / 总开关关闭）时为 null —— 与"连请求都不发"是同一件事 */
    private final OpenAiChatModel chatModel;

    public SpringAiClient(AiSettings settings) {
        this.settings = settings;
        this.chatModel = available() ? buildChatModel(settings) : null;
        log.info("AI 客户端（Spring AI 实现）{}",
                available()
                        ? "已启用 | model=" + settings.model() + " | baseUrl=" + settings.baseUrl()
                        + " | 超时=" + settings.timeoutMs() + "ms"
                        : "未启用 | 原因=" + (settings.enabled() ? "未配置 AI_API_KEY" : "AI_ENABLED=false")
                        + " | 相关接口将返回降级结果（不影响其它功能）");
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
            return AiReply.degraded(AiFailure.NO_KEY);
        }

        long started = System.currentTimeMillis();
        try {
            ChatResponse response = chatModel.call(buildPrompt(request));
            return parse(response, started);
        } catch (Exception e) {
            // 契约：永不外抛。超时单独辨认（它最值得盯），其余一律上游错误
            AiFailure failure = classify(e);
            log.warn("AI 调用失败（spring-ai）| model={} | 耗时={}ms | 原因={} | 异常={} | err={}",
                    settings.model(), System.currentTimeMillis() - started, failure,
                    e.getClass().getSimpleName(), e.getMessage());
            return AiReply.degraded(failure);
        }
    }

    // ==================== 私有工具 ====================

    private static OpenAiChatModel buildChatModel(AiSettings settings) {
        ClientHttpRequestFactorySettings factorySettings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(settings.timeoutMs()))
                .withReadTimeout(Duration.ofMillis(settings.timeoutMs()));
        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.detect().build(factorySettings);

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(settings.baseUrl())
                .apiKey(settings.apiKey())
                .completionsPath("/chat/completions")
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(settings.model()).build())
                .toolCallingManager(ToolCallingManager.builder().build())
                // ⚠️ spring-retry 的默认模板会重试瞬时错误 —— 本项目的口径是"一次调用一次请求"
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    /** system 为空时不塞空消息（与 RestClient 版的 messages 组装保持一致） */
    private Prompt buildPrompt(AiChatRequest request) {
        List<Message> messages = new ArrayList<>(2);
        if (StringUtils.hasText(request.system())) {
            messages.add(new SystemMessage(request.system()));
        }
        messages.add(new UserMessage(request.user()));

        int maxTokens = request.maxTokens() != null ? request.maxTokens() : settings.maxTokens();
        double temperature = request.temperature() != null ? request.temperature() : settings.temperature();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(settings.model())
                .maxTokens(maxTokens)
                .temperature(temperature)
                .build();
        return new Prompt(messages, options);
    }

    /** 宽松解析：上游没有 choices / 内容为空一律降级（与 RestClient 版同一口径） */
    private AiReply parse(ChatResponse response, long started) {
        Generation generation = response == null ? null : response.getResult();
        String content = (generation == null || generation.getOutput() == null)
                ? null : generation.getOutput().getText();
        if (content == null || content.isBlank()) {
            log.warn("AI 返回无可读内容（spring-ai）| model={} | 耗时={}ms", settings.model(),
                    System.currentTimeMillis() - started);
            return AiReply.degraded(AiFailure.EMPTY_RESPONSE);
        }

        String model = settings.model();
        Integer totalTokens = null;
        if (response.getMetadata() != null) {
            if (StringUtils.hasText(response.getMetadata().getModel())) {
                model = response.getMetadata().getModel();
            }
            Usage usage = response.getMetadata().getUsage();
            // ⚠️ 上游没回 usage 时 Spring AI 给的是 EmptyUsage（0/0），而 getTotalTokens()
            //    会把 null 当 0 相加 —— 直接采信就会写出"这次调用用了 0 token"这种假数据。
            //    两个分量都是 0 说明"上游没给"，按 null（未知）记，与 RestClient 版一致。
            if (usage != null && (nonZero(usage.getPromptTokens()) || nonZero(usage.getCompletionTokens()))) {
                totalTokens = usage.getTotalTokens();
            }
        }

        log.debug("AI 调用成功（spring-ai）| model={} | tokens={} | 耗时={}ms | 回答长度={}",
                model, totalTokens, System.currentTimeMillis() - started, content.length());
        return AiReply.success(content, model, totalTokens);
    }

    private static boolean nonZero(Integer value) {
        return value != null && value != 0;
    }

    /**
     * 判定超时。
     *
     * <p>与 RestClient 版同一判据（{@link SocketTimeoutException} / {@link HttpTimeoutException}），
     * 但这里<b>要沿异常链找</b>：Spring AI 的调用被 RetryTemplate 与 Observation 各包了一层，
     * 超时的根因不一定挂在第一层 cause 上。
     */
    private AiFailure classify(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return AiFailure.TIMEOUT;
            }
            Throwable cause = current.getCause();
            current = (cause == current) ? null : cause;
        }
        return AiFailure.UPSTREAM_ERROR;
    }
}