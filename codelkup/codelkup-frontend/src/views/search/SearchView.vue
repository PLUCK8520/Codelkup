<script setup lang="ts">
/**
 * 搜索结果页。
 *
 * 状态全部放在 URL query 里（keyword / sort / board / tag / author / page），原因：
 *  - 搜索结果可以分享、可以刷新、可以前进后退
 *  - 避免"组件内 state + URL"两套状态不同步的经典 bug
 *
 * ★ 本轮新增「高级筛选」：板块 / 标签 / 作者三个维度（零后端改动）。
 *   后端 `/api/search/posts` 一直支持 boardId / tagId / authorId，
 *   而前端此前只传 keyword —— 等于把已经做好的能力藏起来了。
 *   实测（基准帖反向验证）：正确 boardId/tagId/authorId 命中该帖，
 *   错误值（board 99 / tag 1 / author 1）返回 0 条，三者可叠加。
 *
 *   ⚠️ 一个硬约束决定了交互形态：后端 keyword 是 @NotBlank（空关键词 → 40001），
 *      所以筛选只能**收窄**搜索结果，不能空词浏览。因此筛选条只在
 *      "有关键词"或"已带筛选条件（从分享链接进来）"时出现。
 *      作者筛选的入口在帖子详情页的「关于作者」卡片（/search?author=<id>）。
 *
 * 高亮由 PostCard 内部通过 HighlightText 完成（纯文本切片，不用 v-html）。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { searchApi } from '@/api/search'
import { userApi } from '@/api/user'
import { useMetaStore } from '@/stores/meta'
import PostCard from '@/components/PostCard.vue'
import PostListSkeleton from '@/components/PostListSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { PostListVO, PostSearchRequest } from '@/types/api'

const route = useRoute()
const router = useRouter()
const meta = useMetaStore()

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

/* ==================== 高级筛选：URL → 请求参数 ==================== */

/**
 * 归一化 URL 上的 id 参数。
 * ⚠️ 必须挡住三种"看起来像数字但不是 id"的输入：空串（`?board=`）、
 *    非数字（`?board=abc`）、0 与负数 —— 否则会把垃圾参数原样发给后端。
 */
function toId(v: unknown): number | undefined {
  if (v === undefined || v === null || v === '') return undefined
  const n = Number(v)
  return Number.isFinite(n) && n > 0 ? n : undefined
}

const boardId = computed(() => toId(route.query.board))
const tagId = computed(() => toId(route.query.tag))
const authorId = computed(() => toId(route.query.author))

const hasFilters = computed(
  () => boardId.value !== undefined || tagId.value !== undefined || authorId.value !== undefined,
)

const boardName = computed(() => meta.boards.find((b) => b.id === boardId.value)?.name)
const tagName = computed(() => meta.hotTags.find((t) => t.id === tagId.value)?.name)

/**
 * 标签下拉的候选数量上限 —— 与 meta store 的请求口径一致
 * （`tagApi.hot()` 默认 20，见 api/post.ts）。
 *
 * ⚠️ 用它来判断文案该说哪一句：候选数**没到上限**时说明这已经是全部标签，
 *    此时还说"仅列出前 20 个"会让用户以为还有更多没显示（真实标签数会随时间变化，
 *    不能写死一句）。
 */
const TAG_LIMIT = 20
/** 候选就是全部标签时说明全拿到了；到上限才需要声明"只列了热门的一部分" */
const tagsAreComplete = computed(() => meta.hotTags.length < TAG_LIMIT)

/** 作者昵称：只有 id 时向后端要一次；拿不到就退化成「作者 #id」，不影响搜索本身 */
const authorName = ref('')
watch(
  authorId,
  async (id) => {
    authorName.value = ''
    if (!id) return
    try {
      const user = await userApi.getProfile(id)
      authorName.value = user.nickname
    } catch {
      // 静默降级：昵称只是标签文案，搜索条件不受影响
    }
  },
  { immediate: true },
)

/**
 * 筛选条是否出现。
 * 词典没加载出来时不显示（避免出现"点开是空的"假控件）；失败时搜索本身照常可用。
 */
const showFilterBar = computed(() => {
  const canPick = meta.boards.length > 0 || meta.hotTags.length > 0
  return (hasQuery.value && canPick) || hasFilters.value
})

/** 改筛选条件：一律回到第 1 页（换了筛选却停在原来的第 3 页 = 空页） */
function changeFilter(key: 'board' | 'tag' | 'author', value: unknown) {
  router.push({ query: { ...route.query, [key]: toId(value), page: undefined } })
}

