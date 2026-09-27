import { post, request } from '@/utils/request'
import type { AiAnswerVO, AiSummaryVO, SimilarQuestionVO } from '@/types/api'

/**
 * AI 接口 —— 对应后端 AiController（M3-5 / M3-6 / M3-7）。
 *
 * ⚠️ 三个接口里只有**相似问题提示是静默的**：
 *   - 它是"边写边问"的辅助信息（编辑器里去抖调用），失败时的正确表现是"没有提示"，
 *     而不是在用户写作时弹一个提示条 —— 何况它还可能是 42901 限流；
 *   - 提问与摘要是用户**显式动作**，失败必须让用户看见
 *     （尤其是 42901「AI 调用次数已达上限，请 1 小时后再试」）。
 */
export const aiApi = {
  /** 相似问题提示（编辑器边写边问；不调 LLM，毫秒级；静默失败） */
  similarQuestions: (title: string) =>
    request<SimilarQuestionVO[]>({
      method: 'POST',
      url: '/ai/similar-questions',
      data: { title },
      silent: true,
    }),

  /** RAG 问答：检索站内片段 → 依据片段作答并标注引用 */
  ask: (question: string) => post<AiAnswerVO>('/ai/ask', { question }),

  /** 长帖 TL;DR：正文剥离 Markdown 并截断后生成要点 */
  summarize: (postId: number) => post<AiSummaryVO>(`/ai/summarize/${postId}`),
}