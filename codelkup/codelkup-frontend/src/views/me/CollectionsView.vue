<script setup lang="ts">
/**
 * 我的收藏。
 *
 * 数据源 `GET /api/users/me/collections`（需登录，按收藏时间倒序）。
 *
 * 页码放 URL（`?page=2`）而不是组件内 ref：从收藏列表点进详情、再按浏览器
 * 返回时能回到原来那一页 —— 与首页列表同一约定，翻页即改 URL，由 watcher
 * 统一触发加载（不在事件里再调一次 load，避免"点一次页请求两次"）。
 *
 * 卡片复用的是全站统一的 PostCard：后端装配时把当前用户当 viewer，
 * 所以每条都带 `collected=true`，卡片上会显示「已收藏」徽标。
 */
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { userApi } from '@/api/user'
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

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await userApi.getCollections(page.value, size)
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
  <div class="container collections-page">
    <header class="page-head">
      <div>
        <h1 class="page-title">我的收藏</h1>
        <p class="page-desc">
          {{ loading ? '加载中…' : `共 ${total} 篇，按收藏时间倒序` }}
        </p>
      </div>
      <el-button text type="primary" @click="router.push({ name: 'home' })">
        去社区逛逛
      </el-button>
    </header>

    <PostListSkeleton v-if="loading" :count="5" />

    <el-alert v-else-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button text type="primary" @click="load">重试</el-button>
    </el-alert>

    <EmptyState
      v-else-if="!posts.length"
      icon="post"
      title="还没有收藏任何帖子"
      description="在帖子详情点击「收藏」，之后就能在这里快速找回；取消收藏后会自动从列表消失。"
      action-text="去发现内容"
      @action="router.push({ name: 'home' })"
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
.collections-page {
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
  font-variant-numeric: tabular-nums;
}

/* ---------- 列表 ---------- */
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