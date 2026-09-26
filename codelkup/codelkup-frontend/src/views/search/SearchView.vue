<script setup lang="ts">
/**
 * 搜索结果页。
 *
 * 状态全部放在 URL query 里（keyword / sort / page），原因：
 *  - 搜索结果可以分享、可以刷新、可以前进后退
 *  - 避免"组件内 state + URL"两套状态不同步的经典 bug
 *
 * 高亮由 PostCard 内部通过 HighlightText 完成（纯文本切片，不用 v-html）。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { searchApi } from '@/api/search'
import PostCard from '@/components/PostCard.vue'
import PostListSkeleton from '@/components/PostListSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { PostListVO, PostSearchRequest } from '@/types/api'

const route = useRoute()
const router = useRouter()

/** 已提交的关键词（来自 URL）—— 用于请求与高亮 */
const keyword = ref('')
/** 输入框的临时值：只有回车/点击才提交，避免输入过程中反复请求 */
const input = ref('')

const posts = ref<PostListVO[]>([])
const total = ref(0)
const pages = ref(0)
const loading = ref(false)
const error = ref('')
const page = ref(1)
const size = 20

const SORTS = [
  { value: 'relevance', label: '相关性' },
  { value: 'latest', label: '最新' },
  { value: 'hot', label: '热度' },
] as const

const currentSort = computed(() => (route.query.sort as string) || 'relevance')

/** 只有真正提交过关键词才算"搜索过"，否则显示引导态 */
const hasQuery = computed(() => keyword.value.length > 0)

async function load() {
  if (!hasQuery.value) {
    posts.value = []
    total.value = 0
    pages.value = 0
    return
  }

  loading.value = true
  error.value = ''
  try {
    const req: PostSearchRequest = {
      keyword: keyword.value,
      sort: currentSort.value as PostSearchRequest['sort'],
      page: page.value,
      size,
    }
    const res = await searchApi.searchPosts(req)
    posts.value = res.records
    total.value = res.total
    pages.value = res.pages
  } catch (e) {
    error.value = e instanceof Error ? e.message : '搜索失败'
    posts.value = []
  } finally {
    loading.value = false
  }
}

/** 提交搜索：清掉旧的分页与排序，回到默认视图 */
function onSubmit() {
  const q = input.value.trim()
  if (!q) {
    ElMessage.info('请输入搜索关键词')
    return
  }
  if (q === keyword.value && !route.query.page && !route.query.sort) {
    return // 完全相同的查询，不必重新走一遍路由
  }
  router.push({ name: 'search', query: { keyword: q } })
}

function changeSort(sort: string) {
  router.push({ query: { ...route.query, sort, page: undefined } })
}

function changePage(p: number) {
  page.value = p
  router.push({ query: { ...route.query, page: p > 1 ? p : undefined } })
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

watch(
  () => [route.query.keyword, route.query.sort, route.query.page],
  async () => {
    keyword.value = String(route.query.keyword ?? '').trim()
    input.value = keyword.value
    page.value = Number(route.query.page) || 1
    await load()
  },
  { immediate: true },
)
</script>

<template>
  <div class="container search-page">
    <!-- ==================== 搜索框 ==================== -->
    <header class="search-head">
      <h1 class="page-title">搜索</h1>
      <!-- 回车交给表单的隐式提交（input 在 form 内、且有 native-type=submit 的按钮）：
           再单独绑 @keyup.enter 会让一次回车触发两次 onSubmit -->
      <form class="search-form" @submit.prevent="onSubmit">
        <el-input
          v-model="input"
          size="large"
          placeholder="搜索帖子标题与正文，支持中文关键词…"
          clearable
        >
          <template #prefix>
            <svg
              viewBox="0 0 24 24"
              width="17"
              height="17"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              aria-hidden="true"
            >
              <circle cx="11" cy="11" r="8" />
              <path d="m21 21-4.35-4.35" />
            </svg>
          </template>
        </el-input>
        <el-button type="primary" size="large" native-type="submit">搜索</el-button>
      </form>
    </header>

    <!-- ==================== 未输入关键词：引导态 ==================== -->
    <div v-if="!hasQuery" class="hint-card">
      <p class="hint-title">输入关键词开始搜索</p>
      <p class="hint-text">
        检索范围覆盖<b>帖子标题与正文</b>，支持中文分词。
        搜索结果会标出命中的关键词片段。
      </p>
    </div>

    <template v-else>
      <!-- ==================== 工具栏 ==================== -->
      <div class="toolbar">
        <div class="sort-tabs" role="tablist" aria-label="排序方式">
          <button
            v-for="s in SORTS"
            :key="s.value"
            type="button"
            role="tab"
            class="sort-tab"
            :class="{ active: currentSort === s.value }"
            :aria-selected="currentSort === s.value"
            @click="changeSort(s.value)"
          >
            {{ s.label }}
          </button>
        </div>
        <span class="result-count">
          {{ loading ? '搜索中…' : `「${keyword}」找到 ${total} 条结果` }}
        </span>
      </div>

      <PostListSkeleton v-if="loading" :count="5" />

      <el-alert
        v-else-if="error"
        type="error"
        :title="error"
        :closable="false"
        show-icon
        class="error-alert"
      >
        <el-button text type="primary" @click="load">重试</el-button>
      </el-alert>

      <!-- 空结果：给出可操作的建议，而不是一句"没有结果" -->
      <div v-else-if="!posts.length" class="hint-card">
        <p class="hint-title">没有找到「{{ keyword }}」相关的帖子</p>
        <p class="hint-text">
          可以试试：<br />
          · 换一个更长或更常见的关键词（少于 2 个字会退化为模糊匹配，结果可能不准）<br />
          · 减少限定条件，或用更宽泛的词<br />
          · 检查是否有拼写错误
        </p>
      </div>

      <div v-else class="post-list">
        <!-- 传入 keyword，让标题与命中片段标红；错峰入场同首页 -->
        <PostCard
          v-for="(post, i) in posts"
          :key="post.id"
          :post="post"
          :keyword="keyword"
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
    </template>
  </div>
</template>

<style scoped>
.search-page {
  max-width: var(--container-narrow);
}

/* ---------- 页头 ---------- */
.search-head {
  margin-bottom: var(--space-5);
}

.page-title {
  margin-bottom: var(--space-4);
  font-size: var(--text-2xl);
  letter-spacing: -0.02em;
}

.search-form {
  display: flex;
  gap: var(--space-3);
  align-items: center;
}

.search-form :deep(.el-input) {
  flex: 1;
}

/* ---------- 工具栏 ---------- */
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
  font-variant-numeric: tabular-nums;
}

/* ---------- 列表 / 提示卡 ---------- */
.post-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.error-alert {
  margin-bottom: var(--space-4);
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: var(--space-6);
}

.hint-card {
  padding: var(--space-8) var(--space-5);
  text-align: center;
  background: var(--color-card);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
}

.hint-title {
  font-size: var(--text-md);
  font-weight: 600;
  color: var(--color-foreground);
}

.hint-text {
  margin-top: var(--space-3);
  font-size: var(--text-sm);
  line-height: var(--leading-relaxed);
  color: var(--color-muted-foreground);
}

/* ---------- 响应式 ---------- */
@media (max-width: 640px) {
  .page-title {
    font-size: var(--text-xl);
  }

  .search-form {
    flex-direction: column;
    align-items: stretch;
  }

  .toolbar {
    flex-wrap: wrap;
  }
}
</style>
