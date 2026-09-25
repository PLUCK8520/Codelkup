<script setup lang="ts">
/**
 * 公开个人主页。
 *
 * ⚠️ 走的是 GET /api/users/{id}（UserVO，**不含邮箱**）。
 *    只有本人看自己的 /settings 才走 /users/me。这个区分是刻意的：
 *    邮箱属于隐私，不能让任意访客通过遍历 id 拿到。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { postApi } from '@/api/post'
import { userApi } from '@/api/user'
import { followApi } from '@/api/social'
import { useAuthStore } from '@/stores/auth'
import UserAvatar from '@/components/UserAvatar.vue'
import PostCard from '@/components/PostCard.vue'
import PostListSkeleton from '@/components/PostListSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import FollowButton from '@/components/FollowButton.vue'
import { formatCount, formatRelativeTime } from '@/utils/format'
import type { FollowStateVO, PostListVO, UserVO } from '@/types/api'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const user = ref<UserVO | null>(null)
const posts = ref<PostListVO[]>([])
const total = ref(0)
const pages = ref(0)

/** 关注状态与计数。独立于 UserVO —— 计数是公开的，following 依赖登录态 */
const followState = ref<FollowStateVO | null>(null)

const loadingUser = ref(true)
const loadingPosts = ref(true)
const error = ref('')
const notFound = ref(false)

const page = ref(1)
const size = 10

const userId = computed(() => Number(route.params.id))
const isSelf = computed(() => auth.user?.id === userId.value)

/** 技术标签是逗号分隔的字符串，拆成数组渲染 */
const techTags = computed(() =>
  (user.value?.techTags || '')
    .split(/[,，\s]+/)
    .map((s) => s.trim())
    .filter(Boolean),
)

async function loadUser() {
  loadingUser.value = true
  error.value = ''
  notFound.value = false
  user.value = null
  try {
    user.value = await userApi.getProfile(userId.value)
  } catch (e) {
    if ((e as Error & { code?: number }).code === 40401) {
      notFound.value = true
    } else {
      error.value = e instanceof Error ? e.message : '加载失败'
    }
  } finally {
    loadingUser.value = false
  }
}

async function loadPosts() {
  loadingPosts.value = true
  try {
    const res = await postApi.list({ authorId: userId.value, page: page.value, size, sort: 'latest' })
    posts.value = res.records
    total.value = res.total
    pages.value = res.pages
  } catch {
    posts.value = []
  } finally {
    loadingPosts.value = false
  }
}

async function loadFollowState() {
  try {
    followState.value = await followApi.state(userId.value)
  } catch {
    // 关注状态是"附加信息"，拿不到不该让整个主页失败
    followState.value = null
  }
}

/**
 * 关注按钮回调。
 *
 * 本地调整粉丝数而不重新拉 state：一是省一次往返，
 * 二是避免"刚点完关注，数字闪一下"的观感问题。
 * 服务端返回的 followeeCount 是<b>目标用户</b>的，与按钮无关，所以不动它。
 */
function onFollowChange(following: boolean) {
  if (!followState.value) {
    return
  }
  followState.value = {
    ...followState.value,
    following,
    followerCount: Math.max(0, followState.value.followerCount + (following ? 1 : -1)),
  }
}

watch(
  userId,
  () => {
    page.value = 1
    loadUser()
    loadPosts()
    loadFollowState()
  },
  { immediate: true },
)

function changePage(p: number) {
  page.value = p
  loadPosts()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}
</script>

<template>
  <div class="container profile-wrap">
    <!-- 加载中 -->
    <div v-if="loadingUser" class="profile-skeleton" aria-busy="true">
      <span class="sk-avatar" />
      <div class="sk-lines">
        <span class="sk-bar sk-name" />
        <span class="sk-bar sk-bio" />
      </div>
    </div>

    <!-- 用户不存在 -->
    <EmptyState
      v-else-if="notFound"
      icon="search"
      title="用户不存在"
      description="这个用户可能已注销，或者链接有误。"
      action-text="回到首页"
      @action="router.push({ name: 'home' })"
    />

    <!-- 加载失败 -->
    <el-alert v-else-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button text type="primary" @click="loadUser">重试</el-button>
    </el-alert>

    <template v-else-if="user">
      <!-- 资料卡 -->
      <header class="profile-card">
        <UserAvatar
          :src="user.avatar"
          :name="user.nickname"
          :seed="user.id"
          :size="88"
        />

        <div class="profile-info">
          <div class="profile-name-row">
            <h1 class="profile-name">{{ user.nickname }}</h1>
            <span v-if="user.role === 2" class="role-badge role-admin">管理员</span>
            <span v-else-if="user.role === 1" class="role-badge role-mod">版主</span>
            <span v-if="isSelf" class="role-badge role-self">这是你</span>
          </div>

          <p v-if="user.bio" class="profile-bio">{{ user.bio }}</p>
          <p v-else class="profile-bio profile-bio-empty">这个人很低调，还没有填写简介。</p>

          <div v-if="techTags.length" class="tech-tags">
            <span v-for="t in techTags" :key="t" class="tech-tag">{{ t }}</span>
          </div>

          <div class="profile-stats">
            <div class="stat">
              <span class="stat-num">{{ formatCount(followState?.followerCount ?? 0) }}</span>
              <span class="stat-label">粉丝</span>
            </div>
            <div class="stat">
              <span class="stat-num">{{ formatCount(followState?.followeeCount ?? 0) }}</span>
              <span class="stat-label">关注</span>
            </div>
            <div class="stat">
              <span class="stat-num">{{ formatCount(user.reputation) }}</span>
              <span class="stat-label">声望</span>
            </div>
            <div class="stat">
              <span class="stat-num">Lv.{{ user.level }}</span>
              <span class="stat-label">等级</span>
            </div>
            <div class="stat">
              <span class="stat-num">{{ formatCount(total) }}</span>
              <span class="stat-label">发帖</span>
            </div>
            <div class="stat">
              <span class="stat-num">{{ formatRelativeTime(user.createTime) }}</span>
              <span class="stat-label">加入</span>
            </div>
          </div>

          <div v-if="isSelf" class="profile-actions">
            <el-button @click="router.push({ name: 'settings' })">编辑资料</el-button>
          </div>
          <!-- 别人的主页才有关注按钮；自己的主页显示"编辑资料" -->
          <div v-else class="profile-actions">
            <FollowButton
              :user-id="user.id"
              :following="followState?.following ?? null"
              @change="onFollowChange"
            />
          </div>
        </div>
      </header>

      <!-- 发帖列表 -->
      <section class="profile-posts">
        <h2 class="section-title">
          {{ isSelf ? '我的帖子' : 'TA 的帖子' }}
          <span class="section-count">{{ total }}</span>
        </h2>

        <PostListSkeleton v-if="loadingPosts" :count="3" />

        <EmptyState
          v-else-if="!posts.length"
          icon="post"
          :title="isSelf ? '你还没有发过帖子' : 'TA 还没有发过帖子'"
          :description="isSelf ? '分享你的第一个技术心得或问题吧' : ''"
          :action-text="isSelf ? '去发帖' : ''"
          @action="router.push({ name: 'post-create' })"
        />

        <div v-else class="post-list">
          <PostCard v-for="post in posts" :key="post.id" :post="post" />
        </div>

        <div v-if="!loadingPosts && pages > 1" class="pagination">
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
      </section>
    </template>
  </div>
