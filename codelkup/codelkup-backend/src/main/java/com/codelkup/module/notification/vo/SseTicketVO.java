package com.codelkup.module.notification.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * SSE 一次性 ticket —— 登录态换来的短期凭证。
 *
 * @param ticket           票据本体（32 位随机十六进制，消费即作废）
 * @param expiresInSeconds 有效期（秒），前端可以据此判断"要不要重新换一张"
 * @author 刘
 */
@Schema(description = "SSE 一次性 ticket")
public record SseTicketVO(String ticket, long expiresInSeconds) {
}