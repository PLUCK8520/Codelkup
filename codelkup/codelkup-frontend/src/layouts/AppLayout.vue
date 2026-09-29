<script setup lang="ts">
/**
 * 全局布局：顶部导航 + 内容区。
 *
 * 结构：Header（sticky）→ 主内容 → Footer
 * 响应式：≤1100px 收起一级导航改走汉堡面板（含搜索框折行，详见 style 中的断点）
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter, type RouteLocationRaw } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { InputInstance } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'
import { useStatsStore } from '@/stores/stats'
import { notificationApi } from '@/api/notification'
import { formatExact } from '@/utils/format'
import { useNotificationStream } from '@/composables/useNotificationStream'
import UserAvatar from '@/components/UserAvatar.vue'
import LevelBadge from '@/components/LevelBadge.vue'
import AiAnswerPanel from '@/components/AiAnswerPanel.vue'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const notificationStore = useNotificationStore()

const keyword = ref('')
const mobileNavOpen = ref(false)

const searchPlaceholder = '搜索帖子，支持中文关键词…'

/** 提交搜索 → 跳转到搜索页。关键词放 URL，结果可分享、可刷新、可前进后退 */
function onSearch() {
  const q = keyword.value.trim()
  if (!q) {
    ElMessage.info('请输入搜索关键词')
    return
  }
  router.push({ name: 'search', query: { keyword: q } })
  mobileNavOpen.value = false
}

// 在搜索页时把输入框与 URL 同步（从详情页点返回时能回显上次搜的词）。
// 只在搜索页同步：否则用户在首页搜索框输入到一半、点了别的链接，
// 输入内容会被 URL 里的空值清掉。
watch(
  () => [route.name, route.query.keyword],
  () => {
    if (route.name === 'search') {
      keyword.value = String(route.query.keyword ?? '')
    }
  },
)

/**
 * 通知实时通道（M3-2）：登录后建立 SSE，断线按指数退避重连，连不上 3 次转轮询。
 *
 * 依赖全部从 options 注入（composable 本身不 import api / store）——
 * 与 useDraft 同一条理由：状态机要能在 Node 里被契约脚本用假 EventSource 跑通。
 */
const notificationStream = useNotificationStream({
  fetchTicket: async () => (await notificationApi.sseTicket()).ticket,
  onUnread: (count) => notificationStore.setUnread(count),
  poll: () => notificationStore.refreshUnread(),
})

// 登录态变化时维护未读角标与实时通道：
// 登录 → 先拉一次底（SSE 建连需要一次往返）+ 建连；退出 → 关连接 + 清零
// （清零是必须的：否则会把上个账号的数字带给下一个人）
//
// ⚠️ 这里不再需要 onMounted 再补一次刷新：本 watch 带 immediate，
//    而路由守卫会在进入布局前先跑完 fetchCurrentUser —— 登录态定下来时
//    watch 必然再触发一次，硬刷新场景已经被覆盖。
watch(
  () => auth.isLoggedIn,
  (loggedIn) => {
    if (loggedIn) {
      notificationStore.refreshUnread()
      notificationStream.start()
    } else {
      notificationStream.stop()
      notificationStore.reset()
    }
  },
  { immediate: true },
)

/* ⌘K / Ctrl+K 聚焦搜索（v5 轻量版命令面板）。
   快捷键是"看不见的功能"，藏起来等于没有：输入框里放了 ⌘K 键帽提示，
   这里负责真正接线；就地出结果/跳转的完整命令面板留给下一阶段。 */
const searchInputRef = ref<InputInstance>()

function onGlobalKeydown(e: KeyboardEvent) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    searchInputRef.value?.focus()
  }
}

window.addEventListener('keydown', onGlobalKeydown)

onBeforeUnmount(() => {
  notificationStream.stop()
  window.removeEventListener('keydown', onGlobalKeydown)
})

async function onLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return // 用户取消
  }
  await auth.logout()
  ElMessage.success('已退出登录')
  router.push({ name: 'home' })
}

