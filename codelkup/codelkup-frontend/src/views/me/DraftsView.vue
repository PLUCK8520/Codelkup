<script setup lang="ts">
/**
 * 草稿箱（M4-15）。
 *
 * 数据源 `GET /api/drafts`（需登录，按更新时间倒序，只返回摘要不含正文）。
 *
 * 这里只放**服务端草稿**：本地草稿是"本机写前日志"，属于编辑器的内部兜底，
 * 不在这里重复展示 —— 否则同一份内容会以两行出现（本地一行、云端一行），
 * 用户根本分不清该删哪一个。
 *
 * 「继续编辑」不是简单地跳回编辑器，而是带 `?resume=1`：
 * 用户点这个按钮的意图就是"把这份草稿打开继续写"，编辑器据此跳过
 * "是否恢复草稿？"的确认框直接恢复（详见 PostEditorView 的说明）。
 * 编辑稿按 scope 里的 postId 回对应帖子的编辑页；帖子若已被删除，
 * 编辑器拉详情时会拿到 40402 并退回首页 —— 这里的失败态与全站一致。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { draftApi } from '@/api/draft'
import EmptyState from '@/components/EmptyState.vue'
import { formatRelativeTime } from '@/utils/format'
import type { DraftSummaryVO } from '@/types/api'

const router = useRouter()

const drafts = ref<DraftSummaryVO[]>([])
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    drafts.value = await draftApi.list()
  } catch (e) {
    // 失败态三态：不把"没取到"显示成"共 0 条"（与板块页/标签页同一口径）
    error.value = e instanceof Error ? e.message : '加载失败'
    drafts.value = []
  } finally {
    loading.value = false
  }
}
load()

/** 新建稿 / 编辑稿 —— scope 是唯一身份，展示层只做翻译 */
function scopeLabel(d: DraftSummaryVO): string {
  return d.scope === 'new' ? '新建帖子' : '编辑帖子'
}

/** 编辑稿 scope（edit:<postId>）里解析出帖子 ID */
function postIdOf(d: DraftSummaryVO): number {
  return Number(d.scope.slice('edit:'.length))
}

function resume(d: DraftSummaryVO) {
  if (d.scope === 'new') {
    router.push({ name: 'post-create', query: { resume: '1' } })
  } else {
    router.push({ name: 'post-edit', params: { id: postIdOf(d) }, query: { resume: '1' } })
  }
}

async function remove(d: DraftSummaryVO) {
  try {
    await ElMessageBox.confirm('删除后无法恢复，确定删除这份草稿吗？', '删除草稿', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return // 用户取消
  }
  try {
    await draftApi.remove(d.scope)
    drafts.value = drafts.value.filter((x) => x.scope !== d.scope)
    ElMessage.success('草稿已删除')
  } catch (e) {
    // 草稿接口是静默请求，失败要在页面上看得见
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}
</script>

<template>
  <div class="container collections-page">
    <header class="page-head">
      <div>
        <h1 class="page-title">我的草稿</h1>
        <p class="page-desc">
          {{
            loading
              ? '加载中…'
              : error
                ? '…草稿暂时没取到'
                : `共 ${drafts.length} 条 · 编辑中的内容会自动保存到这里，换设备也能接着写`
          }}
        </p>
      </div>
      <el-button text type="primary" @click="router.push({ name: 'post-create' })">
        去创作
      </el-button>
    </header>

    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button text type="primary" @click="load">重试</el-button>
    </el-alert>

    <template v-else-if="loading">
      <el-skeleton :rows="3" animated />
    </template>

    <EmptyState
      v-else-if="!drafts.length"
      icon="post"
      title="草稿箱是空的"
      description="写帖子的过程中内容会自动保存到这里；中途离开也没关系，回来可以接着写。"
      action-text="去创作"
      @action="router.push({ name: 'post-create' })"
    />

    <ul v-else class="draft-list">
      <li v-for="(d, i) in drafts" :key="d.scope" class="draft-item anim-in" :style="{ '--i': Math.min(i, 8) }">
        <div class="draft-main">
          <div class="draft-head">
            <span class="draft-badge">{{ scopeLabel(d) }}</span>
            <span class="draft-title">{{ d.title.trim() || '未命名草稿' }}</span>
          </div>
          <p class="draft-excerpt">{{ d.excerpt?.trim() || '（还没有正文）' }}</p>
          <p class="draft-meta">保存于 {{ formatRelativeTime(d.updateTime) }}</p>
        </div>
        <div class="draft-actions">
          <el-button type="primary" plain size="small" @click="resume(d)">继续编辑</el-button>
          <el-button size="small" @click="remove(d)">删除</el-button>
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
/* 与「我的收藏」共用页面骨架（page-head / 标题层级），只是列表项换成了草稿行 */
.collections-page {
  max-width: var(--container-list);
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: var(--space-5);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--color-border-subtle);
}

.page-title {
  font-size: var(--text-2xl);
  letter-spacing: -0.02em;
}

.page-desc {
  margin-top: var(--space-1);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
}

/* ---------- 草稿行 ---------- */
.draft-list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.draft-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-4) var(--space-5);
  background: var(--color-card);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
  transition: background var(--transition-fast), border-color var(--transition-fast),
    transform var(--transition-fast);
}

.draft-item:hover {
  background: var(--color-card-hover);
  border-color: var(--color-border);
  transform: translateY(-1px);
}

.draft-main {
  min-width: 0;
}

.draft-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}

.draft-badge {
  flex-shrink: 0;
  padding: 1px var(--space-2);
  font-size: var(--text-xs);
  color: var(--color-accent);
  background: var(--color-accent-soft);
  border: 1px solid var(--color-accent-border);
  border-radius: var(--radius-sm);
}

.draft-title {
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--color-foreground);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.draft-excerpt {
  margin-top: var(--space-1);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.draft-meta {
  margin-top: var(--space-1);
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
}

.draft-actions {
  display: flex;
  gap: var(--space-2);
  flex-shrink: 0;
}

@media (max-width: 640px) {
  .page-head {
    flex-direction: column;
  }

  .page-title {
    font-size: var(--text-xl);
  }

  .draft-item {
    flex-direction: column;
    align-items: stretch;
  }

  .draft-actions {
    justify-content: flex-end;
  }
}
</style>