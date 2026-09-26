<script setup lang="ts">
/**
 * 首页 / 板块页（同一组件，靠路由区分）。
 *
 * 路由：
 *   /                → 全部帖子
 *   /boards/:slug    → 某板块
 * 查询参数：sort=latest|hot|active, tag=标签ID, page=页码
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import { postApi } from '@/api/post'
import { feedApi } from '@/api/social'
import { useAuthStore } from '@/stores/auth'
import { useMetaStore } from '@/stores/meta'
import PostCard from '@/components/PostCard.vue'
import PostListSkeleton from '@/components/PostListSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import { formatCount } from '@/utils/format'
import type { PostListVO, PostQueryRequest } from '@/types/api'

const route = useRoute()
const router = useRouter()
const meta = useMetaStore()
const auth = useAuthStore()

const posts = ref<PostListVO[]>([])
const total = ref(0)
const pages = ref(0)
const loading = ref(true)
const error = ref('')

const page = ref(1)
const size = 20

/**
 * 排序/流切换。
 *
 * `following`（关注流）与其余三项**不是同一个接口**：
 * 它走 `/api/feed/following`（拉模式：我关注的人发的帖子），
 * 其余走 `/api/posts?sort=...`。后端刻意没有为"关注"再包一层
 * `/api/posts?sort=following` —— 那是两种不同的查询，混在一个接口里
 * 只会让排序白名单和索引选择都变得难以推理。
 */
const SORTS = [
  { value: 'following', label: '关注' },
  { value: 'latest', label: '最新' },
  { value: 'hot', label: '热度' },
  { value: 'active', label: '最新回复' },
  { value: 'unsolved', label: '待解决' },
] as const

/** 板块页没有"关注"语义（那里是"某板块下的帖子"），只在首页展示 */
const visibleSorts = computed(() =>
  route.params.slug ? SORTS.filter((s) => s.value !== 'following') : SORTS,
)

const isFollowingFeed = computed(() => currentSort.value === 'following')

/**
 * 「待解决」不是排序，而是一个**筛选视图**：`type=1&solved=false`。
 * 它复用列表接口而不是新开一个（后端为此加了 `solved` 参数），
 * 所以这里只需要把 tab 值翻译成查询条件，加载路径不用分叉。
 */
const isUnsolvedTab = computed(() => currentSort.value === 'unsolved')

/** 当前板块（来自路由 slug） */
const currentBoard = computed(() =>
  route.params.slug ? meta.boardBySlug(String(route.params.slug)) : undefined,
)

const currentSort = computed(() => (route.query.sort as string) || 'latest')
const currentTagId = computed(() => (route.query.tag ? Number(route.query.tag) : undefined))
const currentType = computed(() => (route.query.type ? Number(route.query.type) : undefined))

const pageTitle = computed(() => {
  if (currentBoard.value) return currentBoard.value.name
  if (isFollowingFeed.value) return '我的关注'
  if (isUnsolvedTab.value) return '待解决的问题'
  if (currentTagId.value) {
    const tag = meta.hotTags.find((t) => t.id === currentTagId.value)
    return tag ? `#${tag.name}` : '标签'
  }
  return currentSort.value === 'hot' ? '热门讨论' : '最新讨论'
})