/**
 * 一级入口。
 *
 * `match` 用「路由名集合」而不是路径前缀：`/boards`（总览）与 `/boards/:slug`
 * （某板块的帖子）是两个不同的 name，但都该让「板块」亮起来；`/tags` 与
 * `/tags/:id` 同理。
 *
 * ⚠️ 热门 / 最新回复 **不在**顶栏 —— 它们是首页的排序 tab（`/?sort=hot`、
 *    `/?sort=active`），摆在这里等于给同一个页面开三个一级入口。
 */
const NAV_ITEMS: { label: string; to: RouteLocationRaw; match: string[] }[] = [
  { label: '首页', to: { name: 'home' }, match: ['home'] },
  { label: '问答', to: { name: 'questions' }, match: ['questions'] },
  { label: '板块', to: { name: 'boards' }, match: ['boards', 'board'] },
  { label: '标签', to: { name: 'tags' }, match: ['tags', 'tag-detail'] },
  // 排行榜：上线前实测过数据（当前库 19 个账号声望 ≥ 10，榜首 92 分、有明确梯度），
  // 不是"有个页面就挂上去"—— 平榜/空榜比没有榜更伤（见 RankingView 的注释）
  { label: '排行榜', to: { name: 'ranking' }, match: ['ranking'] },
  // 关注流需要登录：守卫会带 redirect 去登录页，登录后回到本页
  { label: '关注', to: { name: 'following' }, match: ['following'] },
]

/**
 * 创作入口：0=发帖讨论，1=提个问题。
 * 提问走 `/publish?type=1`，由编辑器读取 query 预选类型（避免"提问"藏在单选里没人发现）。
 */
function onCreate(type: number) {
  const isQuestion = type === 1
  if (!auth.isLoggedIn) {
    ElMessage.warning(isQuestion ? '请先登录后再提问' : '请先登录后再发帖')
    router.push({
      name: 'login',
      query: { redirect: isQuestion ? '/publish?type=1' : '/publish' },
    })
    return
  }
  router.push({ name: 'post-create', query: isQuestion ? { type: '1' } : {} })
}

const isActive = (names: string[]) => names.includes(String(route.name))

/**
 * 「问 Codelkup」（M3-8）：RAG 问答抽屉。
 *
 * 放在顶栏而不是首页：这个问题天生"随手一问"，用户在详情页、搜索结果里
 * 都可能想问；跳独立页面会丢掉当前上下文。
 * 未登录时不隐藏入口（隐藏了用户根本不知道有这功能）—— 引导去登录并带 redirect 回原地，
 * 与「创作」同一口径。
 */
const askOpen = ref(false)

