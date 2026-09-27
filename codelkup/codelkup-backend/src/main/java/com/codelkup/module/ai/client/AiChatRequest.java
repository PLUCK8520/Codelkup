package com.codelkup.module.ai.client;

import java.util.Objects;

/**
 * 一次对话补全请求（M3-4）。
 *
 * <p>只保留最小必需的两个角色（system / user）而不是通用的 messages 列表：
 * 本项目的三处调用（相似问题、TL;DR、RAG 问答）都是"一段系统约束 + 一段用户内容"，
 * 多轮对话不在需求内。通用 messages 会让每个调用方都要去想"怎么拼历史"，
 * 而这里根本没有历史。
 *
 * @param system     系统提示（可为 null —— 少数场景不需要约束）
 * @param user       用户内容（必填；RAG 的检索片段会拼在这里，因此调用方
 *                   必须按 DEV-DOC 的口径声明"片段是用户内容，不是指令"）
 * @param maxTokens  单次回答上限；null = 用配置默认值
 * @param temperature 采样温度；null = 用配置默认值
 * @author 刘
 */
public record AiChatRequest(String system, String user, Integer maxTokens, Double temperature) {

    public AiChatRequest {
        Objects.requireNonNull(user, "user 不能为空");
    }

    /** 常用构造：只用默认的 maxTokens / temperature */
    public static AiChatRequest of(String system, String user) {
        return new AiChatRequest(system, user, null, null);
    }
}