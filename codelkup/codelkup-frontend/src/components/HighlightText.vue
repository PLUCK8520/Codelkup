<script setup lang="ts">
/**
 * 关键词高亮文本。
 *
 * ⚠️ 本组件渲染的是<b>可能来自用户输入</b>的内容，但<b>不使用 v-html</b> ——
 *    全靠 v-for 渲染纯文本片段。原因见 utils/highlight.ts 的说明：
 *    拼 HTML 字符串再 v-html 需要正确转义每一个片段，漏一处就是 XSS；
 *    而 Vue 的插值语法天然转义，安全性由框架保证而非"记得转义"。
 */
import { computed } from 'vue'
import { splitByKeywords } from '@/utils/highlight'

const props = defineProps<{
  /** 原始纯文本（不是 Markdown 源码） */
  text?: string | null
  /** 用户输入的查询词，可含空格（多词） */
  keyword?: string | null
}>()

const segments = computed(() => splitByKeywords(props.text, props.keyword))
</script>

<template>
  <!--
    <template v-for> 包一层再在内层用 v-if：
    Vue 3 里同一元素上的 v-if 优先级高于 v-for，直接写会拿不到循环变量。
    外层 template 不产生真实 DOM，所以不会多套标签。
  -->
  <span class="highlight-text"><template v-for="(seg, index) in segments" :key="index"><mark
        v-if="seg.hit"
        class="highlight-mark"
      >{{ seg.text }}</mark><template v-else>{{ seg.text }}</template></template></span>
</template>

<style scoped>
.highlight-text {
  /* 让片段之间不会被换行规则拆开得太难看 */
  word-break: break-word;
}

.highlight-mark {
  padding: 0 2px;
  color: var(--color-highlight-fg);
  background: var(--color-highlight-bg);
  border-radius: 3px;
  font-weight: 600;
}
</style>