function onAskAi() {
  if (!auth.isLoggedIn) {
    ElMessage.warning('请先登录后再提问')
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  askOpen.value = true
}

/**
 * 页脚导航（V0.5 四栏改版）。
 *
 * ★ 只放**真实存在**的路由。页脚是最容易堆死链的地方 ——
 * "关于我们 / 隐私政策 / 联系方式 / 友情链接"这类凑数项一个都不放：
 * 点进去 404 或空白，比页脚少几行更伤（这个站没有这些页面，就不装作有）。
 */
const FOOTER_BROWSE: { label: string; to: RouteLocationRaw }[] = [
  { label: '首页', to: { name: 'home' } },
  { label: '问答', to: { name: 'questions' } },
  { label: '板块', to: { name: 'boards' } },
  { label: '标签', to: { name: 'tags' } },
  { label: '排行榜', to: { name: 'ranking' } },
]

/** 「参与」栏：都需要登录，守卫会带 redirect 去登录页、登录后回到原地 */
const FOOTER_JOIN: { label: string; to: RouteLocationRaw }[] = [
  { label: '关注流', to: { name: 'following' } },
  { label: '我的收藏', to: { name: 'my-collections' } },
  { label: '我的草稿', to: { name: 'my-drafts' } },
  // 标签写"发布内容"而不是"发帖提问"：这条链接落在编辑器上，
  // 发帖 / 提问是编辑器里的两种类型（提问走 ?type=1），标成"提问"会与落点不符
  { label: '发布内容', to: { name: 'post-create' } },
]

/**
 * 页脚「站点数据」：真实接口计数（`GET /stats`，游客可读）。
 *
 * 数据来自**共享 stats store**（在途去重 + 30s TTL），不是本组件自己发请求 ——
 * 页脚在每个页面都渲染，首页 / 问答页 / 标签页也要同一份数，
 * 各自发一次的话首页就是"同一 URL 两遍"、问答页还会变成"每翻页一遍"。
 *
 * 除此之外还有三条讲究：
 *  1. `silent: true`（在 store 内）—— 拦截器对非 silent 请求会弹全局红条；
 *     页脚是次要信息，统计接口挂掉时不该打断用户（整栏隐藏即可，见模板的 v-if）。
 *  2. **等首屏空闲再触发**（requestIdleCallback）—— 不跟首屏的帖子列表抢连接。
 *     慢网下页脚数字晚到几百毫秒没人介意，首屏晚到才会被介意。
 *     Safari 没有 requestIdleCallback，退回 300ms 定时器。
 *  3. 拿不到就**整栏不渲染**，不放 0 或 "—" —— 那种东西看起来像站坏了。
 */
const statsStore = useStatsStore()

const FOOTER_STATS = computed(() => {
  const s = statsStore.data
  if (!s) return []
  return [
    { label: '帖子', value: s.boardPostCount, hint: '全部板块下的帖子总数' },
    { label: '板块', value: s.boardCount, hint: '站点开设的讨论板块数' },
    { label: '标签', value: s.tagCount, hint: '已被使用的标签数' },
    { label: '提问', value: s.questionCount, hint: '类型为「提问」的帖子数' },
    { label: '已解决', value: s.questionSolvedCount, hint: '已采纳答案的提问数' },
  ]
})

onMounted(() => {
  // store 的 ensure() 自带失败静默与去重，这里只管"什么时候触发"
  const load = () => void statsStore.ensure()

  // TS 的 lib.dom 未声明 requestIdleCallback，这里就地收窄类型
  const idle = (
    window as Window & {
      requestIdleCallback?: (cb: () => void, opts?: { timeout: number }) => number
    }
  ).requestIdleCallback

  if (typeof idle === 'function') {
    idle(load, { timeout: 2000 })
  } else {
    window.setTimeout(load, 300)
  }
})
</script>

<template>
  <div class="app-shell">
    <!-- ==================== 顶部导航 ==================== -->
    <header class="app-header">
      <div class="container header-inner">
        <!-- Logo -->
        <router-link :to="{ name: 'home' }" class="brand">
          <span class="brand-mark" aria-hidden="true">
            <!-- 鹰标（2026-09-29）：圆形深底徽章 —— 用户提供的低多边形鹰原画，
                 圆形裁切后内嵌 SVG（public/brand-mark.svg）；favicon 同源同图 -->
            <img src="/brand-mark.svg?v=2" alt="" width="30" height="30" />
          </span>
          <span class="brand-name">Codelkup</span>
        </router-link>

        <!-- 主导航（一级入口，6 个）。移动端点完后顺手收起面板，
             否则新页面已经切换、面板还盖在内容上 -->
        <nav class="main-nav" :class="{ open: mobileNavOpen }">
          <RouterLink
            v-for="item in NAV_ITEMS"
            :key="item.label"
            :to="item.to"
            class="nav-link"
            :class="{ active: isActive(item.match) }"
            @click="mobileNavOpen = false"
          >
            {{ item.label }}
          </RouterLink>
        </nav>

        <!-- 搜索 -->
        <div class="search-box">
          <el-input
            ref="searchInputRef"
            v-model="keyword"
            :placeholder="searchPlaceholder"
            clearable
            @keyup.enter="onSearch"
          >
            <template #prefix>
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor"
                   stroke-width="2" stroke-linecap="round">
                <circle cx="11" cy="11" r="8" />
                <path d="m21 21-4.35-4.35" />
              </svg>
            </template>
            <!-- ⌘K 键帽提示：快捷键没人知道等于没有，直接摆在输入框里 -->
            <template #suffix>
              <kbd class="kbd-hint">⌘K</kbd>
            </template>
          </el-input>
        </div>

        <!-- 右侧操作区 -->
        <div class="header-actions">
          <!-- 创作下拉：「提问」与「发帖」同级。
               之前提问藏在编辑器的一个单选里，而提问 → 采纳 → 声望是社区的核心闭环，
               该有一个显式入口（编辑器读 ?type=1 预选类型） -->
          <el-dropdown trigger="click" placement="bottom-end" @command="onCreate">
            <el-button type="primary" class="btn-publish">
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor"
                   stroke-width="2.4" stroke-linecap="round" style="margin-right: 5px">
                <path d="M12 5v14M5 12h14" />
              </svg>
              <span class="btn-publish-label">创作</span>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item :command="0">发帖讨论</el-dropdown-item>
                <el-dropdown-item :command="1">提个问题</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

          <!-- 问 Codelkup（M3-8）：从任意页面随手一问。
               未登录也可点（引导登录），隐藏入口反而让人不知道有这功能 -->
          <button
            type="button"
            class="ask-ai-btn"
            aria-label="问 Codelkup"
            @click="onAskAi"
          >
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor"
                 stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M12 3v3M12 18v3M3 12h3M18 12h3" />
              <path d="M6.3 6.3l2.1 2.1M15.6 15.6l2.1 2.1M17.7 6.3l-2.1 2.1M8.4 15.6l-2.1 2.1" />
            </svg>
            <span class="ask-ai-label">问 Codelkup</span>
          </button>

          <!-- 通知铃铛（仅登录后显示）。角标数字由 aria-hidden 隐藏，
               未读数已写进按钮的 aria-label，避免屏幕阅读器重复播报 -->
          <button
            v-if="auth.isLoggedIn"
            type="button"
            class="notify-btn"
            :aria-label="
              notificationStore.unread > 0
                ? `通知，${notificationStore.unread} 条未读`
                : '通知，无未读'
            "
            @click="router.push({ name: 'notifications' })"
          >
            <svg
              viewBox="0 0 24 24"
              width="18"
              height="18"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
              <path d="M13.73 21a2 2 0 0 1-3.46 0" />
            </svg>
            <span
              v-if="notificationStore.unread > 0"
              class="notify-badge"
              aria-hidden="true"
            >{{ notificationStore.unread > 99 ? '99+' : notificationStore.unread }}</span>
          </button>

          <!-- 已登录 -->
          <el-dropdown v-if="auth.isLoggedIn" trigger="click" placement="bottom-end">
            <button class="user-trigger" type="button" aria-label="用户菜单">
              <UserAvatar :src="auth.user?.avatar" :name="auth.user?.nickname" :size="30" />
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <!-- 身份一行：昵称 + 等级徽章 + 声望。数据与个人主页同源（都是 user 上的 level/reputation），
                     不是两套数字 —— 顶栏只是换个位置再展示一次 -->
                <div class="menu-user-meta">
                  <span class="menu-user-name">{{ auth.user?.nickname }}</span>
                  <LevelBadge :level="auth.user?.level" :reputation="auth.user?.reputation" />
                  <span class="menu-user-rep">声望 {{ auth.user?.reputation ?? 0 }}</span>
                </div>
                <el-dropdown-item @click="router.push({ name: 'user-profile', params: { id: auth.user!.id } })">
                  我的主页
                </el-dropdown-item>
                <!-- 菜单按用途分三组（用分隔线而非小标题：三条目一组时标题比内容还占地方）：
                     内容组（我的主页 / 我的收藏 / 我的草稿）· 设置组（账号设置 / 管理端）· 退出
                     ⚠️ 不放「我的帖子」：个人主页本来就是帖子列表，再加一个入口是同一页两遍 -->
                <el-dropdown-item @click="router.push({ name: 'my-collections' })">
                  我的收藏
                </el-dropdown-item>
                <!-- 草稿箱：M4-15 起编辑器会把草稿同步到服务端，这里给它一个落点 -->
                <el-dropdown-item @click="router.push({ name: 'my-drafts' })">
                  我的草稿
                </el-dropdown-item>
                <el-dropdown-item divided @click="router.push({ name: 'settings' })">
                  账号设置
                </el-dropdown-item>
                <!-- 管理端入口：仅管理员可见（后端另有 @PreAuthorize 兜底） -->
                <el-dropdown-item v-if="auth.isAdmin" @click="router.push({ name: 'admin' })">
                  管理端
                </el-dropdown-item>
                <el-dropdown-item divided @click="onLogout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

          <!-- 未登录 -->
          <template v-else>
            <el-button text class="btn-ghost" @click="router.push({ name: 'login' })">登录</el-button>
            <el-button class="btn-register" @click="router.push({ name: 'register' })">注册</el-button>
          </template>

          <!-- 移动端菜单按钮 -->
          <button class="nav-toggle" type="button" aria-label="展开导航"
                  @click="mobileNavOpen = !mobileNavOpen">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor"
                 stroke-width="2" stroke-linecap="round">
              <path d="M3 6h18M3 12h18M3 18h18" />
            </svg>
          </button>
        </div>
      </div>
    </header>

    <!-- ==================== 主内容 ==================== -->
    <main class="app-main">
      <slot />
    </main>

    <!-- RAG 问答抽屉（M3-8）：挂在布局层，任意页面都能开 -->
    <AiAnswerPanel v-model="askOpen" />

    <!-- ==================== 页脚 ====================
         ★ V0.5 四栏改版：品牌定位 / 浏览 / 参与 / 站点数据 + 底栏。
         只放三类"真东西"——能点进去的路由、真实接口计数、编译期事实（版本 / 技术栈），
         一个凑数的死链都不放。 -->
    <footer class="app-footer">
      <div class="container">
        <div class="footer-grid">
          <!-- 第 1 栏：品牌 + 一句话讲清"这是什么、来了能干什么" -->
          <div class="footer-col footer-about">
            <router-link :to="{ name: 'home' }" class="footer-brand">
              <img src="/brand-mark.svg?v=2" alt="" width="22" height="22" />
              <span>Codelkup</span>
            </router-link>
            <p class="footer-tagline">
              面向开发者与在校学生的技术社区：Markdown 发帖、中文全文检索、
              采纳式问答与站内通知。把问题写清楚，这里有人接。
            </p>
          </div>

          <!-- 第 2 栏：浏览 -->
          <nav class="footer-col" aria-labelledby="footer-browse-title">
            <h2 id="footer-browse-title" class="footer-col-title">浏览</h2>
            <ul class="footer-links">
              <li v-for="item in FOOTER_BROWSE" :key="item.label">
                <router-link :to="item.to">{{ item.label }}</router-link>
              </li>
            </ul>
          </nav>

          <!-- 第 3 栏：参与（未登录点进去由守卫带去登录页并带回原地） -->
          <nav class="footer-col" aria-labelledby="footer-join-title">
            <h2 id="footer-join-title" class="footer-col-title">参与</h2>
            <ul class="footer-links">
              <li v-for="item in FOOTER_JOIN" :key="item.label">
                <router-link :to="item.to">{{ item.label }}</router-link>
              </li>
            </ul>
          </nav>

          <!-- 第 4 栏：站点数据（真实接口计数；取不到就整栏不渲染） -->
          <div v-if="FOOTER_STATS.length" class="footer-col">
            <h2 class="footer-col-title">站点数据</h2>
            <ul class="footer-stats">
              <!-- title 写明每项口径：数字要能被核对，不能让人猜"已解决"算的是什么 -->
              <li v-for="item in FOOTER_STATS" :key="item.label" :title="item.hint">
                <span class="footer-stat-label">{{ item.label }}</span>
                <span class="footer-stat-value">{{ formatExact(item.value) }}</span>
              </li>
            </ul>
          </div>
        </div>

        <!-- 底栏：版权 + 编译期事实。技术栈不是装饰 ——
             它写的是"这套东西真实跑在哪套技术之上"，与页面上的功能一一对得上。 -->
        <div class="footer-bottom">
          <span class="footer-copy">© 2026 Codelkup · 开发者技术社区</span>
          <span class="footer-meta">V0.1.0 · Spring Boot 3.5 · Vue 3 · MySQL ngram</span>
        </div>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

/* ---------- Header ---------- */
.app-header {
  position: sticky;
  top: 0;
  z-index: 100;
  /* 半透明 + 模糊：滚动时内容从下方穿过，比不透明纯色条更有层次。
     ★ v3：底色改为令牌 --surface-header（与页面底色同源），并换成发丝边 ——
     之前这里写死了 rgba(18,27,46,.82)（旧卡片色），底色一改就出现色差横带。 */
  background: var(--surface-header);
  backdrop-filter: blur(16px) saturate(140%);
  -webkit-backdrop-filter: blur(16px) saturate(140%);
  border-bottom: 1px solid var(--color-border-hairline);
}

.header-inner {
  display: flex;
  align-items: center;
  gap: var(--space-5);
  height: 58px;
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-lg);
  font-weight: 700;
  color: var(--color-foreground);
  letter-spacing: var(--tracking-heading);
  flex-shrink: 0;
}

