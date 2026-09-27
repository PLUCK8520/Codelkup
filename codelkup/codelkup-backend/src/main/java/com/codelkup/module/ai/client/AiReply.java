package com.codelkup.module.ai.client;

/**
 * 一次 AI 调用的结果（M3-4）。
 *
 * <p>用"一个对象 + ok 标志"而不是"异常 / null"表达失败：
 * 调用方（AI 面板、摘要、问答）拿到它之后，绝大多数分支是同一句
 * "降级：只展示检索结果 / 提示暂不可用"，不需要 try-catch 包一层再判断。
 *
 * @param ok          是否拿到了可用回答
 * @param content     回答正文（ok=false 时为 null）
 * @param model       实际使用的模型名（用于日志与成本核对）
 * @param totalTokens 本次消耗的 token 数（上游未返回时为 null）
 * @param failure     降级原因（ok=true 时为 null）
 * @author 刘
 */
public record AiReply(boolean ok, String content, String model, Integer totalTokens, AiFailure failure) {

    public static AiReply success(String content, String model, Integer totalTokens) {
        return new AiReply(true, content, model, totalTokens, null);
    }

    public static AiReply degraded(AiFailure failure) {
        return new AiReply(false, null, null, null, failure);
    }
}