<script setup lang="ts">
/**
 * 全局布局：顶部导航 + 内容区。
 *
 * 结构：Header（sticky）→ 主内容 → Footer
 * 响应式：≤1100px 收起一级导航改走汉堡面板（含搜索框折行，详见 style 中的断点）
 */
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter, type RouteLocationRaw } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { InputInstance } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'
import { notificationApi } from '@/api/notification'
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

    <!-- ==================== 页脚 ==================== -->
    <footer class="app-footer">
      <div class="container footer-inner">
        <span class="footer-brand"><span class="sec-num">§</span> Codelkup · 开发者技术社区</span>
        <span class="footer-meta">V0.1.0 · Spring Boot 3.5 · Vue 3 · MySQL ngram</span>
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

/* ---------- Footer ---------- */
.app-footer {
  border-top: 1px solid var(--color-border-subtle);
  padding: var(--space-6) 0;
  margin-top: auto;
}

.footer-inner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-4);
  font-size: var(--text-sm);
  color: var(--color-subtle-foreground);
  flex-wrap: wrap;
}

/* ★ v5：页脚放**真实工程信号**（版本 + 技术栈 + 全文检索方案），等宽小字。
   灵感来自 V2EX：把版本号、在线人数这类"真东西"直接摆上页面 ——
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

  .footer-inner {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