.brand:hover {
  color: var(--color-foreground);
}

/* 品牌标识：圆形深底鹰徽（2026-09-29 用户提供原画，深底与圆都在图片里）。
   容器只负责尺寸与 hover 位移；轻投影让深色徽章在白页上"浮"住。 */
.brand-mark {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  transition: transform var(--transition-fast);
}

.brand-mark img {
  display: block;
  width: 100%;
  height: 100%;
  filter: drop-shadow(0 1px 2px rgba(15, 23, 42, 0.18));
}

.brand:hover .brand-mark {
  transform: translateY(-1px);
}

.main-nav {
  display: flex;
  align-items: center;
  gap: var(--space-1);
}

.nav-link {
  position: relative;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  font-size: var(--text-base);
  color: var(--color-muted-foreground);
  transition: color var(--transition-fast), background var(--transition-fast);
  white-space: nowrap;
}

.nav-link:hover {
  color: var(--color-foreground);
  background: var(--color-muted);
}

/* 当前项用"底部主色线"标记而不是整块背景：
   背景块和 hover 态长得一样，用户分不清"选中"和"悬停"。
   选中态一条 2px 线已经说清，底色交给 hover，两条视觉通道互不干扰。 */
.nav-link.active {
  color: var(--color-foreground);
  background: none;
}

