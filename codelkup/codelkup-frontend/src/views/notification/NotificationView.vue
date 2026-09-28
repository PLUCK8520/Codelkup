<script setup lang="ts">
/**
 * 通知中心。
 *
 * 交互要点：
 *  - 点击一条未读通知 → 先乐观标记已读（本地角标 -1），再发请求，失败时回滚
 *  - 通知的跳转目标由 `type` + `targetType` + `targetId` 在前端拼出（后端不给链接）
 *
 * ★ V0.4 新增类型分组（本轮）：
 *  - 6 种类型收成 5 个 tab：「评论与回复」把 1（评论）+ 2（回复）合成一个 ——
 *    对用户来说都是"有人回了我"，拆成两个 tab 反而难找。后端相应支持
 *    `type=1,2` 的多类型筛选（`IN` 而不是 `=`）。
 *  - 每个 tab 带未读角标，数据来自 `/api/notifications/unread-counts`
 *    （后端一条 GROUP BY 出全部类型，而不是每个 tab 各发一次 COUNT）。
 *  - 筛选状态（tab / 只看未读 / 页码）**全部进 URL**：刷新、分享、前进后退都成立。
 *    这同时修掉一个老问题 —— 点进一条通知再按浏览器返回，列表会跳回第一页且丢掉
 *    "只看未读"，而这恰恰是通知中心里最高频的动作。
 *
 * ★ 实时刷新（本轮）：SSE 推来新未读数时，若正停在第 1 页，列表做一次**静默重拉**
 *   （不闪骨架屏、不打断阅读），新通知直接出现；翻在后续页码时只校准类型角标 ——
 *   正在翻页的人最讨厌列表突然重排。触发信号来自 store 的 `pushedAt`：只认推送，
 *   不认本地已读操作（见 stores/notification.ts 的说明）。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { notificationApi } from '@/api/notification'
import { useNotificationStore } from '@/stores/notification'
import UserAvatar from '@/components/UserAvatar.vue'
import PostListSkeleton from '@/components/PostListSkeleton.vue'
import { formatRelativeTime } from '@/utils/format'
import { NotificationType, type NotificationVO } from '@/types/api'

const route = useRoute()
const router = useRouter()
const notificationStore = useNotificationStore()

const items = ref<NotificationVO[]>([])
const total = ref(0)
const pages = ref(0)
const page = ref(1)
const size = 20
const loading = ref(true)
const error = ref('')

/** 通知类型 → 图标路径与配色（用 currentColor 描边，配色交给 CSS 类） */
const TYPE_META: Record<number, { path: string; label: string }> = {
  [NotificationType.COMMENT]: { path: 'M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z', label: '评论' },
  [NotificationType.REPLY]: { path: 'M9 17H7A4 4 0 0 1 7 9h10M13 5l4 4-4 4', label: '回复' },
  [NotificationType.LIKE]: { path: 'M12 21s-7.5-4.7-9.6-9A5.4 5.4 0 0 1 12 6.5 5.4 5.4 0 0 1 21.6 12c-2.1 4.3-9.6 9-9.6 9Z', label: '点赞' },
  [NotificationType.FOLLOW]: { path: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 7a4 4 0 1 0 0 8 4 4 0 0 0 0-8M19 8v6M22 11h-6', label: '关注' },
  [NotificationType.ACCEPT]: { path: 'M22 11.1V12a10 10 0 1 1-5.9-9.1M22 4 12 14l-3-3', label: '采纳' },
  [NotificationType.SYSTEM]: { path: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20M12 8v4M12 16h.01', label: '系统' },
}

function iconPathOf(type: number): string {
  return (TYPE_META[type] ?? TYPE_META[NotificationType.SYSTEM]).path
}

/* ==================== 类型分组 ==================== */

type TabKey = 'all' | 'reply' | 'like' | 'accept' | 'follow' | 'system'

/**
 * tab → 后端类型集合。
 * 「全部」的 types 为空数组 = 不带 type 参数（而不是"传 6 个类型"）——
 * 后者会在新增类型时漏掉，前者天然跟着后端走。
 */
const TABS: { key: TabKey; label: string; types: number[] }[] = [
  { key: 'all', label: '全部', types: [] },
  { key: 'reply', label: '评论与回复', types: [NotificationType.COMMENT, NotificationType.REPLY] },
  { key: 'like', label: '赞', types: [NotificationType.LIKE] },
  { key: 'accept', label: '采纳', types: [NotificationType.ACCEPT] },
  { key: 'follow', label: '关注', types: [NotificationType.FOLLOW] },
  { key: 'system', label: '系统', types: [NotificationType.SYSTEM] },
]

/** URL 是用户可编辑的：非法 tab 一律回退到「全部」 */
const currentTab = computed<TabKey>(
  () => TABS.find((t) => t.key === String(route.query.tab ?? ''))?.key ?? 'all',
)
const tabMeta = computed(() => TABS.find((t) => t.key === currentTab.value) ?? TABS[0])
const onlyUnread = computed(() => route.query.unread === '1')

/** 各类型未读数（只有有未读的类型会出现，缺的按 0 处理） */
const unreadByType = ref<Record<number, number>>({})

/** tab 角标：「全部」与顶栏红点同源（store），其余按类型求和 */
function unreadOfTab(tab: { key: TabKey; types: number[] }): number {
  if (tab.key === 'all') return notificationStore.unread
  return tab.types.reduce((sum, t) => sum + (unreadByType.value[t] ?? 0), 0)
}

async function refreshUnreadByType() {
  try {
    const list = await notificationApi.unreadCounts()
    const map: Record<number, number> = {}
    for (const item of list) map[item.type] = item.count
    unreadByType.value = map
  } catch {
    // 分组角标是锦上添花：取不到就不显示数字，列表本身照常可用
    unreadByType.value = {}
  }
}

/** 本地增减某类型的未读数（乐观更新 + 失败回滚），与 store 的未读总数同步做 */
function bumpType(type: number, delta: number) {
  const next = { ...unreadByType.value }
  const cur = next[type] ?? 0
  const value = cur + delta
  if (value <= 0) {
    delete next[type]
  } else {
    next[type] = value
  }
  unreadByType.value = next
}

/* ==================== 数据 ==================== */

/**
 * 拼跳转目标。
 *
 * 后端只给事实（目标的类型与 ID），链接是前端的事 ——
 * 路由结构变化时后端不用跟着改。
 *
 * 帖子类通知（评论/回复/点赞/采纳）都指向帖子：评论没有独立详情页，
 * 点进去应该看到完整讨论上下文。关注类通知则指向发起者主页。
 *
 * 评论点赞（targetType=2）的目标是某条评论 —— 它没有独立详情页，
 * 服务端已在 `targetPostId` 里给出该评论所属帖子（第三轮审计），
 * 这里直接跳到帖子，不必再查一次"评论属于哪篇"。
 */
function targetOf(n: NotificationVO) {
  if (n.type === NotificationType.FOLLOW && n.senderId) {
    return { name: 'user-profile', params: { id: n.senderId } }
  }
  if (n.targetType === 2 && n.targetPostId) {
    return { name: 'post-detail', params: { id: n.targetPostId } }
  }
  if (n.targetType === 1 && n.targetId) {
    return { name: 'post-detail', params: { id: n.targetId } }
  }
  return null
}

/**
 * 拉取当前筛选下的列表。
 *
 * @param silent 静默刷新（实时推送触发）：不闪骨架屏；失败也不清列表 ——
 *   一次"锦上添花"的后台刷新，不该把用户正在读的内容换成报错页。
 */
let loadSeq = 0
async function load(silent = false) {
  const seq = ++loadSeq
  if (!silent) {
    loading.value = true
    error.value = ''
  }
  try {
    const types = tabMeta.value.types
    const res = await notificationApi.list({
      onlyUnread: onlyUnread.value,
      type: types.length ? types : undefined,
      page: page.value,
      size,
    })
    // 过期结果直接丢弃：请求期间可能切了 tab / 又触发了一次刷新，后发先至会写错数据
    if (seq !== loadSeq) return
    items.value = res.records
    total.value = res.total
    pages.value = res.pages
    // 静默刷新成功时顺手修好旧的失败态：列表能出现，就没必要继续报错
    error.value = ''
  } catch (e) {
    if (silent || seq !== loadSeq) return
    error.value = e instanceof Error ? e.message : '加载失败'
    items.value = []
  } finally {
    if (!silent && seq === loadSeq) loading.value = false
  }
}

/** 打开某条通知：标记已读 + 跳转 */
async function onOpen(n: NotificationVO) {
  const target = targetOf(n)

  if (n.isRead === 0) {
    // 乐观标记：先改本地，让角标立刻响应；失败再回滚
    n.isRead = 1
    notificationStore.decrement()
    bumpType(n.type, -1)
    try {
      await notificationApi.markRead(n.id)
    } catch {
      n.isRead = 0
      notificationStore.increment()
      bumpType(n.type, 1)
      ElMessage.warning('标记已读失败，请稍后重试')
    }
    // 「只看未读」下列表里不该再留着它 —— 否则"点过了却还在未读列表里"
    if (onlyUnread.value) {
      items.value = items.value.filter((x) => x.id !== n.id)
      total.value = Math.max(0, total.value - 1)
    }
  }

  if (target) {
    router.push(target)
  }
}

async function onMarkAllRead() {
  if (notificationStore.unread === 0) {
    ElMessage.info('没有未读通知')
    return
  }
  try {
    await notificationApi.markAllRead()
    // 本地全部置为已读：比重新拉一页更快，且不受当前分页/筛选影响
    items.value = items.value.map((n) => ({ ...n, isRead: 1 }))
    notificationStore.reset()
    unreadByType.value = {}
    ElMessage.success('已全部标记为已读')
    if (onlyUnread.value) {
      await load()
    }
  } catch {
    // 错误提示由 request.ts 统一弹出，这里不重复提示
  }
}

/* ==================== URL 状态 ==================== */

/** 改筛选条件：一律回到第 1 页（换了 tab 却停在原来的第 3 页 = 空页） */
function pushFiltered(patch: Record<string, unknown>) {
  router.push({ query: { ...route.query, ...patch, page: undefined } })
}

function changeTab(key: TabKey) {
  if (key === currentTab.value) return
  pushFiltered({ tab: key === 'all' ? undefined : key })
}

function toggleOnlyUnread() {
  pushFiltered({ unread: onlyUnread.value ? undefined : '1' })
}

function changePage(p: number) {
  router.push({ query: { ...route.query, page: p > 1 ? p : undefined } })
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

/** 空态文案：先区分"没有未读"与"这一类本来就没有"，再区分 tab */
const emptyText = computed(() => {
  const label = tabMeta.value.label
  const isAll = tabMeta.value.key === 'all'
  if (onlyUnread.value) {
    return isAll
      ? {
          title: '没有未读通知',
          text: '当有人评论你的帖子、回复你的评论、点赞你的内容或关注你时，会在这里看到。',
        }
      : {
          title: `没有未读的${label}通知`,
          text: '取消「只看未读」可以看到这一类的历史通知，或切到「全部」看其它类型。',
        }
  }
  return isAll
    ? {
        title: '还没有收到通知',
        text: '当有人评论你的帖子、回复你的评论、点赞你的内容或关注你时，会在这里看到。',
      }
    : {
        title: `还没有${label}相关的通知`,
        text: '切到「全部」可以看到其它类型的通知。',
      }
})

const isEmpty = computed(() => !loading.value && !error.value && items.value.length === 0)

watch(
  () => [route.query.tab, route.query.unread, route.query.page],
  async () => {
    page.value = Number(route.query.page) || 1
    await load()
  },
  { immediate: true },
)

// 进页面时校准角标（本地值可能是上次会话留下的）
refreshUnreadByType()
notificationStore.refreshUnread()

/* ==================== 实时刷新（SSE 推送 → 列表） ==================== */

/** 防抖窗口：一次评论/回复往往伴随角标连续两次推送（创建 + 未读累计），合并成一次重拉 */
const LIVE_REFRESH_DEBOUNCE_MS = 800
let liveTimer: ReturnType<typeof setTimeout> | null = null

watch(
  () => notificationStore.pushedAt,
  () => {
    if (liveTimer !== null) clearTimeout(liveTimer)
    liveTimer = setTimeout(() => {
      liveTimer = null
      // 第 1 页：静默重拉列表（新通知直接出现）；其它页：只校准类型角标，不动列表
      if (page.value === 1 && !loading.value) void load(true)
      void refreshUnreadByType()
    }, LIVE_REFRESH_DEBOUNCE_MS)
  },
)

onBeforeUnmount(() => {
  if (liveTimer !== null) clearTimeout(liveTimer)
})
</script>

<template>
  <div class="container notification-page">
    <header class="page-head">
      <div>
        <h1 class="page-title">通知</h1>
        <p class="page-desc">
          {{ notificationStore.unread > 0 ? `${notificationStore.unread} 条未读` : '暂无未读通知' }}
        </p>
      </div>
      <div class="head-actions">
        <button
          type="button"
          class="filter-toggle"
          :class="{ active: onlyUnread }"
          :aria-pressed="onlyUnread"
          @click="toggleOnlyUnread"
        >
          只看未读
        </button>
        <el-button :disabled="notificationStore.unread === 0" @click="onMarkAllRead">
          全部已读
        </el-button>
      </div>
    </header>

    <!-- 类型分组：状态在 URL 上，可分享可刷新 -->
    <div class="type-tabs" role="tablist" aria-label="通知类型">
      <button
        v-for="t in TABS"
        :key="t.key"
        type="button"
        role="tab"
        class="type-tab"
        :class="{ active: currentTab === t.key }"
        :aria-selected="currentTab === t.key"
        @click="changeTab(t.key)"
      >
        {{ t.label }}
        <span v-if="unreadOfTab(t) > 0" class="tab-badge">
          {{ unreadOfTab(t) > 99 ? '99+' : unreadOfTab(t) }}
        </span>
      </button>
    </div>

    <PostListSkeleton v-if="loading" :count="5" />

    <el-alert v-else-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button text type="primary" @click="load()">重试</el-button>
    </el-alert>

    <div v-else-if="isEmpty" class="empty-card">
      <p class="empty-title">{{ emptyText.title }}</p>
      <p class="empty-text">{{ emptyText.text }}</p>
    </div>

    <ul v-else class="notification-list">
      <li
        v-for="(n, i) in items"
        :key="n.id"
        class="notification-item anim-in"
        :class="{ unread: n.isRead === 0 }"
        :style="{ '--i': Math.min(i, 8) }"
      >
        <button type="button" class="item-btn" @click="onOpen(n)">
          <!-- 类型图标 -->
          <span class="type-icon" :class="`type-${n.type}`" aria-hidden="true">
            <svg
              viewBox="0 0 24 24"
              width="15"
              height="15"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <path :d="iconPathOf(n.type)" />
            </svg>
          </span>

          <UserAvatar
            :src="n.senderAvatar"
            :name="n.senderNickname"
            :seed="n.senderId ?? 0"
            :size="34"
          />

          <span class="item-body">
            <span class="item-text">{{ n.content }}</span>
            <time class="item-time" :datetime="n.createTime">
              {{ formatRelativeTime(n.createTime) }}
            </time>
          </span>

          <!-- 未读点 -->
          <span v-if="n.isRead === 0" class="unread-dot" aria-label="未读"></span>
        </button>
      </li>
    </ul>

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
.notification-page {
  max-width: var(--container-list);
}

/* ---------- 页头 ---------- */
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: var(--space-4);
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

.head-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-shrink: 0;
}

.filter-toggle {
  padding: var(--space-2) var(--space-3);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  background: none;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  transition: all var(--transition-fast);
}

.filter-toggle:hover {
  color: var(--color-foreground);
  border-color: var(--color-secondary);
}

.filter-toggle.active {
  color: var(--color-on-accent);
  background: var(--color-accent-solid);
  border-color: var(--color-accent-solid);
}

/* ---------- 类型分组 ---------- */
.type-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
  margin-bottom: var(--space-4);
}

.type-tab {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-3);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  background: none;
  border: none;
  border-radius: var(--radius-sm);
  transition: all var(--transition-fast);
}