</template>

<style scoped>
.profile-wrap {
  padding-bottom: var(--space-8);
}

/* ---------- 资料卡 ---------- */
.profile-card {
  display: flex;
  gap: var(--space-6);
  padding: var(--space-6);
  background: var(--color-card);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
}

.profile-info {
  flex: 1;
  min-width: 0;
}

.profile-name-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.profile-name {
  font-size: var(--text-xl);
  letter-spacing: -0.02em;
}

.role-badge {
  padding: 1px var(--space-2);
  font-size: 11px;
  font-weight: 500;
  line-height: 1.7;
  border-radius: var(--radius-sm);
}

.role-admin {
  color: #fca5a5;
  background: rgba(244, 63, 94, 0.18);
  border: 1px solid rgba(244, 63, 94, 0.4);
}

.role-mod {
  color: #93c5fd;
  background: rgba(59, 130, 246, 0.18);
  border: 1px solid rgba(59, 130, 246, 0.4);
}

.role-self {
  color: var(--color-muted-foreground);
  background: var(--color-muted);
  border: 1px solid var(--color-border);
}

.profile-bio {
  margin-top: var(--space-3);
  font-size: var(--text-sm);
  line-height: var(--leading-relaxed);
  color: var(--color-muted-foreground);
}

.profile-bio-empty {
  color: var(--color-subtle-foreground);
  font-style: italic;
}

.tech-tags {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
  margin-top: var(--space-3);
}

.tech-tag {
  padding: 2px var(--space-2);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--color-muted-foreground);
  background: var(--color-muted);
  border-radius: var(--radius-sm);
}

.profile-stats {
  display: flex;
  gap: var(--space-6);
  flex-wrap: wrap;
  margin-top: var(--space-5);
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-border-subtle);
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stat-num {
  font-size: var(--text-md);
  font-weight: 600;
  color: var(--color-foreground);
  font-variant-numeric: tabular-nums;
}

.stat-label {
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
}

.profile-actions {
  margin-top: var(--space-4);
}

/* ---------- 帖子列表 ---------- */
.profile-posts {
  margin-top: var(--space-6);
}

.section-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
  font-size: var(--text-lg);
}

.section-count {
  padding: 0 var(--space-2);
  font-size: var(--text-xs);
  font-weight: 500;
  color: var(--color-muted-foreground);
  background: var(--color-muted);
  border-radius: var(--radius-full);
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

/* ---------- 骨架 ---------- */
.profile-skeleton {
  display: flex;
  gap: var(--space-6);
  padding: var(--space-6);
  background: var(--color-card);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
}

.sk-avatar {
  width: 88px;
  height: 88px;
  flex-shrink: 0;
  border-radius: var(--radius-full);
  background: var(--color-muted);
}

.sk-lines {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding-top: var(--space-2);
}

.sk-bar {
  display: block;
  border-radius: var(--radius-sm);
  background: linear-gradient(
    90deg,
    var(--color-muted) 25%,
    var(--color-elevated) 37%,
    var(--color-muted) 63%
  );
  background-size: 400% 100%;
  animation: sk-shimmer 1.4s ease infinite;
}

.sk-name {
  height: 22px;
  width: 30%;
}

.sk-bio {
  height: 14px;
  width: 60%;
}

@keyframes sk-shimmer {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

/* ---------- 响应式 ---------- */
@media (max-width: 640px) {
  .profile-card {
    flex-direction: column;
    align-items: center;
    text-align: center;
    padding: var(--space-5);
  }

  .profile-name-row,
  .tech-tags,
  .profile-stats {
    justify-content: center;
  }

  .profile-stats {
    width: 100%;
  }

  .stat {
    flex: 1;
    align-items: center;
  }

  .profile-actions {
    width: 100%;
  }

  .profile-actions .el-button {
    width: 100%;
    margin-left: 0;
  }
}
</style>