const pageDesc = computed(() => {
  if (currentBoard.value?.description) return currentBoard.value.description
  if (isFollowingFeed.value) return '你关注的作者最近发布的帖子'
  if (isUnsolvedTab.value) return '还没被采纳回答的提问 —— 你的回答可能正是对方需要的'
  if (currentTagId.value) return '按标签筛选的帖子'
  return '来自社区的最新技术讨论'
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    // 关注流走独立接口 —— 它的"分页语义"与列表一致，但数据来源不同
    if (isFollowingFeed.value) {
      const res = await feedApi.following(page.value, size)
      posts.value = res.records
      total.value = res.total
      pages.value = res.pages
      return
    }

    const query: PostQueryRequest = {
      page: page.value,
      size,
      // 「待解决」按时间倒序（"新问题更需要人答"），其余按所选排序
      sort: isUnsolvedTab.value ? 'latest' : (currentSort.value as PostQueryRequest['sort']),
    }
    if (currentBoard.value) query.boardId = currentBoard.value.id
    if (currentTagId.value) query.tagId = currentTagId.value
    if (isUnsolvedTab.value) {
      query.type = 1
      query.solved = false
    } else if (currentType.value) {
      query.type = currentType.value
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

/** 切换排序 / 标签 / 页码 —— 改 URL，由 watcher 统一触发加载 */
function changeSort(sort: string) {
  // 未登录时不把用户丢进一个必然 401 的页面：直接引导登录，
  // 并把当前路径作为 redirect 带过去，登录后回到原处。
  if (sort === 'following' && !auth.isLoggedIn) {
    ElMessage.info('登录后即可查看关注流')
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }

  const query: LocationQueryRaw = { ...route.query, sort, page: undefined }
  if (sort === 'unsolved') {
    // 待解决 = 提问帖 + 未采纳；这两个参数必须和 tab 一起进出，
    // 否则"切到最新"后会留在单提问帖的视图里，而标签却显示「最新」。
    query.type = '1'
    query.solved = 'false'
  } else {
    delete query.type
    delete query.solved
  }
  router.push({ query })
}

function changeTag(tagId?: number) {
  router.push({ query: { ...route.query, tag: tagId, page: undefined } })
}

function changePage(p: number) {
  page.value = p
  router.push({ query: { ...route.query, page: p > 1 ? p : undefined } })
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

/**
 * 是否展示首屏 Hero。
 *
 * 只在"首页 + 未做任何筛选"时出现：用户一进来先看到产品是什么；
 * 一旦他开始筛板块/标签，说明意图已经明确，此时 Hero 只会挡路。
 * 关注流同理 —— 那是"我的人"的视角，不需要品牌介绍。
 */
const showHero = computed(
  () =>
    route.name === 'home' &&
    !route.query.tag &&
    !route.query.type &&
    !isFollowingFeed.value,
)

/**
 * Hero 里的社区统计。
 *
 * ⚠️ 全部取自真实数据源，**不编造任何增长数字**。
 *    设计系统的转化策略里明确要求："Show member and activity counts
 *    only when current, verified, and dated" —— 编一个"今日新增 128 帖"
 *    在个人项目里既无来源也经不起追问。
 *    加载中显示 "—" 而不是 0：0 是"真的没有"，破折号是"还不知道"。
 */
const heroStats = computed(() => [
  { value: loading.value ? '—' : formatCount(total.value), label: '篇讨论' },
  { value: formatCount(meta.boards.length), label: '个板块' },
  { value: formatCount(meta.hotTags.length), label: '个技术标签' },
])

/** Hero 主 CTA：未登录先去登录，已登录直接开写 */
function onHeroPrimary() {
  if (!auth.isLoggedIn) {
    ElMessage.info('登录后即可发帖')
    router.push({ name: 'login', query: { redirect: '/publish' } })
    return
  }
  router.push({ name: 'post-create' })
}

function onHeroSecondary() {
  router.push({ query: { ...route.query, sort: 'hot', page: undefined } })
}

/**
 * 空态按钮的落点。
 *
 * 三种空态的原因完全不同，给的出路也应该不同：
 *  - 关注流空 = "你还没关注人" → 引导去发现内容（切到最新）
 *  - 待解决空 = "提问都被采纳了" → 这是好事，切到最新看其它讨论
 *  - 列表空 = "这里真的没帖子" → 引导去发帖
 * 给同一个按钮只会让其中一种用户困惑。
 */
function onEmptyAction() {
  if (isFollowingFeed.value || isUnsolvedTab.value) {
    router.push({ query: { ...route.query, sort: 'latest', type: undefined, solved: undefined, page: undefined } })
  } else {
    router.push({ name: 'post-create' })
  }
}

// 依赖变化时重新加载：板块 / 排序 / 标签 / 类型 / 已解决筛选 / 页码
// （solved 必须进依赖：从"待解决"切走时 type 会消失，但再切回来 type 与 solved 一起变，
//   把它列上可以让"只改 solved"的场景也不会漏加载）
watch(
  () => [
    route.params.slug,
    route.query.sort,
    route.query.tag,
    route.query.type,
    route.query.solved,
    route.query.page,
  ],
  async () => {
    page.value = Number(route.query.page) || 1
    // 板块信息可能还没加载完（直接访问 /boards/backend 时），先等它
    await meta.loadBoards()
    await load()
  },
  { immediate: true },
)

// 侧栏数据
meta.loadBoards()
meta.loadHotTags()
</script>

<template>
  <div class="container home-layout">
    <!-- ==================== 主列 ==================== -->
    <div class="main-col">
      <!-- ==================== 首屏 Hero ====================
           设计系统的 Page Pattern（Community/Forum Landing）要求首屏是
           "Hero → 热门内容 → 成员 → CTA"，而上一版首页直接就是帖子列表 ——
           第一眼像后台管理页，不像一个产品。这里补上品牌面貌。
           数据全部取自真实接口，不编造增长数字。 -->
      <section v-if="showHero" class="hero">
        <div class="hero-glow" aria-hidden="true" />
        <div class="hero-grid" aria-hidden="true" />

        <p class="hero-eyebrow">
          <span class="hero-pulse" aria-hidden="true" />
          开发者技术社区
        </p>

        <h1 class="hero-title">
          把踩过的坑
          <span class="hero-title-accent">变成别人少走的弯路</span>
        </h1>

        <p class="hero-sub">
          Markdown 发帖 · 中文全文检索 · 回答采纳 —— 写下来，就是最好的复习。
        </p>

        <div class="hero-actions">
          <button type="button" class="hero-btn hero-btn-primary" @click="onHeroPrimary">
            <svg
              viewBox="0 0 24 24"
              width="16"
              height="16"
              fill="none"
              stroke="currentColor"
              stroke-width="2.4"
              stroke-linecap="round"
              aria-hidden="true"
            >
              <path d="M12 5v14M5 12h14" />
            </svg>
            {{ auth.isLoggedIn ? '发布帖子' : '加入讨论' }}
          </button>
          <button type="button" class="hero-btn hero-btn-ghost" @click="onHeroSecondary">
            浏览热门
          </button>
        </div>

        <dl class="hero-stats">
          <div v-for="s in heroStats" :key="s.label" class="hero-stat">
            <!-- dt 是"术语"、dd 是"描述"，语义上标签在前；
                 视觉上要让数值在上，所以用 column-reverse 反转显示顺序 -->
            <dt class="hero-stat-label">{{ s.label }}</dt>
            <dd class="hero-stat-value">{{ s.value }}</dd>
          </div>
        </dl>
      </section>

      <!-- 页头（有 Hero 时让位；筛选/板块页保留） -->
      <header v-else class="page-head">
        <div class="page-head-text">
          <h1 class="page-title">{{ pageTitle }}</h1>
          <p class="page-desc">{{ pageDesc }}</p>
        </div>
        <div class="page-head-actions">
          <el-button type="primary" @click="router.push({ name: 'post-create' })">
            发布帖子
          </el-button>
        </div>
      </header>

      <!-- 排序 + 结果计数 -->
      <div class="toolbar">
        <div class="sort-tabs" role="tablist" aria-label="排序方式">
          <button
            v-for="s in visibleSorts"
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
          {{ loading ? '加载中…' : `共 ${total} 篇` }}
        </span>
      </div>

      <!-- 加载骨架（不用 spinner —— 骨架能保持布局稳定，避免 CLS） -->
      <PostListSkeleton v-if="loading" :count="5" />

      <!-- 错误 -->
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

      <!-- 空态：关注流 / 待解决 / 普通列表的成因不同，提示与出路也应不同 -->
      <EmptyState
        v-else-if="!posts.length"
        :title="
          isFollowingFeed
            ? '关注流还是空的'
            : isUnsolvedTab
              ? '暂时没有待解决的问题'
              : '这里还没有帖子'
        "
        :description="
          isFollowingFeed
            ? '关注一些感兴趣的作者，他们发布的新帖子会自动出现在这里'
            : isUnsolvedTab
              ? '所有提问都已经有采纳的答案了 —— 去「最新」看看其它讨论，或自己提一个问题'
              : '成为第一个发帖的人，分享你的技术心得或提问'
        "
        :action-text="isFollowingFeed || isUnsolvedTab ? '去发现内容' : '去发帖'"
        @action="onEmptyAction"
      />

      <!-- 列表 -->
      <div v-else class="post-list">
        <!-- 错峰入场：anim-in 与 --i 定义在全局 tokens.css，
             延迟封顶在 8 档（i 被 min 截断）——
             否则第 20 条要等近 1 秒才出现，滚动到底时反而像卡住了。 -->
        <PostCard
          v-for="(post, i) in posts"
          :key="post.id"
          :post="post"
          class="anim-in"
          :style="{ '--i': Math.min(i, 8) }"
        />
      </div>

      <!-- 分页 -->
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

    <!-- ==================== 侧栏 ==================== -->
    <aside class="side-col">
      <!-- 板块导航 -->
      <section class="side-card">
        <h2 class="side-title">板块</h2>
        <nav class="board-nav">
          <RouterLink
            :to="{ name: 'home' }"
            class="board-item"
            :class="{ active: !route.params.slug && !currentTagId }"
          >
            <span class="board-name">全部讨论</span>
          </RouterLink>
          <RouterLink
            v-for="board in meta.boards"
            :key="board.id"
            :to="{ name: 'board', params: { slug: board.slug } }"
            class="board-item"
            :class="{ active: route.params.slug === board.slug }"
          >
            <span class="board-name">{{ board.name }}</span>
            <span v-if="board.postCount > 0" class="board-count">{{ board.postCount }}</span>
          </RouterLink>
        </nav>
      </section>

      <!-- 热门标签 -->
      <section v-if="meta.hotTags.length" class="side-card">
        <h2 class="side-title">热门标签</h2>
        <div class="tag-cloud">
          <button
            v-for="tag in meta.hotTags"
            :key="tag.id"
            type="button"
            class="cloud-tag"
            :class="{ active: currentTagId === tag.id }"
            @click="changeTag(currentTagId === tag.id ? undefined : tag.id)"
          >
            {{ tag.name }}
          </button>
        </div>
      </section>

      <!-- 关于 -->
      <section class="side-card about-card">
        <h2 class="side-title">关于 Codelkup</h2>
        <p class="about-text">
          面向开发者的技术问答与讨论社区。支持 Markdown 发帖、
          标签分类与中文全文检索。
        </p>
        <p class="about-stack">Spring Boot 3.5 · Vue 3 · MySQL ngram</p>
      </section>
    </aside>
  </div>
</template>

<style scoped>
.home-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: var(--space-6);
  align-items: start;
}

/* ---------- 主列 ---------- */
.main-col {
  min-width: 0;
}

/* ==================== 首屏 Hero ====================
   做法说明（为什么这样写才"不廉价"）：
   1. 面不是纯色，而是极浅的斜向渐变 + 一层网格纹理 —— 纯色暗块在
      大屏上会显得空；纹理提供"材质感"，但透明度压到 2.8%，
      只在余光里起作用，不会干扰阅读。
   2. 光晕放在左上角（视线起点），用 accent 色的模糊径向渐变制造纵深。
   3. 标题第二行用**单色高光渐变**（白 → 70% 白）建立记忆点。
      ⚠️ v3 修正：原来是"蓝→靛→绿"三色渐变，那是模板感的典型特征，
      且把品牌强调色稀释成了彩虹。
   4. 数据统计用真实接口值，加载中显示破折号而非 0。 */
.hero {
  position: relative;
  overflow: hidden;
  margin-bottom: var(--space-6);
  padding: var(--space-10) var(--space-8) var(--space-8);
  /* ★ v3：三色渐变底（#16233d → #121b2e，都是旧色值）→ 纯卡片色
     + 一层极弱的强调色顶光。整块用渐变会让首屏像"海报"，而干净的
     表面 + 一条发丝边 + 顶部内高光，才是暗色产品的高级观感。 */
  background-color: var(--color-card);
  background-image: linear-gradient(180deg, rgba(59, 130, 246, 0.045) 0%, transparent 46%);
  border: 1px solid var(--color-border-hairline);
  border-radius: var(--radius-xl);
  box-shadow: var(--inset-highlight);
}

.hero-glow {
  position: absolute;
  top: -150px;
  left: -90px;
  width: 540px;
  height: 340px;
  background: radial-gradient(closest-side, var(--color-accent-glow), transparent 78%);
  filter: blur(56px);
  /* ★ v3：0.6 → 0.3。首屏保留一点纵深，但不再"发着光"。 */
  opacity: 0.3;
  pointer-events: none;
}

.hero-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.022) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.022) 1px, transparent 1px);
  background-size: 44px 44px;
  /* 只在左上区域显形，向右下淡出，避免整块"格子布" */
  mask-image: radial-gradient(130% 105% at 18% 0%, #000 28%, transparent 76%);
  -webkit-mask-image: radial-gradient(130% 105% at 18% 0%, #000 28%, transparent 76%);
  pointer-events: none;
}

.hero-eyebrow {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: 4px var(--space-3);
  margin-bottom: var(--space-4);
  font-size: var(--text-xs);
  font-weight: 500;
  letter-spacing: var(--tracking-label);
  color: var(--color-muted-foreground);
  background: var(--color-neutral-soft);
  border: 1px solid var(--color-border-hairline);
  border-radius: var(--radius-full);
}

/* 小圆点表示"这里活着"。★ v3：把绿色光晕的扩散半径与不透明度都压下来
   （7px/0.5 → 5px/0.32）—— 原来的呼吸光晕在暗色首屏上像一盏闪烁的灯。 */
.hero-pulse {
  width: 6px;
  height: 6px;
  border-radius: var(--radius-full);
  background: var(--color-success);
  box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.32);
  animation: hero-pulse 2.8s var(--ease-out) infinite;
}