.type-tab:hover {
  color: var(--color-foreground);
  background: var(--color-muted);
}

.type-tab.active {
  color: var(--color-foreground);
  background: var(--color-muted);
  font-weight: 500;
}

/* 角标：低饱和底 + 强调色文字（与全站徽标同一套语言，不做彩色块） */
.tab-badge {
  min-width: 18px;
  padding: 0 5px;
  font-size: var(--text-xs);
  font-weight: 500;
  line-height: 18px;
  text-align: center;
  color: var(--color-accent);
  background: var(--color-accent-soft);
  border-radius: var(--radius-full);
  font-variant-numeric: tabular-nums;
}

/* ---------- 列表 ---------- */
.notification-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.notification-item {
  background: var(--color-card);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
  transition: border-color var(--transition-fast);
}

.notification-item:hover {
  border-color: var(--color-border);
}

/* 未读：左侧一道强调色 + 略亮的底，一眼能扫出来 */
.notification-item.unread {
  background: var(--color-muted);
  box-shadow: inset 3px 0 0 var(--color-accent);
}

.item-btn {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  width: 100%;
  padding: var(--space-3) var(--space-4);
  text-align: left;
  color: inherit;
  background: none;
  border: none;
  border-radius: var(--radius-lg);
}

.type-icon {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: var(--radius-full);
  flex-shrink: 0;
  color: var(--color-muted-foreground);
  background: var(--color-muted);
}

