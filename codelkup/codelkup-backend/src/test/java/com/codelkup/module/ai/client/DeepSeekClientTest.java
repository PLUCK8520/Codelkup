package com.codelkup.module.ai.client;

import com.codelkup.module.ai.config.AiSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * DeepSeekClient 的行为测试（M3-4）。
 *
 * <h2>为什么用本地 stub 而不是 mock RestClient</h2>
 * 这一层要钉死的东西全在<b>协议与超时</b>上：请求打到哪里、带了什么头与字段、
 * 上游报错/超时/空响应分别变成哪种降级、以及"到底发了几次请求（有没有偷偷重试）"。
 * 用 mock 只能验证"我调用了某个方法"，用真实 HTTP 打本地 stub 能验证
 * **线上真正会发生的那条链路**（序列化、连接、读取超时、状态码处理）。
 * stub 用 JDK 自带的 {@code com.sun.net.httpserver}，零新增依赖，也不碰 Spring 容器。
 *
 * <h2>不可验证的部分（如实记录）</h2>
 * 真实 DeepSeek 端点的响应细节（限流头、错误体格式）无法在没有 key 的情况下验证；
 * 本测试覆盖的是"OpenAI 兼容协议"这一层，换任何兼容端点都成立。
 */
class DeepSeekClientTest {

    private static final String API_KEY = "test-key-not-a-real-secret";
    private static final String SYSTEM_PROMPT = "你是 Codelkup 的助手，只依据给定片段回答。";
    private static final String USER_PROMPT = "虚拟线程和平台线程有什么区别？";

    private static HttpServer server;
    private static String baseUrl;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 每次请求的路径与请求体（按到达顺序） */
    private static final List<String> receivedPaths = new CopyOnWriteArrayList<>();
    private static final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private static final List<String> receivedAuthHeaders = new CopyOnWriteArrayList<>();

    /** stub 的应答行为（每个用例前重置） */
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
                // 客户端已超时断开（读超时用例）：stub 侧的写失败无需处理
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
        DeepSeekClient client = client(settings(true, "", 8000));

        assertFalse(client.available(), "没配 key 时 available() 必须是 false");

        AiReply reply = client.chat(AiChatRequest.of(SYSTEM_PROMPT, USER_PROMPT));

