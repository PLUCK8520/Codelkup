<script setup lang="ts">
/**
 * 长帖 AI 摘要（M3-8 —— M3-7 后端接口的前端落点）。
 *
 * <h2>两个刻意的选择</h2>
 * <ol>
 *   <li><b>放在正文上方</b>（标题与正文之间）：TL;DR 的价值就是"读之前先看要点"；
 *       放到正文末尾，等于用户已经读完了才看到摘要。</li>
 *   <li><b>点了才请求</b>：自动摘要会让"每浏览一篇文章"都可能触发一次花钱的调用
 *       （还占用用户 10 次/小时的额度）—— 那是把成本闸门当摆设。
 *       摘要结果只放在组件内：后端已按正文哈希缓存，前端再加一层
 *       只会多一个"什么时候失效"的问题。</li>
 * </ol>
 *
 * <p>降级不报错：未配置密钥 / 超预算 / 上游失败都落成一句可读提示（{@link aiDegradeText}），
 * 正文照常阅读 —— AI 是锦上添花，不能因为它挂了就让帖子看不下去。
 */
import { computed, ref } from 'vue'
import { aiApi } from '@/api/ai'
import { aiDegradeText } from '@/utils/ai'

const props = defineProps<{ postId: number }>()

const expanded = ref(false)
/** idle=还没问过；loading=请求中；done=有结论（成功/降级/失败都算结论） */
const state = ref<'idle' | 'loading' | 'done'>('idle')
const summary = ref<string | null>(null)
const degradeText = ref('')
const failed = ref(false)

/** 摘要按行拆分并去掉模型常见的 "- " 前缀，渲染成列表（模板里 v-for，无 v-html） */
const summaryLines = computed(() =>
  (summary.value ?? '')
    .split('\n')
    .map((line) => line.replace(/^\s*[-*]\s+/, '').trim())
    .filter(Boolean),
)

async function onToggle() {
  expanded.value = !expanded.value
  // 只在"首次展开且从未请求过"时自动发起；失败后由用户点「重试」决定是否再花钱
  if (expanded.value && state.value === 'idle') {
    await load()
  }
}

async function load() {
  state.value = 'loading'
  failed.value = false
  try {
    const res = await aiApi.summarize(props.postId)
    if (res.degraded) {
      degradeText.value = aiDegradeText(res.degradeReason)
      summary.value = null
    } else {
      summary.value = res.summary ?? null
    }
    state.value = 'done'
  } catch {
    // 统一拦截器已经提示过（例如 42901「AI 调用次数已达上限」）；
    // 这里只把状态落成"可重试"，绝不假装生成出了摘要
    failed.value = true
    state.value = 'done'
  }
}
</script>

<template>
  <section class="tldr">
    <button
      type="button"
      class="tldr-toggle"
      :aria-expanded="expanded"
      :aria-label="expanded ? '收起 AI 摘要' : '展开 AI 摘要'"
      @click="onToggle"
    >
      <span class="tldr-spark" aria-hidden="true">✦</span>
      <span>AI 摘要</span>
      <span class="tldr-caret" aria-hidden="true">{{ expanded ? '▲' : '▼' }}</span>
    </button>

    <div v-if="expanded" class="tldr-body" aria-live="polite">
      <p v-if="state === 'loading'" class="tldr-hint">正在生成摘要…</p>

      <template v-else-if="failed">
        <p class="tldr-hint">摘要生成失败。</p>
        <button type="button" class="tldr-retry" @click="load">重试</button>
      </template>

      <p v-else-if="degradeText" class="tldr-hint is-degraded">{{ degradeText }}</p>

      <p v-else-if="!summaryLines.length" class="tldr-hint">这篇帖子没有生成出可用的摘要。</p>

      <template v-else>
        <ul class="tldr-list">
          <li v-for="(line, i) in summaryLines" :key="i">{{ line }}</li>
        </ul>
        <p class="tldr-foot">AI 生成，可能不准确 —— 请以正文为准</p>
      </template>
    </div>
  </section>
</template>

<style scoped>
/* 与正文同宽的一块折叠区：未展开时只是一个不起眼的小按钮，
   展开后是一张浅色卡片（不抢正文的视觉重量） */
.tldr {
  margin-bottom: var(--space-4);
}

.tldr-toggle {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  font-size: var(--text-sm);
  color: var(--color-accent);
  background: var(--color-accent-soft);
  border: 1px solid var(--color-accent-border);
  border-radius: var(--radius-md);
  transition: background var(--transition-fast), border-color var(--transition-fast);
}

.tldr-toggle:hover {
  background: var(--color-card-hover);
  border-color: var(--color-accent);
}

.tldr-spark {
  font-size: var(--text-base);
  line-height: 1;
}

.tldr-caret {
  font-size: 10px;
  opacity: 0.8;
}

.tldr-body {
  margin-top: var(--space-3);
  padding: var(--space-4);
  font-size: var(--text-sm);
  line-height: 1.7;
  color: var(--color-muted-foreground);
  background: var(--color-muted);
  border: 1px solid var(--color-border-subtle);
  border-left: 3px solid var(--color-accent);
  border-radius: var(--radius-md);
}

.tldr-hint {
  color: var(--color-muted-foreground);
}

/* 降级用 warning 令牌（"不是错误，是暂时没有"—— 与真失败区分开） */
.tldr-hint.is-degraded {
  color: var(--color-on-warning-soft);
  background: var(--color-warning-soft);
  border: 1px solid var(--color-warning-border);
  border-radius: var(--radius-sm);
  padding: var(--space-2) var(--space-3);
  display: inline-block;
}

.tldr-list {
  margin: 0;
  padding-left: var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.tldr-list li::marker {
  color: var(--color-accent);
}

.tldr-foot {
  margin-top: var(--space-3);
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
}

.tldr-retry {
  margin-top: var(--space-2);
  padding: var(--space-1) var(--space-3);
  font-size: var(--text-xs);
  color: var(--color-foreground);
  background: var(--color-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.tldr-retry:hover {
  border-color: var(--color-secondary);
}
</style>