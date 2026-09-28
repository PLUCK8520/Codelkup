<script setup lang="ts">
/**
 * 帖子列表项（紧凑行）。
 *
 * ★ v6：从"大卡片"改为**紧凑行式**（掘金 / V2EX 同款形态）——
 *   信息层级 = 标记行 → 标题 → 单行摘要 → 底行（标签 / 作者元信息 / 互动计数）。
 *   高度比 v5 的卡片矮约 40%，一屏能多扫 3~4 条：列表页的第一价值是
 *   "扫得快"，而不是"每条都长得像作品卡"。
 *
 * 可访问性：整卡可点，但用覆盖式链接而非包一层 div 点击事件 ——
 *          后者键盘用户无法触发，也无法用浏览器"在新标签页打开"。
 *
 * ⚠️ 本组件是全站 8 个列表页（首页/板块/标签/问答/搜索/关注/收藏/个人主页）
 *    共用的——改这里等于改全站列表，任何布局调整都要考虑上述全部页面。
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

/** 标签最多展示 3 个：行式列表的底行是"元信息"，标签一多会把它挤成第二行 */
const visibleTags = computed(() => (props.post.tags ?? []).slice(0, 3))
</script>

<template>
  <article class="post-card" :class="{ pinned: post.isTop === 1 }">
    <!-- 标记行：版主标记在前，我个人互动状态在后 -->
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

    <p v-if="displayText" class="summary">
      <HighlightText :text="displayText" :keyword="keyword" />
    </p>

    <!-- 底行：左＝标签与作者元信息，右＝互动计数 -->
    <div class="footer">
      <div v-if="visibleTags.length" class="tags">
        <span v-for="tag in visibleTags" :key="tag.id" class="tag">#{{ tag.name }}</span>
      </div>

      <div class="meta">
        <!-- 工单编号：等宽 + 表格数字。给每条帖子一个稳定"档案号"，翻页对号用 -->
        <span class="idx">#{{ post.id }}</span>
        <span class="meta-divider">·</span>

        <RouterLink :to="authorLink" class="meta-author">
          <UserAvatar
            :src="post.authorAvatar"
            :name="post.authorNickname"
            :seed="post.authorId"
            :size="18"
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

      <!-- 互动计数：图标 + 数字横排（v6 从左侧竖排计数条收进底行右侧）。
           有互动时数字变主色 —— 保留 v5 的"信号"语义，不做成第二个装饰色。 -->
      <div class="counts">
        <span class="count" :class="{ active: post.likeCount > 0 }">
          <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true">
            <path
              d="M12 21s-7.5-4.7-9.6-9A5.4 5.4 0 0 1 12 6.5 5.4 5.4 0 0 1 21.6 12c-2.1 4.3-9.6 9-9.6 9Z"
              fill="currentColor"
            />
          </svg>
          <span class="sr-only">赞</span>
          <span class="count-num">{{ formatCount(post.likeCount) }}</span>
        </span>
        <span class="count" :class="{ active: post.commentCount > 0 }">
          <svg
            viewBox="0 0 24 24"
            width="14"
            height="14"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
          </svg>
          <span class="sr-only">评论</span>
          <span class="count-num">{{ formatCount(post.commentCount) }}</span>
        </span>
        <span class="count">
          <svg
            viewBox="0 0 24 24"
            width="14"
            height="14"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
            <circle cx="12" cy="12" r="3" />
          </svg>
          <span class="sr-only">浏览</span>
          <span class="count-num">{{ formatCount(post.viewCount) }}</span>
        </span>
      </div>
    </div>
  </article>
</template>

<style scoped>
/* 行式卡片：白纸 + 发丝边，"一行"而不是"一张作品卡"。
   ⚠️ hover 不加位移、不加投影 —— 列表里连续行若都"浮起来"会显得晃；
   反馈交给底色与边框两条通道（比阴影更安静、更适合高密度列表）。 */
.post-card {
  padding: var(--space-3) var(--space-4);
  background: var(--color-card);
  border: 1px solid var(--color-border-hairline);
  border-radius: var(--radius-md);
  transition:
    border-color var(--transition-fast),
    background var(--transition-fast);
}

.post-card:hover {
  border-color: var(--color-border);
  background: var(--color-card-hover);
}

