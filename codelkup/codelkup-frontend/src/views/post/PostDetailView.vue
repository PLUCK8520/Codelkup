<script setup lang="ts">
/**
 * 帖子详情页。
 *
 * 渲染链路（安全关键）：
 *   后端存的 Markdown 原文 → MarkdownViewer → marked 渲染
 *   → DOMPurify 白名单消毒 → v-html
 * 后端不做任何 HTML 处理（设计修订 D4：content_html 不落库），
 * 所以**消毒是前端的唯一防线**，一切都要走 MarkdownViewer，禁止直接 v-html。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { postApi } from '@/api/post'
import { postActionApi } from '@/api/interaction'
import { useAuthStore } from '@/stores/auth'
import UserAvatar from '@/components/UserAvatar.vue'
import MarkdownViewer from '@/components/MarkdownViewer.vue'
import PostTldr from '@/components/PostTldr.vue'
import EmptyState from '@/components/EmptyState.vue'
import CommentSection from '@/components/CommentSection.vue'
import ReportDialog from '@/components/ReportDialog.vue'
import { formatDateTime, formatRelativeTime, formatCount } from '@/utils/format'
import type { PostDetailVO } from '@/types/api'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const post = ref<PostDetailVO | null>(null)
const loading = ref(true)
const error = ref('')
const notFound = ref(false)
const deleting = ref(false)

/* ---------- 互动状态（Sprint 3） ----------
 * 本地副本而非直接绑 post.value.liked：乐观更新时只改副本，
 * 失败回滚也只回滚副本，不用去动整个 post 对象。
 */
const liked = ref(false)
const collected = ref(false)
const likeCount = ref(0)
const collectCount = ref(0)
const liking = ref(false)
const collecting = ref(false)

const postId = computed(() => Number(route.params.id))

async function load() {
  loading.value = true
  error.value = ''
  notFound.value = false
  post.value = null
  try {
    const data = await postApi.detail(postId.value)
    post.value = data
    // 同步互动状态到本地副本
    liked.value = data.liked === true
    collected.value = data.collected === true
    likeCount.value = data.likeCount
    collectCount.value = data.collectCount
  } catch (e) {
    const code = (e as Error & { code?: number }).code
    // 40402 = 帖子不存在（ErrorCode.POST_NOT_FOUND）；也可能是被作者删除、
    // 被管理员删除（含举报删帖）或仍在审核中。
    // ⚠️ 这里原本判的是 40401（USER_NOT_FOUND）—— 条件永远不成立，于是"帖子不存在"
    //    那个 EmptyState 成了死代码，删帖后的旧链接只显示一句"加载失败"。
    if (code === 40402) {
      notFound.value = true
    } else {
      error.value = e instanceof Error ? e.message : '加载失败'
    }
  } finally {
    loading.value = false
  }
}

watch(postId, load, { immediate: true })

/** 能否编辑：后端在 detail 里算了 canEdit（作者本人或管理员） */
const canEdit = computed(() => post.value?.canEdit === true)
const canModerate = computed(() => auth.canModerate)

/* ---------- 举报（V0.5 / M5） ----------
 * 游客与作者本人都不显示入口：未登录点了只会被 40101 拦下，
 * 自己的帖子下面该出现的是"编辑 / 删除"而不是"举报"。
 * （后端同样会以 40001 拒绝自举报 —— 前端隐藏是体验，后端拦截才是边界。）
 */
const reportVisible = ref(false)
const canReport = computed(
  () => auth.isLoggedIn && post.value !== null && post.value.authorId !== auth.user?.id,
)

