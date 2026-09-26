<script setup lang="ts">
/**
 * 管理端（极简版）：只做两件事 —— 帖子置顶/加精、计数重建。
 *
 * <p>为什么不做"大而全后台"：这两件事的接口早就写好了
 * （ContentController.feature、InteractionController.rebuild*），
 * 缺的只是入口。补上入口，让"有接口没人用"变成"能演示、能自证"，
 * 成本最低、演示价值最高。
 *
 * ⚠️ 权限说明：前端隐藏入口只是体验，真正的边界在后端
 *    （@PreAuthorize("hasRole('ADMIN')") + 路由守卫双保险）。
 */
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { postApi } from '@/api/post'
import { adminApi } from '@/api/admin'
import type { PostDetailVO, RebuildReportVO } from '@/types/api'

const postId = ref<number>()
const post = ref<PostDetailVO | null>(null)
const loadingPost = ref(false)
const savingFlags = ref(false)
const rebuildingOne = ref(false)
const rebuildingAll = ref(false)
const report = ref<RebuildReportVO | null>(null)

async function loadPost() {
  if (!postId.value) {
    ElMessage.warning('请先填写帖子 ID')
    return
  }
  loadingPost.value = true
  try {
    post.value = await postApi.detail(postId.value)
  } catch {
    // request.ts 已统一提示业务错误，这里只负责清空旧结果，避免张冠李戴
    post.value = null
  } finally {
    loadingPost.value = false
  }
}

async function setFlags(params: { top?: boolean; featured?: boolean }) {
  if (!post.value) return
  savingFlags.value = true
  try {
    await postApi.feature(post.value.id, params)
    // 本地同步而不是重新拉详情：开关是幂等写操作，为一个布尔值再发一次请求不划算
    if (params.top !== undefined) post.value.isTop = params.top ? 1 : 0
    if (params.featured !== undefined) post.value.isFeatured = params.featured ? 1 : 0
    ElMessage.success('帖子标记已更新')
  } finally {
    savingFlags.value = false
  }
}

async function rebuildOne() {
  if (!postId.value) {
    ElMessage.warning('请先填写帖子 ID')
    return
  }
  rebuildingOne.value = true
  try {
    report.value = await adminApi.rebuildPost(postId.value)
    ElMessage.success('该帖计数已按明细表校准')
  } finally {
    rebuildingOne.value = false
  }
}

