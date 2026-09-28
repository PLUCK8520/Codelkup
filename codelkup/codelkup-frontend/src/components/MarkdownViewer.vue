<script setup lang="ts">
/**
 * Markdown 阅读器。
 *
 * ⚠️ 这里用 v-html 输出 —— 安全性**完全依赖** renderMarkdown() 内部的
 *    DOMPurify 白名单消毒。任何绕过它直接 v-html 原始 Markdown 的改动
 *    都会引入存储型 XSS（后端是原样存 Markdown 原文的）。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { renderMarkdown } from '@/utils/markdown'
import { enhanceCodeBlocks } from '@/composables/useCodeBlocks'

const props = defineProps<{ content?: string | null }>()

const html = computed(() => renderMarkdown(props.content))

/**
 * 代码块增强（语言标签 / 复制 / 行号）。
 *
 * 时机很关键：v-html 每次更新都会<b>整体重建</b>内部 DOM，
 * 之前包好的节点会被丢掉 —— 所以每次 HTML 变化后都要重新增强。
 * 这里用 watcher + nextTick 而不是 onMounted 一处，就是为了覆盖"内容变了"
 * （详情页加载完成、AI 摘要刷新等）而不仅是首次挂载。
 */
const rootRef = ref<HTMLElement | null>(null)

async function enhance() {
  await nextTick()
  enhanceCodeBlocks(rootRef.value)
}

watch(html, enhance)
onMounted(enhance)
</script>

<template>
  <!-- eslint-disable-next-line vue/no-v-html -- 已由 renderMarkdown 内部 DOMPurify 白名单消毒 -->
  <div ref="rootRef" class="markdown-body" v-html="html" />
</template>

<style scoped>
.markdown-body {
  font-size: var(--text-md);
  line-height: var(--leading-relaxed);
  color: var(--color-foreground);
  word-wrap: break-word;
  overflow-wrap: anywhere;
}

/* ---------- 标题 ---------- */
.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: var(--space-8) 0 var(--space-3);
  line-height: var(--leading-tight);
}

.markdown-body :deep(h1) {
  font-size: var(--text-2xl);
  padding-bottom: var(--space-3);
  border-bottom: 1px solid var(--color-border-subtle);
}

.markdown-body :deep(h2) {
  font-size: var(--text-xl);
  padding-bottom: var(--space-2);
  border-bottom: 1px solid var(--color-border-subtle);
}

.markdown-body :deep(h3) {
  font-size: var(--text-lg);
}

.markdown-body :deep(h4) {
  font-size: var(--text-md);
}

.markdown-body :deep(> :first-child) {
  margin-top: 0;
}

/* ---------- 段落与文本 ---------- */
.markdown-body :deep(p) {
  margin: var(--space-4) 0;
}

.markdown-body :deep(strong) {
  font-weight: 600;
  color: var(--color-foreground);
}

.markdown-body :deep(a) {
  color: var(--color-accent);
  text-decoration: underline;
  text-underline-offset: 2px;
  /* 下划线用主色低透明度：装饰弱于文字本身 */
  text-decoration-color: var(--color-accent-border);
}

.markdown-body :deep(a:hover) {
  text-decoration-color: currentColor;
}

.markdown-body :deep(hr) {
  margin: var(--space-8) 0;
  border: none;
  border-top: 1px solid var(--color-border-subtle);
}

/* ---------- 列表 ---------- */
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: var(--space-4) 0;
  padding-left: var(--space-6);
}

.markdown-body :deep(li) {
  margin: var(--space-2) 0;
}

.markdown-body :deep(li::marker) {
  color: var(--color-secondary);
}

/* ---------- 行内代码与代码块 ---------- */
.markdown-body :deep(code) {
  font-family: var(--font-mono);
  font-size: 0.88em;
  padding: 0.15em 0.4em;
  border-radius: var(--radius-sm);
  background: var(--color-muted);
  color: var(--code-keyword);
  border: 1px solid var(--color-border-subtle);
}