/** 点赞：乐观更新 + 失败回滚。与评论点赞同一套模式 */
async function onToggleLike() {
  if (!auth.isLoggedIn) {
    ElMessage.info('登录后即可点赞')
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  if (liking.value || !post.value) return

  liking.value = true
  const prevLiked = liked.value
  const prevCount = likeCount.value

  liked.value = !prevLiked
  likeCount.value = prevCount + (prevLiked ? -1 : 1)

  try {
    const res = liked.value
      ? await postActionApi.like(post.value.id)
      : await postActionApi.unlike(post.value.id)
    liked.value = res.active
    likeCount.value = res.count
  } catch {
    liked.value = prevLiked
    likeCount.value = prevCount
  } finally {
    liking.value = false
  }
}

/** 收藏：同点赞 */
async function onToggleCollect() {
  if (!auth.isLoggedIn) {
    ElMessage.info('登录后即可收藏')
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  if (collecting.value || !post.value) return

  collecting.value = true
  const prevCollected = collected.value
  const prevCount = collectCount.value

  collected.value = !prevCollected
  collectCount.value = prevCount + (prevCollected ? -1 : 1)

  try {
    const res = collected.value
      ? await postActionApi.collect(post.value.id)
      : await postActionApi.uncollect(post.value.id)
    collected.value = res.active
    collectCount.value = res.count
  } catch {
    collected.value = prevCollected
    collectCount.value = prevCount
  } finally {
    collecting.value = false
  }
}

/** 评论数变化：由 CommentSection 上报，同步显示 */
function onCommentCountChange(delta: number) {
  if (post.value) {
    post.value.commentCount = Math.max(0, post.value.commentCount + delta)
  }
}

async function onDelete() {
  if (!post.value) return
  try {
    await ElMessageBox.confirm(
      '删除后帖子将不再可见，且无法恢复。确定要删除吗？',
      '删除帖子',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  deleting.value = true
  try {
    await postApi.remove(post.value.id)
    ElMessage.success('帖子已删除')
    router.replace({ name: 'home' })
  } catch {
    // 拦截器已提示
  } finally {
    deleting.value = false
  }
}

async function onFeature(action: 'top' | 'featured') {
  if (!post.value) return
  const current = post.value
  const next =
    action === 'top' ? (current.isTop === 1 ? false : true) : current.isFeatured === 1 ? false : true

  try {
    await postApi.feature(current.id, action === 'top' ? { top: next } : { featured: next })
    ElMessage.success(next ? '设置成功' : '已取消')
    await load()
  } catch {
    // 拦截器已提示
  }
}
</script>

<template>
  <div class="container detail-layout">
    <!-- ==================== 主体 ==================== -->
    <div class="main-col">
      <!-- 加载中 -->
      <div v-if="loading" class="detail-skeleton" aria-busy="true">
        <span class="sk-bar sk-h1" />
        <span class="sk-bar sk-meta" />
        <span class="sk-bar sk-p" />
        <span class="sk-bar sk-p" />
        <span class="sk-bar sk-p sk-short" />
      </div>

      <!-- 不存在 -->
      <EmptyState
        v-else-if="notFound"
        icon="post"
        title="帖子不存在或已被删除"
        description="它可能被作者删除了，或者链接有误。去看看其他讨论吧。"
        action-text="回到首页"
        @action="router.push({ name: 'home' })"
      />

      <!-- 加载出错 -->
      <el-alert
        v-else-if="error"
        type="error"
        :title="error"
        :closable="false"
        show-icon
      >
        <el-button text type="primary" @click="load">重试</el-button>
      </el-alert>

      <!-- 正文 -->
      <template v-else-if="post">
        <nav class="crumbs" aria-label="面包屑">
          <RouterLink :to="{ name: 'home' }">首页</RouterLink>
          <span class="crumb-sep">/</span>
          <RouterLink
            v-if="post.boardSlug"
            :to="{ name: 'board', params: { slug: post.boardSlug } }"
          >
            {{ post.boardName }}
          </RouterLink>
          <span v-else class="crumb-current">{{ post.boardName }}</span>
        </nav>

        <article class="post">
          <!-- 标记 -->
          <div v-if="post.isTop === 1 || post.isFeatured === 1 || post.type === 1" class="badges">
            <span v-if="post.isTop === 1" class="badge badge-pin">置顶</span>
            <span v-if="post.isFeatured === 1" class="badge badge-feature">精华</span>
            <span v-if="post.type === 1" class="badge badge-question">提问</span>
            <!-- 提问的解决状态：靠 acceptedCommentId 判断（不是另存一个布尔字段，
                 少一个可能与"采纳"动作不同步的状态） -->
            <span v-if="post.type === 1 && post.acceptedCommentId" class="badge badge-solved">
              <svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor"
                   stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M20 6 9 17l-5-5" />
              </svg>
              已解决
            </span>
            <span v-else-if="post.type === 1" class="badge badge-unsolved">待解决</span>
          </div>

          <h1 class="post-title">{{ post.title }}</h1>

          <!-- 作者与时间 -->
          <div class="post-meta">
            <!-- v5：工单编号 —— 元信息行最前面的档案号（.idx 为全球统一令牌样式） -->
            <span class="idx">#{{ post.id }}</span>
            <span class="meta-sep" aria-hidden="true">·</span>
            <RouterLink
              :to="{ name: 'user-profile', params: { id: post.authorId } }"
              class="meta-author"
            >
              <UserAvatar
                :src="post.authorAvatar"
                :name="post.authorNickname"
                :seed="post.authorId"
                :size="36"
              />
              <span class="author-info">
                <span class="author-name">{{ post.authorNickname || '匿名' }}</span>
                <!--
                  ⚠️ 这里原本显示「声望 {{ post.author?.reputation ?? 0 }}」，是一个真实 bug：
                     `post.author` 的类型是 UserBriefVO，而它按设计**只有 id/nickname/avatar**
                     （见该类的注释：列表页每页都要展示作者，不需要 bio/techTags/reputation）。
                     所以 `author.reputation` 在类型上就不存在，运行时恒为 undefined，
                     这个"声望"一直显示 0 —— 而 `?? 0` 兜底恰好把问题掩盖了，看起来"正常"。
                     这类错误只有在完整的 `vue-tsc -b` 下才会暴露（vite build 不做类型检查）。
                     声望的完整数据在个人主页（UserVO）上，那里展示才是对的。
                -->
              </span>
            </RouterLink>

            <div class="meta-time">
              <time :datetime="post.createTime" :title="formatDateTime(post.createTime)">
                {{ formatRelativeTime(post.createTime) }}发布
              </time>
              <span v-if="post.updateTime && post.updateTime !== post.createTime">
                · 编辑于 {{ formatRelativeTime(post.updateTime) }}
              </span>
            </div>

            <!-- 作者/管理员操作 -->
            <div v-if="canEdit || canModerate" class="meta-actions">
              <el-button v-if="canEdit" text size="small" @click="router.push({ name: 'post-edit', params: { id: post.id } })">
                编辑
              </el-button>
              <el-button v-if="canModerate" text size="small" @click="onFeature('top')">
                {{ post.isTop === 1 ? '取消置顶' : '置顶' }}
              </el-button>
              <el-button v-if="canModerate" text size="small" @click="onFeature('featured')">
                {{ post.isFeatured === 1 ? '取消精华' : '加精' }}
              </el-button>
              <el-button
                v-if="canEdit"
                text
                size="small"
                type="danger"
                :loading="deleting"
                @click="onDelete"
              >
                删除
              </el-button>
            </div>
          </div>

          <!-- 标签 -->
          <div v-if="post.tags?.length" class="post-tags">
            <button
              v-for="tag in post.tags"
              :key="tag.id"
              type="button"
              class="tag"
              @click="router.push({ name: 'home', query: { tag: tag.id } })"
            >
              #{{ tag.name }}
            </button>
          </div>

          <!-- ★ AI 摘要（M3-8）：放在正文之前 —— 读者先看要点，再决定要不要读全文 -->
          <PostTldr :post-id="postId" />

          <!-- ★ Markdown 正文：必须走 MarkdownViewer（内含 DOMPurify 消毒） -->
          <div class="post-content">
            <MarkdownViewer :content="post.content" />
          </div>

          <!-- 计数 -->
          <footer class="post-footer">
            <div class="counters">
              <span class="counter">
                <strong>{{ formatCount(post.viewCount) }}</strong> 浏览
              </span>
              <span class="counter">
                <strong>{{ formatCount(likeCount) }}</strong> 赞
              </span>
              <span class="counter">
                <strong>{{ formatCount(post.commentCount) }}</strong> 评论
              </span>
              <span class="counter">
                <strong>{{ formatCount(collectCount) }}</strong> 收藏
              </span>
            </div>

            <!-- 互动按钮（Sprint 3）：
                 点过赞/收藏时用强调色 + aria-pressed 表达"已激活"状态。
                 未登录不禁用按钮 —— 而是引导去登录，比灰掉更友好。 -->
            <div class="interactions">
              <!-- 自赞拦截（L-1）：自己的帖子不显示点赞按钮（后端同样硬拦 40001）；
                   收藏保留 —— 收藏是书签语义，允许收藏自己的帖子 -->
              <button
                v-if="post.authorId !== auth.user?.id"
                type="button"
                class="act-btn"
                :class="{ 'is-active': liked }"
                :aria-pressed="liked"
                :disabled="liking"
                @click="onToggleLike"
              >
                <span aria-hidden="true">▲</span>
                {{ liked ? '已赞' : '点赞' }}
                <span class="act-count">{{ formatCount(likeCount) }}</span>
              </button>

              <button
                type="button"
                class="act-btn"
                :class="{ 'is-active': collected }"
                :aria-pressed="collected"
                :disabled="collecting"
                @click="onToggleCollect"
              >
                <span aria-hidden="true">★</span>
                {{ collected ? '已收藏' : '收藏' }}
                <span class="act-count">{{ formatCount(collectCount) }}</span>
              </button>

              <!-- 举报（V0.5）：游客与作者本人不显示，见 script 里的 canReport 说明 -->
              <button
                v-if="canReport"
                type="button"
                class="act-btn"
                title="举报这篇帖子"
                @click="reportVisible = true"
              >
                举报
              </button>
            </div>
          </footer>
        </article>

        <!-- ★ 评论区（Sprint 3） -->
        <CommentSection
          :post-id="post.id"
          :post-author-id="post.authorId"
          :is-question="post.type === 1"
          @count-change="onCommentCountChange"
        />
      </template>
    </div>

    <!-- ==================== 侧栏 ==================== -->
    <aside class="side-col">
      <section v-if="post" class="side-card">
        <h2 class="side-title">关于作者</h2>
        <RouterLink
          :to="{ name: 'user-profile', params: { id: post.authorId } }"
          class="author-box"
        >
          <UserAvatar
            :src="post.authorAvatar"
            :name="post.authorNickname"
            :seed="post.authorId"
            :size="44"
          />
          <span class="author-box-name">{{ post.authorNickname || '匿名' }}</span>
        </RouterLink>

        <!-- 高级筛选的入口：跳到搜索页并预置「只看这位作者」。
             后端搜索接口支持 authorId，但没有"按昵称找用户"的接口，
             所以作者筛选只能从这种真实上下文进入（不是一个悬空的手填参数）。 -->
        <RouterLink
          :to="{ name: 'search', query: { author: post.authorId } }"
          class="author-search"
        >
          搜索 TA 的帖子
        </RouterLink>
      </section>

      <section v-if="post" class="side-card">
        <h2 class="side-title">帖子信息</h2>
        <dl class="info-list">
          <div class="info-row">
            <dt>发布于</dt>
            <dd>{{ formatDateTime(post.createTime) }}</dd>
          </div>
          <div class="info-row">
            <dt>最后活跃</dt>
            <dd>{{ formatRelativeTime(post.lastActive) }}</dd>
          </div>
          <div class="info-row">
            <dt>板块</dt>
            <dd>{{ post.boardName }}</dd>
          </div>
        </dl>
      </section>
    </aside>

    <!-- 举报弹窗（帖子级）：目标用当前帖子 ID -->
    <ReportDialog
      v-model:visible="reportVisible"
      :target-type="1"
      :target-id="postId"
      target-label="这篇帖子"
    />
  </div>
</template>

<style scoped>
.detail-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: var(--space-6);
  align-items: start;
}

.main-col {
  min-width: 0;
}

/* ---------- 面包屑 ---------- */
.crumbs {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
  font-size: var(--text-sm);
  color: var(--color-subtle-foreground);
}

.crumbs a {
  color: var(--color-muted-foreground);
}

.crumbs a:hover {
  color: var(--color-accent);
}

.crumb-sep {
  color: var(--color-border);
}

.crumb-current {
  color: var(--color-muted-foreground);
}

/* ---------- 帖子 ---------- */
.post {
  padding: var(--space-6);
  background: var(--color-card);
  /* 实体白卡只用 1px 发丝边勾轮廓（内部分隔线交给 border-subtle） */
  border: 1px solid var(--color-border-hairline);
  border-radius: var(--radius-lg);
}

.badges {
  display: flex;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.badge {
  padding: 1px var(--space-2);
  font-size: 11px;
  font-weight: 500;
  line-height: 1.7;
  border-radius: var(--radius-sm);
}

.badge-pin {
  color: var(--color-on-accent);
  background: var(--color-accent-solid);
}

.badge-feature {
  color: var(--color-on-warning-soft);
  background: var(--color-warning-soft);
  border: 1px solid var(--color-warning-border);
}

.badge-question {
  color: var(--color-muted-foreground);
  background: var(--color-neutral-soft);
  border: 1px solid var(--color-border-hairline);
}

/* 提问的解决状态：绿=有答案了，琥珀=还等人答。
   两个徽标都用"柔和底 + 同色描边"，与置顶/精华的视觉重量保持一致，
   不靠高饱和底色抢标题的注意力。 */
.badge-solved {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: var(--color-success);
  background: var(--color-success-soft);
  border: 1px solid var(--color-success-border);
}

.badge-unsolved {
  color: var(--color-warning);
  background: var(--color-warning-soft);
  border: 1px solid var(--color-warning-border);
}

.post-title {
  font-size: var(--text-2xl);
  line-height: var(--leading-tight);
  letter-spacing: var(--tracking-heading);
}

/* ---------- 元信息 ---------- */
.post-meta {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  flex-wrap: wrap;
  margin-top: var(--space-4);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--color-border-subtle);
}

/* 工单编号后的分隔点（与面包屑的 .crumb-sep 同色） */
.meta-sep {
  color: var(--color-border);
}

.meta-author {
  display: inline-flex;
  align-items: center;
  gap: var(--space-3);
}

.author-info {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.author-name {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-foreground);
}

.meta-time {
  display: flex;
  gap: var(--space-2);
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
  flex-wrap: wrap;
}

.meta-actions {
  display: flex;
  gap: var(--space-1);
  margin-left: auto;
}

/* ---------- 标签 ---------- */
.post-tags {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
  margin-top: var(--space-4);
}

.tag {
  padding: 2px var(--space-3);
  font-size: var(--text-xs);
  color: var(--color-muted-foreground);
  background: var(--color-muted);
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  transition: all var(--transition-fast);
}

.tag:hover {
  color: var(--color-accent);
  border-color: var(--color-border);
}

/* ---------- 正文（v5 阅读区）----------
   阅读区是"全产品最舒服的区域"：定宽 720px（约 40 汉字/行）、16px / 1.8、
   标题与段落留白充足 —— 与列表页的"高密度"刻意反差。
   MarkdownViewer 的排版是组件级 scoped，这里用 :deep 在本文件内再收一道口
   （本轮只允许改本文件，不动子组件）。加 `.post` 前缀是为了在层叠里稳压子组件规则。 */
.post-content {
  max-width: var(--container-reading);
  /* ★ v6.1：在卡片内**居中** —— v5 靠左时右侧留下约 150px 空白，
     1440 桌面下读起来像"排版没做完"；居中后两侧均衡。 */
  margin: var(--space-5) auto 0;
}

/* ⚠️ 令牌没有 1.8 这一档（最接近的 --leading-relaxed = 1.75）。
   阅读区按 v5 规格取 1.8 —— 本次唯一直接写的排版字面量（非色值）。 */
.post .post-content :deep(.markdown-body) {
  line-height: 1.8;
}

/* 标题上下留白加大：h2 上间距按规格示例取 --space-8 */
.post .post-content :deep(.markdown-body h2) {
  margin-top: var(--space-8);
  margin-bottom: var(--space-4);
}

.post .post-content :deep(.markdown-body h3) {
  margin-top: var(--space-6);
  margin-bottom: var(--space-3);
}

/* 段间距充分：16 → 20px */
.post .post-content :deep(.markdown-body p) {
  margin: var(--space-5) 0;
}

/* 引用块：左侧 2px 主色线 + 直角、无底色 —— 蓝图式引用，不要彩色底 */
.post .post-content :deep(.markdown-body blockquote) {
  margin: var(--space-5) 0;
  padding: var(--space-3) var(--space-4);
  background: none;
  border-left: 2px solid var(--color-accent);
  border-radius: 0;
  color: var(--color-muted-foreground);
}

/* 独立代码块：--code-bg + --radius-md + 1px 发丝边。
   ⚠️ 只选 `.markdown-body > pre`（未被增强包裹的裸 pre）——
   被 useCodeBlocks 包过的 pre 是 .code-block 的子节点，边框交给外壳，避免双重边框 */
.post .post-content :deep(.markdown-body > pre),
.post .post-content :deep(.markdown-body .code-block) {
  background: var(--code-bg);
  border: 1px solid var(--color-border-hairline);
  border-radius: var(--radius-md);
}

/* 行内 code：--code-bg-soft（用 :not(pre) 排除块内代码，不需要 Level 4 的复杂 :not） */
.post .post-content :deep(.markdown-body :not(pre) > code) {
  background: var(--code-bg-soft);
  border: 1px solid var(--color-border-hairline);
}

/* ---------- 底部计数 ---------- */
.post-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
  margin-top: var(--space-8);
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-border-subtle);
}

.counters {
  display: flex;
  gap: var(--space-5);
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
}

.counter strong {
  color: var(--color-muted-foreground);
  font-variant-numeric: tabular-nums;
}

.interactions {
  display: flex;
  gap: var(--space-2);
}

/* ---------- 互动按钮（Sprint 3） ---------- */
.act-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--color-muted-foreground);
  background: var(--color-muted);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  transition: all var(--transition-fast);
}