@keyframes hero-pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.32);
  }
  70% {
    box-shadow: 0 0 0 5px rgba(16, 185, 129, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(16, 185, 129, 0);
  }
}

.hero-title {
  position: relative;
  max-width: 15em;
  font-size: clamp(26px, 3.6vw, var(--text-4xl));
  font-weight: 700;
  line-height: 1.18;
  letter-spacing: -0.032em;
  color: var(--color-foreground);
}

.hero-title-accent {
  display: block;
  /* ★ v3：蓝→靛→绿的三色渐变 → 白→70% 白的单色渐变。
     多色相渐变是"AI 模板感"最强的特征；单色高光渐变既保留"标题有光"，
     又不会把品牌色稀释成彩虹。配方来自 ui-ux-pro-max
     --domain typography「Modern Dark Cinema (Inter System)」。 */
  background: linear-gradient(180deg, #ffffff 0%, rgba(255, 255, 255, 0.72) 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.hero-sub {
  position: relative;
  max-width: 46ch;
  margin-top: var(--space-4);
  font-size: var(--text-md);
  line-height: var(--leading-relaxed);
  color: var(--color-muted-foreground);
}

.hero-actions {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-top: var(--space-6);
}

.hero-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: 11px var(--space-5);
  font-family: inherit;
  font-size: var(--text-base);
  font-weight: 600;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  transition:
    background var(--transition-base),
    border-color var(--transition-base),
    color var(--transition-base),
    box-shadow var(--transition-base),
    transform var(--transition-fast);
}

