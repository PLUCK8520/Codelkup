<script setup lang="ts">
/**
 * 单条评论（含其回复列表）。
 *
 * 递归深度固定为 1：本组件渲染一条评论，若它是根评论则再渲染一层回复，
 * 回复<b>不再</b>嵌套 CommentItem —— 直接内联渲染，避免无限递归的可能。
 * 这与后端"只做两级"的决策一致。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { commentApi } from '@/api/interaction'
import { useAuthStore } from '@/stores/auth'
import UserAvatar from '@/components/UserAvatar.vue'
import ReportDialog from '@/components/ReportDialog.vue'
import { formatRelativeTime, formatDateTime, formatCount } from '@/utils/format'
import type { CommentVO } from '@/types/api'

const props = defineProps<{
  comment: CommentVO
  /** 当前帖子的楼主 ID —— 用于标识"楼主"徽章与采纳按钮可见性 */
  postAuthorId: number
  /** 帖子是否为提问帖 —— 决定是否显示采纳按钮 */
  isQuestion: boolean
}>()

const emit = defineEmits<{
  (e: 'reply', comment: CommentVO): void
  (e: 'deleted', commentId: number): void
  (e: 'accepted', commentId: number): void
}>()

const auth = useAuthStore()

/** 正在回复的目标（受控在父组件，这里只转发） */
const replying = ref(false)
const deleting = ref(false)
const liking = ref(false)

/** 本地计数：乐观更新用。初始值来自后端（已合并 Redis 实时值） */
const likeCount = ref(props.comment.likeCount)
const liked = ref(props.comment.liked === true)

/* 2026-09-29 纠错：本地状态必须跟随后端刷新（此前只在初始化时读一次 props）。
   父组件重新拉取评论列表（发新评论后、登录态变化后）时，组件实例被复用、
   两个 ref 却停留在旧值 —— 表现为"点赞数变回去了 / 我明明点过赞却没高亮"。
   监听 props 同步，服务端返回的权威值始终能覆盖到本地乐观值。 */
watch(
  () => props.comment.likeCount,
  (v) => {
    likeCount.value = v
  },
)
watch(
  () => props.comment.liked,
  (v) => {
    liked.value = v === true
  },
)

const isPostAuthor = computed(() => props.comment.authorId === props.postAuthorId)
const isAccepted = computed(() => props.comment.isAccepted === 1)

/**
 * 采纳按钮可见性 —— **必须是楼主本人**。
 *
 * 2026-09-29 修复：原来只判「已登录 + 提问帖 + 未采纳 + 一级评论」，漏了楼主身份。
 * 后果是任何登录用户在别人的提问帖下都能看到每层都挂着的「采纳」按钮，
 * 点下去被后端 `CommentService.accept` 的 `post.authorId == operatorId` 顶回 40302
 * —— 不是越权（后端兜得住），而是在别人帖子里摆了一排点不动的按钮 + 误导性报错。
 * 口径也对齐了同组件里点赞/举报的判定方式。
 */
const canAccept = computed(
  () =>
    auth.isLoggedIn &&
    props.isQuestion &&
    !isAccepted.value &&
    props.comment.parentId === 0 &&
    auth.user?.id === props.postAuthorId,
)

/* ==================== 举报（V0.5 / M5） ====================
   根评论与回复共用一个弹窗实例：举报目标用 reportTargetId 记录，
   点哪一条的"举报"就把哪一条的 ID 填进去。
   可见性：登录 + 不是自己的内容 —— 自己的评论下面显示"举报"没有意义
   （后端也会以 40001 拒绝自举报，前端只是不把这个错误摆到用户面前）。 */
const reportVisible = ref(false)
const reportTargetId = ref<number>()

function openReport(commentId: number) {
  reportTargetId.value = commentId
  reportVisible.value = true
}

/**
 * 点赞：乐观更新 + 失败回滚。
 *
 * 先改本地状态让按钮立刻响应（不等网络），失败再回滚并提示。
 * 这对"连点"体验很重要 —— 等 200ms 才变色会显得很卡。
 */
async function onToggleLike() {
  if (liking.value) return
  if (!auth.isLoggedIn) {
    ElMessage.info('登录后即可点赞')
    return
  }

  liking.value = true
  const prevLiked = liked.value
  const prevCount = likeCount.value

  // 乐观更新
  liked.value = !prevLiked
  likeCount.value = prevCount + (prevLiked ? -1 : 1)

  try {
    const res = liked.value
      ? await commentApi.like(props.comment.id)
      : await commentApi.unlike(props.comment.id)
    // 以服务端返回为准（避免本地估算与实际偏差）
    liked.value = res.active
    likeCount.value = res.count
  } catch {
    // 回滚
    liked.value = prevLiked
    likeCount.value = prevCount
  } finally {
    liking.value = false
  }
}

