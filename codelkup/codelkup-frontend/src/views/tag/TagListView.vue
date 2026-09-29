<script setup lang="ts">
/**
 * 标签总览。
 *
 * ⚠️ 口径要说清楚：后端只有「热门标签榜」(`/tags/hot`)，上限 50 条（服务端
 *    `Math.min(limit, 50)` 夹住的），所以这是一个**按引用次数排序的前 50**，
 *    不是全量标签表 —— 页面文案照实写，不假装它是全部。
 *    真要全量就得加一个分页接口，那属于后端改动，本轮（B 档）不做。
 */
import { computed, onMounted, ref } from 'vue'
import { tagApi } from '@/api/post'
import { useStatsStore } from '@/stores/stats'
import type { TagVO } from '@/types/api'

const TAG_LIMIT = 50

const tags = ref<TagVO[]>([])
const loading = ref(true)
const error = ref('')

/**
 * 标签总数（来自 /api/stats 的服务端聚合）。
 *
 * 为什么需要它：热门榜上限 50，列表长度不是全量 —— 没有总数时文案只能说
 * "引用最多的 N 个标签"（诚实但含糊）。拿到总数后可以像搜索页那样直说
 * "共 X 个标签"，被上限截断时再明说"列出前 N 个"。
 * 拿不到（null）时退回旧文案：它是锦上添花，不该让整页失败。
 *
 * 2026-09-29：改读共享 store（页脚 / 首页 / 问答页同一份，在途去重 + 30s TTL）——
 * 此前本页与页脚会对同一个 URL 各发一次请求。用 computed 顺带修掉一个时序问题：
 * 直接赋值的话，"进页面时 stats 还没回来"就永远停在降级文案，等它回来也不再更新。
 */
const statsStore = useStatsStore()
const tagTotal = computed(() => statsStore.data?.tagCount ?? null)

/**
 * 计数行文案。
 * ⚠️ 失败时**不显示数字**：请求失败时 tags 是空数组，直接插值会渲染成
 *    「引用最多的 0 个标签」—— 那是把"没拿到"说成了"没有"。
 */
const countText = computed(() => {
  if (loading.value) return '加载中…'
  if (error.value) return '标签列表暂时没取到'
  // 总数没拿到：退回旧文案（诚实但不精确）
  if (tagTotal.value === null) {
    return `引用最多的 ${tags.value.length} 个标签 · 点进去看同标签下的讨论`
  }
  // 总数拿到了：被上限截断时明说"前 N 个"，否则直报总数（与搜索页"共 9 个标签"同口径）
  const shown = tags.value.length
  const capped = tagTotal.value > shown ? ` · 按引用次数列出前 ${shown} 个` : ''
  return `共 ${tagTotal.value} 个标签${capped} · 点进去看同标签下的讨论`
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    // 绕开 meta store 的 20 条缓存：这里要的就是"尽可能全"，改 store 的默认值
    // 会让首页右栏也一起变宽，不如各取所需
    tags.value = await tagApi.hot(TAG_LIMIT)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '标签加载失败'
    tags.value = []
  } finally {
    loading.value = false
  }

  // 总数走共享 store：与页脚共用同一份（在途去重 + 30s TTL）。
  // 它依旧是"锦上添花"——失败只影响文案精度，不影响标签列表本身
  statsStore.ensure()
}

onMounted(load)
</script>

<template>
  <div class="container tags-page">
    <header class="page-head">
      <div>
        <h1 class="page-title">标签</h1>
        <p class="page-desc">{{ countText }}</p>
      </div>
    </header>

    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button text type="primary" @click="load">重试</el-button>
    </el-alert>

    <div v-else class="tag-grid">
      <RouterLink
        v-for="(tag, i) in tags"
        :key="tag.id"
        :to="{ name: 'tag-detail', params: { id: tag.id } }"
        class="tag-card anim-in"
        :style="{ '--i': Math.min(i, 8) }"
      >
        <span class="tag-name">#{{ tag.name }}</span>
        <span class="tag-count">{{ tag.useCount ?? 0 }}</span>
      </RouterLink>
    </div>
  </div>
</template>

<style scoped>
/* 标签用"云"而不是卡片网格：标签名长短差异大，网格会让长短不一的卡片显得参差 */
.tag-grid {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.tag-card {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  background: var(--color-card);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  transition: all var(--transition-fast);
}

.tag-card:hover {
  color: var(--color-foreground);
  background: var(--color-card-hover);
  border-color: var(--color-border);
}

.tag-name {
  font-family: var(--font-mono);
  font-size: var(--text-xs);
}

.tag-count {
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
  font-variant-numeric: tabular-nums;
}
</style>