async function rebuildAll() {
  try {
    await ElMessageBox.confirm(
      '全量重建会扫描全部帖子并覆盖 Redis 与 MySQL 的计数，1 万帖量级约需 20~40 秒。确定继续？',
      '全量重建计数',
      { confirmButtonText: '开始重建', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  rebuildingAll.value = true
  try {
    report.value = await adminApi.rebuildAll()
    ElMessage.success('全量重建完成')
  } finally {
    rebuildingAll.value = false
  }
}
</script>

<template>
  <div class="container admin-page">
    <header class="page-head">
      <h1 class="page-title">管理端</h1>
      <p class="page-desc">
        置顶 / 加精与计数重建。计数重建是<b>破坏性写操作</b>（用明细表真值覆盖 Redis 与 MySQL），
        仅在计数漂移时使用。
      </p>
    </header>

    <!-- ---------- 定位帖子 ---------- -->
    <section class="admin-card">
      <h2 class="card-title">定位帖子</h2>
      <div class="row">
        <el-input-number
          v-model="postId"
          :min="1"
          :controls="false"
          placeholder="帖子 ID"
          class="id-input"
          @keyup.enter="loadPost"
        />
        <el-button :loading="loadingPost" @click="loadPost">读取</el-button>
      </div>

      <div v-if="post" class="post-brief">
        <router-link :to="{ name: 'post-detail', params: { id: post.id } }" class="post-title">
          {{ post.title }}
        </router-link>
        <div class="post-meta">
          <el-tag size="small" type="info">{{ post.boardName || '未知板块' }}</el-tag>
          <el-tag v-if="post.isTop === 1" size="small" type="warning">置顶</el-tag>
          <el-tag v-if="post.isFeatured === 1" size="small" type="success">加精</el-tag>
          <span class="meta-text">
            赞 {{ post.likeCount }} · 藏 {{ post.collectCount }} · 评 {{ post.commentCount }}
          </span>
        </div>
      </div>
    </section>

    <!-- ---------- 标记 ---------- -->
    <section class="admin-card" :class="{ disabled: !post }">
      <h2 class="card-title">帖子标记</h2>
      <div class="row">
        <label class="switch-item">
          <span>置顶</span>
          <el-switch
            :model-value="post?.isTop === 1"
            :disabled="!post || savingFlags"
            @change="(v: string | number | boolean) => setFlags({ top: Boolean(v) })"
          />
        </label>
        <label class="switch-item">
          <span>加精</span>
          <el-switch
            :model-value="post?.isFeatured === 1"
            :disabled="!post || savingFlags"
            @change="(v: string | number | boolean) => setFlags({ featured: Boolean(v) })"
          />
        </label>
      </div>
      <p v-if="!post" class="hint">先读取一个帖子，才能修改标记。</p>
    </section>

    <!-- ---------- 计数重建 ---------- -->
    <section class="admin-card">
      <h2 class="card-title">计数重建</h2>
      <div class="row">
        <el-button :loading="rebuildingOne" :disabled="!postId" @click="rebuildOne">
          重建该帖
        </el-button>
        <el-button type="danger" plain :loading="rebuildingAll" @click="rebuildAll">
          全量重建
        </el-button>
      </div>

      <div v-if="report" class="report">
        <div class="report-head">
          <span class="scope">{{ report.scope }}</span>
          <el-tag size="small" :type="report.success ? 'success' : 'danger'">
            {{ report.success ? '成功' : '失败' }}
          </el-tag>
          <span v-if="report.driftCount !== undefined" class="drift">
            偏差修正 <b>{{ report.driftCount }}</b>
          </span>
          <span v-if="report.totalPosts !== undefined" class="meta-text">
            扫描 {{ report.totalPosts }} 帖 / 修正 {{ report.fixedPosts ?? 0 }} 帖
          </span>
        </div>
        <p v-if="report.message" class="meta-text">{{ report.message }}</p>

        <table v-if="report.beforeLike !== undefined" class="report-table">
          <thead>
            <tr>
              <th>维度</th>
              <th>重建前（MySQL 基线）</th>
              <th>重建后（明细表真值）</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>点赞</td>
              <td>{{ report.beforeLike }}</td>
              <td>{{ report.afterLike }}</td>
            </tr>
            <tr>
              <td>收藏</td>
              <td>{{ report.beforeCollect }}</td>
              <td>{{ report.afterCollect }}</td>
            </tr>
            <tr>
              <td>评论</td>
              <td>{{ report.beforeComment }}</td>
              <td>{{ report.afterComment }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.admin-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  max-width: 760px;
}

.page-head {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.page-title {
  font-size: var(--text-xl);
  letter-spacing: var(--tracking-heading);
}

.page-desc {
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  line-height: 1.7;
}

.admin-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: var(--space-5);
  background: var(--color-card);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
}

.admin-card.disabled {
  opacity: 0.72;
}

.card-title {
  font-size: var(--text-md);
  font-weight: 600;
  color: var(--color-foreground);
}

.row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.id-input {
  width: 140px;
}

.switch-item {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
}

.post-brief {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-border-subtle);
}

.post-title {
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--color-foreground);
}

.post-meta {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.meta-text,
.hint {
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
}

.report {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-4);
  background: var(--color-muted);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
}

.report-head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
  font-size: var(--text-sm);
}

.scope {
  font-family: var(--font-mono);
  color: var(--color-muted-foreground);
}

/* 偏差量是这个页面最该被看见的数字：它是"Redis 丢了多少增量"的量化证据 */
.drift {
  color: var(--color-muted-foreground);
}

.drift b {
  color: var(--color-accent);
  font-variant-numeric: tabular-nums;
}

.report-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--text-sm);
  font-variant-numeric: tabular-nums;
}

.report-table th,
.report-table td {
  padding: var(--space-2) var(--space-3);
  text-align: left;
  border-bottom: 1px solid var(--color-border-subtle);
}

.report-table th {
  color: var(--color-subtle-foreground);
  font-weight: 500;
}
</style>