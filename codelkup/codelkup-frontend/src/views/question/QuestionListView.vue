<script setup lang="ts">
/**
 * 问答专区 —— 把「提问闭环」从首页的一个 tab 提升为一级入口。
 *
 * 后端在 M2-14 就已经把 `type=1`（提问）与 `solved` 筛选做完了，三个 tab
 * 全部翻译成 `/api/posts` 的查询参数；全站计数后来收敛到 `/api/stats`
 * 服务端聚合（见下方 stats 的说明）。
 *
 * tab 与页码都放 URL（`?tab=unsolved&page=2`）：可分享、可刷新、前进后退正常。
 * 与首页同一约定 —— 切 tab / 翻页只改 URL，由 watcher 统一触发加载，
 * 不在事件里再调一次 load（否则"点一次请求两次"）。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { postApi } from '@/api/post'
import { statsApi } from '@/api/stats'
import { useAuthStore } from '@/stores/auth'
import PostCard from '@/components/PostCard.vue'
import PostListSkeleton from '@/components/PostListSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { PostListVO, PostQueryRequest, SiteStatsVO } from '@/types/api'

type TabValue = 'unsolved' | 'latest' | 'solved'

const TABS: { value: TabValue; label: string }[] = [
  { value: 'unsolved', label: '待解决' },
  { value: 'latest', label: '最新提问' },
  { value: 'solved', label: '已解决' },
]

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/** URL 是用户可编辑的：非法 tab 一律回退到「待解决」，不能让它把页面搞崩 */
const tab = computed<TabValue>(
  () => TABS.find((t) => t.value === String(route.query.tab ?? ''))?.value ?? 'unsolved',
)

const posts = ref<PostListVO[]>([])
const total = ref(0)
const pages = ref(0)
const page = ref(1)
const size = 20
const loading = ref(true)
const error = ref('')
/**
 * 全站提问计数（总数 / 待解决 / 已解决）—— 来自 /api/stats 的服务端聚合。
 *
 * ⚠️ 不能用本页列表的 total 当"共 N 个提问"：total 随 tab 变化（"待解决" tab 下
 *    它只是待解决数），同一行会渲染出「共 3 个提问 · 待解决 3 · 已解决 10」
 *    这种自相矛盾的数字。聚合接口口径恒定，三个数永远满足 N = A + B。
 * ⚠️ 失败时为 null：计数行整行不显示数字（见 statsText），而不是把"没拿到"说成 0。
 */
const stats = ref<SiteStatsVO | null>(null)

/**
 * 计数行文案。
 * ⚠️ 失败时**不显示数字**：请求失败时 total/stats 都停在初始的 0，
 *    直接插值会渲染成「共 0 个提问 · 待解决 0」—— 那是把"没拿到"说成了"没有"。
 *    具体原因由下方 el-alert 说明。
 */
const statsText = computed(() => {
  if (loading.value) return '加载中…'
  if (error.value || !stats.value) return '问题数据暂时没取到'
  return `共 ${stats.value.questionCount} 个提问 · 待解决 ${stats.value.questionUnsolvedCount} · 已解决 ${stats.value.questionSolvedCount}`
})

/** tab → 查询参数。`solved` 只在提问帖上有意义，所以每次都带着 type=1 */
function buildQuery(value: TabValue, p: number): PostQueryRequest {
  const query: PostQueryRequest = { type: 1, sort: 'latest', page: p, size }
  if (value === 'unsolved') query.solved = false
  if (value === 'solved') query.solved = true
  return query
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    // 列表 + 全站计数（服务端聚合）：计数不再是"本页 total 的分身"，
    // 也不再需要两个 size=1 的探针请求 —— 3 个请求收敛成 2 个。
    const [list, siteStats] = await Promise.all([
      postApi.list(buildQuery(tab.value, page.value)),
      statsApi.overview(),
    ])
    posts.value = list.records
    total.value = list.total
    pages.value = list.pages
    stats.value = siteStats
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
    posts.value = []
  } finally {
    loading.value = false
  }
}

function changeTab(value: TabValue) {
  if (value === tab.value) return
  // 切 tab 时把 page 一起清掉，否则从"待解决第 3 页"切到"已解决"会落在一个空页上
  router.push({ query: { tab: value === 'unsolved' ? undefined : value, page: undefined } })
}

function changePage(p: number) {
  router.push({ query: { ...route.query, page: p > 1 ? p : undefined } })
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

/** 提问入口：游客先去登录，登录后直接落到预选了「提问」的编辑器 */
function onAsk() {
  if (!auth.isLoggedIn) {
    ElMessage.warning('请先登录后再提问')
    router.push({ name: 'login', query: { redirect: '/publish?type=1' } })
    return
  }
  router.push({ name: 'post-create', query: { type: '1' } })
}

const empty = computed(() => {
  if (tab.value === 'unsolved') {
    return {
      title: '暂时没有待解决的问题',
      description: '所有提问都已经有采纳的答案了 —— 去「已解决」翻翻别人的解法，或自己提一个问题',
      action: '我要提问',
    }
  }
  if (tab.value === 'solved') {
    return {
      title: '还没有已解决的提问',
      description: '答案被提问者采纳之后，这条提问会出现在这里',
      action: '去看待解决',
    }
  }
  return {
    title: '还没有人提问',
    description: '把卡住你的问题写清楚：环境、报错、已经试过什么 —— 社区里也许有人刚踩过同一个坑',
    action: '我要提问',
  }
})

function onEmptyAction() {
  if (tab.value === 'solved') {
    changeTab('unsolved')
    return
  }
  onAsk()
}

watch(
  () => [route.query.tab, route.query.page],
  async () => {
    page.value = Number(route.query.page) || 1
    await load()
  },
  { immediate: true },
)
</script>

<template>
  <div class="container question-page">
    <header class="page-head">
      <div>
        <h1 class="page-title">问答</h1>
        <p class="page-desc">
          提问 · 回答 · 采纳 —— 采纳率会计入回答者的声望
        </p>
      </div>
      <el-button type="primary" @click="onAsk">我要提问</el-button>
    </header>

    <!-- 筛选 + 计数 -->
    <div class="toolbar">
      <div class="sort-tabs" role="tablist" aria-label="问题状态">
        <button
          v-for="t in TABS"
          :key="t.value"
          type="button"
          role="tab"
          class="sort-tab"
          :class="{ active: tab === t.value }"
          :aria-selected="tab === t.value"
          @click="changeTab(t.value)"
        >
          {{ t.label }}
        </button>
      </div>
      <span class="result-count">{{ statsText }}</span>
    </div>

    <PostListSkeleton v-if="loading" :count="5" />

    <el-alert v-else-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button text type="primary" @click="load">重试</el-button>
    </el-alert>

    <EmptyState
      v-else-if="!posts.length"
      icon="post"
      :title="empty.title"
      :description="empty.description"
      :action-text="empty.action"
      @action="onEmptyAction"
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
.question-page {
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

.page-title {
  font-size: var(--text-2xl);
  letter-spacing: -0.02em;
}

.page-desc {
  margin-top: var(--space-1);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
  padding-bottom: var(--space-3);
  border-bottom: 1px solid var(--color-border-subtle);
  flex-wrap: wrap;
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