.hero-btn-primary {
  color: var(--color-on-accent);
  background: var(--color-accent-solid);
  box-shadow: 0 6px 22px -8px var(--color-accent-glow);
}

.hero-btn-primary:hover {
  background: var(--color-accent-solid-hover);
  transform: translateY(-1px);
  box-shadow: 0 12px 30px -10px var(--color-accent-glow);
}

.hero-btn-ghost {
  color: var(--color-muted-foreground);
  background: var(--color-neutral-soft);
  border-color: var(--color-border);
}

.hero-btn-ghost:hover {
  color: var(--color-foreground);
  background: rgba(255, 255, 255, 0.075);
  border-color: var(--color-secondary);
}

/* ---------- Hero 统计 ---------- */
.hero-stats {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-8);
  margin: var(--space-8) 0 0;
  padding-top: var(--space-5);
  border-top: 1px solid var(--color-border-subtle);
}

.hero-stat {
  display: flex;
  /* 值在上、标签在下；dt/dd 的 DOM 顺序保持语义（标签在前） */
  flex-direction: column-reverse;
  gap: 2px;
}

.hero-stat dt,
.hero-stat dd {
  margin: 0;
  padding: 0;
}

.hero-stat-value {
  font-size: var(--text-xl);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.025em;
  color: var(--color-foreground);
}