.act-btn:hover:not(:disabled) {
  color: var(--color-foreground);
  border-color: var(--color-accent-border);
  /* v6 hover：纸片轻浮（蓝调柔影），不做硬印章/彩色光晕 */
  transform: translateY(-1px);
  box-shadow: var(--shadow-lift);
}

.act-btn:disabled {
  opacity: 0.6;
  cursor: progress;
}

/* 已激活（已赞 / 已藏）：低饱和底 + 同色描边 + 信号色文字，
   主色只表达"我点过"这一状态，不做大面积实心色块 */
.act-btn.is-active {
  color: var(--color-accent);
  background: var(--color-accent-soft);
  border-color: var(--color-accent-border);
}

.act-count {
  font-variant-numeric: tabular-nums;
  opacity: 0.8;
}

/* ---------- 侧栏 ---------- */
.side-col {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  position: sticky;
  top: 74px;
}

.side-card {
  padding: var(--space-4);
  background: var(--color-card);
  /* 白纸卡 + 1px 发丝边 */
  border: 1px solid var(--color-border-hairline);
  border-radius: var(--radius-lg);
  transition: background var(--transition-fast), box-shadow var(--transition-fast),
    transform var(--transition-fast);
}

/* v6 hover：纸片轻浮（蓝调柔影） */
.side-card:hover {
  background: var(--color-card-hover);
  transform: translateY(-1px);
  box-shadow: var(--shadow-lift);
}

