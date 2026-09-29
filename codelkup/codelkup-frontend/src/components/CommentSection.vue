<script setup lang="ts">
/**
 * 评论区：列表 + 发表框 + 回复框。
 *
 * 回复交互设计：点击某条评论的「回复」后，把目标评论记录在 `replyTarget`，
 * 在主输入框上方显示「正在回复 @某人」并提供取消 —— 不做弹窗。
 * 理由：弹窗会把用户从阅读上下文中拽走，而评论回复天然需要在
 * 看到原评论的情况下写。GitHub / 掘金 都用这个模式。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { commentApi, postActionApi } from '@/api/interaction'
import { useAuthStore } from '@/stores/auth'
import CommentItem from '@/components/CommentItem.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { CommentVO } from '@/types/api'

const props = defineProps<{
  postId: number
  postAuthorId: number
  isQuestion: boolean
}>()

const emit = defineEmits<{
  /** 评论数变化 —— 让父组件同步帖子的评论计数 */
  (e: 'count-change', delta: number): void
}>()

const auth = useAuthStore()

const comments = ref<CommentVO[]>([])
const loading = ref(true)
const submitting = ref(false)
const content = ref('')
const replyTarget = ref<CommentVO | null>(null)
const textareaRef = ref<HTMLTextAreaElement | null>(null)

/** 评论总数：后端返回的是树，计数要递归展开 */
const totalCount = computed(() => {
  let n = 0
  for (const c of comments.value) {
    n += 1
    n += c.replies?.length ?? 0
  }
  return n
})

async function load() {
  loading.value = true
  try {
    comments.value = await commentApi.list(props.postId)
  } catch {
    // 拦截器已提示；评论加载失败不该让整页崩，保持空列表
    comments.value = []
  } finally {
    loading.value = false
  }
}

watch(() => props.postId, load, { immediate: true })

/** 点击回复：记录目标并聚焦输入框 */
async function onReply(comment: CommentVO) {
  if (!auth.isLoggedIn) {
    ElMessage.info('登录后即可回复')
    return
  }
  replyTarget.value = comment
  await nextTick()
  textareaRef.value?.focus()
}

function cancelReply() {
  replyTarget.value = null
}

/**
 * 提交评论 / 回复。
 *
 * 提交成功后**不重新拉全量**，而是就地把返回的那条插进正确位置 ——
 * 减少一次请求，也避免列表重排导致的滚动位置跳动。
 */
