<script setup lang="ts">
/**
 * 首页 / 板块页（同一组件，靠路由区分）。
 *
 * 路由：
 *   /                → 全部帖子
 *   /boards/:slug    → 某板块
 * 查询参数：sort=latest|hot|active, tag=标签ID, page=页码
 *
 * ★ V0.4 首页信息密度改版（本轮，零后端改动）：
 *   1. Hero 统计 3 项 → 4 项，并**统一成精确值**：原来用 formatCount 把 10119 缩写成
 *      "10k"，与下方列表的"共 10119 篇"同屏并存 —— 同一件事两个数字，读起来像不严谨。
 *      新增的「待解决」是行动号召（点开就是 /questions），也是唯一会随时间变化的那个数。
 *   2. 侧栏的「板块 / 标签」变成**预览 + 入口**：板块只列前 6（当前板块必定在列，
 *      否则板块页会找不到高亮项），更多走 `/boards`；标签同理走 `/tags`。
 *      这两个页面是 M4 才有的 —— 在此之前侧栏只能当"全集容器"用。
 *   3. 侧栏新增「活跃作者」（复用排行榜接口取前 5），并**移除「关于 Codelkup」卡片**：
 *      它那三句话与 Hero 副标题、页脚里的技术栈完全重复，却占着约 150px 把侧栏顶出视口
 *      （sticky 侧栏一旦高过视口，最底下的卡片实际就看不到了）。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import { postApi } from '@/api/post'
import { statsApi } from '@/api/stats'
import { feedApi } from '@/api/social'
import { userApi } from '@/api/user'
import { useAuthStore } from '@/stores/auth'
import { useMetaStore } from '@/stores/meta'
import PostCard from '@/components/PostCard.vue'
import PostListSkeleton from '@/components/PostListSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import type { PostListVO, PostQueryRequest, RankingUserVO, SiteStatsVO } from '@/types/api'

const route = useRoute()
const router = useRouter()
const meta = useMetaStore()
const auth = useAuthStore()

const posts = ref<PostListVO[]>([])
const total = ref(0)
const pages = ref(0)
const loading = ref(true)
const error = ref('')

/** 侧栏「活跃作者」：复用排行榜接口取前 5（零后端改动）。拉不到就整块不渲染 */
const topAuthors = ref<RankingUserVO[]>([])

/**
 * Hero 的社区计数（板块 / 标签 / 待解决）—— 来自 /api/stats 的服务端聚合。
 *
 * 为什么不再拼"字典长度"：板块字典是 app 启动时的内存快照、热门标签默认只取
 * 20 条 —— 拿它们当"全站有多少"，会在数据超过上限或字典过期时悄悄撒谎。
 * 聚合接口给的是**当前全量、同一时刻**的三个数。
 * 拿不到（null）时：待解决整项不显示、板块 / 标签退回字典长度（即旧行为），
 * 任何情况下都不显示 0 —— 与全站"失败态不显示 0"同一口径。
 */
const siteStats = ref<SiteStatsVO | null>(null)

/** 侧栏板块预览条数：更多走 `/boards` */
const SIDEBAR_BOARD_LIMIT = 6

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

/** 请求序号（2026-09-29 纠错）：快速切换排序/标签/翻页时会有多个请求并发在途，
 *  先发出的可能后返回（网络乱序）—— 旧响应会把新数据覆盖回"上一次筛选"的结果。
 *  每次请求领一个单调递增号，只有最新一次的结果才允许写入状态。 */
let loadSeq = 0