.nav-link.active::after {
  content: '';
  position: absolute;
  left: var(--space-3);
  right: var(--space-3);
  bottom: -1px;
  height: 2px;
  background: var(--color-accent);
}

.search-box {
  flex: 1;
  /* ★ V0.4：340 → 300。一级入口从 3 个变 5 个，搜索框要让出一点宽度，
     否则 1100px 附近会出现"导航、搜索、操作区三方抢宽度"的挤压 */
  max-width: 300px;
  margin-left: auto;
}

.search-box :deep(.el-input__wrapper) {
  background: var(--color-muted);
  box-shadow: none;
  border-radius: var(--radius-md);
}

.search-box :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--color-ring) inset;
}

/* ⌘K 键帽（v5）：等宽小字 + 发丝边，像键盘上的实体键 ——
   它同时承担"发现性"：用户看到它才知道有快捷键可用了 */
.kbd-hint {
  padding: 0 var(--space-1);
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  line-height: 16px;
  color: var(--color-subtle-foreground);
  background: var(--color-card);
  border: 1px solid var(--color-border-hairline);
  border-radius: var(--radius-sm);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-shrink: 0;
}

.btn-publish {
  font-weight: 500;
}

.btn-ghost {
  color: var(--color-muted-foreground);
}

.btn-ghost:hover {
  color: var(--color-foreground);
}

