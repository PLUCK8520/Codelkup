<script setup lang="ts">
/**
 * 关注流 —— 我关注的人发的帖子（按时间倒序）。
 *
 * 之前它只是首页的一个排序 tab（`?sort=following`），离开首页就没了；
 * 现在它是一个独立落点，顶栏「关注」直接指向这里。
 *
 * 数据源 `GET /api/feed/following`（需登录，路由 meta 已标 requiresAuth，
 * 未登录由守卫带去登录页并带上 redirect，登录后回到本页）。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { feedApi } from '@/api/social'
import PostCard from '@/components/PostCard.vue'
import PostListSkeleton from '@/components/PostListSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { PostListVO } from '@/types/api'

const route = useRoute()
const router = useRouter()

const posts = ref<PostListVO[]>([])
const total = ref(0)
const pages = ref(0)
const page = ref(1)
const size = 20
const loading = ref(true)
const error = ref('')

/**
 * 计数行文案。
 * ⚠️ 失败时**不显示数字**：请求失败时 total 停在 0，直接插值会渲染成
 *    「共 0 篇来自你关注的作者」—— 与"你真的没关注人"是两回事。
 */
const countText = computed(() => {
  if (loading.value) return '加载中…'
  if (error.value) return '关注流暂时没取到'
  return `共 ${total.value} 篇来自你关注的作者`
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await feedApi.following(page.value, size)
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

function changePage(p: number) {
  router.push({ query: { ...route.query, page: p > 1 ? p : undefined } })
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

watch(
  () => route.query.page,
  async () => {
    page.value = Number(route.query.page) || 1
    await load()
  },
  { immediate: true },
)
</script>

<template>
  <div class="container following-page">
    <header class="page-head">
      <div>
        <h1 class="page-title">关注</h1>
        <p class="page-desc">{{ countText }}</p>
      </div>
      <el-button text type="primary" @click="router.push({ name: 'boards' })">
        去发现更多作者
      </el-button>
    </header>

    <PostListSkeleton v-if="loading" :count="5" />

    <el-alert v-else-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button text type="primary" @click="load">重试</el-button>
    </el-alert>

    <EmptyState
      v-else-if="!posts.length"
      icon="post"
      title="关注流还是空的"
      description="在板块里找几个感兴趣的作者关注一下 —— 他们发布的新帖子会自动出现在这里"
      action-text="去板块看看"
      @action="router.push({ name: 'boards' })"
    />

    <div v-else class="post-list">
      <PostCard
        v-for="(post, i) in posts"
        :key="post.id"
        :post="post"
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
  </div>
</template>

<style scoped>
.following-page {
  max-width: var(--container-narrow);
}

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

@media (max-width: 640px) {
  .page-head {
    flex-direction: column;
  }

  .page-title {
    font-size: var(--text-xl);
  }
}
</style>