<script setup lang="ts">
/**
 * 标签详情 —— 某个标签下的讨论列表。
 *
 * 后端没有「按 id 取标签」的单查接口（本轮是 B 档：零后端改动），标签名有两条
 * 就地取回的路径，按可靠性排序：
 *   1. 列表里第一条帖子的 `tags[]` —— 既然是被 `tagId` 筛出来的，它必然带上这个标签
 *   2. meta store 的热门标签（标签不在热榜里、或列表为空时用不上）
 * 两条都没有（空榜单 + 无人引用）时退化成 `#id`，页面照常可用而不是报错。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { postApi } from '@/api/post'
import { useMetaStore } from '@/stores/meta'
import PostCard from '@/components/PostCard.vue'
import PostListSkeleton from '@/components/PostListSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { PostListVO, PostQueryRequest } from '@/types/api'

type SortValue = 'latest' | 'hot'

const SORTS: { value: SortValue; label: string }[] = [
  { value: 'latest', label: '最新' },
  { value: 'hot', label: '热度' },
]

const route = useRoute()
const router = useRouter()
const meta = useMetaStore()

const tagId = computed(() => Number(route.params.id))
const sort = computed<SortValue>(() => (route.query.sort === 'hot' ? 'hot' : 'latest'))

const posts = ref<PostListVO[]>([])
const total = ref(0)
const pages = ref(0)
const page = ref(1)
const size = 20
const loading = ref(true)
const error = ref('')

/**
 * 计数行文案。
 * ⚠️ 失败时**不显示数字**：请求失败时 total 停在 0，直接插值会渲染成
 *    「共 0 篇讨论」—— 那是把"没拿到"说成了"没有"（这个标签可能也有帖子）。
 */
const countText = computed(() => {
  if (loading.value) return '加载中…'
  if (error.value) return '讨论数暂时没取到'
  return `共 ${total.value} 篇讨论`
})

/** 标签名：优先取自列表数据，其次查热门标签缓存 */
const tagName = computed(() => {
  const fromPosts = posts.value.flatMap((p) => p.tags ?? []).find((t) => t.id === tagId.value)
  if (fromPosts) return fromPosts.name
  return meta.hotTags.find((t) => t.id === tagId.value)?.name ?? ''
})

const heading = computed(() => (tagName.value ? `#${tagName.value}` : `#${tagId.value}`))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const query: PostQueryRequest = {
      tagId: tagId.value,
      sort: sort.value,
      page: page.value,
      size,
    }
    const res = await postApi.list(query)
    posts.value = res.records
    total.value = res.total
    pages.value = res.pages
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
    posts.value = []
  } finally {
    loading.value = false
  }
}

function changeSort(value: SortValue) {
  if (value === sort.value) return
  router.push({ query: { sort: value === 'latest' ? undefined : value, page: undefined } })
}

function changePage(p: number) {
  router.push({ query: { ...route.query, page: p > 1 ? p : undefined } })
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

watch(
  () => [route.params.id, route.query.sort, route.query.page],
  async () => {
    page.value = Number(route.query.page) || 1
    // 换标签时先清空，否则会把上一个标签的帖子算进"标签名"的推断里
    posts.value = []
    await load()
  },
  { immediate: true },
)

// 顺手把热门标签灌进缓存：既能让上面的 tagName 兜底，也让首页右栏不再重复请求
meta.loadHotTags().catch(() => {})
</script>

<template>
  <div class="container tag-page">
    <header class="page-head">
      <div>
        <RouterLink :to="{ name: 'tags' }" class="crumb">← 全部标签</RouterLink>
        <h1 class="page-title">{{ heading }}</h1>
        <p class="page-desc">{{ countText }}</p>
      </div>
      <el-button type="primary" @click="router.push({ name: 'post-create' })">发布帖子</el-button>
    </header>

    <div class="toolbar">
      <div class="sort-tabs" role="tablist" aria-label="排序方式">
        <button
          v-for="s in SORTS"
          :key="s.value"
          type="button"
          role="tab"
          class="sort-tab"
          :class="{ active: sort === s.value }"
          :aria-selected="sort === s.value"
          @click="changeSort(s.value)"
        >
          {{ s.label }}
        </button>
      </div>
      <span class="result-count">按标签筛选</span>
    </div>

    <PostListSkeleton v-if="loading" :count="5" />

    <el-alert v-else-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button text type="primary" @click="load">重试</el-button>
    </el-alert>

    <EmptyState
      v-else-if="!posts.length"
      icon="post"
      title="这个标签下还没有帖子"
      description="发帖时在标签框里输入它，就能把帖子归到这里 —— 标签不存在会自动创建"
      action-text="去发帖"
      @action="router.push({ name: 'post-create' })"
    />

    <div v-else class="post-list">
      <PostCard
        v-for="(post, i) in posts"
        :key="post.id"
        :post="post"
        class="anim-in"
        :style="{ '--i': Math.min(i, 8) }"
      />
    </div>

    <div v-if="!loading && pages > 1" class="pagination">
      <el-pagination
        layout="prev, pager, next"
        :current-page="page"
        :page-size="size"
        :total="total"
        background
        hide-on-single-page
        @current-change="changePage"
      />
    </div>
  </div>
</template>

<style scoped>
.tag-page {
  max-width: var(--container-narrow);
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: var(--space-5);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--color-border-subtle);
}

.crumb {
  display: inline-block;
  margin-bottom: var(--space-2);
  font-size: var(--text-xs);
  color: var(--color-muted-foreground);
}

.crumb:hover {
  color: var(--color-accent);
}

.page-title {
  font-family: var(--font-mono);
  font-size: var(--text-2xl);
  letter-spacing: var(--tracking-heading);
}

.page-desc {
  margin-top: var(--space-1);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  font-variant-numeric: tabular-nums;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
  padding-bottom: var(--space-3);
  border-bottom: 1px solid var(--color-border-subtle);
}

.sort-tabs {
  display: flex;
  gap: var(--space-1);
}

.sort-tab {
  padding: var(--space-1) var(--space-3);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  background: none;
  border: none;
  border-radius: var(--radius-sm);
  transition: all var(--transition-fast);
}

.sort-tab:hover {
  color: var(--color-foreground);
  background: var(--color-muted);
}

.sort-tab.active {
  color: var(--color-foreground);
  background: var(--color-muted);
  font-weight: 500;
}

.result-count {
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
}

.post-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: var(--space-6);
}

@media (max-width: 640px) {
  .page-head {
    flex-direction: column;
  }

  .page-title {
    font-size: var(--text-xl);
  }
}
</style>