.markdown-body :deep(pre) {
  margin: var(--space-5) 0;
  padding: var(--space-4);
  background: var(--code-bg);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  overflow-x: auto; /* 横向滚动而非撑破布局 */
  line-height: 1.6;
}

.markdown-body :deep(pre code) {
  padding: 0;
  border: none;
  background: none;
  color: var(--code-fg);
  font-size: var(--text-sm);
}

/* ---------- 代码块增强（语言标签 / 复制 / 行号） ----------
   结构由 useCodeBlocks.ts 在渲染后包装出来：
     div.code-block > div.code-head(span.code-lang + button.code-copy) + pre > code > span.code-line
   头部走**普通流式布局**（不是绝对定位）—— 这样窄屏下它永远在代码上方，
   不会盖住代码（M2-13 的移动端验收项）。 */
.markdown-body :deep(.code-block) {
  margin: var(--space-5) 0;
  background: var(--code-bg);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.markdown-body :deep(.code-head) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--color-border-subtle);
  background: var(--code-bg-soft);
}

.markdown-body :deep(.code-lang) {
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--color-subtle-foreground);
}

.markdown-body :deep(.code-copy) {
  flex-shrink: 0;
  padding: 2px var(--space-3);
  font-family: inherit;
  font-size: var(--text-xs);
  color: var(--color-muted-foreground);
  background: none;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  transition: all var(--transition-fast);
}

.markdown-body :deep(.code-copy:hover) {
  color: var(--color-foreground);
  background: var(--color-elevated);
  border-color: var(--color-secondary);
}

.markdown-body :deep(.code-copy.copied) {
  color: var(--color-success);
  border-color: var(--color-success);
}

/* 头部已有分隔线与内边距，pre 自身不再重复 */
.markdown-body :deep(.code-block pre) {
  margin: 0;
  border: none;
  border-radius: 0;
}

/* 行号：用 CSS 计数器 + ::before，数字**不是文本节点** ——
   所以复制/框选都拿不到它（textContent 不含伪元素内容，user-select 阻断框选）。 */
.markdown-body :deep(.code-block pre code) {
  display: block;
  counter-reset: code-line;
}

.markdown-body :deep(.code-line) {
  display: block;
  counter-increment: code-line;
}

.markdown-body :deep(.code-line)::before {
  content: counter(code-line);
  display: inline-block;
  width: 2.2em;
  margin-right: var(--space-3);
  text-align: right;
  color: var(--color-subtle-foreground);
  opacity: 0.55;
  user-select: none;
  -webkit-user-select: none;
}

/* ---------- 引用 ---------- */
.markdown-body :deep(blockquote) {
  margin: var(--space-5) 0;
  padding: var(--space-2) var(--space-4);
  border-left: 3px solid var(--color-accent);
  background: var(--color-muted);
  border-radius: 0 var(--radius-md) var(--radius-md) 0;
  color: var(--color-muted-foreground);
}

.markdown-body :deep(blockquote > :first-child) {
  margin-top: 0;
}

.markdown-body :deep(blockquote > :last-child) {
  margin-bottom: 0;
}

/* ---------- 图片 ---------- */
.markdown-body :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border-subtle);
  /* 预留高度避免图片加载完成时内容跳动（CLS）*/
  display: block;
  margin: var(--space-4) auto;
}

/* ---------- 表格 ---------- */
.markdown-body :deep(table) {
  width: 100%;
  margin: var(--space-5) 0;
  border-collapse: collapse;
  font-size: var(--text-sm);
  display: block;
  overflow-x: auto; /* 宽表格横向滚动 */
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border-subtle);
  text-align: left;
}

.markdown-body :deep(th) {
  background: var(--color-muted);
  font-weight: 600;
  white-space: nowrap;
}

.markdown-body :deep(tbody tr:hover) {
  background: var(--color-muted);
}
</style>
