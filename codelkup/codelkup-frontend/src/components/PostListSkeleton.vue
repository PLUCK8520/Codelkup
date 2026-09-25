<script setup lang="ts">
/**
 * 列表骨架屏。
 *
 * 用骨架而非 spinner：骨架的空间占位与真实卡片一致，
 * 数据到达时布局不跳动（CLS ≈ 0）；spinner 会让内容"顶"上来。
 *
 * 无障碍：容器标 aria-busy，对屏幕阅读器只播报一次"加载中"，
 *        而不是逐块朗读无意义的灰色方块。
 */
withDefaults(defineProps<{ count?: number }>(), { count: 5 })
</script>

<template>
  <div class="skeleton-list" role="status" aria-busy="true" aria-live="polite">
    <span class="sr-only">正在加载帖子列表…</span>

    <div v-for="i in count" :key="i" class="skeleton-card" aria-hidden="true">
      <!-- 左侧计数条占位（与 PostCard 的 figures 同宽） -->
      <div class="sk-figures">
        <span class="sk-bar sk-bar-sm" />
        <span class="sk-bar sk-bar-sm" />
        <span class="sk-bar sk-bar-sm" />
      </div>

      <div class="sk-body">
        <span class="sk-bar sk-title" />
        <span class="sk-bar sk-line" />
        <span class="sk-bar sk-line sk-line-short" />
        <div class="sk-footer">
          <span class="sk-bar sk-chip" />
          <span class="sk-bar sk-chip" />
          <span class="sk-bar sk-meta" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.skeleton-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.skeleton-card {
  display: flex;
  gap: var(--space-4);
  padding: var(--space-4) var(--space-5);
  background: var(--color-card);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
}

.sk-figures {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  min-width: 52px;
  flex-shrink: 0;
}

.sk-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

/* ---------- 骨架条 ---------- */
.sk-bar {
  display: block;
  border-radius: var(--radius-sm);
  /* 微光扫过效果：比静态灰块更能传达"正在加载" */
  background: linear-gradient(
    90deg,
    var(--color-muted) 25%,
    var(--color-elevated) 37%,
    var(--color-muted) 63%
  );
  background-size: 400% 100%;
  animation: sk-shimmer 1.4s ease infinite;
}

.sk-bar-sm {
  height: 12px;
  width: 100%;
}

.sk-title {
  height: 18px;
  width: 62%;
}

.sk-line {
  height: 13px;
  width: 100%;
}

.sk-line-short {
  width: 78%;
}

.sk-footer {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-1);
}

.sk-chip {
  height: 18px;
  width: 52px;
  border-radius: var(--radius-sm);
}

.sk-meta {
  height: 13px;
  width: 150px;
  margin-left: auto;
}

@keyframes sk-shimmer {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

/* 屏幕阅读器专用文本 */
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

@media (max-width: 640px) {
  .skeleton-card {
    padding: var(--space-3) var(--space-4);
  }

  .sk-figures {
    display: none;
  }
}
</style>
