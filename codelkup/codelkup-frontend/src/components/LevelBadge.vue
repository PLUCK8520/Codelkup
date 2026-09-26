<script setup lang="ts">
/**
 * 等级徽章。
 *
 * ⚠️ 等级是<b>声望的派生值</b>（阈值在后端 ReputationService.levelOf：0/50/200/500/1000），
 *    所以这里只负责展示，<b>不在前端重算</b> —— 否则前后端两套阈值迟早各说各话，
 *    出现"主页显示 Lv.3、接口返回 Lv.2"这种无法解释的现象。
 *
 * tooltip 里的阈值同样只是"解释文案"，判定权始终在后端。
 */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    level?: number
    reputation?: number
    /** sm 用于昵称旁 / md 用于资料卡 */
    size?: 'sm' | 'md'
  }>(),
  { level: 1, reputation: 0, size: 'sm' },
)

/** 与后端 LEVEL_THRESHOLDS 对齐（仅用于 tooltip 文案） */
const THRESHOLDS = [0, 50, 200, 500, 1000]

const tip = computed(() => {
  const rule = '等级由声望决定：被赞 +2、被收藏 +3、回答被采纳 +15'
  const next = THRESHOLDS[props.level]
  if (!next) {
    return `${rule}；已是最高等级`
  }
  const need = Math.max(0, next - (props.reputation ?? 0))
  return `${rule}；再涨 ${need} 点声望升到 Lv.${props.level + 1}`
})
</script>

<template>
  <el-tooltip :content="tip" placement="top">
    <span class="level-badge" :class="[`lv-${Math.min(Math.max(level, 1), 5)}`, `size-${size}`]">
      Lv.{{ level }}
    </span>
  </el-tooltip>
</template>

<style scoped>
.level-badge {
  display: inline-flex;
  align-items: center;
  font-family: var(--font-mono);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  border-radius: var(--radius-sm);
  border: 1px solid transparent;
  white-space: nowrap;
}

/* 低级中性、高级逐步转向强调色：用"色阶"表达成长，而不是堆图标/星星 */
.lv-1 {
  color: var(--color-subtle-foreground);
  background: var(--color-muted);
  border-color: var(--color-border-subtle);
}

.lv-2 {
  color: var(--color-muted-foreground);
  background: var(--color-neutral-soft);
  border-color: var(--color-border);
}

.lv-3 {
  color: var(--color-accent);
  background: var(--color-accent-soft);
  border-color: var(--color-accent-border);
}

.lv-4 {
  color: var(--color-accent-hover);
  background: var(--color-accent-soft);
  border-color: var(--color-accent-border);
}

.lv-5 {
  color: #fff;
  background: var(--color-accent-solid);
  border-color: var(--color-accent-solid);
  box-shadow: var(--inset-highlight);
}

.size-sm {
  padding: 1px var(--space-2);
  font-size: 11px;
  line-height: 1.7;
}

.size-md {
  padding: 2px var(--space-3);
  font-size: var(--text-xs);
  line-height: 1.8;
}
</style>