.hero-stat-label {
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
}

/* ---------- 页头 ---------- */
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: var(--space-5);
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

.page-head-actions {
  flex-shrink: 0;
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

/* ---------- 列表 ---------- */
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

/* ---------- 侧栏 ---------- */
.side-col {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  position: sticky;
  top: 74px; /* header 高度 + 间距 */
}

.side-card {
  padding: var(--space-4);
  background: var(--color-card);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
}

.side-title {
  margin-bottom: var(--space-3);
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-muted-foreground);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.board-nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.board-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-2) var(--space-3);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  border-radius: var(--radius-md);
  transition: all var(--transition-fast);
}

.board-item:hover {
  color: var(--color-foreground);
  background: var(--color-muted);
}

.board-item.active {
  color: var(--color-foreground);
  background: var(--color-muted);
  font-weight: 500;
  /* 左侧竖条标识当前板块 */
  box-shadow: inset 2px 0 0 var(--color-accent);
}

.board-count {
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
  font-variant-numeric: tabular-nums;
}

.tag-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.cloud-tag {
  padding: 2px var(--space-2);
  font-size: var(--text-xs);
  color: var(--color-muted-foreground);
  background: var(--color-muted);
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  transition: all var(--transition-fast);
}

.cloud-tag:hover {
  color: var(--color-foreground);
  border-color: var(--color-border);
}

.cloud-tag.active {
  color: var(--color-on-accent);
  background: var(--color-accent-solid);
  border-color: var(--color-accent-solid);
}

.about-card .about-text {
  font-size: var(--text-sm);
  line-height: var(--leading-normal);
  color: var(--color-muted-foreground);
}

.about-stack {
  margin-top: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-border-subtle);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--color-subtle-foreground);
}

/* ---------- 响应式 ---------- */
@media (max-width: 1024px) {
  .home-layout {
    grid-template-columns: 1fr;
  }

  .side-col {
    position: static;
    /* 侧栏移到列表下方时，用两列排布节省高度 */
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
    order: 2;
  }

  .main-col {
    order: 1;
  }
}

@media (max-width: 640px) {
  .page-head {
    flex-direction: column;
  }

  .page-title {
    font-size: var(--text-xl);
  }

  .toolbar {
    flex-wrap: wrap;
  }
}
</style>
