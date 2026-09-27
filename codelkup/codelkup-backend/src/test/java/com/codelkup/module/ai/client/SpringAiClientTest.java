package com.codelkup.module.ai.client;

import com.codelkup.module.ai.config.AiSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.support.RetryTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SpringAiClient 的行为测试（M3-11）。
 *
 * <h2>为什么和 DeepSeekClientTest 长得几乎一样</h2>
 * 因为要验的正是<b>"两个实现的行为是一个"</b>：同样的降级矩阵、同样只发一次请求、
 * 同样打到 {@code /chat/completions}、同样区分超时。两份测试平行存在不是重复 ——
 * 如果哪天有人换掉其中一边的写法（比如打开 Spring AI 的默认重试），
 * 只有"另一份还在守同一组断言"才看得出来。
 *
 * <h2>stub 与依赖</h2>
 * 同 DeepSeekClientTest：JDK 自带 {@code com.sun.net.httpserver} 当上游，真打 HTTP，
 * 不起 Spring 容器；唯一的差别是这里显式校验 {@code RetryTemplate.maxAttempts=1}
 * 确实生效（Spring AI 默认模板会重试瞬时错误，本项目的口径是一次调用一次请求）。
 */
class SpringAiClientTest {

    private static final String API_KEY = "test-key-not-a-real-secret";
    private static final String SYSTEM_PROMPT = "你是 Codelkup 的助手，只依据给定片段回答。";
    private static final String USER_PROMPT = "虚拟线程和平台线程有什么区别？";

    private static HttpServer server;
    private static String baseUrl;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> receivedPaths = new CopyOnWriteArrayList<>();
    private static final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private static final List<String> receivedAuthHeaders = new CopyOnWriteArrayList<>();

    private static volatile int status = 200;
    private static volatile String responseBody = okBody();
    private static volatile long delayMs = 0;

