<script setup lang="ts">
/**
 * Markdown 编辑器：左编辑、右实时预览。
 *
 * 预览复用 MarkdownViewer（即已消毒的渲染链路），好处是
 * **所见即所得的真实安全效果** —— 预览里被过滤掉的危险标签，
 * 发表后同样会被过滤，不会出现"预览正常、发布后变样"的落差。
 */
import { computed, ref } from 'vue'
import { useImagePaste } from '@/composables/useImagePaste'
import MarkdownViewer from './MarkdownViewer.vue'

const model = defineModel<string>({ default: '' })

const props = withDefaults(defineProps<{ placeholder?: string; minRows?: number }>(), {
  placeholder: '支持 Markdown：**粗体**、`代码`、```代码块```、> 引用、- 列表、[链接](url)',
  minRows: 16,
})

/**
 * ⚠️ 不要在 textarea 上再写 `@input="emit('update:modelValue', model)"`。
 *
 * 这行曾经存在，看起来"更保险"，实际是重复上报同一次输入：
 * v-model 自己就会在 input 时上报（`update:modelValue` 已由 defineModel 声明），
 * 而多出来的那次读到的是**尚未更新**的旧值 —— 原生元素的 v-model 由
 * vModelText 指令实现，赋值时机与模板上的 `@input` 监听器不是一回事，
 * 最终哪个值生效取决于监听器注册顺序。一次输入被上报两次、其中一次是过期值，
 * 属于"能跑但说不清"的写法，已删除，输入只经 v-model 一条路径。
 */

/** 移动端不并排显示预览，用 tab 切换 */
const mobileTab = ref<'edit' | 'preview'>('edit')

const charCount = computed(() => model.value.length)

/** 在光标处插入语法片段（工具栏按钮用） */
const textareaRef = ref<HTMLTextAreaElement | null>(null)

/**
 * 在光标处插入原文（不包裹、不占位）—— 图片这样的"成品片段"用它。
 * 有选区时用插入内容替换选区，这是所有编辑器的共同预期。
 */
function insertAtCursor(text: string) {
  const el = textareaRef.value
  if (!el) {
    // 极端情况（组件未挂载出 textarea）：退化为追加，至少不丢内容
    model.value += text
    return
  }

  const start = el.selectionStart
  const end = el.selectionEnd
  model.value = model.value.slice(0, start) + text + model.value.slice(end)

  // 光标落在插入内容之后，便于继续输入
  requestAnimationFrame(() => {
    el.focus()
    el.setSelectionRange(start + text.length, start + text.length)
  })
}

function insert(before: string, after = '', placeholder = '') {
  const el = textareaRef.value
  if (!el) return

  const selected = model.value.slice(el.selectionStart, el.selectionEnd)
  const inner = selected || placeholder

  insertAtCursor(before + inner + after)

  // 选中刚插入的内容（而非光标落在末尾），便于连续输入
  const start = el.selectionStart
  const beforeLength = before.length
  requestAnimationFrame(() => {
    el.setSelectionRange(start + beforeLength, start + beforeLength + inner.length)
  })
}

/* ==================== 图片（粘贴 / 选择） ==================== */

const fileInputRef = ref<HTMLInputElement | null>(null)

const {
  uploading: imageUploading,
  onPaste: onPasteImage,
  uploadAndInsert: uploadImage,
} = useImagePaste({ insert: insertAtCursor })

/** 工具栏"插入图片"：打开文件选择器（移动端同样可用，比粘贴更可靠） */
function pickImage() {
  fileInputRef.value?.click()
}

async function onImagePicked(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  // 先清空 input：否则连续选同一个文件不会再触发 change
  input.value = ''
  if (file) {
    await uploadImage(file)
  }
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

      <!-- 插入图片：粘贴与选文件两条路都通（移动端没有粘贴板图片，只能选文件） -->
      <button
        type="button"
        class="tool-btn"
        :class="{ busy: imageUploading }"
        :title="imageUploading ? '图片上传中…' : '插入图片（也可直接粘贴）'"
        aria-label="插入图片"
        :disabled="imageUploading"
        @click="pickImage"
      >
        {{ imageUploading ? '…' : '🖼' }}
      </button>
      <input
        ref="fileInputRef"
        type="file"
        accept="image/png,image/jpeg,image/webp"
        class="file-input"
        @change="onImagePicked"
      />

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
        @paste="onPasteImage"
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

/* 上传中的图片按钮：占位宽度不变，避免工具栏抖动 */
.tool-btn.busy {
  color: var(--color-accent);
  cursor: progress;
}

/* 隐藏的文件选择器：用隐藏 input + 按钮触发，是为了让按钮能带 aria-label 与禁用态 */
.file-input {
  display: none;
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

/* ⚠️ 激活态必须是**白字**压蓝底（--color-on-accent）：
   深色字（--color-foreground）压 #2563EB 只有 3.35:1，不达标 —— v5 的
   "浅字压橙底"其实是同一类错误，v6 一并按对比度结论修正。 */
.switch-btn.active {
  color: var(--color-on-accent);
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
