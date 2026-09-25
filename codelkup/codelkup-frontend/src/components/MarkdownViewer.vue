<script setup lang="ts">
/**
 * Markdown 阅读器。
 *
 * ⚠️ 这里用 v-html 输出 —— 安全性**完全依赖** renderMarkdown() 内部的
 *    DOMPurify 白名单消毒。任何绕过它直接 v-html 原始 Markdown 的改动
 *    都会引入存储型 XSS（后端是原样存 Markdown 原文的）。
 */
import { computed } from 'vue'
import { renderMarkdown } from '@/utils/markdown'

const props = defineProps<{ content?: string | null }>()

const html = computed(() => renderMarkdown(props.content))
</script>

<template>
  <!-- eslint-disable-next-line vue/no-v-html -- 已由 renderMarkdown 内部 DOMPurify 白名单消毒 -->
  <div class="markdown-body" v-html="html" />
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
  text-decoration-color: rgba(59, 130, 246, 0.4);
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
  color: #f0abfc;
  border: 1px solid var(--color-border-subtle);
}

.markdown-body :deep(pre) {
  margin: var(--space-5) 0;
  padding: var(--space-4);
  background: #0b1120;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  overflow-x: auto; /* 横向滚动而非撑破布局 */
  line-height: 1.6;
}

.markdown-body :deep(pre code) {
  padding: 0;
  border: none;
  background: none;
  color: #e2e8f0;
  font-size: var(--text-sm);
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
  background: rgba(30, 41, 59, 0.5);
}
</style>
