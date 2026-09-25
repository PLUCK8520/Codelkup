package com.codelkup.module.content.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 搜索输入清洗的单元测试。
 *
 * <p>对应 S4 实测得出的结论：参数化绑定挡住的是 <b>SQL 注入</b>，但挡不住
 * <b>BOOLEAN 操作符造成的语义漂移</b>（用户搜 {@code -线程} 会被 MySQL 当成
 * "排除线程"而返回空）。所以后端必须在进 MATCH AGAINST 之前清洗输入。
 * 这里钉死清洗规则，防止未来有人"顺手"改坏。
 */
class SearchServiceSanitizeTest {

    // ==================== sanitize：BOOLEAN 操作符清洗 ====================

    @Test
    void null_becomesEmpty() {
        assertEquals("", SearchService.sanitize(null));
    }

    @Test
    void blank_becomesEmpty() {
        assertEquals("", SearchService.sanitize("   "));
        assertEquals("", SearchService.sanitize("  \t\n "));
    }

    @Test
    void normalWord_unchanged() {
        assertEquals("虚拟线程", SearchService.sanitize("虚拟线程"));
        assertEquals("Redis 计数", SearchService.sanitize("Redis 计数"));
    }

    @Test
    void plusPrefix_stripped() {
        // + 在 BOOLEAN MODE 里是"必须包含"，会改变语义 → 剥掉
        assertEquals("线程", SearchService.sanitize("+线程"));
    }

    @Test
    void minusPrefix_stripped() {
        // - 是"排除"，用户搜 -线程 反而搜不到 → 剥掉
        assertEquals("线程", SearchService.sanitize("-线程"));
    }

    @Test
    void hyphenBetweenWords_becomesSpace_notConcat() {
        // 关键：剥成空格而非删除，否则 "线程-并发" 被粘成 "线程并发"（不存在的词）
        assertEquals("线程 并发", SearchService.sanitize("线程-并发"));
    }

    @Test
    void cppPlusPlus_becomesSpaced() {
        assertEquals("C 并发", SearchService.sanitize("C++ 并发"));
    }

    @Test
    void quote_stripped() {
        assertEquals("a b", SearchService.sanitize("a\"b"));
    }

    @Test
    void atSign_stripped() {
        assertEquals("a b", SearchService.sanitize("a@b"));
    }

    @Test
    void parens_brackets_tilde_star_stripped() {
        assertEquals("x y", SearchService.sanitize("(x) y"));
        assertEquals("x y", SearchService.sanitize("x> y"));
        assertEquals("x y", SearchService.sanitize("x< y"));
        assertEquals("x y", SearchService.sanitize("x~ y"));
        assertEquals("x y", SearchService.sanitize("x* y"));
    }

    @Test
    void collapseWhitespace() {
        assertEquals("Redis 计数", SearchService.sanitize("  Redis   计数  "));
    }

    @Test
    void overlong_truncatedByCodePoints() {
        String longText = "a".repeat(60);
        String result = SearchService.sanitize(longText);
        assertEquals(50, result.length(), "超过 MAX_QUERY_LENGTH 应截断到 50");
        assertEquals("a".repeat(50), result);
    }

    @Test
    void emoji_notSplitByTruncation() {
        // 49 个"测" + 1 个 emoji（占 2 个 char 的代理对）+ 尾巴 = 53 码点 > 50，触发截断。
        // 截断到 50 码点 = 49 个"测" + 完整 emoji，不应劈出半个代理对。
        String emoji = "😀";
        String result = SearchService.sanitize("测".repeat(49) + emoji + "多余内容");
        assertEquals(50, result.codePointCount(0, result.length()));
        assertFalse(Character.isHighSurrogate(result.charAt(result.length() - 1)), "结尾不应是孤立代理字符");
        assertEquals(emoji, result.substring(result.length() - emoji.length()), "emoji 应完整保留");
    }

    // ==================== escapeLike：LIKE 通配符转义 ====================

    @Test
    void percent_escaped() {
        // 用户搜 50% 时，% 是通配符，不转义会匹配任意 → 转义成字面量
        assertEquals("50!%", SearchService.escapeLike("50%"));
    }

    @Test
    void underscore_escaped() {
        assertEquals("user!_id", SearchService.escapeLike("user_id"));
    }

    @Test
    void escapeCharItself_escapedFirst() {
        // 转义符自身必须先转义，否则会把"刚插入的转义符"再转义一次
        assertEquals("a!!b", SearchService.escapeLike("a!b"));
    }

    @Test
    void plainText_unchanged() {
        assertEquals("普通词", SearchService.escapeLike("普通词"));
    }
}
