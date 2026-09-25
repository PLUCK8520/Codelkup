<script setup lang="ts">
/**
 * 帖子卡片（列表项）。
 *
 * 信息层级：状态标记 → 标题 → 摘要 → 标签 → 元信息（作者/板块/计数）
 * 可访问性：整卡可点，但用覆盖式链接而非包一层 div 点击事件 ——
 *          后者键盘用户无法触发，也无法用浏览器"在新标签页打开"。
 */
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import UserAvatar from './UserAvatar.vue'
import HighlightText from './HighlightText.vue'
import { formatRelativeTime, formatCount } from '@/utils/format'
import type { PostListVO } from '@/types/api'

const props = defineProps<{
  post: PostListVO
  /**
   * 搜索关键词（可选）。传入时标题与摘要把命中部分标红。
   * 普通列表不传，组件行为与之前完全一致。
   */
  keyword?: string
}>()

/**
 * 摘要区展示什么。
 *
 * 搜索结果的 `snippet` 优先于 `summary`：summary 取自正文<b>开头</b>，
 * 关键词在正文深处时它里面根本没有关键词，高亮不出来，
 * 用户也就看不出这条为什么被搜出来。snippet 是后端专门为此生成的
 * "关键词附近的一小段"。
 */
const displayText = computed(() => props.post.snippet || props.post.summary)

const detailLink = computed(() => ({ name: 'post-detail', params: { id: props.post.id } }))

const boardLink = computed(() =>
  props.post.boardSlug ? { name: 'board', params: { slug: props.post.boardSlug } } : null,
)

const authorLink = computed(() => ({ name: 'user-profile', params: { id: props.post.authorId } }))

/**
 * 是否需要渲染 badge 行。
 * 注意 `liked`/`collected` 是 `boolean | null | undefined`——未登录时为 null。
 * 必须用 `=== true` 严格判断，否则 null 会被当成"已赞"（假阳性）。
 */
const hasBadges = computed(
  () =>
    props.post.isTop === 1 ||
    props.post.isFeatured === 1 ||
    props.post.type === 1 ||
    props.post.liked === true ||
    props.post.collected === true,
)
</script>

<template>
  <article class="post-card" :class="{ pinned: post.isTop === 1 }">
    <!-- 左侧计数条（桌面端） -->
    <div class="figures">
      <div class="figure" :class="{ highlight: post.likeCount > 0 }">
        <span class="figure-num">{{ formatCount(post.likeCount) }}</span>
        <span class="figure-label">赞</span>
      </div>
      <div class="figure" :class="{ highlight: post.commentCount > 0 }">
        <span class="figure-num">{{ formatCount(post.commentCount) }}</span>
        <span class="figure-label">评论</span>
      </div>
      <div class="figure">
        <span class="figure-num">{{ formatCount(post.viewCount) }}</span>
        <span class="figure-label">浏览</span>
      </div>
    </div>

    <!-- 主体 -->
    <div class="body">
      <!-- 标记：版主标记在前，我个人互动状态在后 -->
      <div v-if="hasBadges" class="badges">
        <span v-if="post.isTop === 1" class="badge badge-pin">置顶</span>
        <span v-if="post.isFeatured === 1" class="badge badge-feature">精华</span>
        <span v-if="post.type === 1" class="badge badge-question">提问</span>
        <!-- 只在我点过时出现，避免未登录/未互动时制造噪音 -->
        <span v-if="post.liked === true" class="badge badge-liked" title="我点过赞">
          <svg viewBox="0 0 24 24" width="11" height="11" aria-hidden="true">
            <path
              d="M12 21s-7.5-4.7-9.6-9A5.4 5.4 0 0 1 12 6.5 5.4 5.4 0 0 1 21.6 12c-2.1 4.3-9.6 9-9.6 9Z"
              fill="currentColor"
            />
          </svg>
          已赞
        </span>
        <span v-if="post.collected === true" class="badge badge-collected" title="我收藏过">
          <svg viewBox="0 0 24 24" width="11" height="11" aria-hidden="true">
            <path
              d="M6 3h12a1 1 0 0 1 1 1v17l-7-4-7 4V4a1 1 0 0 1 1-1Z"
              fill="currentColor"
            />
          </svg>
          已收藏
        </span>
      </div>

      <h3 class="title">
        <RouterLink :to="detailLink" class="title-link">
          <HighlightText :text="post.title" :keyword="keyword" />
        </RouterLink>
      </h3>

      <p v-if="displayText" class="summary line-clamp-2">
        <HighlightText :text="displayText" :keyword="keyword" />
      </p>

      <div class="footer">
        <!-- 标签 -->
        <div v-if="post.tags?.length" class="tags">
          <span v-for="tag in post.tags" :key="tag.id" class="tag">#{{ tag.name }}</span>
        </div>

        <!-- 元信息 -->
        <div class="meta">
          <RouterLink :to="authorLink" class="meta-author">
            <UserAvatar
              :src="post.authorAvatar"
              :name="post.authorNickname"
              :seed="post.authorId"
              :size="20"
            />
            <span class="meta-name">{{ post.authorNickname || '匿名' }}</span>
          </RouterLink>

          <span class="meta-divider">·</span>

          <RouterLink v-if="boardLink" :to="boardLink" class="meta-board">
            {{ post.boardName }}
          </RouterLink>
          <span v-else class="meta-board">{{ post.boardName }}</span>

          <span class="meta-divider">·</span>
          <time class="meta-time" :datetime="post.createTime">
            {{ formatRelativeTime(post.createTime) }}
          </time>
        </div>
      </div>
    </div>
  </article>
