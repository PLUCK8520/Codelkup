package com.codelkup.module.ai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiSummaryService} 纯逻辑测试（M3-7）：截断与 prompt 组装。
 *
 * <p>其余链路（调用、缓存、预算、日志、40402 口径）在端到端脚本 verify-v03-ai.py 里
 * 用<b>本地 stub 冒充上游</b>验证 —— 那里能真打 HTTP、能查日志表；这里只钉住
 * "超长帖怎么被截"和"prompt 里有没有防注入声明"这两条一眼看不出对错的规则。
 */
class AiSummaryServiceTest {

    // ==================== 截断 ====================

    @Test
    void truncate_keepsTextShorterThanLimit() {
        assertEquals("短文", AiSummaryService.truncateForSummary("短文", 6000));
        assertEquals("五字以内不截断", AiSummaryService.truncateForSummary("五字以内不截断", 10));
    }

    @Test
    void truncate_cutsLongTextWithEllipsis() {
        // 25000 字的帖子（验收要求的量级）：截到 6000 字 + 省略号
        String long_ = "虚".repeat(25000);

        String cut = AiSummaryService.truncateForSummary(long_, 6000);

        assertEquals(6001, cut.length(), "应为 6000 字 + 1 个省略号");
        assertTrue(cut.endsWith("…"), "必须留下「被截断」的可见信号 —— prompt 里靠它告诉模型别脑补");
        assertTrue(cut.startsWith("虚"));
    }

    @Test
    void truncate_handlesNullAndZeroLimit() {
        assertEquals("", AiSummaryService.truncateForSummary(null, 100));
        assertEquals("不限长", AiSummaryService.truncateForSummary("不限长", 0), "limit<=0 视为不截断");
    }

    // ==================== prompt ====================

    @Test
    void prompt_containsTitleBodyAndTruncationHint() {
        String prompt = AiSummaryService.buildUserPrompt("虚拟线程实测", "正文第一段…");

        assertTrue(prompt.startsWith("标题：虚拟线程实测"), prompt);
        assertTrue(prompt.contains("正文：\n正文第一段…"), prompt);
    }

    @Test
    void prompt_titleNullSafe() {
        assertTrue(AiSummaryService.buildUserPrompt(null, "正文").startsWith("标题：\n"), "标题为 null 时不能出现 null 字样");
    }

    @Test
    void systemPrompt_declaresUserContentAndTruncationRule() {
        String system = AiSummaryService.SYSTEM_PROMPT;

        assertTrue(system.contains("用户内容"), "system 必须声明正文是用户内容");
        assertTrue(system.contains("绝不执行"), "system 必须声明不执行正文里的文字");
        assertTrue(system.contains("省略号"), "system 必须解释截断标记（否则模型会脑补后半篇）");
    }
}