async function onDelete() {
  try {
    await ElMessageBox.confirm('删除后该评论将不再可见。确定删除吗？', '删除评论', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }

  deleting.value = true
  try {
    await commentApi.remove(props.comment.id)
    ElMessage.success('评论已删除')
    emit('deleted', props.comment.id)
  } catch {
    // 拦截器已提示
  } finally {
    deleting.value = false
  }
}

/** 正在删除的回复 ID（回复是内联渲染的，共用一个状态即可） */
const deletingReplyId = ref<number | null>(null)

/**
 * 删除一条回复。
 *
 * 2026-09-29 补：此前回复行只有「回复 / 举报」两个按钮，**作者发错回复后没有任何撤回入口** ——
 * 而后端 `DELETE /api/comments/{id}` 本来就支持删回复（对任何评论都做归属校验）。
 * 缺的只是前端落点，属于"能力有、入口没有"的功能缺口。
 * 流程与根评论保持一致：确认 → 调用 → 上报，计数由父组件按 -1 处理。
 */
async function onDeleteReply(reply: CommentVO) {
  try {
    await ElMessageBox.confirm('删除后该回复将不再可见。确定删除吗？', '删除回复', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }

  deletingReplyId.value = reply.id
  try {
    await commentApi.remove(reply.id)
    ElMessage.success('回复已删除')
    emit('deleted', reply.id)
  } catch {
    // 拦截器已提示
  } finally {
    deletingReplyId.value = null
  }
}
</script>

<template>
  <article class="comment" :class="{ 'is-accepted': isAccepted }">
    <!-- 头像 -->
    <RouterLink
      :to="{ name: 'user-profile', params: { id: comment.authorId } }"
      class="c-avatar"
      :aria-label="`查看 ${comment.authorNickname || '匿名'} 的主页`"
    >
      <UserAvatar
        :src="comment.authorAvatar"
        :name="comment.authorNickname"
        :seed="comment.authorId"
        :size="34"
      />
    </RouterLink>

    <div class="c-body">
      <!-- 头部：作者 + 楼主标记 + 采纳标记 -->
      <header class="c-head">
        <RouterLink
          :to="{ name: 'user-profile', params: { id: comment.authorId } }"
          class="c-author"
        >
          {{ comment.authorNickname || '匿名' }}
        </RouterLink>
        <span v-if="isPostAuthor" class="c-badge c-badge-author">楼主</span>
        <span v-if="isAccepted" class="c-badge c-badge-accepted">✓ 已采纳</span>
        <time
          class="c-time"
          :datetime="comment.createTime"
          :title="formatDateTime(comment.createTime)"
        >
          {{ formatRelativeTime(comment.createTime) }}
        </time>
      </header>

      <!-- 内容：纯文本，直接插值（Vue 默认转义，不存在 XSS 面） -->
      <p class="c-content">{{ comment.content }}</p>

      <!-- 操作条 -->
      <footer class="c-actions">
        <!-- 自赞拦截（L-1）：自己的评论不显示点赞按钮（后端同样硬拦 40001） -->
        <button
          v-if="comment.authorId !== auth.user?.id"
          type="button"
          class="c-act"
          :class="{ 'is-active': liked }"
          :aria-pressed="liked"
          :disabled="liking"
          @click="onToggleLike"
        >
          <span aria-hidden="true">▲</span>
          <span>{{ formatCount(likeCount) }}</span>
        </button>

        <button type="button" class="c-act" @click="emit('reply', comment)">
          回复
        </button>

        <button
          v-if="auth.isLoggedIn"
          type="button"
          class="c-act"
          @click="onDelete"
          :disabled="!comment.canDelete || deleting"
          :title="comment.canDelete ? '删除评论' : '只能删除自己的评论'"
        >
          删除
        </button>

        <button
          v-if="auth.isLoggedIn && comment.authorId !== auth.user?.id"
          type="button"
          class="c-act"
          title="举报这条评论"
          @click="openReport(comment.id)"
        >
          举报
        </button>

        <button
          v-if="canAccept"
          type="button"
          class="c-act c-act-accept"
          @click="emit('accepted', comment.id)"
        >
          采纳
        </button>
      </footer>

      <!-- 回复列表（固定一层，不再嵌套组件） -->
      <div v-if="comment.replies?.length" class="c-replies">
        <div v-for="reply in comment.replies" :key="reply.id" class="reply">
          <RouterLink
            :to="{ name: 'user-profile', params: { id: reply.authorId } }"
            class="r-avatar"
          >
            <UserAvatar
              :src="reply.authorAvatar"
              :name="reply.authorNickname"
              :seed="reply.authorId"
              :size="26"
            />
          </RouterLink>

          <div class="r-body">
            <header class="r-head">
              <RouterLink
                :to="{ name: 'user-profile', params: { id: reply.authorId } }"
                class="r-author"
              >
                {{ reply.authorNickname || '匿名' }}
              </RouterLink>

              <!-- 「回复 @某人」：仅当被回复者不是楼主自己时才有信息量 -->
              <template v-if="reply.replyUser && reply.replyUser.id !== reply.authorId">
                <span class="r-arrow">回复</span>
                <RouterLink
                  :to="{ name: 'user-profile', params: { id: reply.replyUser.id } }"
                  class="r-replyto"
                >
                  @{{ reply.replyUser.nickname || '匿名' }}
                </RouterLink>
              </template>

              <span v-if="reply.authorId === postAuthorId" class="c-badge c-badge-author">楼主</span>

              <time
                class="c-time"
                :datetime="reply.createTime"
                :title="formatDateTime(reply.createTime)"
              >
                {{ formatRelativeTime(reply.createTime) }}
              </time>
            </header>

            <p class="c-content">{{ reply.content }}</p>

            <footer class="c-actions">
              <button type="button" class="c-act" @click="emit('reply', reply)">回复</button>

              <!-- 与根评论同口径：登录即可见，但只有自己的（或管理员）能点 -->
              <button
                v-if="auth.isLoggedIn"
                type="button"
                class="c-act"
                :disabled="!reply.canDelete || deletingReplyId === reply.id"
                :title="reply.canDelete ? '删除回复' : '只能删除自己的回复'"
                @click="onDeleteReply(reply)"
              >
                删除
              </button>

              <button
                v-if="auth.isLoggedIn && reply.authorId !== auth.user?.id"
                type="button"
                class="c-act"
                title="举报这条回复"
                @click="openReport(reply.id)"
              >
                举报
              </button>
            </footer>
          </div>
        </div>
      </div>
    </div>

    <!-- 举报弹窗：根评论与回复共用（target 由 openReport 决定） -->
    <ReportDialog
      v-model:visible="reportVisible"
      :target-type="2"
      :target-id="reportTargetId ?? 0"
      target-label="这条评论"
    />
  </article>
</template>

<style scoped>
.comment {
  display: flex;
  gap: var(--space-3);
  padding: var(--space-4) 0;
  border-bottom: 1px solid var(--color-border-subtle);
}

.comment:last-child {
  border-bottom: none;
}

.c-avatar {
  flex-shrink: 0;
  line-height: 0;
}

.c-body {
  flex: 1;
  min-width: 0;
}

/* ---------- 头部 ---------- */
.c-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  margin-bottom: var(--space-2);
}

.c-author {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-foreground);
}