/** 清空筛选，保留关键词与排序 */
function clearFilters() {
  router.push({
    query: { keyword: route.query.keyword, sort: route.query.sort, page: undefined },
  })
}

/* ==================== 查询 ==================== */

/** 请求序号（2026-09-29 纠错）：关键词/筛选/排序连续变更时多个请求并发在途，
 *  先发的可能后到 —— 旧响应会覆盖新结果（含"清空关键词后又被旧结果填回来"）。
 *  只允许最新一次请求的结果写入状态。 */
let loadSeq = 0

async function load() {
  const seq = ++loadSeq
  if (!hasQuery.value) {
    posts.value = []
    total.value = 0
    pages.value = 0
    // 空查询也要把 loading 收掉：在途的旧请求已被序号作废，
    // 不会再替这一次执行 finally（否则骨架屏会一直挂着）
    loading.value = false
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
    if (boardId.value) req.boardId = boardId.value
    if (tagId.value) req.tagId = tagId.value
    if (authorId.value) req.authorId = authorId.value

    const res = await searchApi.searchPosts(req)
    if (seq !== loadSeq) return
    posts.value = res.records
    total.value = res.total
    pages.value = res.pages
  } catch (e) {
    if (seq !== loadSeq) return
    error.value = e instanceof Error ? e.message : '搜索失败'
    posts.value = []
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

/** 把 query 归一化成可比较的字符串（page 不参与：翻页不代表查询变了） */
function queryKey(q: Record<string, unknown>): string {
  return Object.keys(q)
    .filter((k) => k !== 'page' && q[k] !== undefined && q[k] !== '')
    .sort()
    .map((k) => `${k}=${String(q[k])}`)
    .join('&')
}

/**
 * 提交新关键词。
 *
 * ★ 本轮顺手修掉一个既有 bug：原实现直接 `push({ keyword })`，会把 URL 上已有的
 *   sort 与筛选条件**一起丢掉** —— 表现为"切到热度、再点一次搜索，排序悄悄回到相关性"。
 *   现在保留全部条件，只把页码归零；并在"查询没变"时直接返回，省掉一次无意义的请求。
 */
function onSubmit() {
  const q = input.value.trim()
  if (!q) {
    ElMessage.info('请输入搜索关键词')
    return
  }
  const next = { ...route.query, keyword: q, page: undefined }
  if (queryKey(next) === queryKey(route.query as Record<string, unknown>)) return
  router.push({ query: next })
}

function changeSort(sort: string) {
  router.push({ query: { ...route.query, sort, page: undefined } })
}

function changePage(p: number) {
  page.value = p
  router.push({ query: { ...route.query, page: p > 1 ? p : undefined } })
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

/** 空结果的建议：带筛选时说"先松条件"，不带筛选时才说"换关键词" */
const emptyHint = computed(() =>
  hasFilters.value
    ? {
        title: `没有同时满足这些条件的帖子`,
        text: '当前的关键词叠加了筛选条件。可以试试移除其中一两个条件，或换一个更宽泛的关键词。',
      }
    : {
        title: `没有找到「${keyword.value}」相关的帖子`,
        text: '可以试试：换一个更长或更常见的词（少于 2 个字会退化为模糊匹配，结果可能不准）；或者减少限定条件。',
      },
)

watch(
  () => [route.query.keyword, route.query.sort, route.query.board, route.query.tag, route.query.author, route.query.page],
  async () => {
    keyword.value = String(route.query.keyword ?? '').trim()
    input.value = keyword.value
    page.value = Number(route.query.page) || 1
    await load()
  },
  { immediate: true },
)

onMounted(() => {
  // 板块与标签都是全站缓存字典（meta store 全站只请求一次，首页通常已经取过）。
  // 失败就静默降级：筛选条会因为没有候选而自动隐藏，搜索本身不受影响。
  meta.loadBoards().catch(() => {})
  meta.loadHotTags().catch(() => {})
})
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

    <!-- ==================== 高级筛选 ==================== -->
    <div v-if="showFilterBar" class="filter-bar">
      <span class="filter-label">筛选</span>

      <el-select
        v-if="meta.boards.length"
        :model-value="boardId ?? ''"
        placeholder="全部板块"
        class="filter-select"
        clearable
        aria-label="按板块筛选"
        @change="(v: unknown) => changeFilter('board', v)"
      >
        <el-option v-for="b in meta.boards" :key="b.id" :label="b.name" :value="b.id" />
      </el-select>

      <el-select
        v-if="meta.hotTags.length"
        :model-value="tagId ?? ''"
        placeholder="全部标签"
        class="filter-select"
        clearable
        aria-label="按标签筛选"
        @change="(v: unknown) => changeFilter('tag', v)"
      >
        <el-option
          v-for="t in meta.hotTags"
          :key="t.id"
          :label="`#${t.name}`"
          :value="t.id"
        />
      </el-select>

      <!-- 作者没有选择器（后端没有"按昵称搜用户"的接口），
           只能从帖子详情页「关于作者」的入口带过来，所以用可移除的标签展示 -->
      <span v-if="authorId" class="filter-chip">
        作者：{{ authorName || `#${authorId}` }}
        <button
          type="button"
          class="chip-remove"
          aria-label="移除作者筛选"
          @click="changeFilter('author', undefined)"
        >
          <svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor"
               stroke-width="2.6" stroke-linecap="round" aria-hidden="true">
            <path d="M18 6 6 18M6 6l12 12" />
          </svg>
        </button>
      </span>

      <button v-if="hasFilters" type="button" class="filter-clear" @click="clearFilters">
        清空筛选
      </button>

      <!-- 如实说明候选范围：候选数没到上限就是全部标签；到上限才需要声明只列了热门的一部分 -->
      <span class="filter-note">
        <template v-if="tagsAreComplete">共 {{ meta.hotTags.length }} 个标签 · </template>
        <template v-else>标签取引用最多的 {{ TAG_LIMIT }} 个 · </template>
        <RouterLink :to="{ name: 'tags' }">全部标签</RouterLink>
      </span>
    </div>

    <!-- ==================== 未输入关键词：引导态 ==================== -->
    <div v-if="!hasQuery" class="hint-card">
      <p class="hint-title">
        {{ hasFilters ? '已限定范围 · 输入关键词开始搜索' : '输入关键词开始搜索' }}
      </p>
      <p class="hint-text">
        <template v-if="hasFilters">
          筛选条件已生效（
          <b v-if="boardName">{{ boardName }}</b>
          <b v-if="boardName && (tagName || authorId)"> · </b>
          <b v-if="tagName">#{{ tagName }}</b>
          <b v-if="tagName && authorId"> · </b>
          <b v-if="authorId">作者 {{ authorName || `#${authorId}` }}</b>
          ），输入关键词后只会在该范围内检索。
        </template>
        <template v-else>
          检索范围覆盖<b>帖子标题与正文</b>，支持中文分词。
          搜索结果会标出命中的关键词片段。
        </template>
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

      <!-- 空结果：带筛选时先建议松开条件（那才是更可能的原因） -->
      <EmptyState
        v-else-if="!posts.length"
        icon="search"
        :title="emptyHint.title"
        :description="emptyHint.text"
        :action-text="hasFilters ? '清空筛选' : ''"
        @action="clearFilters"
      />

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
  max-width: var(--container-list);
}

/* ---------- 页头 ---------- */
.search-head {
  margin-bottom: var(--space-4);
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

/* ---------- 高级筛选条 ---------- */
.filter-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}

.filter-label {
  font-size: var(--text-xs);
  font-weight: 500;
  letter-spacing: var(--tracking-label);
  color: var(--color-subtle-foreground);
}

.filter-select {
  width: 150px;
}

.filter-select :deep(.el-select__wrapper) {
  background: var(--color-muted);
  box-shadow: none;
  border-radius: var(--radius-md);
}

.filter-select :deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px var(--color-ring) inset;
}

/* 作者筛选：没有选择器，只有从详情页带过来的 id → 用可移除的标签展示 */
.filter-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-2) var(--space-1) var(--space-3);
  font-size: var(--text-sm);
  color: var(--color-foreground);
  background: var(--color-accent-soft);
  border: 1px solid var(--color-accent-border);
  border-radius: var(--radius-full);
}

.chip-remove {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  padding: 0;
  color: var(--color-accent);
  background: none;
  border: none;
  border-radius: var(--radius-full);
  transition: background var(--transition-fast);
}

.chip-remove:hover {
  background: var(--color-accent-soft);
}

.filter-clear {
  padding: var(--space-1) var(--space-2);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  background: none;
  border: none;
  border-radius: var(--radius-sm);
  transition: color var(--transition-fast);
}

.filter-clear:hover {
  color: var(--color-foreground);
  text-decoration: underline;
}

.filter-note {
  margin-left: auto;
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
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
  gap: var(--space-2);
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

  /* 窄屏：两个选择器各占一半，说明文字换行到最下方 */
  .filter-select {
    width: calc(50% - var(--space-1));
  }

  .filter-note {
    margin-left: 0;
    flex-basis: 100%;
  }
}
</style>