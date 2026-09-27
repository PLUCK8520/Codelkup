<script setup lang="ts">
/**
 * 「问 Codelkup」：RAG 问答面板（M3-8 —— M3-6 后端接口的前端落点）。
 *
 * <h2>为什么是抽屉而不是独立页面</h2>
 * 这个问题天生是"随手一问"：用户在首页列表、详情页、搜索结果里都可能想问，
 * 跳到独立页面会丢掉当前上下文（还得自己找回来）。抽屉从任意页面都能开合。
 *
 * <h2>渲染口径（与全站 D4 一致）</h2>
 * 答案是<b>纯文本</b>，用 {@link parseAnswerSegments} 切成"文本段 / 引用段"后
 * v-for 渲染 —— 全程没有 v-html，"AI 输出"这条链路不存在 XSS 面。
 * 引用角标 `[n]` 渲染成上标链接，点击跳对应帖子；下方另有引用清单
 * （标题 + 片段 + 板块），回答"这句话的依据是什么"。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { aiApi } from '@/api/ai'
import { parseAnswerSegments } from '@/utils/ai'
import type { AiAnswerVO } from '@/types/api'

const visible = defineModel<boolean>({ required: true })
const router = useRouter()

const question = ref('')
const asking = ref(false)
const failed = ref(false)
const result = ref<AiAnswerVO | null>(null)

/** 问过一轮之后就不能再改题干（避免"答案与问题对不上"的错觉），提供「重新提问」复位 */
const answered = computed(() => result.value !== null || failed.value)

const segments = computed(() => parseAnswerSegments(result.value?.answer))
const citations = computed(() => result.value?.citations ?? [])

/** [n] → citations[n-1]（后端编号从 1 起）。越界返回 undefined，模板里降级成纯文本 */
function citationAt(index: number) {
  return citations.value[index - 1]
}

/** 单字无法构成 ngram token（后端会把 <2 字判成 NO_EVIDENCE）—— 前端先拦住，省一次请求 */
const canAsk = computed(() => question.value.trim().length >= 2 && !asking.value)

async function onAsk() {
  if (!canAsk.value) return
  asking.value = true
  failed.value = false
  try {
    result.value = await aiApi.ask(question.value.trim())
  } catch {
    // 拦截器已统一提示（含 42901「AI 调用次数已达上限」）；这里只落成可重试状态
    result.value = null
    failed.value = true
  } finally {
    asking.value = false
  }
}

function reset() {
  result.value = null
  failed.value = false
}

/** 点引用 → 跳对应帖子并收起抽屉（用户要看的是帖子，不是抽屉） */
function openCitation(postId: number) {
  visible.value = false
  router.push({ name: 'post-detail', params: { id: postId } })
}

const SAMPLES = ['虚拟线程和平台线程有什么区别？', '上传接口为什么要做配额？']
</script>