/* 置顶帖左侧加一道高亮，不改变整体尺寸（避免布局跳动） */
.post-card.pinned {
  border-left: 2px solid var(--color-accent);
  padding-left: calc(var(--space-4) - 1px);
}

/* ---------- 标记行 ---------- */
.badges {
  display: flex;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
}

.badge {
  /* 小圆角"规格标签"形态：小圆角 + 字距（蓝图/工单语言，非社交胶囊） */
  padding: 1px var(--space-2);
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.04em;
  line-height: 1.7;
  border-radius: var(--radius-sm);
  border: 1px solid transparent;
}

/* 版主标记：低饱和底 + 同色系文字 + 细边 */
.badge-pin {
  color: var(--color-accent);
  background: var(--color-accent-soft);
  border-color: var(--color-accent-border);
}

.badge-feature {
  color: var(--color-on-warning-soft);
  background: var(--color-warning-soft);
  border-color: var(--color-warning-border);
}

/* 提问是"帖子类型"而不是"值得突出的状态" —— 用中性色 */
.badge-question {
  color: var(--color-muted-foreground);
  background: var(--color-neutral-soft);
  border-color: var(--color-border-hairline);
}

/* 个人互动状态：比版主标记弱一级（无边框），避免抢注意力 */
.badge-liked,
.badge-collected {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: var(--color-accent);
  background: var(--color-accent-soft);
}

.badge-liked svg,
.badge-collected svg {
  flex-shrink: 0;
}

/* ---------- 标题 ---------- */
.title {
  margin: 0;
  /* 16px / 600：行式列表的标题要"抓得住"，但不靠大字号——
     密集列表里字号一大，五行之外全是标题谷 */
  font-size: var(--text-md);
  font-weight: 600;
  letter-spacing: var(--tracking-heading);
  line-height: var(--leading-snug);
}

.title-link {
  color: var(--color-foreground);
  transition: color var(--transition-fast);
}

/* 悬停给"颜色 + 下划线"两个信号（下划线颜色跟主色，避免默认蓝与主题冲突） */
.title-link:hover {
  color: var(--color-accent);
  text-decoration: underline;
  text-underline-offset: 3px;
  text-decoration-thickness: 1px;
  text-decoration-color: var(--color-accent-border);
}

/* ---------- 摘要：单行截断 ---------- */
.summary {
  margin: var(--space-1) 0 0;
  font-size: var(--text-sm);
  line-height: 1.6;
  color: var(--color-muted-foreground);
  /* 单行省略：紧凑行的"第二行"只负责给一眼上下文，不抢标题 */
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---------- 底行 ---------- */
.footer {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-2);
  flex-wrap: wrap;
}

.tags {
  display: flex;
  gap: var(--space-2);
}

.tag {
  padding: 0 var(--space-2);
  font-size: 11px;
  line-height: 1.8;
  letter-spacing: 0.02em;
  color: var(--color-muted-foreground);
  background: transparent;
  border: 1px solid var(--color-border-hairline);
  border-radius: var(--radius-sm);
  transition:
    color var(--transition-fast),
    border-color var(--transition-fast);
}

.tag:hover {
  color: var(--color-accent);
  border-color: var(--color-accent-border);
}

.meta {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
}

.meta-author {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
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
  color: var(--color-border-strong);
}

.meta-time {
  /* 时间可能变化，用等宽数字避免宽度跳动 */
  font-variant-numeric: tabular-nums;
}

/* ---------- 互动计数 ---------- */
.counts {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  margin-left: auto;
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
}

.count {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
}

.count svg {
  opacity: 0.8;
}

/* 有互动就点亮数字 —— 这一行只让"有人读过、顶过、回过"的信号亮出来 */
.count.active {
  color: var(--color-accent);
}

.count-num {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
}

/* 屏幕阅读器专用文本（图标是装饰，语义靠它） */
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

/* ---------- 响应式 ---------- */
@media (max-width: 640px) {
  .post-card {
    padding: var(--space-3);
  }

  .post-card.pinned {
    padding-left: calc(var(--space-3) - 2px);
  }

  /* 手机端先收起计数（详情页仍可见）——横向空间要留给标题与作者 */
  .counts {
    display: none;
  }

  .meta {
    width: 100%;
  }
}
</style>