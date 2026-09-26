<script setup lang="ts">
/**
 * 通知中心。
 *
 * 交互要点：
 *  - 点击一条未读通知 → 先乐观标记已读（本地角标 -1），再发请求，失败时回滚
 *  - 通知的跳转目标由 `type` + `targetType` + `targetId` 在前端拼出（后端不给链接）
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { notificationApi } from '@/api/notification'
import { useNotificationStore } from '@/stores/notification'
import UserAvatar from '@/components/UserAvatar.vue'
import PostListSkeleton from '@/components/PostListSkeleton.vue'
import { formatRelativeTime } from '@/utils/format'
import { NotificationType, type NotificationVO } from '@/types/api'

const router = useRouter()
const notificationStore = useNotificationStore()

const items = ref<NotificationVO[]>([])
const total = ref(0)
const pages = ref(0)
const page = ref(1)
const size = 20
const onlyUnread = ref(false)
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

/**
 * 拼跳转目标。
 *
 * 后端只给事实（目标的类型与 ID），链接是前端的事 ——
 * 路由结构变化时后端不用跟着改。
 *
 * 帖子类通知（评论/回复/点赞/采纳）都指向帖子：评论没有独立详情页，
 * 点进去应该看到完整讨论上下文。关注类通知则指向发起者主页。
 */
function targetOf(n: NotificationVO) {
  if (n.type === NotificationType.FOLLOW && n.senderId) {
    return { name: 'user-profile', params: { id: n.senderId } }
  }
  if (n.targetType === 1 && n.targetId) {
    return { name: 'post-detail', params: { id: n.targetId } }
  }
  return null
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await notificationApi.list({ onlyUnread: onlyUnread.value, page: page.value, size })
    items.value = res.records
    total.value = res.total
    pages.value = res.pages
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
    items.value = []
  } finally {
    loading.value = false
  }
}

/** 打开某条通知：标记已读 + 跳转 */
async function onOpen(n: NotificationVO) {
  const target = targetOf(n)

  if (n.isRead === 0) {
    // 乐观标记：先改本地，让角标立刻响应；失败再回滚
    n.isRead = 1
    notificationStore.decrement()
    try {
      await notificationApi.markRead(n.id)
    } catch {
      n.isRead = 0
      notificationStore.increment()
      ElMessage.warning('标记已读失败，请稍后重试')
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
    ElMessage.success('已全部标记为已读')
    if (onlyUnread.value) {
      await load()
    }
  } catch {
    // 错误提示由 request.ts 统一弹出，这里不重复提示
  }
}

function toggleOnlyUnread() {
  onlyUnread.value = !onlyUnread.value
  page.value = 1
  load()
}

function changePage(p: number) {
  page.value = p
  load()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

const isEmpty = computed(() => !loading.value && !error.value && items.value.length === 0)

onMounted(async () => {
  await load()
  // 进页面时校准一次角标（本地值可能是上次会话留下的）
  await notificationStore.refreshUnread()
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

    <PostListSkeleton v-if="loading" :count="5" />

    <el-alert v-else-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button text type="primary" @click="load">重试</el-button>
    </el-alert>

    <div v-else-if="isEmpty" class="empty-card">
      <p class="empty-title">{{ onlyUnread ? '没有未读通知' : '还没有收到通知' }}</p>
      <p class="empty-text">
        当有人评论你的帖子、回复你的评论、点赞你的内容或关注你时，会在这里看到。
      </p>
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
  max-width: var(--container-narrow);
}

/* ---------- 页头 ---------- */
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
