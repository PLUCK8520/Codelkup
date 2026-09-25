package com.codelkup.module.content.util;

/**
 * Markdown 文本工具。
 *
 * <p>用途：把 Markdown 原文转成列表页展示用的纯文本摘要。
 *
 * <p>⚠️ <b>这里不引入 Markdown 渲染器</b>。理由：
 * <ol>
 *   <li>摘要只需要"去掉语法符号"，不需要完整解析 —— 引 commons-markdown 或 flexmark
 *       只为生成 200 字摘要属于过度依赖（且渲染器有 CVE 维护成本）</li>
 *   <li>DevDoc 修订 D4 已经把渲染职责放在<b>前端</b>，后端不该出现第二套渲染逻辑，
 *       否则前后端渲染结果可能不一致</li>
 * </ol>
 * 因此摘要生成用正则做"够用就好"的处理，且结果只用于展示，不参与检索（检索走 ngram 全文索引）。
 *
 * @author 刘
 */
public final class MarkdownUtils {

    /** 摘要最大长度（对应 t_post.summary VARCHAR(255)） */
    private static final int SUMMARY_MAX_LENGTH = 200;

    private MarkdownUtils() {
    }

    /**
     * 从 Markdown 正文提取纯文本摘要。
     *
     * <p>处理顺序有讲究：必须先摘掉代码块/行内代码，再处理其他语法。
     * 否则代码块里的 {@code #}、{@code *} 会被误当成标题和强调符号。
     */
    public static String extractSummary(String markdown) {
        String text = stripMarkdown(markdown);
        if (text == null) {
            return null;
        }
        return text.length() <= SUMMARY_MAX_LENGTH
                ? text
                : text.substring(0, SUMMARY_MAX_LENGTH) + "…";
    }

    /**
     * 把 Markdown 正文转成<b>不截断</b>的纯文本。
     *
     * <p>与 {@link #extractSummary} 共用同一套剥离规则，区别只在不去头截尾 ——
     * 搜索结果需要"关键词附近的一小段"（snippet），而关键词可能出现在正文深处，
     * 截断到 200 字就找不到了。抽出此方法而不是让调用方自己写一遍正则，
     * 是为了避免出现两套"去 Markdown 语法"的实现（S2 的 summary 与 S4 的 snippet
     * 必须对同一段正文给出同样干净的文本）。
     *
     * @return 纯文本；输入为空或剥完为空时返回 {@code null}
     */
    public static String stripMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return null;
        }

        String text = markdown;

        // 1. 先摘代码块（```...```）—— 必须最先做，否则内部符号会干扰后续规则
        text = text.replaceAll("(?s)```.*?```", " ");
        // 2. 行内代码 `code`
        text = text.replaceAll("`[^`]*`", " ");
        // 3. 图片 ![alt](url) —— 整体丢弃（摘要里留着 url 毫无意义）
        text = text.replaceAll("!\\[[^\\]]*]\\([^)]*\\)", " ");
        // 4. 链接 [text](url) —— 只保留 text
        text = text.replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1");
        // 5. HTML 标签（用户可能直接贴 HTML）
        text = text.replaceAll("<[^>]+>", " ");
        // 6. 引用块 / 标题 / 列表符号（行首）
        text = text.replaceAll("(?m)^\\s{0,3}(#{1,6}|>|[-*+]|\\d+\\.)\\s*", "");
        // 7. 强调、删除线、表格分隔符
        text = text.replaceAll("(\\*{1,3}|_{1,3}|~~|\\|)", "");
        // 8. 水平线
        text = text.replaceAll("(?m)^\\s*([-*_]\\s*){3,}$", "");
        // 9. 转义反斜杠
        text = text.replace("\\", "");

        // 10. 折叠空白（含换行）为单空格
        text = text.replaceAll("\\s+", " ").trim();

        if (text.isEmpty()) {
            return null;
        }

        // 不截断 —— 截断交由调用方决定（摘要要 200 字，snippet 要"关键词附近"）
        return text;
    }

    /**
     * 生成唯一化摘要 —— 与 {@link #extractSummary} 的区别是末尾不加省略号。
     *
     * <p>S3 的防重复发帖校验会用到"正文指纹"，此处预留语义入口；
     * 当前实现与 extractSummary 一致，仅截断长度不同。
     */
    public static String extractPlainText(String markdown, int maxLength) {
        String summary = extractSummary(markdown);
        if (summary == null) {
            return null;
        }
        return summary.length() <= maxLength ? summary : summary.substring(0, maxLength);
    }
}