.side-title {
  margin-bottom: var(--space-3);
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-muted-foreground);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.author-box {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.author-box-name {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-foreground);
}

.author-search {
  display: block;
  margin-top: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-border-subtle);
  font-size: var(--text-xs);
}

.info-list {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.info-row {
  display: flex;
  justify-content: space-between;
  gap: var(--space-3);
  font-size: var(--text-xs);
}

.info-row dt {
  color: var(--color-subtle-foreground);
}

.info-row dd {
  margin: 0;
  color: var(--color-muted-foreground);
  text-align: right;
  font-variant-numeric: tabular-nums;
}

/* ---------- 评论区（子组件在本文件内深度重制）----------
   评论区容器与单条评论都是"卡片 + 1px 发丝边 + --radius-lg"。
   单条评论用 --color-muted 降一档底色：浅色下灰蓝底比描边更能区分相邻评论。 */
.main-col :deep(.comments) {
  border: 1px solid var(--color-border-hairline);
  border-radius: var(--radius-lg);
}

/* 单条评论：嵌套实体卡，用 flex gap 取代原来的 border-bottom 分隔 */
.main-col :deep(.comments-list) {
  gap: var(--space-3);
}

.main-col :deep(.comment) {
  padding: var(--space-4);
  background: var(--color-muted);
  border: 1px solid var(--color-border-hairline);
  border-radius: var(--radius-lg);
}

/* 评论输入框底提到 --color-muted（:not(:focus) 是为了不盖掉子组件的焦点主色边） */
.main-col :deep(.composer-input:not(:focus)) {
  background: var(--color-muted);
  border: 1px solid var(--color-border-hairline);
}

/* 采纳态：success 实色左边条（与 CommentItem 内部规则同值，避免两处不一致） */
.main-col :deep(.is-accepted) {
  border-left-color: var(--color-success);
}

/* ---------- 骨架 ---------- */
.detail-skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: var(--space-6);
  background: var(--color-card);
  border: 1px solid var(--color-border-hairline);
  border-radius: var(--radius-lg);
}

.detail-skeleton .sk-bar {
  display: block;
  border-radius: var(--radius-sm);
  background: linear-gradient(
    90deg,
    var(--color-muted) 25%,
    var(--color-elevated) 37%,
    var(--color-muted) 63%
  );
  background-size: 400% 100%;
  animation: sk-shimmer 1.4s ease infinite;
}

.sk-h1 {
  height: 26px;
  width: 70%;
}

.sk-meta {
  height: 14px;
  width: 200px;
}

.sk-p {
  height: 15px;
  width: 100%;
}

.sk-short {
  width: 55%;
}

@keyframes sk-shimmer {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

/* ---------- 响应式 ---------- */
@media (max-width: 1024px) {
  .detail-layout {
    grid-template-columns: 1fr;
  }

  .side-col {
    position: static;
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  }
}

@media (max-width: 640px) {
  .post {
    padding: var(--space-4);
  }

  .post-title {
    font-size: var(--text-xl);
  }

  .meta-actions {
    margin-left: 0;
    width: 100%;
  }

  .post-footer {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
