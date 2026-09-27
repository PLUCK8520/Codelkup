package com.codelkup.module.ai.service;

import com.codelkup.common.result.PageResult;
import com.codelkup.module.ai.vo.SimilarQuestionVO;
import com.codelkup.module.content.dto.PostSearchRequest;
import com.codelkup.module.content.service.SearchService;
import com.codelkup.module.content.vo.PostListVO;
import com.codelkup.module.content.vo.PostSearchRowVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 相似问题提示（M3-5）—— <b>不调用 LLM</b>。
 *
 * <h2>为什么要做"不调 LLM 的相似提示"</h2>
 * 用户在提问前最需要知道的是"这件事有没有人问过"，而这个问题<b>检索就能回答</b>：
 * 让大模型把检索结果重新说一遍，只会多出成本、延迟与幻觉三件事。
 * LLM 用在"把片段组织成答案"（M3-6）上，这里只做"把像的挑出来"。
 *
 * <h2>两路取数（都不新增检索实现）</h2>
 * <ol>
 *   <li><b>标题前缀探测</b>（{@link SearchService#byTitlePrefix}）：标题以输入开头的帖子 ——
 *       最像的一类，必须保证进得来。</li>
 *   <li><b>通用检索兜底</b>（{@link SearchService#search}）：标题里出现过、或正文相关的帖子，
 *       取候选后再按"与标题的最长公共前缀"重排。</li>
 * </ol>
 * 先取第一路，不足 {@value #TOP_N} 条时用第二路补齐（按 id 去重）。
 *
 * <p><b>为什么必须是两路</b>：检索的 {@code MATCH ... IN BOOLEAN MODE} 是 OR 语义，
 * 遇到常见词（如 {@code java}）候选池会被打满 —— 实测一篇标题就叫
 * {@code java virtual thread} 的新帖<b>没能进前 20</b>。只靠"重排"救不了
 * "根本没进候选池"的帖子，所以"标题最像"必须有自己的取数路径
 * （理由与实测记录见 {@code PostMapper.selectByTitlePrefix}）。
 *
 * <p><b>为什么不直接改检索 SQL</b>：{@code /api/search} 有一整套既有契约与验收
 * （Sprint 4 的 84 条断言），为了一个提示接口去动它的排序语义是拿大风险换小收益。
 * 合并逻辑放在本服务里，检索那边一行不改。
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimilarQuestionService {

    /** 标题前缀探测的取数上限（比最终返回的 3 条宽一点，留合并余量） */
    private static final int PREFIX_LIMIT = 5;

    /** 兜底检索的候选池大小 */
    private static final int CANDIDATE_SIZE = 20;

    /** 返回条数 */
    private static final int TOP_N = 3;

    /**
     * 低于这个长度直接返回空。
     *
     * <p>单字无法构成 ngram token（§8.5：{@code ngram_token_size=2}），
     * 检索会降级成 LIKE —— 那是搜索页用来"兜底不空手而归"的手段，
     * 用在提示上只会拿一堆与标题无关的帖子凑数。宁可不提示，也不给噪音。
     */
    private static final int MIN_INPUT_LENGTH = 2;

    private final SearchService searchService;

    /**
     * 找出与给定标题最像的站内讨论（最多 3 条）。
     *
     * @param rawTitle 正在写的标题（原始输入，允许不足 2 字）
     * @param viewerId 当前登录用户（检索装配互动态用）；调用方已强制登录
     */
    public List<SimilarQuestionVO> find(String rawTitle, Long viewerId) {
        String query = rawTitle == null ? "" : rawTitle.trim();
        if (query.length() < MIN_INPUT_LENGTH) {
            return List.of();
        }

        List<SimilarQuestionVO> result = new ArrayList<>(TOP_N);
        Set<Long> picked = new HashSet<>();

        // ---- 第一路：标题以它开头的帖子 ----
        // 预览用 summary：命中发生在标题上，正文里"关键词附近"没有意义，
        // 而 summary 本来就是列表页在用的正文摘要，直接复用最自然。
        for (PostSearchRowVO row : searchService.byTitlePrefix(query, PREFIX_LIMIT)) {
            if (result.size() >= TOP_N) {
                break;
            }
            if (picked.add(row.getId())) {
                result.add(toVO(row, row.getSummary()));
            }
        }

        // ---- 第二路：通用检索补齐 ----
        if (result.size() < TOP_N) {
            PostSearchRequest request = new PostSearchRequest();
            request.setKeyword(query);
            request.setSort("relevance");
            request.setPage(1);
            request.setSize(CANDIDATE_SIZE);

            List<PostListVO> candidates = new ArrayList<>(searchService.search(request, viewerId).getRecords());
            // 稳定排序：List.sort 是稳定的，前缀重合度相同的记录会保持检索给出的相关性顺序，
            // 不会被"重排"打乱成随机顺序
            String lowerQuery = query.toLowerCase();
            candidates.sort((a, b) -> Integer.compare(
                    commonPrefixLength(lowerQuery, safeLower(b.getTitle())),
                    commonPrefixLength(lowerQuery, safeLower(a.getTitle()))));

            for (PostListVO row : candidates) {
                if (result.size() >= TOP_N) {
                    break;
                }
                if (picked.add(row.getId())) {
                    result.add(toVO(row, row.getSnippet()));
                }
            }
        }

        log.debug("相似问题提示 | 输入长度={} | 返回={} | 首位=#{}",
                query.length(), result.size(),
                result.isEmpty() ? "-" : result.get(0).getPostId());
        return result;
    }

    // ==================== 私有工具 ====================

    private SimilarQuestionVO toVO(PostListVO row, String snippet) {
        SimilarQuestionVO vo = new SimilarQuestionVO();
        vo.setPostId(row.getId());
        vo.setTitle(row.getTitle());
        vo.setSnippet(snippet);
        vo.setBoardName(row.getBoardName());
        return vo;
    }

    /**
     * 两串的最长公共前缀长度（大小写不敏感）。
     *
     * <p>不区分大小写是必须的：英文标题里 {@code Java virtual thread} 与
     * {@code java virtual thread} 在用户眼里是同一件事，区分大小写会让
     * "换个大小写就提示不出来"成为一个诡异的行为。
     */
    private static int commonPrefixLength(String a, String b) {
        int limit = Math.min(a.length(), b.length());
        int i = 0;
        while (i < limit && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    private static String safeLower(String text) {
        return text == null ? "" : text.toLowerCase();
    }
}