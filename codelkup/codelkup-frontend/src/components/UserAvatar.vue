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

/**
 * 一组**低饱和**占位色。
 *
 * ⚠️ v3 修订：上一版是 8 个纯饱和色（#dc2626 正红、#c026d3 品红、#7c3aed 紫…）。
 *    同一屏列表里出现 4~5 个互不相关的饱和色块，是"廉价感"最直接的来源 ——
 *    它把视觉重心全抢到边角的小色块上，页面主色（强调蓝）反而失去存在感。
 *    现在换成同族低饱和色（明度接近、饱和度压到 30% 上下），
 *    只在"需要区分不同人"这件事上起作用，不参与视觉竞争。
 *
 * 白字压其上的对比度实测 7.28~8.91:1（见 `.workbuddy/tools/wcag-contrast.py`）。
 */
const PALETTE = [
  '#3f4a8a', '#2f5a5d', '#5a3f6b', '#6b4a3f',
  '#46506b', '#4a5a3f', '#6b3f52', '#3f5a6b',
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