.btn-register {
  background: var(--color-muted);
  border-color: var(--color-border);
  color: var(--color-foreground);
}

.btn-register:hover {
  background: var(--color-elevated);
  border-color: var(--color-secondary);
  color: var(--color-foreground);
}

.user-trigger {
  display: grid;
  place-items: center;
  padding: 2px;
  background: none;
  border: 2px solid transparent;
  border-radius: var(--radius-full);
  transition: border-color var(--transition-fast);
}

.user-trigger:hover {
  border-color: var(--color-border);
}

/* ---------- 通知铃铛 ---------- */
.notify-btn {
  position: relative;
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  color: var(--color-muted-foreground);
  background: none;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  transition: all var(--transition-fast);
}

.notify-btn:hover {
  color: var(--color-foreground);
  background: var(--color-muted);
  border-color: var(--color-border);
}

/* 角标用绝对定位而非行内元素：出现/消失时不会把铃铛位置推来推去。
   未读是"状态"，用唯一信号色（v6 起为蓝）；红色留给真正的危险操作
   （破坏性语义一旦被日常状态占用，删除按钮就不再吓人了）。 */
.notify-badge {
  position: absolute;
  top: -2px;
  right: -2px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  font-size: 10px;
  font-weight: 600;
  line-height: 16px;
  color: var(--color-on-accent);
  text-align: center;
  background: var(--color-accent-solid);
  border: 1.5px solid var(--color-background);
  border-radius: var(--radius-full);
  font-variant-numeric: tabular-nums;
}