/* 不同类型给不同的低调配色，便于快速区分 */
/* ★ v4：6 种通知类型原本配了 6 种高饱和色（青/红/绿/琥珀…）。
   在玻璃底上这些色块会显得更杂，而颜色本该只承担"未读/已读"这一个语义 ——
   类型差异交给图标与文案。统一为"单一强调色 + 低饱和底"。 */
.type-1,
.type-2,
.type-3,
.type-4,
.type-5 {
  color: var(--color-accent);
  background: var(--color-accent-soft);
}
.type-6 { color: var(--color-subtle-foreground); background: var(--color-neutral-soft); }

.item-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.item-text {
  font-size: var(--text-base);
  color: var(--color-foreground);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-time {
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
  font-variant-numeric: tabular-nums;
}

.unread-dot {
  width: 8px;
  height: 8px;
  border-radius: var(--radius-full);
  background: var(--color-accent);
  flex-shrink: 0;
}

/* ---------- 空态 ---------- */
.empty-card {
  padding: var(--space-10) var(--space-5);
  text-align: center;
  background: var(--color-card);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
}

.empty-title {
  font-size: var(--text-md);
  font-weight: 600;
}

.empty-text {
  margin-top: var(--space-3);
  font-size: var(--text-sm);
  line-height: var(--leading-relaxed);
  color: var(--color-muted-foreground);
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: var(--space-6);
}

/* ---------- 响应式 ---------- */
@media (max-width: 640px) {
  .page-head {
    flex-direction: column;
  }

  .page-title {
    font-size: var(--text-xl);
  }

  .head-actions {
    width: 100%;
  }

  .item-btn {
    align-items: flex-start;
    padding: var(--space-3);
  }
}
</style>