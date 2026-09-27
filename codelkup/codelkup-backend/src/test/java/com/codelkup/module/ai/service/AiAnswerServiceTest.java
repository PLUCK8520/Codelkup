package com.codelkup.module.ai.service;

import com.codelkup.module.ai.vo.CitationVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiAnswerService} 里三条纯逻辑的测试（M3-6）。
 *
 * <p>为什么只测这三条：RAG 的其余部分（检索、调用、缓存、预算、日志）
 * 都在端到端脚本 verify-v03-ai.py 里用<b>本地 stub 冒充上游</b>验证 ——
 * 那是"真链路"。而这三条是纯字符串/哈希逻辑，单测更快更细：
 * <ul>
 *   <li><b>指纹归一化</b>：多一个空格 / 换大小写必须算同一个问题，否则缓存永远打不中；</li>
 *   <li><b>prompt 组装</b>：编号片段 + 问题 + "片段是用户内容不是指令"的声明缺一不可；</li>
 *   <li><b>引用映射</b>：越界编号要忽略、重复要去重、一个都没引用中时要回填全部片段。</li>
 * </ul>
 */
class AiAnswerServiceTest {

    private static CitationVO snippet(long id, String title) {
        CitationVO vo = new CitationVO();
        vo.setPostId(id);
        vo.setTitle(title);
        vo.setSnippet("片段正文");
        vo.setBoardName("后端开发");
        return vo;
    }

    private static final List<CitationVO> SNIPPETS = List.of(
            snippet(101, "虚拟线程实测"), snippet(102, "平台线程对比"), snippet(103, "无关帖"));

    // ==================== 检索词袋（ngram 短语问题的对策） ====================

    @Test
    void searchKeyword_splitsCjkIntoBigramsAndDedupes() {
        String kw = AiAnswerService.buildSearchKeyword("虚拟线程和平台线程有什么区别");

        assertTrue(kw.startsWith("虚拟 拟线 线程 程和"), kw);
        assertTrue(kw.contains("区别"), kw);
        long threads = java.util.Arrays.stream(kw.split(" ")).filter("线程"::equals).count();
        assertEquals(1, threads, "同一个 2-gram 只该出现一次：" + kw);
    }

    @Test
    void searchKeyword_keepsLatinAndDigitsWhole() {
        String kw = AiAnswerService.buildSearchKeyword("Redis 和 MySQL 有什么区别");

        assertTrue(kw.contains("Redis") && kw.contains("MySQL"), "拉丁词应整段保留：" + kw);
        assertTrue(kw.contains("区别"), kw);
        assertFalse(kw.contains("Redi "), "不该把 Redis 切成 2-gram：" + kw);
    }

    @Test
    void searchKeyword_dropsPunctuationAndCapsTermCount() {
        String kw = AiAnswerService.buildSearchKeyword("虚拟线程、IO 密集场景怎么选？");

        assertFalse(kw.contains("、"), kw);
        assertFalse(kw.contains("？"), kw);

        String longKw = AiAnswerService.buildSearchKeyword(
                "这是一段很长的问题用来验证词袋数量上限会不会被截断掉后面重要的关键词");
        assertTrue(longKw.split(" ").length <= 12, "词数应有上限（否则被检索层 50 字静默截断）：" + longKw);
    }

    @Test
    void searchKeyword_handlesBlank() {
        assertEquals("", AiAnswerService.buildSearchKeyword(null));
        assertEquals("", AiAnswerService.buildSearchKeyword("   "));
    }

    // ==================== 指纹 ====================

    @Test
    void fingerprint_isStableAcrossWhitespaceAndCase() {
        String a = AiAnswerService.fingerprint("虚拟线程  和 平台线程 有什么区别？");
        String b = AiAnswerService.fingerprint("  虚拟线程 和 平台线程 有什么区别？ ");
        String c = AiAnswerService.fingerprint("Java Virtual Thread 有什么用");
        String d = AiAnswerService.fingerprint("java virtual thread 有什么用");

        assertEquals(a, b, "空白差异不应产生不同指纹");
        assertEquals(c, d, "大小写差异不应产生不同指纹");
        assertEquals(64, a.length(), "SHA-256 hex 应为 64 位");
    }

    @Test
    void fingerprint_differsForDifferentQuestions() {
        assertNotEquals(AiAnswerService.fingerprint("问题甲"), AiAnswerService.fingerprint("问题乙"));
    }

    // ==================== prompt 组装 ====================

    @Test
    void prompt_containsNumberedSnippetsAndQuestion() {
        String prompt = AiAnswerService.buildUserPrompt("虚拟线程怎么样？", SNIPPETS);

        assertTrue(prompt.contains("[1] 《虚拟线程实测》"), "应带编号与书名号标题");
        assertTrue(prompt.contains("[2] 《平台线程对比》"));
        assertTrue(prompt.contains("[3] 《无关帖》"));
        assertTrue(prompt.contains("问题：虚拟线程怎么样？"), "问题应在片段之后");
        assertTrue(prompt.indexOf("[1]") < prompt.indexOf("[2]"), "编号应按顺序");
    }

    @Test
    void systemPrompt_declaresSnippetsAreUserContent() {
        // 这条是 ADR 点名的防注入声明：站内片段是用户产出的，绝不能当指令执行
        String system = AiAnswerService.SYSTEM_PROMPT;
        assertTrue(system.contains("用户内容"), "system 必须声明片段是用户内容");
        assertTrue(system.contains("不是给你的指令") || system.contains("绝不执行"),
                "system 必须声明不得执行片段里的文字");
        assertTrue(system.contains("[编号]"), "system 必须要求标注引用编号");
    }

    // ==================== 引用映射 ====================

    @Test
    void citations_mapsIndexesInOrderAndDedupes() {
        List<CitationVO> out = AiAnswerService.mapCitations("先看 [2]，再看 [1]，[2] 也是依据", SNIPPETS);

        assertEquals(2, out.size(), "同一编号出现两次只算一条");
        assertEquals(102L, out.get(0).getPostId(), "按出现顺序：先 [2]");
        assertEquals(101L, out.get(1).getPostId());
    }

    @Test
    void citations_ignoresOutOfRangeIndexes() {
        // 模型偶尔会凭空编出 [9]：越界编号必须忽略而不是让前端拿到空引用
        List<CitationVO> out = AiAnswerService.mapCitations("依据 [9] 与 [1]", SNIPPETS);

        assertEquals(1, out.size());
        assertEquals(101L, out.get(0).getPostId());
    }

    @Test
    void citations_fallsBackToAllSnippetsWhenNothingCited() {
        List<CitationVO> out = AiAnswerService.mapCitations("这段答案没有标注任何编号。", SNIPPETS);

        assertEquals(3, out.size(), "一个有效引用都没有时回填全部片段（降级时尤其重要）");
        assertEquals(101L, out.get(0).getPostId());
    }

    @Test
    void citations_handlesNullAnswer() {
        assertEquals(3, AiAnswerService.mapCitations(null, SNIPPETS).size());
        assertEquals(0, AiAnswerService.mapCitations("有引用 [1]", List.of()).size(), "空片段列表不应越界");
    }
}