    private static String okBody() {
        return """
                {"id":"chat-1","model":"deepseek-chat","choices":[{"index":0,"message":
                {"role":"assistant","content":"虚拟线程由 JVM 调度，适合高并发 IO。"}}],
                "usage":{"prompt_tokens":80,"completion_tokens":43,"total_tokens":123}}
                """;
    }

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedPaths.add(exchange.getRequestURI().toString());
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            receivedAuthHeaders.add(auth == null ? "" : auth);
            try (InputStream in = exchange.getRequestBody()) {
                receivedBodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            } catch (IOException ignored) {
                // 客户端已超时断开：stub 侧写失败无需处理
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void resetStub() {
        receivedPaths.clear();
        receivedBodies.clear();
        receivedAuthHeaders.clear();
        status = 200;
        responseBody = okBody();
        delayMs = 0;
    }

    // ==================== 降级路径（不产生任何请求） ====================

    @Test
    void noKey_isUnavailableAndDegradesWithoutHttpCall() {
        SpringAiClient client = client(settings(true, "", 8000));

        assertFalse(client.available(), "没配 key 时 available() 必须是 false");
        assertEquals(AiFailure.NO_KEY, client.chat(AiChatRequest.of(SYSTEM_PROMPT, USER_PROMPT)).failure());
        assertEquals(0, receivedPaths.size(), "未启用时不该发出任何 HTTP 请求（构建阶段也不能去撞网络）");
    }

    @Test
    void disabledSwitch_degradesEvenWithKey() {
        SpringAiClient client = client(settings(false, API_KEY, 8000));

        assertFalse(client.available());
        assertEquals(AiFailure.DISABLED, client.chat(AiChatRequest.of(null, USER_PROMPT)).failure());
        assertEquals(0, receivedPaths.size());
    }

    // ==================== 正常路径（协议与解析） ====================

    @Test
    void success_parsesContentModelAndTokens() {
        SpringAiClient client = client(settings(true, API_KEY, 8000));

        assertTrue(client.available());
        AiReply reply = client.chat(AiChatRequest.of(SYSTEM_PROMPT, USER_PROMPT));

        assertTrue(reply.ok(), "正常应答应返回 ok");
        assertEquals("虚拟线程由 JVM 调度，适合高并发 IO。", reply.content());
        assertEquals("deepseek-chat", reply.model());
        assertEquals(123, reply.totalTokens());
        assertNull(reply.failure());
        assertEquals(1, receivedPaths.size(), "成功路径也只发一次请求");
    }

    @Test
    void success_sendsOpenAiCompatibleRequest() throws Exception {
        SpringAiClient client = client(settings(true, API_KEY, 8000));

        client.chat(new AiChatRequest(SYSTEM_PROMPT, USER_PROMPT, 50, 0.9));

        assertEquals("/chat/completions", receivedPaths.get(0),
                "必须与 RestClient 版打同一个路径（Spring AI 默认是 /v1/chat/completions）");
        assertEquals("Bearer " + API_KEY, receivedAuthHeaders.get(0));

        JsonNode body = MAPPER.readTree(receivedBodies.get(0));
        String raw = receivedBodies.get(0);
        assertEquals("deepseek-chat", body.path("model").asText(), "请求体=" + raw);
        assertEquals("system", body.path("messages").get(0).path("role").asText(), "请求体=" + raw);
        assertTrue(body.path("messages").get(0).path("content").asText().contains(SYSTEM_PROMPT),
                "请求体=" + raw);
        assertEquals("user", body.path("messages").get(1).path("role").asText(), "请求体=" + raw);
        assertTrue(body.path("messages").get(1).path("content").asText().contains(USER_PROMPT),
                "请求体=" + raw);
        assertEquals(50, body.path("max_tokens").asInt(), "请求体=" + raw);
        assertEquals(0.9, body.path("temperature").asDouble(), 1e-9, "请求体=" + raw);
    }

    @Test
    void defaultsFillMissingRequestFields() throws Exception {
        SpringAiClient client = client(settings(true, API_KEY, 8000));

        client.chat(AiChatRequest.of(null, USER_PROMPT));

        JsonNode body = MAPPER.readTree(receivedBodies.get(0));
        String raw = receivedBodies.get(0);
        assertEquals(800, body.path("max_tokens").asInt(), "请求体=" + raw);
        assertEquals(0.3, body.path("temperature").asDouble(), 1e-9, "请求体=" + raw);
        assertEquals(1, body.path("messages").size(),
                "system 为 null 时不该塞一条空消息（与 RestClient 版一致）：" + raw);
    }

    // ==================== 失败路径（全部降级，且都不重试） ====================

    @Test
    void upstreamServerError_degradesWithoutRetry() {
        status = 500;
        responseBody = "{\"error\":{\"message\":\"internal error\"}}";
        SpringAiClient client = client(settings(true, API_KEY, 8000));

        AiReply reply = client.chat(AiChatRequest.of(SYSTEM_PROMPT, USER_PROMPT));

        assertFalse(reply.ok());
        assertEquals(AiFailure.UPSTREAM_ERROR, reply.failure());
        assertEquals(1, receivedPaths.size(),
                "500 只发一次：Spring AI 默认 RetryTemplate 会重试瞬时错误（实测发 3 次），必须被我们关掉");
    }

    @Test
    void unauthorizedKey_degradesAsUpstreamError() {
        status = 401;
        responseBody = "{\"error\":{\"message\":\"invalid api key\"}}";
        SpringAiClient client = client(settings(true, API_KEY, 8000));

        assertEquals(AiFailure.UPSTREAM_ERROR, client.chat(AiChatRequest.of(null, USER_PROMPT)).failure());
        assertEquals(1, receivedPaths.size(), "4xx 同样不重试");
    }

    @Test
    void readTimeout_isReportedAsTimeout() {
        delayMs = 500;
        SpringAiClient client = client(settings(true, API_KEY, 150));

        long t0 = System.currentTimeMillis();
        AiReply reply = client.chat(AiChatRequest.of(SYSTEM_PROMPT, USER_PROMPT));
        long elapsed = System.currentTimeMillis() - t0;

        assertFalse(reply.ok());
        assertEquals(AiFailure.TIMEOUT, reply.failure(),
                "超时必须与普通上游错误区分开（框架会把异常包几层，判据要沿异常链找）");
        assertTrue(elapsed < 400, "应当在配置的超时窗口内返回（实测 " + elapsed + "ms）");
        assertEquals(1, receivedPaths.size());
    }

    @Test
    void emptyChoices_degradesAsEmptyResponse() {
        responseBody = "{\"model\":\"deepseek-chat\",\"choices\":[]}";
        SpringAiClient client = client(settings(true, API_KEY, 8000));

        assertEquals(AiFailure.EMPTY_RESPONSE, client.chat(AiChatRequest.of(null, USER_PROMPT)).failure());
    }

    @Test
    void blankContent_degradesAsEmptyResponse() {
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}";
        SpringAiClient client = client(settings(true, API_KEY, 8000));

        assertEquals(AiFailure.EMPTY_RESPONSE, client.chat(AiChatRequest.of(null, USER_PROMPT)).failure());
    }

