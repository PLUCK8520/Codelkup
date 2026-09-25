<script setup lang="ts">
/**
 * 404 页面。
 *
 * 常见场景是用户手输/收藏了一个已被删除的帖子 ID，
 * 所以除了回首页，也提供一个"返回上一页"的出口。
 */
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="container-narrow nf-wrap">
    <div class="nf-code" aria-hidden="true">404</div>
    <h1 class="nf-title">页面不存在</h1>
    <p class="nf-desc">
      这个地址可能已经失效，或者链接被写错了。检查一下拼写，
      或者回首页看看最新的技术讨论。
    </p>

    <div class="nf-actions">
      <el-button type="primary" @click="router.push({ name: 'home' })">回到首页</el-button>
      <el-button @click="router.back()">返回上一页</el-button>
    </div>
  </div>
</template>

<style scoped>
.nf-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding-top: var(--space-16);
  padding-bottom: var(--space-16);
}

.nf-code {
  font-family: var(--font-mono);
  font-size: 88px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: -0.04em;
  /* 用渐变文字弱化"错误"的刺眼感，同时保留视觉重量 */
  background: linear-gradient(180deg, var(--color-border) 0%, var(--color-background) 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  user-select: none;
}

.nf-title {
  margin-top: var(--space-4);
  font-size: var(--text-xl);
}

.nf-desc {
  margin-top: var(--space-3);
  max-width: 420px;
  font-size: var(--text-sm);
  line-height: var(--leading-relaxed);
  color: var(--color-muted-foreground);
}

.nf-actions {
  display: flex;
  gap: var(--space-3);
  margin-top: var(--space-6);
}
</style>