.nav-toggle {
  display: none;
  padding: var(--space-2);
  background: none;
  border: none;
  color: var(--color-muted-foreground);
  border-radius: var(--radius-md);
}

/* ---------- 问 Codelkup 按钮 ---------- */
.ask-ai-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  height: 34px;
  padding: 0 var(--space-3);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  background: none;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  white-space: nowrap;
  transition: color var(--transition-fast), background var(--transition-fast),
    border-color var(--transition-fast);
}

.ask-ai-btn:hover {
  color: var(--color-accent);
  background: var(--color-accent-soft);
  border-color: var(--color-accent-border);
}

/* 窄屏只留图标：顶栏已经有 5 个一级入口 + 搜索 + 创作 + 铃铛 + 头像 */
@media (max-width: 1400px) {
  .ask-ai-label {
    display: none;
  }

  .ask-ai-btn {
    padding: 0 var(--space-2);
  }
}

/* ---------- 用户菜单里的身份行 ---------- */
.menu-user-meta {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-1);
  padding: var(--space-2) var(--space-4) var(--space-2);
  border-bottom: 1px solid var(--color-border-subtle);
}

.menu-user-name {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-foreground);
}

.menu-user-rep {
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
  font-variant-numeric: tabular-nums;
}

/* ---------- Main ---------- */
.app-main {
  flex: 1;
  padding: var(--space-6) 0 var(--space-12);
}

/* ---------- Footer（V0.5 四栏改版）----------
   原来只有"品牌 + 技术栈"一行，信息量撑不起页脚 —— 用户扫到底部会以为站很空。
   现在四栏：品牌定位（略宽，给 tagline 换行空间）/ 浏览 / 参与 / 站点数据。
   ★ 断点与 header 同一套口径：1100 掉两栏、768 掉单栏。 */
.app-footer {
  border-top: 1px solid var(--color-border-subtle);
  padding: var(--space-10) 0 var(--space-6);
  margin-top: auto;
}

.footer-grid {
  display: grid;
  grid-template-columns: 1.7fr 1fr 1fr 1.25fr;
  gap: var(--space-8) var(--space-6);
}

.footer-col {
  min-width: 0;
}

/* 品牌：与顶栏同一枚鹰标 + 同一套等宽字 —— 页脚也要让人认出"这是同一个站" */
.footer-brand {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  font-family: var(--font-mono);
  font-size: var(--text-md);
  font-weight: 600;
  letter-spacing: var(--tracking-heading);
  color: var(--color-foreground);
  text-decoration: none;
  transition: color var(--transition-fast) var(--ease-out);
}

.footer-brand:hover {
  color: var(--color-accent);
}

.footer-brand img {
  display: block;
  border-radius: var(--radius-full);
}

.footer-tagline {
  margin: var(--space-3) 0 0;
  /* 40ch：中文行宽按字符数不好控，但 tagline 是固定文案，
     这个上限保证它在宽屏下换 2 行左右、不拉成一条长横线 */
  max-width: 40ch;
  font-size: var(--text-sm);
  line-height: var(--leading-relaxed);
  color: var(--color-muted-foreground);
}

.footer-col-title {
  margin: 0 0 var(--space-4);
  font-size: var(--text-xs);
  font-weight: 600;
  letter-spacing: var(--tracking-label);
  color: var(--color-muted-foreground);
}

