<script setup lang="ts">
/**
 * 关注 / 粉丝列表。
 *
 * 一个组件两条路由（`/users/:id/followers` 与 `/users/:id/followees`）：
 * tab 由**路由**决定而不是组件内的 ref —— 两个列表各自可分享、可刷新、可前进后退，
 * 切 tab 也天然回到第 1 页。
 *
 * 数据源是 Sprint 4 就写好的两个接口（`/api/users/{id}/followers|followees`），
 * 本轮**零后端改动**：此前这两个能力只被个人主页的两个数字代表，而数字还点不动 ——
 * 典型的"能力早就有了，只是没有落点"。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { followApi } from '@/api/social'
import { userApi } from '@/api/user'
import UserAvatar from '@/components/UserAvatar.vue'
import FollowButton from '@/components/FollowButton.vue'
import PostListSkeleton from '@/components/PostListSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { FollowUserVO } from '@/types/api'

type TabKey = 'followers' | 'followees'

const route = useRoute()
const router = useRouter()

const userId = computed(() => Number(route.params.id))
/** tab 由路由名决定：`user-followees` 是关注列表，其余按粉丝列表处理 */
const tab = computed<TabKey>(() => (route.name === 'user-followees' ? 'followees' : 'followers'))
const isFollowers = computed(() => tab.value === 'followers')

const rows = ref<FollowUserVO[]>([])
const total = ref(0)
const pages = ref(0)
const page = ref(1)
const size = 20
const loading = ref(true)
const error = ref('')
/** 列表归属人的昵称：从分享链接直接进来时，H1 得说清"这是谁的粉丝" */
const ownerName = ref('')

const countText = computed(() => {
  if (loading.value) return '加载中…'
  if (error.value) return '列表暂时没取到'
  return `共 ${total.value} 人`
})

const heading = computed(() => {
  const suffix = isFollowers.value ? '粉丝' : '关注'
  return ownerName.value ? `${ownerName.value} 的${suffix}` : suffix
})

async function loadOwnerName() {
  try {
    const user = await userApi.getProfile(userId.value)
    ownerName.value = user.nickname
  } catch {
    // 拿不到昵称不影响列表本身（H1 退化成「粉丝」/「关注」）
    ownerName.value = ''
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = isFollowers.value
      ? await followApi.followers(userId.value, page.value, size)
      : await followApi.followees(userId.value, page.value, size)
    rows.value = res.records
    total.value = res.total
    pages.value = res.pages
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
    rows.value = []
  } finally {
    loading.value = false
  }
}

/** 切 tab = 换路由（页面组件复用，但 URL 变了，刷新/分享都成立） */
function changeTab(key: TabKey) {
  if (key === tab.value) return
  router.push({
    name: key === 'followers' ? 'user-followers' : 'user-followees',
    params: { id: userId.value },
  })
}

function changePage(p: number) {
  router.push({ query: { ...route.query, page: p > 1 ? p : undefined } })
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

watch(
  () => [route.params.id, route.name, route.query.page],
  async () => {
    page.value = Number(route.query.page) || 1
    await load()
    await loadOwnerName()
  },
  { immediate: true },
)
</script>

<template>
  <div class="container connections-page">
    <header class="page-head">
      <div>
        <RouterLink
          :to="{ name: 'user-profile', params: { id: userId } }"
          class="crumb"
        >← 返回主页</RouterLink>
        <h1 class="page-title">{{ heading }}</h1>
        <p class="page-desc">{{ countText }}</p>
      </div>
    </header>

    <!-- tab 状态在 URL 上：切过去就是换了一条路由 -->
    <div class="conn-tabs" role="tablist" aria-label="列表类型">
      <button
        type="button"
        role="tab"
        class="conn-tab"
        :class="{ active: isFollowers }"
        :aria-selected="isFollowers"
        @click="changeTab('followers')"
      >
        粉丝
      </button>
      <button
        type="button"
        role="tab"
        class="conn-tab"
        :class="{ active: !isFollowers }"
        :aria-selected="!isFollowers"
        @click="changeTab('followees')"
      >
        关注
      </button>
    </div>

    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button text type="primary" @click="load">重试</el-button>
    </el-alert>

    <PostListSkeleton v-else-if="loading" :count="5" />

    <EmptyState
      v-else-if="!rows.length"
      icon="empty"
      :title="isFollowers ? '还没有粉丝' : '还没有关注任何人'"
      :description="
        isFollowers
          ? '当有人关注 TA 时，会出现在这里。'
          : 'TA 关注的人会显示在这里；你也可以去板块里找几个感兴趣的作者关注。'
      "
      :action-text="isFollowers ? '' : '去板块看看'"
      @action="router.push({ name: 'boards' })"
    />

    <ul v-else class="conn-list">
      <li
        v-for="(u, i) in rows"
        :key="u.id"
        class="conn-item anim-in"
        :style="{ '--i': Math.min(i, 8) }"
      >
        <RouterLink
          :to="{ name: 'user-profile', params: { id: u.id } }"
          class="conn-main"
        >
          <UserAvatar :src="u.avatar" :name="u.nickname" :seed="u.id" :size="38" />
          <span class="conn-body">
            <span class="conn-name">{{ u.nickname }}</span>
            <span v-if="u.bio" class="conn-bio line-clamp-2">{{ u.bio }}</span>
          </span>
        </RouterLink>

        <!-- 关注按钮自己处理乐观更新与"未登录引导登录"，自己那一行不渲染 -->
        <FollowButton :user-id="u.id" :following="u.following ?? null" size="small" />
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
.connections-page {
  max-width: var(--container-narrow);
}

.page-head {
  margin-bottom: var(--space-4);
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
  font-size: var(--text-2xl);
  letter-spacing: -0.02em;
}

.page-desc {
  margin-top: var(--space-1);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  font-variant-numeric: tabular-nums;
}

/* ---------- tab ---------- */
.conn-tabs {
  display: flex;
  gap: var(--space-1);
  margin-bottom: var(--space-4);
}

.conn-tab {
  padding: var(--space-1) var(--space-3);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  background: none;
  border: none;
  border-radius: var(--radius-sm);
  transition: all var(--transition-fast);
}

.conn-tab:hover {
  color: var(--color-foreground);
  background: var(--color-muted);
}

.conn-tab.active {
  color: var(--color-foreground);
  background: var(--color-muted);
  font-weight: 500;
}

/* ---------- 列表 ---------- */
.conn-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.conn-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  background: var(--color-card);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
  transition: background var(--transition-fast), border-color var(--transition-fast),
    transform var(--transition-fast);
}

.conn-item:hover {
  background: var(--color-card-hover);
  border-color: var(--color-border);
  transform: translateY(-1px);
}

.conn-main {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex: 1;
  min-width: 0;
}

.conn-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.conn-name {
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--color-foreground);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conn-main:hover .conn-name {
  color: var(--color-accent);
}

.conn-bio {
  font-size: var(--text-xs);
  line-height: var(--leading-snug);
  color: var(--color-muted-foreground);
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: var(--space-6);
}

@media (max-width: 640px) {
  .page-title {
    font-size: var(--text-xl);
  }

  .conn-item {
    padding: var(--space-3);
  }
}
</style>