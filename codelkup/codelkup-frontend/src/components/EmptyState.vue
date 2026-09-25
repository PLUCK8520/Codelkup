<script setup lang="ts">
/**
 * 空状态占位。
 *
 * 三种情况共用：列表无数据 / 搜索无结果 / 个人主页无发帖。
 * 关键是**给出下一步动作**，而不是只说"暂无数据" —— 后者对用户毫无帮助。
 */
withDefaults(
  defineProps<{
    /** 顶部图标类型 */
    icon?: 'empty' | 'search' | 'post'
    title?: string
    description?: string
    /** 不传则不渲染按钮 */
    actionText?: string
  }>(),
  {
    icon: 'empty',
    title: '暂无内容',
    description: '',
    actionText: '',
  },
)

defineEmits<{ (e: 'action'): void }>()
</script>

<template>
  <div class="empty-state">
    <div class="empty-icon" aria-hidden="true">
      <!-- 空盒子 -->
      <svg v-if="icon === 'empty'" viewBox="0 0 24 24" width="34" height="34" fill="none"
           stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
        <path d="M21 8v13H3V8" />
        <path d="M1 3h22v5H1z" />
        <path d="M10 12h4" />
      </svg>
      <!-- 放大镜 -->
      <svg v-else-if="icon === 'search'" viewBox="0 0 24 24" width="34" height="34" fill="none"
           stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="11" cy="11" r="8" />
        <path d="m21 21-4.35-4.35" />
      </svg>
      <!-- 文档 -->
      <svg v-else viewBox="0 0 24 24" width="34" height="34" fill="none"
           stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
        <path d="M14 2v6h6M16 13H8M16 17H8M10 9H8" />
      </svg>
    </div>

    <h3 class="empty-title">{{ title }}</h3>
    <p v-if="description" class="empty-desc">{{ description }}</p>

    <el-button v-if="actionText" type="primary" class="empty-action" @click="$emit('action')">
      {{ actionText }}
    </el-button>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: var(--space-12) var(--space-6);
  background: var(--color-card);
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-lg);
}

.empty-icon {
  display: grid;
  place-items: center;
  width: 64px;
  height: 64px;
  margin-bottom: var(--space-4);
  color: var(--color-subtle-foreground);
  background: var(--color-muted);
  border-radius: var(--radius-full);
}

.empty-title {
  font-size: var(--text-md);
  font-weight: 600;
  color: var(--color-foreground);
}

.empty-desc {
  margin-top: var(--space-2);
  max-width: 380px;
  font-size: var(--text-sm);
  line-height: var(--leading-normal);
  color: var(--color-muted-foreground);
}

.empty-action {
  margin-top: var(--space-5);
}
</style>