async function onSubmit() {
  const text = content.value.trim()
  if (!text) {
    ElMessage.warning('请输入评论内容')
    return
  }
  if (!auth.isLoggedIn) {
    ElMessage.info('登录后即可评论')
    return
  }

  submitting.value = true
  try {
    const created = await commentApi.create(props.postId, {
      content: text,
      parentId: replyTarget.value?.id ?? 0,
    })

    if (replyTarget.value) {
      // 回复：归一化到根评论（后端也这么做，这里保持一致以便正确插入）
      const rootId =
        replyTarget.value.parentId === 0 ? replyTarget.value.id : replyTarget.value.parentId
      const root = comments.value.find((c) => c.id === rootId)
      if (root) {
        root.replies = [...(root.replies ?? []), created]
      } else {
        // 极端情况：根评论已被删除，降级为一级评论展示，内容不丢
        comments.value = [...comments.value, created]
      }
    } else {
      // 一级评论：追加到末尾（列表按时间升序）
      comments.value = [...comments.value, created]
    }

    emit('count-change', 1)
    content.value = ''
    replyTarget.value = null
    ElMessage.success('发表成功')
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

/**
 * 删除后同步本地树与计数。
 *
 * 2026-09-29 修复（计数漂移）：**根评论不能按"整棵子树"扣数**。
 * 服务端 `CommentService.delete` 只逻辑删除根评论这一条、计数只 -1；
 * 其下的回复行仍在，`listByPost` 会把它们**提升为一级评论**重新返回
 * （后端有意为之：不因为楼主删了自己的评论就静默吞掉别人的回复）。
 * 原来的实现本地删掉整棵子树并扣 `1 + N` —— 于是
 *   ① 评论数被多扣 N，与服务端立刻对不上；
 *   ② 刷新后那 N 条回复又出现了，用户看到的是"删了又回来"。
 * 现在：根评论只扣 1，然后重新拉取，让服务端的真实树（含被提升的回复）接管显示。
 */
async function onDeleted(commentId: number) {
  // 回复：从所属根评论的 replies 里摘掉，计数 -1（与服务端逻辑删除一致）
  for (const root of comments.value) {
    const idx = root.replies?.findIndex((r) => r.id === commentId) ?? -1
    if (idx >= 0 && root.replies) {
      const removed = root.replies.splice(idx, 1)
      emit('count-change', -removed.length)
      return
    }
  }

  // 一级评论
  const rootIdx = comments.value.findIndex((c) => c.id === commentId)
  if (rootIdx < 0) return
  comments.value.splice(rootIdx, 1)
  emit('count-change', -1)
  await load()
}

/** 采纳答案 —— 采纳后重新拉取（只有一个采纳位，就地改状态容易漏掉旧采纳的取消） */
async function onAccepted(commentId: number) {
  try {
    await postActionApi.accept(props.postId, commentId)
    ElMessage.success('已采纳该答案')
    await load()
  } catch {
    // 拦截器已提示
  }
}
</script>

<template>
  <section class="comments">
    <h2 class="section-title">
      评论
      <span class="section-count">{{ totalCount }}</span>
    </h2>

    <!-- ---------- 发表框 ---------- -->
    <div class="composer">
      <template v-if="auth.isLoggedIn">
        <!-- 回复提示条 -->
        <div v-if="replyTarget" class="reply-banner">
          <span class="reply-text">
            正在回复
            <strong>@{{ replyTarget.authorNickname || '匿名' }}</strong>
          </span>
          <button type="button" class="reply-cancel" @click="cancelReply">取消</button>
        </div>

        <textarea
          ref="textareaRef"
          v-model="content"
          class="composer-input"
          :placeholder="replyTarget ? '写下你的回复…' : '写下你的想法…（支持换行，不支持 Markdown）'"
          rows="3"
          maxlength="1000"
        />

        <div class="composer-foot">
          <span class="composer-hint">{{ content.length }} / 1000</span>
          <el-button
            type="primary"
            size="small"
            :loading="submitting"
            :disabled="!content.trim()"
            @click="onSubmit"
          >
            {{ replyTarget ? '发表回复' : '发表评论' }}
          </el-button>
        </div>
      </template>

      <!-- 未登录引导 -->
      <div v-else class="composer-guest">
        <span>参与讨论，先登录一下</span>
        <RouterLink
          :to="{ name: 'login', query: { redirect: `/posts/${postId}` } }"
          class="guest-link"
        >
          去登录
        </RouterLink>
      </div>
    </div>

    <!-- ---------- 列表 ---------- -->
    <div v-if="loading" class="comments-loading" aria-busy="true">
      <span class="sk-line" />
      <span class="sk-line sk-short" />
      <span class="sk-line" />
    </div>

    <EmptyState
      v-else-if="!comments.length"
      icon="empty"
      title="还没有评论"
      description="来做第一个发表看法的人吧。"
    />

    <div v-else class="comments-list">
      <CommentItem
        v-for="comment in comments"
        :key="comment.id"
        :comment="comment"
        :post-author-id="postAuthorId"
        :is-question="isQuestion"
        @reply="onReply"
        @deleted="onDeleted"
        @accepted="onAccepted"
      />
    </div>
  </section>
</template>

<style scoped>
.comments {
  margin-top: var(--space-6);
  padding: var(--space-5);
  background: var(--color-card);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
}

.section-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
  font-size: var(--text-lg);
}

.section-count {
  padding: 0 var(--space-2);
  font-size: var(--text-xs);
  font-weight: 500;
  color: var(--color-muted-foreground);
  background: var(--color-muted);
  border-radius: var(--radius-full);
  font-variant-numeric: tabular-nums;
}

/* ---------- 发表框 ---------- */
.composer {
  margin-bottom: var(--space-5);
}

.reply-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  margin-bottom: var(--space-2);
  font-size: var(--text-xs);
  color: var(--color-muted-foreground);
  background: var(--color-muted);
  border-left: 2px solid var(--color-accent);
  border-radius: var(--radius-sm);
}

.reply-text strong {
  color: var(--color-accent);
}

.reply-cancel {
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
  background: transparent;
  border: none;
}

.reply-cancel:hover {
  color: var(--color-foreground);
}

.composer-input {
  display: block;
  width: 100%;
  padding: var(--space-3);
  font-family: inherit;
  font-size: var(--text-sm);
  line-height: var(--leading-relaxed);
  color: var(--color-foreground);
  background: var(--color-background);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  resize: vertical;
  transition: border-color var(--transition-fast);
}

.composer-input::placeholder {
  color: var(--color-subtle-foreground);
}

.composer-input:focus {
  border-color: var(--color-accent);
  outline: none;
}

.composer-foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-2);
}

.composer-hint {
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
  font-variant-numeric: tabular-nums;
}

.composer-guest {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-4);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  background: var(--color-muted);
  border-radius: var(--radius-md);
}

.guest-link {
  color: var(--color-accent);
  font-weight: 500;
}

/* ---------- 列表 ---------- */
.comments-list {
  display: flex;
  flex-direction: column;
}

.comments-loading {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-4) 0;
}

.sk-line {
  display: block;
  height: 14px;
  border-radius: var(--radius-sm);
  background: linear-gradient(
    90deg,
    var(--color-muted) 25%,
    var(--color-elevated) 37%,
    var(--color-muted) 63%
  );
  background-size: 400% 100%;
  animation: cm-shimmer 1.4s ease infinite;
}

.sk-short {
  width: 60%;
}

@keyframes cm-shimmer {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

@media (max-width: 640px) {
  .comments {
    padding: var(--space-4);
  }
}
</style>