        assertFalse(reply.ok());
        assertEquals(AiFailure.NO_KEY, reply.failure());
        assertEquals(0, receivedPaths.size(), "未启用时不该发出任何 HTTP 请求");
    }

    @Test
    void disabledSwitch_degradesEvenWithKey() {
        DeepSeekClient client = client(settings(false, API_KEY, 8000));

        assertFalse(client.available());
        assertEquals(AiFailure.DISABLED, client.chat(AiChatRequest.of(null, USER_PROMPT)).failure());
        assertEquals(0, receivedPaths.size());
    }

    // ==================== 正常路径（协议与解析） ====================

    @Test
    void success_parsesContentModelAndTokens() {
        DeepSeekClient client = client(settings(true, API_KEY, 8000));

        assertTrue(client.available());
        AiReply reply = client.chat(AiChatRequest.of(SYSTEM_PROMPT, USER_PROMPT));

        assertTrue(reply.ok(), "正常应答应返回 ok");
        assertEquals("虚拟线程由 JVM 调度，适合高并发 IO。", reply.content());
        assertEquals("deepseek-chat", reply.model());
        assertEquals(123, reply.totalTokens());
        assertNull(reply.failure());
        assertEquals(1, receivedPaths.size(), "成功路径也只发一次请求（无重试）");
    }

    @Test
    void success_sendsOpenAiCompatibleRequest() throws Exception {
        DeepSeekClient client = client(settings(true, API_KEY, 8000));

        client.chat(new AiChatRequest(SYSTEM_PROMPT, USER_PROMPT, 50, 0.9));

        assertEquals("/chat/completions", receivedPaths.get(0));
        assertEquals("Bearer " + API_KEY, receivedAuthHeaders.get(0));

        JsonNode body = MAPPER.readTree(receivedBodies.get(0));
        assertEquals("deepseek-chat", body.path("model").asText());
        assertEquals(SYSTEM_PROMPT, body.path("messages").get(0).path("content").asText());
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertEquals(USER_PROMPT, body.path("messages").get(1).path("content").asText());
        assertEquals("user", body.path("messages").get(1).path("role").asText());
        assertEquals(50, body.path("max_tokens").asInt(), "请求里的 maxTokens 覆盖配置默认值");
        assertEquals(0.9, body.path("temperature").asDouble(), 1e-9);
        assertFalse(body.path("stream").asBoolean(), "必须显式声明非流式");
    }

    @Test
    void defaultsFillMissingRequestFields() throws Exception {
        DeepSeekClient client = client(settings(true, API_KEY, 8000));

        client.chat(AiChatRequest.of(null, USER_PROMPT));

        JsonNode body = MAPPER.readTree(receivedBodies.get(0));
        assertEquals(800, body.path("max_tokens").asInt());
        assertEquals(0.3, body.path("temperature").asDouble(), 1e-9);
        assertEquals("", body.path("messages").get(0).path("content").asText(), "system 为 null 时补空串");
    }

    // ==================== 失败路径（全部降级，且都不重试） ====================

    @Test
    void upstreamError_degradesWithoutRetry() {
        status = 500;
        responseBody = "{\"error\":{\"message\":\"internal error\"}}";
        DeepSeekClient client = client(settings(true, API_KEY, 8000));

        AiReply reply = client.chat(AiChatRequest.of(SYSTEM_PROMPT, USER_PROMPT));

        assertFalse(reply.ok());
        assertEquals(AiFailure.UPSTREAM_ERROR, reply.failure());
        assertEquals(1, receivedPaths.size(), "失败也不重试（重试等于偷偷翻倍成本）");
    }

    @Test
    void unauthorizedKey_degradesAsUpstreamError() {
        status = 401;
        responseBody = "{\"error\":{\"message\":\"invalid api key\"}}";
        DeepSeekClient client = client(settings(true, API_KEY, 8000));

        assertEquals(AiFailure.UPSTREAM_ERROR, client.chat(AiChatRequest.of(null, USER_PROMPT)).failure());
    }

    @Test
    void readTimeout_isReportedAsTimeout() {
        delayMs = 500;
        DeepSeekClient client = client(settings(true, API_KEY, 150));

        long t0 = System.currentTimeMillis();
        AiReply reply = client.chat(AiChatRequest.of(SYSTEM_PROMPT, USER_PROMPT));
        long elapsed = System.currentTimeMillis() - t0;

        assertFalse(reply.ok());
        assertEquals(AiFailure.TIMEOUT, reply.failure(), "超时必须与普通上游错误区分开");
        assertTrue(elapsed < 400, "应当在配置的超时窗口内返回（实测 " + elapsed + "ms）");
        assertEquals(1, receivedPaths.size());
    }

    @Test
    void emptyChoices_degradesAsEmptyResponse() {
        responseBody = "{\"model\":\"deepseek-chat\",\"choices\":[]}";
        DeepSeekClient client = client(settings(true, API_KEY, 8000));

        assertEquals(AiFailure.EMPTY_RESPONSE, client.chat(AiChatRequest.of(null, USER_PROMPT)).failure());
    }

    @Test
    void blankContent_degradesAsEmptyResponse() {
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}";
        DeepSeekClient client = client(settings(true, API_KEY, 8000));

        assertEquals(AiFailure.EMPTY_RESPONSE, client.chat(AiChatRequest.of(null, USER_PROMPT)).failure());
    }

    // ==================== 工具 ====================

    private static AiSettings settings(boolean enabled, String apiKey, long timeoutMs) {
        return new AiSettings(enabled, apiKey, baseUrl, "deepseek-chat", timeoutMs, 800, 0.3);
    }

    private static DeepSeekClient client(AiSettings settings) {
        return new DeepSeekClient(settings);
    }
}