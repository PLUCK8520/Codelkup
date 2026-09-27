package com.codelkup.module.ai.client;

/**
 * AI 调用的降级原因（M3-4）。
 *
 * <p>刻意分成几种而不是一个笼统的 FAILED：调用方与日志需要区分
 * "根本没配 key"（正常状态，不该报警）与"上游超时/报错"（要盯的异常），
 * 混在一起就只剩一句"AI 不可用"，排查时无从下手。
 *
 * @author 刘
 */
public enum AiFailure {

    /** 总开关关闭（enabled=false，线上临时止血用） */
    DISABLED,

    /** 没配置 key —— 默认状态。此时 available() 也是 false */
    NO_KEY,

    /** 连接或读取超时（用户等不起的那一类） */
    TIMEOUT,

    /** 上游返回 4xx/5xx，或网络/解析异常 */
    UPSTREAM_ERROR,

    /** 上游 200 但拿不到可用内容（choices 为空等） */
    EMPTY_RESPONSE
}