async function load() {
  const seq = ++loadSeq
  loading.value = true
  error.value = ''
  try {
    // 关注流走独立接口 —— 它的"分页语义"与列表一致，但数据来源不同
    if (isFollowingFeed.value) {
      const res = await feedApi.following(page.value, size)
      if (seq !== loadSeq) return
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
    if (seq !== loadSeq) return
    posts.value = res.records
    total.value = res.total
    pages.value = res.pages
  } catch (e) {
    if (seq !== loadSeq) return
    error.value = e instanceof Error ? e.message : '加载失败'
    posts.value = []
  } finally {
    if (seq === loadSeq) loading.value = false
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
 *
 * ⚠️ 数字来源只有两处：帖子总数（列表接口）与全站计数（`/api/stats` 服务端聚合，
 *    含板块 / 标签 / 待解决三项）；字典长度只作为聚合失败时的降级显示。
 *    设计系统的转化策略建议 "Show member and activity counts"，但它同时要求
 *    "only when current, verified, and dated" —— 编一个"今日新增 128 帖"在个人项目里
 *    既没有来源也经不起追问，所以这里**不做时间维度的数字**（实测今日确实有 44 帖，
 *    但新部署的库当天必然是 0，摆一个"今日 0 帖"比不摆更伤）。
 *
 * ★ 本轮两点改动：
 *   - 去掉 formatCount 缩写：`10119` 曾被缩成 "10k"，与列表上方的"共 10119 篇"
 *     同屏并存 —— 同一件事两个数字，是"看起来不严谨"的典型来源。宁可要精确值。
 *   - 增加「待解决」：它是行动号召（点进去就是 /questions），也是这里唯一会变的数字；
 *     拉不到时整项不显示（不显示 0）。
 */
const heroStats = computed(() => {
  const stats: { value: number | string; label: string }[] = [
    { value: loading.value ? '—' : total.value, label: '篇讨论' },
  ]
  if (siteStats.value !== null) {
    stats.push({ value: siteStats.value.questionUnsolvedCount, label: '个待解决' })
  }
  // 板块 / 标签：优先聚合值（全量口径）；聚合没拿到时退回字典长度（旧的降级行为）
  stats.push({ value: siteStats.value?.boardCount ?? meta.boards.length, label: '个板块' })
  stats.push({ value: siteStats.value?.tagCount ?? meta.hotTags.length, label: '个技术标签' })
  return stats
})

/**
 * 侧栏只列前 6 个板块，其余走 `/boards`。
 *
 * ⚠️ 当前板块**必须**在列表里：板块页的高亮就靠这一列（`active` 类）。
 *    若正在看的板块排在第 7 位之后，只取前 6 会让"我在哪个板块"这个信息消失 ——
 *    所以命中当前板块时把它补进列表。
 */
const sidebarBoards = computed(() => {
  const head = meta.boards.slice(0, SIDEBAR_BOARD_LIMIT)
  const active = currentBoard.value
  if (active && !head.some((b) => b.id === active.id)) {
    return [...head, active]
  }
  return head
})

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
// 附加信息：失败静默 —— 侧栏卡片与 Hero 计数是锦上添花，不该让页面主体受牵连
userApi
  .ranking(5)
  .then((rows) => {
    topAuthors.value = rows
  })
  .catch(() => {})
statsApi
  .overview()
  .then((s) => {
    siteStats.value = s
  })
  .catch(() => {})
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
        <div class="hero-grid" aria-hidden="true" />

        <p class="hero-eyebrow">
          <span class="sec-num">§ 01</span>
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

      <!-- 页头（有 Hero 时让位；筛选/板块页保留）。
           ★ v6.2：挪到横排带**之前**——板块页的顺序变成"板块名 → 横排带 → 帖子"
           （标题优先），同时与 hero 保持相邻（v-else 得以恢复）。 -->
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

      <!-- ==================== 顶部横排带（v6.2 新布局）====================
           原右侧栏的三张卡（板块 / 热门标签 / 活跃作者）按用户指定
           移到页面上方、横排为一排；页面回归单列，帖子流用双列卡片。 -->
      <aside class="top-strip">
        <!-- 板块导航：预览前 6 + 入口（全集在 /boards，那里还有每个板块的简介） -->
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
              v-for="board in sidebarBoards"
              :key="board.id"
              :to="{ name: 'board', params: { slug: board.slug } }"
              class="board-item"
              :class="{ active: route.params.slug === board.slug }"
            >
              <span class="board-name">{{ board.name }}</span>
              <span v-if="board.postCount > 0" class="board-count">{{ board.postCount }}</span>
            </RouterLink>
          </nav>
          <RouterLink :to="{ name: 'boards' }" class="side-more">全部板块 →</RouterLink>
        </section>

        <!-- 热门标签：同样只做预览，全集在 /tags -->
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
          <RouterLink :to="{ name: 'tags' }" class="side-more">全部标签 →</RouterLink>
        </section>

        <!-- 活跃作者（复用排行榜接口的前 5）：给"这个社区有活人"一个证据 -->
        <section v-if="topAuthors.length" class="side-card">
          <h2 class="side-title">活跃作者</h2>
          <ul class="author-list">
            <li v-for="a in topAuthors" :key="a.id">
              <RouterLink
                :to="{ name: 'user-profile', params: { id: a.id } }"
                class="author-row"
              >
                <UserAvatar :src="a.avatar" :name="a.nickname" :seed="a.id" :size="26" />
                <span class="author-name">{{ a.nickname }}</span>
                <span class="author-rep">{{ a.reputation }}</span>
              </RouterLink>
            </li>
          </ul>
          <RouterLink :to="{ name: 'ranking' }" class="side-more">完整榜单 →</RouterLink>
        </section>
      </aside>

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

      <!-- 加载骨架（不用 spinner —— 骨架能保持布局稳定，避免 CLS）。
           ⚠️ v6.2：骨架包进 .post-list（并随它双列化，见样式里的 :deep 覆写），
           否则"单列骨架 → 双列内容"会在数据到达时整页跳一下（CLS ≈ 0 的承诺就破了）。 -->
      <div v-if="loading" class="post-list">
        <PostListSkeleton :count="6" />
      </div>

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

    <!-- 右侧栏已移至顶部横排带（v6.2，见上方 .top-strip） -->
    </div>
  </template>

<style scoped>
/* ★ v6.2：右栏三卡移到页面上方横排（.top-strip）后，页面回归**单列** ——
   帖子流接管整个 1200 容器，用双列卡片（见 .post-list）。 */

/* ---------- 主列 ---------- */
.main-col {
  min-width: 0;
}

/* ==================== 首屏 Hero ====================
   v6「白纸浅蓝」的做法：
   1. 白纸卡（--color-card）+ 1px 发丝边：浅色下不叠任何彩色底 ——
      首屏要像一张干净的扉页，不是海报。
   2. 网格纹理改为**浅蓝**且全站只留这一处（--color-grid-line，24px）：
      浅色下纹理超过 6% 就会"先被看见、再被讨厌"，所以再压一档。
   3. 标题第二行用实心蓝立记忆点（全站唯一允许的大面积主色文字）。
   4. 数据统计用真实接口值，加载中显示破折号而非 0。 */
.hero {
  position: relative;
  overflow: hidden;
  margin-bottom: var(--space-6);
  padding: var(--space-8);
  background-color: var(--color-card);
  border: 1px solid var(--color-border-hairline);
  border-radius: var(--radius-lg);
  /* ★ v6.1：从"单列 + 底部统计"改为**左文案 / 右统计**双栏 ——
     1440 桌面下旧版右侧一大片空白、整卡高约 330px 挡掉半屏帖子；
     改后高度约 290px，首屏能多看 1~2 条。元素间距沿用各自的 margin，
     所以 row-gap 归零、只留列间距（不改模板，用 grid 位置定位 stats）。 */
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  column-gap: var(--space-10);
}

.hero-grid {
  position: absolute;
  inset: 0;
  /* ★ v6：线色改走令牌 --color-grid-line（浅蓝 5.5%）——
     v5 的"深底白线"照搬到浅色会把首屏印成格子布。 */
  background-image:
    linear-gradient(var(--color-grid-line) 1px, transparent 1px),
    linear-gradient(90deg, var(--color-grid-line) 1px, transparent 1px);
  background-size: 24px 24px;
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
  /* grid stretch 会把 inline-flex 拉成整行宽的横条，这里是"标签"不是"横条" */
  justify-self: start;
  padding: 4px var(--space-3);
  margin-bottom: var(--space-4);
  font-size: var(--text-xs);
  font-weight: 500;
  letter-spacing: var(--tracking-label);
  color: var(--color-muted-foreground);
  background: var(--color-neutral-soft);
  border: 1px solid var(--color-border-hairline);
  /* ★ v5：胶囊形态 → 规格标签（--radius-sm），与全站硬朗圆角一致 */
  border-radius: var(--radius-sm);
}

/* 小圆点表示"这里活着"。颜色用唯一信号色（v6 起为蓝）；扩散改用伪元素的
   scale（1 → 1.7，6px 上约 4px 幅度）代替 box-shadow 扩散 ——
   有生命感，但不会闪成一只跑马灯，也避免了硬编码 rgba。 */
.hero-pulse {
  position: relative;
  width: 6px;
  height: 6px;
  border-radius: var(--radius-full);
  background: var(--color-accent);
}

.hero-pulse::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: var(--radius-full);
  background: var(--color-accent);
  animation: hero-pulse 2.8s var(--ease-out) infinite;
}

@keyframes hero-pulse {
  0% {
    transform: scale(1);
    opacity: 0.5;
  }
  70% {
    transform: scale(1.7);
    opacity: 0;
  }
  100% {
    transform: scale(1.7);
    opacity: 0;
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
  /* 标题就是纯色（不做渐变文字/发光）。这里是全站唯一允许的
     大面积主色文字 —— 实心蓝既是品牌记忆点，也守住"主色只做信号"的纪律。 */
  color: var(--color-accent);
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
  /* 蓝底白字（#2563EB + #FFFFFF = 5.2:1，见 wcag-contrast.py） */
  color: var(--color-on-accent);
  background: var(--color-accent-solid);
}

.hero-btn-primary:hover {
  /* hover = 深一档底 + 纸片轻浮（蓝调柔影），不做彩色光晕 */
  background: var(--color-accent-solid-hover);
  transform: translateY(-1px);
  box-shadow: var(--shadow-lift);
}

.hero-btn-ghost {
  color: var(--color-muted-foreground);
  background: transparent;
  border-color: var(--color-border);
}

.hero-btn-ghost:hover {
  /* hover 只提亮前景与描边（border → border-strong），不加底色。 */
  color: var(--color-foreground);
  border-color: var(--color-border-strong);
}

/* ---------- Hero 统计（v6.1：底部横排 → 右栏 2×2） ---------- */
.hero-stats {
  position: relative;
  /* 定位到右列、纵向跨左列的 4 行（eyebrow/标题/副标/按钮各一行） */
  grid-column: 2;
  grid-row: 1 / span 4;
  align-self: center;
  display: grid;
  grid-template-columns: repeat(2, minmax(96px, 1fr));
  gap: var(--space-5) var(--space-8);
  margin: 0;
  padding-left: var(--space-8);
  border-left: 1px solid var(--color-border-subtle);
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
  /* ★ v5：数字改用等宽 + 表格数字（JetBrains Mono）——"数字精确"是
     工程社区的真实信号，等宽让多组计数纵向对齐。字重 700 → 600。 */
  font-family: var(--font-mono);
  font-size: var(--text-xl);
  font-weight: 600;
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
  /* ★ v5：从"药丸底片"改**下划线式**（透明底、无圆角）—— 排序是导航，
     不该用实心底与内容抢注意力；当前项靠一条 2px 主色线标记。 */
  border-radius: 0;
  transition:
    color var(--transition-fast),
    box-shadow var(--transition-fast);
}

.sort-tab:hover {
  color: var(--color-foreground);
}

.sort-tab.active {
  color: var(--color-foreground);
  box-shadow: inset 0 -2px 0 var(--color-accent);
  font-weight: 500;
}

.result-count {
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
  font-variant-numeric: tabular-nums;
}

/* ---------- 列表（v6.2：单列行式 → 双列卡片网格） ----------
   用户指定"像小红书那样"：两列卡片、同行等高（grid 默认 stretch）。
   阅读顺序保持 1,2 / 3,4 —— CSS columns 的瀑布流会把顺序拆成"先左列后右列"，
   列表有时间序，不能那样排。 */
.post-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3);
}

/* 骨架随双列走（组件根是单列 flex，这里覆写为同规格 grid） */
.post-list :deep(.skeleton-list) {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
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

/* ---------- 顶部横排带（原右栏三卡，v6.2 移到上方横排） ---------- */
.top-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-3);
  align-items: stretch;
  margin-bottom: var(--space-5);
}

