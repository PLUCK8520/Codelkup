<script setup lang="ts">
/**
 * 关注按钮。
 *
 * 交互约定与 S3 的点赞/收藏保持一致：
 *  - **乐观更新**：点击立刻切换状态，不等接口返回（关注是高频轻量操作，等待会显得迟钝）
 *  - **失败回滚**：接口报错就退回原状态并提示，绝不让界面停在一个假状态上
 *  - **未登录不禁用，而是引导登录**：禁用按钮等于告诉用户"你不能点"，
 *    但其实登录后就能点。给出路径比给出拒绝更有用。
 */
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { followApi } from '@/api/social'
import { useAuthStore } from '@/stores/auth'

const props = withDefaults(
  defineProps<{
    userId: number
    /** 初始关注状态；未登录时为 null —— 此时按"未关注"渲染，点击再引导登录 */
    following?: boolean | null
    size?: 'small' | 'default'
  }>(),
  { following: null, size: 'default' },
)

const emit = defineEmits<{ (e: 'change', following: boolean): void }>()

const auth = useAuthStore()
const router = useRouter()

const active = ref(false)
const loading = ref(false)

// 父组件异步拿到状态后要能同步进来（例如详情页先渲染再拉 follow-state）
watch(
  () => props.following,
  (v) => {
    active.value = v === true
  },
  { immediate: true },
)

/** 自己的主页不显示关注按钮 —— 关注自己是后端明确拒绝的操作（40908） */
const isSelf = computed(() => auth.user?.id === props.userId)

async function onToggle() {
  if (!auth.isLoggedIn) {
    ElMessage.info('登录后即可关注作者')
    router.push({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
    return
  }
  if (loading.value) {
    return
  }

  const previous = active.value
  active.value = !previous // 乐观更新
  loading.value = true
  try {
    const state = previous
      ? await followApi.unfollow(props.userId)
      : await followApi.follow(props.userId)
    // 以后端返回的状态为准（它同时带回了最新计数）
    active.value = state.following === true
    emit('change', active.value)
    ElMessage.success(active.value ? '已关注' : '已取消关注')
  } catch {
    active.value = previous // 失败回滚；错误提示由 request.ts 统一弹出
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <button
    v-if="!isSelf"
    type="button"
    class="follow-btn"
    :class="[{ 'is-active': active, 'is-small': size === 'small' }]"
    :disabled="loading"
    :aria-pressed="active"
    @click.stop.prevent="onToggle"
  >
    <svg
      v-if="!active"
      viewBox="0 0 24 24"
      width="14"
      height="14"
      fill="none"
      stroke="currentColor"
      stroke-width="2.4"
      stroke-linecap="round"
      aria-hidden="true"
    >
      <path d="M12 5v14M5 12h14" />
    </svg>
    <svg
      v-else
      viewBox="0 0 24 24"
      width="14"
      height="14"
      fill="none"
      stroke="currentColor"
      stroke-width="2.6"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      <polyline points="20 6 9 17 4 12" />
    </svg>
    <span>{{ active ? '已关注' : '关注' }}</span>
  </button>
</template>

<style scoped>
.follow-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-4);
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--color-on-accent);
  background: var(--color-accent-solid);
  border: 1px solid var(--color-accent-solid);
  border-radius: var(--radius-md);
  transition: all var(--transition-fast);
  white-space: nowrap;
}

.follow-btn:hover:not(:disabled) {
  background: var(--color-accent-solid-hover);
  border-color: var(--color-accent-hover);
}

/* 已关注：降级为描边按钮，不要和"未关注"抢视觉焦点 */
.follow-btn.is-active {
  color: var(--color-muted-foreground);
  background: transparent;
  border-color: var(--color-border);
}

.follow-btn.is-active:hover:not(:disabled) {
  color: var(--color-foreground);
  border-color: var(--color-secondary);
  background: var(--color-muted);
}

.follow-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.follow-btn.is-small {
  padding: var(--space-1) var(--space-3);
  font-size: var(--text-xs);
}
</style>
