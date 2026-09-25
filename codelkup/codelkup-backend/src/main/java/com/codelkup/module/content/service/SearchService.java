package com.codelkup.module.content.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codelkup.common.result.PageResult;
import com.codelkup.module.content.dto.PostSearchRequest;
import com.codelkup.module.content.mapper.PostMapper;
import com.codelkup.module.content.util.MarkdownUtils;
import com.codelkup.module.content.vo.PostListVO;
import com.codelkup.module.content.vo.PostSearchRowVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 帖子检索服务（Sprint 4）。
 *
 * <h2>为什么独立成一个 Service，而不是往 PostService 里加个方法</h2>
 * 检索的"读写形状"和列表完全不同：默认排序是<b>相关性</b>而非时间、
 * 要额外算 snippet、要对输入做清洗。混进 PostService 会让那个类承担两种关注点。
 *
 * <p>更实际的收益是<b>可替换性</b>：DevDoc 修订 D1 把 Elasticsearch 留给了 V0.2
 * 作为"优化前后对比"的叙事素材。届时替换检索实现时，只要这个类的对外签名不变，
 * Controller 与前端一行都不用改。这是个货真价实的收益，不是为了分层而分层。
 *
 * <h2>输入清洗 —— 本类存在的第一个理由</h2>
 * MySQL 的 {@code BOOLEAN MODE} 里，{@code + - > < ( ) ~ * " @} 都有<b>语法含义</b>。
 * 参数化绑定（{@code #{}}）挡住了注入，但挡不住<b>语义漂移</b>：
 * <pre>
 *   实测（2026-09-25）：
 *   AGAINST('-线程')  → 0 条   ← '-' 被解释为"排除含'线程'的文档"，与用户意图相反
 *   AGAINST('+线程')  → 4 条   ← '+' 被解释为"必须包含"，比默认更严格
 *   AGAINST('虚拟线程') → 4 条  ← 正常
 * </pre>
 * 也就是说：用户搜"线程"能出结果，搜"-线程"反而什么都没有 —— 这在用户看来
 * 就是"搜索坏了"。所以必须在拼 SQL 前把操作符剥掉。
 *
 * <p>⚠️ 已知取舍：剥操作符会让 {@code C++} 变成 {@code C}。不剥则 {@code -词} 失效。
 * 两害相权，选"结果符合直觉"的那个，并在注释里记下来而不是假装没这回事。
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    /** 关键词长度上限（与 {@code PostSearchRequest} 的 @Size 保持一致） */
    private static final int MAX_QUERY_LENGTH = 50;

    /**
     * 走全文索引所需的最小长度。
     *
     * <p>本机 {@code ngram_token_size = 2}（§8.5 实测），索引的最小单位是双字，
     * 单个汉字无法构成 token —— 实测 {@code AGAINST('帖')} 返回 0 条。
     * 低于此长度必须降级为 LIKE，否则用户搜单字会得到"无结果"且不知道为什么。
     */
    private static final int MIN_FULLTEXT_LENGTH = 2;

    /**
     * BOOLEAN MODE 下具有语法含义的字符。剥离理由见类注释（语义漂移）。
     *
     * <p>注意编译期的双重转义：Java 字符串 {@code "[+\\-><()~*\"@]"} 表示正则
     * {@code [+-><()~*"@]} —— 其中 {@code \-} 是字符类里转义的减号，
     * 不加反斜杠会被当成范围符号（{@code +-&gt;} 会被解释成"从 + 到 > 的区间"）。
     */
    private static final Pattern BOOLEAN_OPERATORS = Pattern.compile("[+\\-><()~*\"@]");

    /**
     * 排序白名单。
     *
     * <p>与 PostService 同理：{@code orderBy} 以 {@code ${}} 拼进 SQL，
     * <b>绝不能</b>来自请求参数。这里多一条 {@code relevance}，
     * 且它是搜索的默认值 —— 搜索的意义就在于把最相关的排在前面。
     *
     * <p>{@code p.is_top DESC} 放在相关性<b>之后</b>：搜索时置顶帖不该无条件霸占首位，
     * 只在相关性持平时作为次级排序键。
     */
    private static final Map<String, String> ORDER_BY_WHITELIST = Map.of(
            "relevance", "relevance DESC, p.is_top DESC, p.create_time DESC",
            "latest", "p.is_top DESC, p.create_time DESC",
            "hot", "p.is_top DESC, p.score DESC, p.create_time DESC"
    );

    /** snippet 中关键词单侧保留的上下文字数 */
    private static final int SNIPPET_CONTEXT = 60;

    /** snippet 未命中关键词时的兜底长度 */
    private static final int SNIPPET_FALLBACK_LENGTH = 120;

    /** LIKE 转义符。选 '!' 是因为正文里基本不出现，且不涉及反斜杠的多层转义 */
    private static final char LIKE_ESCAPE = '!';

    private final PostMapper postMapper;
    private final PostListAssembler assembler;

    /**
     * 检索帖子。
     *
     * @param request  检索参数（keyword 必填）
     * @param viewerId 当前登录用户，用于标记 liked/collected；游客传 null
     */
    public PageResult<PostListVO> search(PostSearchRequest request, Long viewerId) {
        String cleaned = sanitize(request.getKeyword());

        // 清洗后为空 —— 用户只输入了操作符（如 "+++"）。
        // 明确返回空结果，而不是拿空串去 MATCH（那会返回全表或报错）。
        if (cleaned.isEmpty()) {
            log.debug("检索词清洗后为空，直接返回空结果 | raw={}", request.getKeyword());
            return PageResult.empty(request.getPage(), request.getSize());
        }

        // 短词降级：ngram 双字粒度下单字必然搜不到
        boolean useLike = cleaned.length() < MIN_FULLTEXT_LENGTH;
        String likeQuery = useLike ? escapeLike(cleaned) : null;
        String orderBy = resolveOrderBy(request.getSort(), useLike);

        Page<PostSearchRowVO> page = new Page<>(request.getPage(), request.getSize());
        IPage<PostSearchRowVO> result = postMapper.searchPostPage(
                page, cleaned, likeQuery, useLike,
                request.getBoardId(), request.getTagId(), request.getAuthorId(), orderBy);

        // ---- 转换：算出 snippet 后丢掉正文（正文绝不进响应体）----
        List<PostListVO> records = new ArrayList<>(result.getRecords().size());
        for (PostSearchRowVO row : result.getRecords()) {
            PostListVO vo = new PostListVO();
            // 只拷共同字段；content 不在 PostListVO 上，天然不会泄露
            BeanUtils.copyProperties(row, vo);
            vo.setSnippet(buildSnippet(row, cleaned));
            records.add(vo);
        }

        // 与列表页共用同一套装配（标签 / 实时计数 / 我的互动状态）
        assembler.assemble(records, viewerId);

        log.debug("检索完成 | keyword={} cleaned={} useLike={} total={}",
                request.getKeyword(), cleaned, useLike, result.getTotal());
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), records);
    }

    // ==================== 输入清洗 ====================

    /**
     * 清洗检索词：剥离 BOOLEAN 操作符 → 折叠空白 → 截断超长。
     *
     * <p>顺序不能反：必须先剥操作符再折叠空白，否则 {@code "C++ 并发"} 剥完会留下
     * 多个连续空格，折叠后仍是一个空格 —— 但若先折叠，剥完又会产生新的连续空格。
     */
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        // 剥操作符 —— 逐个替换成空格而非直接删除，
        // 避免 "线程-并发" 被粘成 "线程并发"（一个不存在的词）
        String cleaned = BOOLEAN_OPERATORS.matcher(raw.trim()).replaceAll(" ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        if (cleaned.length() > MAX_QUERY_LENGTH) {
            // 按码点截断，避免把 emoji 之类的代理对劈成半个字符
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0,
                    Math.min(MAX_QUERY_LENGTH, cleaned.codePointCount(0, cleaned.length()))));
        }
        return cleaned;
    }

    /**
     * 转义 LIKE 通配符。
     *
     * <p>用户输入 {@code 50%} 或 {@code user_id} 时，{@code %} / {@code _} 在 LIKE 里
     * 是通配符 —— 不转义就会变成"匹配任意"和"匹配单字"，用户搜 {@code %} 会得到全表。
     * 先转义符自身再转义通配符，顺序不能颠倒（否则会把刚插入的转义符再转义一次）。
     */
    static String escapeLike(String raw) {
        return raw.replace(String.valueOf(LIKE_ESCAPE), LIKE_ESCAPE + String.valueOf(LIKE_ESCAPE))
                .replace("%", LIKE_ESCAPE + "%")
                .replace("_", LIKE_ESCAPE + "_");
    }

    /** 决定最终 orderBy 片段 —— 永远只从白名单取值，命中不了就回落默认 */
    private String resolveOrderBy(String sort, boolean useLike) {
        // LIKE 降级时 relevance 恒为 0（SELECT 里就是这么写的），按它排序毫无意义，
        // 会让分页顺序变得随机（同分记录顺序不稳）。强制回落时间序。
        if (useLike) {
            return ORDER_BY_WHITELIST.get("latest");
        }
        String key = (sort == null || sort.isBlank()) ? "relevance" : sort.toLowerCase();
        return ORDER_BY_WHITELIST.getOrDefault(key, ORDER_BY_WHITELIST.get("relevance"));
    }

    // ==================== snippet 生成 ====================

    /**
     * 生成"关键词附近的一段纯文本"。
     *
     * <p>为什么要单独做这个，而不是让前端拿 {@code summary} 高亮：
     * {@code summary} 是正文<b>开头</b> 200 字，而关键词常常出现在正文深处 ——
     * 那时 summary 里根本没有关键词，前端无从高亮，用户也看不出这条为什么被搜出来。
     *
     * <p>返回的永远是<b>纯文本</b>，不含任何高亮标记。标红是前端的事（D4：
     * 后端不产生 HTML），这里只负责把"该给用户看的那一段"挑出来。
     *
     * <h2>快慢两条路径</h2>
     * <ol>
     *   <li><b>快</b>：摘要里已含关键词（说明命中就在开头）→ 直接返回摘要，
     *       省掉对整个正文跑 10 条正则</li>
     *   <li><b>慢</b>：剥离 Markdown 得到全文，定位首次命中位置，取前后各
     *       {@value #SNIPPET_CONTEXT} 字</li>
     * </ol>
     * 正文可能很长，但一页最多 50 条，且慢路径只在"命中在开头 200 字之外"时触发。
     */
    private String buildSnippet(PostSearchRowVO row, String cleaned) {
        String[] words = cleaned.split(" ");

        // ---- 快路径：摘要里已经能看到关键词 ----
        String summary = row.getSummary();
        if (summary != null && !summary.isEmpty() && containsAny(summary, words)) {
            return summary;
        }

        // ---- 慢路径：剥离 Markdown 后定位 ----
        String plain = MarkdownUtils.stripMarkdown(row.getContent());
        if (plain == null || plain.isEmpty()) {
            // 正文剥完是空的（如纯图片帖）→ 退回摘要（可能为 null，前端自行兜底）
            return summary;
        }

        int pos = firstHitPosition(plain, words);
        if (pos < 0) {
            // 关键词只在标题里（或只匹配到分词的一部分）→ 给正文开头
            return plain.length() <= SNIPPET_FALLBACK_LENGTH
                    ? plain
                    : plain.substring(0, SNIPPET_FALLBACK_LENGTH) + "…";
        }

        int start = Math.max(0, pos - SNIPPET_CONTEXT);
        int end = Math.min(plain.length(), pos + SNIPPET_CONTEXT);
        String snippet = plain.substring(start, end);

        // 只在真正截断的一侧加省略号 —— 两边都不截就别加，否则看着像内容丢了
        if (start > 0) {
            snippet = "…" + snippet;
        }
        if (end < plain.length()) {
            snippet = snippet + "…";
        }
        return snippet;
    }

    /** 任一关键词出现即算命中 */
    private boolean containsAny(String text, String[] words) {
        for (String word : words) {
            if (!word.isEmpty() && text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** 返回最早出现的关键词位置；都没命中返回 -1 */
    private int firstHitPosition(String text, String[] words) {
        int best = -1;
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            int pos = text.indexOf(word);
            if (pos >= 0 && (best < 0 || pos < best)) {
                best = pos;
            }
        }
        return best;
    }
}