.side-card {
  padding: var(--space-4);
  /* ★ v5：实体卡 + 发丝边（border-subtle → border-hairline），与 .card 同规格 */
  background: var(--color-card);
  border: 1px solid var(--color-border-hairline);
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

/* 横排带里的板块列表改 2 列：一列 7 项会把整条带子撑到 300px 高 */
.top-strip .board-nav {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 2px var(--space-2);
}

.board-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-2) var(--space-3);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  /* ★ v5：--radius-md → --radius-sm，小控件圆角整体收硬 */
  border-radius: var(--radius-sm);
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

/* 侧栏「更多」入口：把"预览"与"全集"分开 ——
   这一列不再需要装下所有板块/标签，长度也就压住了
   （sticky 侧栏一旦高过视口，最底下的卡片实际看不到） */
.side-more {
  display: block;
  width: 100%;
  margin-top: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-border-subtle);
  font-size: var(--text-xs);
  color: var(--color-muted-foreground);
}

.side-more:hover {
  color: var(--color-accent);
}

/* ---------- 活跃作者 ---------- */
.author-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
}

/* padding 与 .board-item 对齐，两列名单的左边界一致 */
.author-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  transition: background var(--transition-fast);
}

.author-row:hover {
  background: var(--color-muted);
}

.author-name {
  flex: 1;
  min-width: 0;
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.author-row:hover .author-name {
  color: var(--color-foreground);
}

.author-rep {
  flex-shrink: 0;
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
  font-variant-numeric: tabular-nums;
}

/* ---------- 响应式 ---------- */
@media (max-width: 1024px) {
  /* 帖子流回单列（双列在窄屏每列太窄），横排带降为 2 列 */
  .post-list,
  .post-list :deep(.skeleton-list) {
    grid-template-columns: 1fr;
  }

  .top-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

/* Hero 双栏在窄屏拍回单列（统计回到文案下方横排） */
@media (max-width: 768px) {
  .hero {
    grid-template-columns: 1fr;
    padding: var(--space-6) var(--space-5);
  }

  .hero-stats {
    grid-column: 1;
    grid-row: auto;
    margin-top: var(--space-6);
    padding-left: 0;
    padding-top: var(--space-5);
    border-left: none;
    border-top: 1px solid var(--color-border-subtle);
    grid-template-columns: repeat(auto-fit, minmax(84px, 1fr));
  }
}

@media (max-width: 640px) {
  .top-strip {
    grid-template-columns: 1fr;
  }

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
