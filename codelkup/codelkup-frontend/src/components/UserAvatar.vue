<script setup lang="ts">
/**
 * 用户头像。
 *
 * 无头像时用「昵称首字 + 稳定派生色」生成占位 —— 比一张默认灰图有辨识度，
 * 且颜色由 id/昵称哈希决定，同一用户每次刷新颜色一致。
 */
import { computed, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    src?: string
    name?: string
    size?: number
    /** 用于颜色种子，优先用 id 保证稳定 */
    seed?: number | string
  }>(),
  { size: 32, name: '匿名' },
)

/** 一组在深色背景上对比度都 ≥4.5:1 的颜色 */
const PALETTE = [
  '#2563eb', '#0891b2', '#059669', '#ca8a04',
  '#dc2626', '#c026d3', '#7c3aed', '#db2777',
]

const initial = computed(() => {
  const n = (props.name || '?').trim()
  return n ? n.charAt(0).toUpperCase() : '?'
})

const seedColor = computed(() => {
  const seed = String(props.seed ?? props.name ?? '')
  let hash = 0
  for (let i = 0; i < seed.length; i++) {
    hash = (hash * 31 + seed.charCodeAt(i)) >>> 0
  }
  return PALETTE[hash % PALETTE.length]
})

const style = computed(() => ({
  width: `${props.size}px`,
  height: `${props.size}px`,
  fontSize: `${Math.max(11, Math.round(props.size * 0.42))}px`,
  background: props.src ? 'transparent' : seedColor.value,
}))

/** 图片加载失败时回落到首字占位，避免出现破图图标 */
const broken = ref(false)
</script>

<template>
  <img
    v-if="src && !broken"
    :src="src"
    :alt="`${name} 的头像`"
    :style="style"
    class="avatar"
    loading="lazy"
    decoding="async"
    @error="broken = true"
  />
  <span v-else class="avatar avatar-fallback" :style="style" :aria-label="`${name} 的头像`">
    {{ initial }}
  </span>
</template>

<style scoped>
.avatar {
  display: inline-grid;
  place-items: center;
  flex-shrink: 0;
  border-radius: var(--radius-full);
  object-fit: cover;
  color: #fff;
  font-weight: 600;
  line-height: 1;
  user-select: none;
  border: 1px solid var(--color-border-subtle);
}

.avatar-fallback {
  /* 无图时的首字占位 */
}
</style>
