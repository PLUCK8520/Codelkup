package com.codelkup.module.content.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * MarkdownUtils 单元测试。
 *
 * <p>聚焦两件事：
 * <ol>
 *   <li>「先摘代码块/行内代码，再剥其它语法」的顺序是否正确 —— 否则代码里的
 *       {@code #}、{@code *} 会被误当成标题/强调符号（S2 的 XSS 相关核心）。</li>
 *   <li>空输入 / 剥完为空 / 超长截断 等边界。</li>
 * </ol>
 */
class MarkdownUtilsTest {

    // ==================== stripMarkdown：基础剥离 ====================

    @Test
    void nullInput_returnsNull() {
        assertNull(MarkdownUtils.stripMarkdown(null));
        assertNull(MarkdownUtils.extractSummary(null));
    }

    @Test
    void blankInput_returnsNull() {
        assertNull(MarkdownUtils.stripMarkdown("   "));
        assertNull(MarkdownUtils.stripMarkdown("\n\t\n"));
    }

    @Test
    void bold_italic_stripped() {
        assertEquals("加粗斜体", MarkdownUtils.stripMarkdown("**加粗***斜体*"));
        // 删除线：~~标记~~ 去掉标记、保留内容（删除线只影响样式，文本内容仍要展示）
        assertEquals("删字", MarkdownUtils.stripMarkdown("~~删~~字"));
    }

    @Test
    void headingHash_stripped() {
        assertEquals("标题", MarkdownUtils.stripMarkdown("# 标题"));
        assertEquals("三级", MarkdownUtils.stripMarkdown("### 三级"));
    }

    @Test
    void link_keepsText() {
        assertEquals("链接文字", MarkdownUtils.stripMarkdown("[链接文字](https://example.com/a)"));
    }

    @Test
    void image_droppedEntirely() {
        // 图片整体丢弃：摘要里留着 url 毫无意义，剥完为空 → null
        assertNull(MarkdownUtils.stripMarkdown("![alt](https://example.com/x.png)"));
    }

    @Test
    void inlineCode_stripped() {
        // 行内代码替换成空格（而非直接删除），避免前后文字粘连成"前后"
        assertEquals("前 后", MarkdownUtils.stripMarkdown("前`代码`后"));
    }

    @Test
    void htmlTag_stripped() {
        assertEquals("加粗文字", MarkdownUtils.stripMarkdown("<b>加粗文字</b>"));
    }

    @Test
    void backslash_stripped() {
        assertEquals("ab", MarkdownUtils.stripMarkdown("a\\b"));
    }

    // ==================== 顺序正确性（S2 核心） ====================

    @Test
    void hashInsideCodeBlock_notMisstripped() {
        // 代码块里的 # 不该被当成标题剥掉 —— 代码块整体先被替换，内部内容不参与后续规则
        String md = "```\n# 这不是标题\nint x = 1;\n```\n# 真标题";
        String result = MarkdownUtils.stripMarkdown(md);
        // 代码块内容整体消失，只剩最后一行"真标题"
        assertEquals("真标题", result);
    }

    @Test
    void asteriskInsideCodeBlock_notMisstripped() {
        String md = "```java\nint a = b * c;\n```\n正文";
        assertEquals("正文", MarkdownUtils.stripMarkdown(md));
    }

    // ==================== 摘要截断 ====================

    @Test
    void extractSummary_shortText_unchanged() {
        assertEquals("短文本", MarkdownUtils.extractSummary("短文本"));
    }

    @Test
    void extractSummary_longText_truncatedWithEllipsis() {
        String longText = "a".repeat(300);
        String result = MarkdownUtils.extractSummary(longText);
        assertEquals(200 + 1, result.length(), "200 字 + 1 个省略号");
        assertEquals("a".repeat(200) + "…", result);
    }

    @Test
    void extractPlainText_truncatesToMaxLength() {
        assertEquals("a".repeat(100), MarkdownUtils.extractPlainText("a".repeat(300), 100));
    }

    @Test
    void extractPlainText_shortUnchanged() {
        assertEquals("hello", MarkdownUtils.extractPlainText("hello", 100));
    }
}
