<script setup lang="ts">
/**
 * 声望榜。
 *
 * ★ 为什么只有一个维度（而不是"声望榜 / 采纳榜"两个 tab）：
 *   榜单的价值取决于数据的**区分度**，实测当前库：
 *   - 声望：92 / 51 / 51 / 15 … —— 有真实梯度 ✓
 *   - 被采纳的回答：全库 29 条，分散在 **29 个不同作者**手里，人人都是 1
 *     ⇒ "并列第 1~29 名"的榜单没有任何信息量
 *   - 发帖数：种子数据把大家拉平成 230 左右/人，且"发帖多"≠"贡献大"（会奖励刷帖）
 *   所以不摆一个全员并列的榜 —— 等真实采纳数据积累起来再加维度，
 *   比现在放一个假榜要诚实。判断依据记在 TASKS.md 的备注 18。
 *
 * 数据：`GET /api/users/ranking`（公开接口，游客可见）。
 * `rank` 来自服务端，前端不做下标算术（将来榜单加分页时名次才不会被算错）。
 */
import { computed, onMounted, ref } from 'vue'
import { userApi } from '@/api/user'
import { useAuthStore } from '@/stores/auth'
import UserAvatar from '@/components/UserAvatar.vue'
import LevelBadge from '@/components/LevelBadge.vue'
import EmptyState from '@/components/EmptyState.vue'
import PostListSkeleton from '@/components/PostListSkeleton.vue'
import { formatRelativeTime } from '@/utils/format'
import type { RankingUserVO } from '@/types/api'

const TOP_N = 20

const auth = useAuthStore()

const rows = ref<RankingUserVO[]>([])
const loading = ref(true)
const error = ref('')

/** 计数行三态：失败时**不显示数字** —— 把"没拿到"显示成"0 人上榜"是两回事 */
const countText = computed(() => {
  if (loading.value) return '加载中…'
  if (error.value) return '榜单暂时没取到'
  return `按声望倒序 · 显示前 ${rows.value.length} 名（采纳 +15 / 被赞 +2 / 被收藏 +3）`
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = await userApi.ranking(TOP_N)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
    rows.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="container ranking-page">
    <header class="page-head">
      <div>
        <h1 class="page-title">排行榜</h1>
        <p class="page-desc">{{ countText }}</p>
      </div>
    </header>

    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button text type="primary" @click="load">重试</el-button>
    </el-alert>

    <PostListSkeleton v-else-if="loading" :count="6" />

    <EmptyState
      v-else-if="!rows.length"
      icon="empty"
      title="还没有人上榜"
      description="声望来自被采纳、被点赞、被收藏 —— 回答别人的问题、写出被收藏的内容，就会出现在这里。"
    />

    <ol v-else class="rank-list">
      <li
        v-for="(u, i) in rows"
        :key="u.id"
        class="rank-item anim-in"
        :class="{ 'is-top': u.rank <= 3, 'is-me': u.id === auth.user?.id }"
        :style="{ '--i': Math.min(i, 8) }"
      >
        <span class="rank-no" :class="{ 'is-top': u.rank <= 3 }" aria-label="名次">{{ u.rank }}</span>

        <UserAvatar :src="u.avatar" :name="u.nickname" :seed="u.id" :size="38" />

        <span class="rank-body">
          <span class="rank-line">
            <RouterLink
              :to="{ name: 'user-profile', params: { id: u.id } }"
              class="rank-name"
            >{{ u.nickname }}</RouterLink>
            <!-- 「我」标记：登录后一眼找到自己，不必逐个比对昵称 -->
            <span v-if="u.id === auth.user?.id" class="rank-me-tag">我</span>
          </span>
          <span class="rank-meta">
            <LevelBadge :level="u.level" :reputation="u.reputation" />
            <span v-if="u.lastActive" class="rank-active">
              {{ formatRelativeTime(u.lastActive) }}活跃
            </span>
          </span>
        </span>

        <span class="rank-rep">
          <b>{{ u.reputation }}</b>
          <i>声望</i>
        </span>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.ranking-page {
  max-width: var(--container-list);
}

.page-head {
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

.rank-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.rank-item {
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

.rank-item:hover {
  background: var(--color-card-hover);
  border-color: var(--color-border);
  transform: translateY(-1px);
}

/* 我的那一行：左侧一道强调色，扫一眼就能定位 */
.rank-item.is-me {
  box-shadow: inset 3px 0 0 var(--color-accent);
}

/* ---------- 名次 ---------- */
.rank-no {
  min-width: 28px;
  font-family: var(--font-mono);
  font-size: var(--text-md);
  font-weight: 500;
  text-align: center;
  color: var(--color-subtle-foreground);
  font-variant-numeric: tabular-nums;
}

/* 前三名：只加字重与颜色，不引入金/银/铜三套色（同屏多色相是廉价感的首要来源） */
.rank-no.is-top {
  color: var(--color-accent);
  font-weight: 700;
}

/* ---------- 主体 ---------- */
.rank-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  flex: 1;
  min-width: 0;
}

.rank-line {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}

.rank-name {
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--color-foreground);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rank-name:hover {
  color: var(--color-accent);
}

.rank-me-tag {
  flex-shrink: 0;
  padding: 0 var(--space-2);
  font-size: var(--text-xs);
  color: var(--color-accent);
  background: var(--color-accent-soft);
  border-radius: var(--radius-full);
}

.rank-meta {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
}

/* ---------- 声望值 ---------- */
.rank-rep {
  display: flex;
  align-items: baseline;
  gap: var(--space-1);
  flex-shrink: 0;
}

.rank-rep b {
  font-size: var(--text-lg);
  font-weight: 600;
  color: var(--color-foreground);
  font-variant-numeric: tabular-nums;
}

.rank-rep i {
  font-style: normal;
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
}

@media (max-width: 640px) {
  .page-title {
    font-size: var(--text-xl);
  }

  .rank-item {
    padding: var(--space-3);
    gap: var(--space-2);
  }

  /* 窄屏把"活跃时间"藏掉：一行里塞不下三个信息块，优先保住名次/昵称/声望 */
  .rank-active {
    display: none;
  }
}
</style>