</template>

<style scoped>
.post-card {
  display: flex;
  gap: var(--space-4);
  padding: var(--space-4) var(--space-5);
  background: var(--color-card);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
  /* 过渡逐属性声明而不是 `all`：all 会把布局属性也带上，
     容易被后续改动意外触发重排。这里只有视觉属性参与动画。 */
  transition:
    border-color var(--transition-base),
    background var(--transition-base),
    box-shadow var(--transition-base),
    transform var(--transition-fast);
}

/* hover 反馈由三件事叠加：
   边框提亮 + 背景上一层 + 阴影浮起。
   上一版只改了 border-color，在暗色背景上几乎看不出来 ——
   用户无法判断"鼠标是否在这个卡片上"。 */
.post-card:hover {
  border-color: var(--color-border);
  background: var(--color-elevated);
  box-shadow: var(--shadow-md);
  /* 只用 transform 做位移，不碰 margin/height，因此不会引起列表重排。
     幅度压到 1px：列表里连续的卡片若浮得太多会显得"晃"。 */
  transform: translateY(-1px);
}

/* 置顶帖左侧加一道高亮，不改变整体尺寸（避免布局跳动） */
.post-card.pinned {
  border-left: 3px solid var(--color-accent);
  padding-left: calc(var(--space-5) - 2px);
}

.post-card.pinned:hover {
  box-shadow: var(--shadow-glow-sm);
}

/* ---------- 左侧计数 ---------- */
.figures {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  min-width: 52px;
  text-align: center;
  flex-shrink: 0;
}

.figure {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.figure-num {
  font-size: var(--text-md);
  font-weight: 600;
  color: var(--color-muted-foreground);
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}

.figure-label {
  font-size: 10px;
  color: var(--color-subtle-foreground);
  letter-spacing: 0.04em;
}

.figure.highlight .figure-num {
  color: var(--color-accent);
}

/* ---------- 主体 ---------- */
.body {
  flex: 1;
  min-width: 0; /* 关键：让内部的 text-overflow 生效 */
}

.badges {
  display: flex;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
}

.badge {
  padding: 1px var(--space-2);
  font-size: 11px;
  font-weight: 500;
  line-height: 1.6;
  border-radius: var(--radius-sm);
}

.badge-pin {
  color: #fff;
  background: var(--color-accent-solid);
}

.badge-feature {
  color: #fde68a;
  background: rgba(245, 158, 11, 0.18);
  border: 1px solid rgba(245, 158, 11, 0.4);
}

.badge-question {
  color: #a5f3fc;
  background: rgba(8, 145, 178, 0.18);
  border: 1px solid rgba(8, 145, 178, 0.4);
}

/* 个人互动状态：比版主标记弱一级（描边而非实底），避免抢注意力 */
.badge-liked,
.badge-collected {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: var(--color-accent);
  background: transparent;
  border: 1px solid var(--color-accent);
}

.badge-liked svg,
.badge-collected svg {
  flex-shrink: 0;
}

.title {
  margin: 0 0 var(--space-2);
  font-size: var(--text-md);
  font-weight: 600;
  line-height: var(--leading-tight);
}

.title-link {
  color: var(--color-foreground);
  transition: color var(--transition-fast);
}

.title-link:hover {
  color: var(--color-accent);
}

.summary {
  margin: 0 0 var(--space-3);
  font-size: var(--text-sm);
  line-height: var(--leading-normal);
  color: var(--color-muted-foreground);
}

/* ---------- 底部 ---------- */
.footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.tags {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.tag {
  padding: 1px var(--space-2);
  font-size: 11px;
  color: var(--color-muted-foreground);
  background: var(--color-muted);
  border-radius: var(--radius-sm);
  transition: color var(--transition-fast);
}

.tag:hover {
  color: var(--color-accent);
}

.meta {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
  margin-left: auto;
}

.meta-author {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--color-muted-foreground);
}

.meta-author:hover {
  color: var(--color-accent);
}

.meta-board {
  color: var(--color-muted-foreground);
}

a.meta-board:hover {
  color: var(--color-accent);
}

.meta-divider {
  color: var(--color-border);
}

.meta-time {
  /* 时间可能变化，用等宽数字避免宽度跳动 */
  font-variant-numeric: tabular-nums;
}

/* ---------- 响应式 ---------- */
@media (max-width: 640px) {
  .post-card {
    padding: var(--space-3) var(--space-4);
    gap: var(--space-3);
  }

  .post-card.pinned {
    padding-left: calc(var(--space-4) - 2px);
  }

  /* 手机端计数条移到标题下方会侵占用空间，直接隐藏 —— 计数在详情页仍可见 */
  .figures {
    display: none;
  }

  .meta {
    margin-left: 0;
    width: 100%;
  }
}
</style>