    @Test
    void missingUsage_reportsNullTokensInsteadOfZero() {
        // 上游不回 usage 时 Spring AI 用 EmptyUsage（0/0）——直接采信就会写出"用了 0 token"的假数据
        responseBody = "{\"model\":\"deepseek-chat\",\"choices\":[{\"message\":{\"content\":\"有答案\"}}]}";
        SpringAiClient client = client(settings(true, API_KEY, 8000));

        AiReply reply = client.chat(AiChatRequest.of(null, USER_PROMPT));

        assertTrue(reply.ok());
        assertNull(reply.totalTokens(), "上游没给 usage 时应记 null（未知），不是 0");
    }

    @Test
    void retryMachinery_isLiveInsideTheModel_whichIsWhyWeOverrideIt() {
        // 这条不测我们的代码，测"我们为什么要覆盖 retryTemplate"：
        // 用同样方式构建，但把重试次数设成 3 —— 若 OpenAiChatModel 内部真的按模板重试，
        // stub 应该收到 3 次请求。与上面 upstreamServerError_degradesWithoutRetry 的"只发 1 次"
        // 合起来，就是"重试机制是活的、被我们的 maxAttempts=1 关掉了"的完整证据。
        //
        // 为什么不直接量 Spring AI 的默认模板：它的默认（{@code RetryUtils.DEFAULT_RETRY_TEMPLATE}）
        // 带退避，跑满一轮要几分钟 —— 不适合放进单测；这里量的是**机制**，默认值本身在框架源码里
        // （{@code OpenAiChatModel.Builder#retryTemplate} 的初值）。
        status = 500;
        responseBody = "{\"error\":{\"message\":\"internal error\"}}";

        OpenAiApi api = OpenAiApi.builder().baseUrl(baseUrl).apiKey(API_KEY)
                .completionsPath("/chat/completions").build();
        OpenAiChatModel bare = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("deepseek-chat").build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(3).build())
                .build();

        try {
            bare.call(new Prompt(new UserMessage(USER_PROMPT)));
        } catch (Exception ignored) {
            // 裸模型会把异常抛出来（没有我们的降级包装）——这里只关心"发了几次"
        }

        assertEquals(3, receivedPaths.size(),
                "重试机制应当真的生效（实测 " + receivedPaths.size() + " 次）——"
                        + "所以 SpringAiClient 必须显式传 maxAttempts=1");
    }

    // ==================== 工具 ====================

    private static AiSettings settings(boolean enabled, String apiKey, long timeoutMs) {
        return new AiSettings(enabled, apiKey, baseUrl, "deepseek-chat", timeoutMs, 800, 0.3);
    }

    private static SpringAiClient client(AiSettings settings) {
        return new SpringAiClient(settings);
    }
}