.footer-links {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.footer-links a {
  font-size: var(--text-sm);
  color: var(--color-subtle-foreground);
  text-decoration: none;
  transition: color var(--transition-fast) var(--ease-out);
}

.footer-links a:hover {
  color: var(--color-accent);
}

/* 键盘可达性：页脚链接不少，焦点不可见等于键盘用户没法用 */
.footer-links a:focus-visible,
.footer-brand:focus-visible {
  outline: 2px solid var(--color-ring);
  outline-offset: 2px;
  border-radius: var(--radius-sm);
}

/* 站点数据：做成「标签 …… 读数」的对齐列表而不是统计卡片 ——
   等宽 + tabular-nums 让数字纵向对齐，读起来像后台读数，不是装饰性的 widget */
.footer-stats {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.footer-stats li {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-3);
  font-size: var(--text-sm);
}

.footer-stat-label {
  color: var(--color-subtle-foreground);
}

.footer-stat-value {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  color: var(--color-foreground);
}

.footer-bottom {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2) var(--space-4);
  margin-top: var(--space-8);
  padding-top: var(--space-5);
  border-top: 1px solid var(--color-border-hairline);
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
}

/* ★ v5 保留：页脚放**真实工程信号**（版本 + 技术栈 + 全文检索方案），等宽小字。
   灵感来自 V2EX：把版本号这类"真东西"直接摆上页面 ——
   它比任何装饰都更像"这里真的有人在维护"。 */
.footer-meta {
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
  font-variant-numeric: tabular-nums;
}

/* ---------- 响应式：平板 / 窄屏 ----------
   ★ V0.4：断点从 1024 提到 1100 —— 一级入口 3 → 5 个，1100 以内横排开始挤。
   顺带修掉一个既有问题：原来 769~1024 这段区间导航被 display:none 隐藏、
   汉堡按钮又是 display:none，导航在这段宽度里**根本打不开**；
   现在汉堡跟着断点一起出现，这段区间不再有"无导航可用"的窗口。 */
@media (max-width: 1100px) {
  .main-nav {
    display: none;
  }

  .search-box {
    max-width: none;
  }

  .nav-toggle {
    display: grid;
    place-items: center;
  }

  /* 导航展开为下拉面板 */
  .main-nav.open {
    display: flex;
    position: absolute;
    /* 相对 .app-header（sticky 即定位祖先）的 100%：头部无论多高都贴在它下方。
       v3 之前写死 top: 58px，窄屏头部换行后正好落在搜索框上，链接点不到。 */
    top: 100%;
    left: 0;
    right: 0;
    flex-direction: column;
    align-items: stretch;
    gap: 0;
    padding: var(--space-2);
    background: var(--color-elevated);
    border-bottom: 1px solid var(--color-border);
    box-shadow: var(--shadow-md);
  }

  /* 页脚：四栏挤不下 → 两栏（品牌栏与浏览同排，参与与数据同排） */
  .footer-grid {
    grid-template-columns: 1fr 1fr;
  }
}

/* ---------- 响应式：手机 ---------- */
@media (max-width: 768px) {
  .header-inner {
    gap: var(--space-3);
  }

  .brand-name {
    display: none;
  }

  .search-box {
    order: 3;
    flex-basis: 100%;
  }

  .header-inner {
    flex-wrap: wrap;
    height: auto;
    padding-top: var(--space-2);
    padding-bottom: var(--space-2);
  }

  /* ★ V0.4：原来写的是 .btn-publish span —— Element Plus 会把整个 slot
     包在自己的 <span> 里，那条规则其实把按钮内容（含图标）全隐藏了，
     窄屏只剩一个空蓝块。改成只隐藏我们的文字标签。 */
  .btn-publish-label {
    display: none;
  }

  /* 页脚：手机单栏堆叠；底栏也改为左对齐纵向排，
     否则"版权 / 技术栈"在窄屏会被挤成两行参差的中间对齐 */
  .footer-grid {
    grid-template-columns: 1fr;
    gap: var(--space-6);
  }

  .footer-bottom {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