.c-author:hover {
  color: var(--color-accent);
}

.c-badge {
  padding: 0 var(--space-2);
  font-size: 10px;
  font-weight: 600;
  line-height: 1.8;
  border-radius: var(--radius-sm);
}

.c-badge-author {
  color: var(--color-muted-foreground);
  background: var(--color-neutral-soft);
  border: 1px solid var(--color-border-hairline);
}

.c-badge-accepted {
  color: var(--color-success);
  background: var(--color-success-soft);
  border: 1px solid var(--color-success-border);
}

.c-time {
  margin-left: auto;
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
}

/* ---------- 内容 ---------- */
.c-content {
  font-size: var(--text-sm);
  line-height: var(--leading-relaxed);
  color: var(--color-muted-foreground);
  /* 保留用户输入的换行，但不解析任何标记 */
  white-space: pre-wrap;
  word-break: break-word;
}

/* ---------- 操作条 ---------- */
.c-actions {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  margin-top: var(--space-2);
}

.c-act {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: 2px var(--space-2);
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  transition: all var(--transition-fast);
  font-variant-numeric: tabular-nums;
}

.c-act:hover:not(:disabled) {
  color: var(--color-accent);
  background: var(--color-muted);
}

.c-act:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

/* 已点赞：用品牌强调色，与"我点过"的状态一致 */
.c-act.is-active {
  color: var(--color-accent);
  background: var(--color-accent-soft);
  border-color: var(--color-accent-border);
}

.c-act-accept {
  margin-left: auto;
  color: var(--color-success);
}

.c-act-accept:hover:not(:disabled) {
  color: var(--color-success);
  background: var(--color-success-soft);
}

/* ---------- 被采纳的高亮 ---------- */
.is-accepted {
  padding-left: var(--space-3);
  margin-left: calc(var(--space-3) * -1);
  border-left: 2px solid var(--color-success);
}

/* ---------- 回复 ---------- */
.c-replies {
  margin-top: var(--space-3);
  padding-left: var(--space-3);
  border-left: 2px solid var(--color-border-subtle);
}

.reply {
  display: flex;
  gap: var(--space-2);
  padding: var(--space-3) 0;
}

.reply:first-child {
  padding-top: 0;
}

.reply:last-child {
  padding-bottom: 0;
}

.r-avatar {
  flex-shrink: 0;
  line-height: 0;
}

.r-body {
  flex: 1;
  min-width: 0;
}

.r-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  margin-bottom: var(--space-1);
}

.r-author {
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--color-foreground);
}

.r-author:hover {
  color: var(--color-accent);
}

.r-arrow {
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
}

.r-replyto {
  font-size: var(--text-xs);
  color: var(--color-accent);
}

@media (max-width: 640px) {
  .c-time {
    margin-left: 0;
    width: 100%;
  }
}
</style>
