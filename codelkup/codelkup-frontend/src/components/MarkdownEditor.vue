<script setup lang="ts">
/**
 * Markdown 编辑器：左编辑、右实时预览。
 *
 * 预览复用 MarkdownViewer（即已消毒的渲染链路），好处是
 * **所见即所得的真实安全效果** —— 预览里被过滤掉的危险标签，
 * 发表后同样会被过滤，不会出现"预览正常、发布后变样"的落差。
 */
import { computed, ref } from 'vue'
import MarkdownViewer from './MarkdownViewer.vue'

const model = defineModel<string>({ default: '' })

const props = withDefaults(defineProps<{ placeholder?: string; minRows?: number }>(), {
  placeholder: '支持 Markdown：**粗体**、`代码`、```代码块```、> 引用、- 列表、[链接](url)',
  minRows: 16,
})

const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

/** 移动端不并排显示预览，用 tab 切换 */
const mobileTab = ref<'edit' | 'preview'>('edit')

const charCount = computed(() => model.value.length)

/** 在光标处插入语法片段（工具栏按钮用） */
const textareaRef = ref<HTMLTextAreaElement | null>(null)

function insert(before: string, after = '', placeholder = '') {
  const el = textareaRef.value
  if (!el) return

  const start = el.selectionStart
  const end = el.selectionEnd
  const selected = model.value.slice(start, end)
  const inner = selected || placeholder

  model.value = model.value.slice(0, start) + before + inner + after + model.value.slice(end)

  // 恢复焦点并选中插入的内容，便于连续输入
  requestAnimationFrame(() => {
    el.focus()
    el.setSelectionRange(start + before.length, start + before.length + inner.length)
  })
}

const TOOLBAR = [
  { label: '粗体', icon: 'B', action: () => insert('**', '**', '粗体文字') },
  { label: '斜体', icon: 'I', action: () => insert('*', '*', '斜体文字') },
  { label: '标题', icon: 'H', action: () => insert('## ', '', '标题') },
  { label: '引用', icon: '❝', action: () => insert('> ', '', '引用内容') },
  { label: '行内代码', icon: '</>', action: () => insert('`', '`', 'code') },
  { label: '代码块', icon: '{ }', action: () => insert('```java\n', '\n```', '// 代码') },
  { label: '链接', icon: '🔗', action: () => insert('[', '](https://)', '链接文字') },
  { label: '列表', icon: '≡', action: () => insert('- ', '', '列表项') },
]
</script>

<template>
  <div class="md-editor">
    <!-- 工具栏 -->
    <div class="md-toolbar" role="toolbar" aria-label="Markdown 格式工具栏">
      <button
        v-for="tool in TOOLBAR"
        :key="tool.label"
        type="button"
        class="tool-btn"
        :title="tool.label"
        :aria-label="tool.label"
        @click="tool.action()"
      >
        {{ tool.icon }}
      </button>

      <span class="toolbar-spacer" />

      <!-- 移动端切换 -->
      <div class="mobile-switch">
        <button
          type="button"
          class="switch-btn"
          :class="{ active: mobileTab === 'edit' }"
          @click="mobileTab = 'edit'"
        >编辑</button>
        <button
          type="button"
          class="switch-btn"
          :class="{ active: mobileTab === 'preview' }"
          @click="mobileTab = 'preview'"
        >预览</button>
      </div>

      <span class="char-count">{{ charCount }} 字</span>
    </div>

    <!-- 编辑 + 预览 -->
    <div class="md-body">
      <textarea
        ref="textareaRef"
        v-model="model"
        class="md-input"
        :class="{ 'mobile-hidden': mobileTab !== 'edit' }"
        :rows="props.minRows"
        :placeholder="props.placeholder"
        spellcheck="false"
        @input="emit('update:modelValue', model)"
      />

      <div class="md-preview" :class="{ 'mobile-hidden': mobileTab !== 'preview' }">
        <div v-if="!model" class="preview-empty">预览区 —— 开始输入后实时渲染</div>
        <MarkdownViewer v-else :content="model" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.md-editor {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--color-card);
}

/* ---------- 工具栏 ---------- */
.md-toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2);
  background: var(--color-muted);
  border-bottom: 1px solid var(--color-border-subtle);
  flex-wrap: wrap;
}

.tool-btn {
  display: grid;
  place-items: center;
  min-width: 30px;
  height: 28px;
  padding: 0 var(--space-2);
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--color-muted-foreground);
  background: none;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  transition: all var(--transition-fast);
}

.tool-btn:hover {
  color: var(--color-foreground);
  background: var(--color-elevated);
  border-color: var(--color-border);
}

.toolbar-spacer {
  flex: 1;
}

.char-count {
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
  font-variant-numeric: tabular-nums;
  padding-right: var(--space-1);
}

.mobile-switch {
  display: none;
  gap: var(--space-1);
}

.switch-btn {
  padding: var(--space-1) var(--space-3);
  font-size: var(--text-xs);
  color: var(--color-muted-foreground);
  background: none;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.switch-btn.active {
  color: var(--color-foreground);
  background: var(--color-accent-solid);
  border-color: var(--color-accent-solid);
}

/* ---------- 编辑 / 预览 ---------- */
.md-body {
  display: grid;
  grid-template-columns: 1fr 1fr;
  min-height: 380px;
}

.md-input {
  width: 100%;
  padding: var(--space-4);
  font-family: var(--font-mono);
  font-size: var(--text-sm);
  line-height: 1.7;
  color: var(--color-foreground);
  background: var(--color-card);
  border: none;
  border-right: 1px solid var(--color-border-subtle);
  resize: vertical;
  outline: none;
  tab-size: 2;
}

.md-input::placeholder {
  color: var(--color-subtle-foreground);
  line-height: 1.9;
}

.md-input:focus-visible {
  outline: 2px solid var(--color-ring);
  outline-offset: -2px;
}

.md-preview {
  padding: var(--space-4);
  overflow-x: auto;
  background: var(--color-background);
}

.preview-empty {
  display: grid;
  place-items: center;
  height: 100%;
  min-height: 200px;
  font-size: var(--text-sm);
  color: var(--color-subtle-foreground);
}

/* ---------- 响应式 ---------- */
@media (max-width: 768px) {
  .md-body {
    grid-template-columns: 1fr;
  }

  .mobile-switch {
    display: flex;
  }

  .mobile-hidden {
    display: none;
  }

  .md-input {
    border-right: none;
  }
}
</style>