<template>
  <el-drawer v-model="visible" title="问 Codelkup" size="480px" direction="rtl">
    <div class="ai-panel">
      <p class="ai-intro">
        基于站内已有讨论回答，并给出出处 —— 不编造，找不到就说找不到。
      </p>

      <div class="ai-input-row">
        <el-input
          v-model="question"
          placeholder="问一个技术问题（至少 2 个字）"
          maxlength="200"
          show-word-limit
          :disabled="answered"
          @keyup.enter="onAsk"
        />
        <el-button
          v-if="!answered"
          type="primary"
          :loading="asking"
          :disabled="!canAsk"
          @click="onAsk"
        >
          提问
        </el-button>
        <el-button v-else @click="reset">重新提问</el-button>
      </div>

      <!-- 没问之前给两个样例：空输入框比"填什么"更难用 -->
      <div v-if="!answered" class="ai-samples">
        <span class="ai-samples-label">试试：</span>
        <button
          v-for="s in SAMPLES"
          :key="s"
          type="button"
          class="ai-sample"
          @click="question = s"
        >
          {{ s }}
        </button>
      </div>

      <p v-if="asking" class="ai-hint">正在检索站内讨论并生成回答…</p>

      <template v-else-if="failed">
        <!-- 具体原因由统一拦截器提示（例如 42901「AI 调用次数已达上限，请 1 小时后再试」）；
             这里只给"可以重试"的出口，避免把"额度用完"这类原因猜错在面板里 -->
        <p class="ai-hint">暂时没能拿到回答（具体原因见上方提示）。</p>
        <el-button size="small" @click="onAsk">重试</el-button>
      </template>

      <template v-else-if="result">
        <div v-if="result.degraded" class="ai-degraded">
          {{ result.answer }}
        </div>

        <p v-else class="ai-answer">
          <template v-for="(seg, i) in segments" :key="i">
            <template v-if="seg.type === 'text'">{{ seg.text }}</template>
            <sup v-else-if="citationAt(seg.index)" class="ai-cite">
              <a
                href="#"
                :title="citationAt(seg.index)!.title"
                @click.prevent="openCitation(citationAt(seg.index)!.postId)"
              >[{{ seg.index }}]</a>
            </sup>
            <template v-else>[{{ seg.index }}]</template>
          </template>
        </p>

        <section v-if="citations.length" class="ai-cites">
          <h4 class="ai-cites-title">
            依据 {{ citations.length }} 条站内讨论
          </h4>
          <ul class="ai-cites-list">
            <li v-for="(c, i) in citations" :key="c.postId">
              <a
                href="#"
                class="ai-cite-item"
                @click.prevent="openCitation(c.postId)"
              >
                <span class="ai-cite-index">[{{ i + 1 }}]</span>
                <span class="ai-cite-title">{{ c.title }}</span>
              </a>
              <p v-if="c.snippet" class="ai-cite-snippet">{{ c.snippet }}</p>
              <span v-if="c.boardName" class="ai-cite-board">{{ c.boardName }}</span>
            </li>
          </ul>
        </section>

        <p v-if="!citations.length && !result.degraded" class="ai-hint">
          站内没有相关讨论，可以直接去发帖提问。
        </p>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.ai-panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.ai-intro {
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  line-height: 1.6;
}

.ai-input-row {
  display: flex;
  gap: var(--space-2);
  align-items: flex-start;
}

.ai-samples {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-xs);
}

.ai-samples-label {
  color: var(--color-subtle-foreground);
}

.ai-sample {
  padding: var(--space-1) var(--space-2);
  font-size: var(--text-xs);
  color: var(--color-muted-foreground);
  background: var(--color-muted);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-full);
  transition: color var(--transition-fast), border-color var(--transition-fast);
}

.ai-sample:hover {
  color: var(--color-foreground);
  border-color: var(--color-accent-border);
}

.ai-hint {
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
}

/* 降级：warning 令牌 —— "暂时没有"而不是"出错" */
.ai-degraded {
  padding: var(--space-3);
  font-size: var(--text-sm);
  line-height: 1.7;
  color: var(--color-on-warning-soft);
  background: var(--color-warning-soft);
  border: 1px solid var(--color-warning-border);
  border-radius: var(--radius-md);
}

.ai-answer {
  font-size: var(--text-base);
  line-height: 1.85;
  color: var(--color-foreground);
  white-space: pre-wrap;
}

.ai-cite {
  font-size: var(--text-xs);
  margin-left: 2px;
}

.ai-cite a {
  color: var(--color-accent);
  text-decoration: none;
}

.ai-cite a:hover {
  text-decoration: underline;
}

.ai-cites-title {
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--color-subtle-foreground);
  letter-spacing: var(--tracking-label);
  margin-bottom: var(--space-2);
}

.ai-cites-list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.ai-cites-list li {
  padding: var(--space-3);
  background: var(--color-card);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
}

.ai-cite-item {
  display: flex;
  gap: var(--space-2);
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-foreground);
  text-decoration: none;
}

.ai-cite-item:hover {
  color: var(--color-accent);
}

.ai-cite-index {
  flex-shrink: 0;
  color: var(--color-accent);
}

.ai-cite-snippet {
  margin-top: var(--space-1);
  font-size: var(--text-xs);
  line-height: 1.6;
  color: var(--color-muted-foreground);
}

.ai-cite-board {
  display: inline-block;
  margin-top: var(--space-2);
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
}

@media (max-width: 640px) {
  .ai-input-row {
    flex-direction: column;
  }
}
</style>