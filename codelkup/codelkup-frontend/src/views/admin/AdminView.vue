<script setup lang="ts">
/**
 * 管理端（极简版）：帖子置顶/加精、计数重建、举报审核队列。
 *
 * <p>为什么不做"大而全后台"：这三件事的接口早就写好了
 * （ContentController.feature、InteractionController.rebuild*、ReportController.*），
 * 缺的只是入口。补上入口，让"有接口没人用"变成"能演示、能自证"，
 * 成本最低、演示价值最高。
 *
 * ⚠️ 权限说明：前端隐藏入口只是体验，真正的边界在后端
 *    （@PreAuthorize("hasRole('ADMIN')") + 路由守卫双保险）。
 */
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { postApi } from '@/api/post'
import { adminApi } from '@/api/admin'
import { REPORT_STATUS, REPORT_STATUS_LABELS, reasonLabel } from '@/api/report'
import { formatRelativeTime } from '@/utils/format'
import type { PostDetailVO, RebuildReportVO, ReportAdminVO } from '@/types/api'

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

/* ==================== 举报队列（V0.5 / M5） ====================
   审核是一条条看、一条条判的活，所以队列按时间倒序 + 一页 10 条：
   一屏之内能把"目标是什么、谁投诉的、依据什么"看清，再决定驳回还是删内容。
   已处理的行**不隐藏**（切到对应筛选就能看到留痕：谁处理的、何时、备注），
   这正是不做"批量处理"的原因 —— 每条处理都要有人签字。 */
const REPORT_PAGE_SIZE = 10

const reportStatus = ref<number>(REPORT_STATUS.pending)
const reportPage = ref(1)
const reportRows = ref<ReportAdminVO[]>([])
const reportTotal = ref(0)
const reportPending = ref(0)
const loadingReports = ref(false)
/** 正在提交的举报 ID —— 同一时刻只允许一行在提交，避免连点造成重复处理（后端会回 40910） */
const handlingId = ref<number>()
/** 行内备注输入（按举报 ID 索引），提交成功后清空 */
const noteDraft = reactive<Record<number, string>>({})

async function loadPendingCount() {
  try {
    reportPending.value = await adminApi.pendingReportCount()
  } catch {
    // 拦截器已提示；角标保持上一次的值即可，不打断队列浏览
  }
}

async function loadReports() {
  loadingReports.value = true
  try {
    const res = await adminApi.reports({
      status: reportStatus.value,
      page: reportPage.value,
      size: REPORT_PAGE_SIZE,
    })
    reportRows.value = res.records
    reportTotal.value = res.total
  } finally {
    loadingReports.value = false
  }
}

// 换筛选必须回到第 1 页：否则会停在一个"新筛选下并不存在"的页码上，列表空白
watch(reportStatus, () => {
  reportPage.value = 1
  loadReports()
})

async function onHandleReport(row: ReportAdminVO, action: 'dismiss' | 'delete_target') {
  if (action === 'delete_target') {
    try {
      await ElMessageBox.confirm(
        `将删除${row.targetType === 1 ? '这篇帖子' : '这条评论'}，作者会失去这条内容。确定继续？`,
        '删除被举报内容',
        { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
  }

  handlingId.value = row.id
  try {
    const res = await adminApi.handleReport(row.id, {
      action,
      note: noteDraft[row.id]?.trim() || undefined,
    })
    noteDraft[row.id] = ''
    ElMessage.success(
      action === 'dismiss'
        ? '已驳回，内容保持不变'
        : `内容已删除${res.closedSiblings > 0 ? `，同时关闭 ${res.closedSiblings} 条同目标的举报` : ''}`,
    )
    // 两个数都要刷新：这一行的状态变了，待处理数也变了
    await Promise.all([loadReports(), loadPendingCount()])
  } catch {
    // 拦截器已提示（典型情况：并发下另一个管理员抢先处理 → 40910）
  } finally {
    handlingId.value = undefined
  }
}

onMounted(() => {
  loadReports()
  loadPendingCount()
})
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

    <!-- ---------- 举报队列（V0.5 / M5） ---------- -->
    <section class="admin-card">
      <div class="card-head">
        <h2 class="card-title">举报队列</h2>
        <span class="pending-badge" :class="{ 'is-zero': reportPending === 0 }">
          待处理 {{ reportPending }}
        </span>
      </div>
      <p class="page-desc">
        每条举报逐个处理：驳回则内容保持不变，删除内容会复用作者删除的同一套路径
        （板块计数、标签引用、Redis 计数一并同步）。处理记录永久留痕。
      </p>

      <el-radio-group v-model="reportStatus" size="small">
        <el-radio-button :value="REPORT_STATUS.pending">待处理</el-radio-button>
        <el-radio-button :value="REPORT_STATUS.dismissed">已驳回</el-radio-button>
        <el-radio-button :value="REPORT_STATUS.deletedTarget">已删除内容</el-radio-button>
      </el-radio-group>

      <div v-loading="loadingReports" class="report-list">
        <p v-if="!loadingReports && reportRows.length === 0" class="hint">
          当前筛选下没有举报记录。
        </p>

        <article v-for="row in reportRows" :key="row.id" class="report-row">
          <div class="report-main">
            <div class="report-target">
              <el-tag size="small" type="info">
                {{ row.targetType === 1 ? '帖子' : '评论' }}
              </el-tag>

              <!-- 目标仍在 → 可点开核对；已删除 → 明确写出来，不留白 -->
              <template v-if="row.targetExists">
                <RouterLink
                  v-if="row.targetType === 1"
                  :to="{ name: 'post-detail', params: { id: row.targetId } }"
                  class="report-link"
                >
                  {{ row.targetTitle || `帖子 #${row.targetId}` }}
                </RouterLink>
                <template v-else>
                  <RouterLink
                    v-if="row.targetPostId"
                    :to="{ name: 'post-detail', params: { id: row.targetPostId } }"
                    class="report-link"
                  >
                    帖子 #{{ row.targetPostId }} 下的评论
                  </RouterLink>
                  <span class="report-excerpt">{{ row.targetExcerpt }}</span>
                </template>
              </template>
              <span v-else class="report-gone">
                内容已删除（原{{ row.targetType === 1 ? '帖子' : '评论' }}已不存在）
              </span>
            </div>

            <div class="report-meta">
              <el-tag v-if="row.reportedCount > 1" size="small" type="warning" effect="plain">
                同一目标被举报 {{ row.reportedCount }} 次
              </el-tag>
              <span>{{ reasonLabel(String(row.reason)) }}</span>
              <span class="dot">·</span>
              <span>举报人 {{ row.reporterNickname || `#${row.reporterId}` }}</span>
              <span class="dot">·</span>
              <time :datetime="row.createTime">{{ formatRelativeTime(row.createTime) }}</time>
              <template v-if="row.targetAuthorNickname">
                <span class="dot">·</span>
                <span>作者 {{ row.targetAuthorNickname }}</span>
              </template>
            </div>

            <p v-if="row.detail" class="report-detail">说明：{{ row.detail }}</p>

            <!-- 已处理的行不隐藏：把"谁、何时、依据什么"摆出来 -->
            <p v-if="row.status !== 0" class="report-trace">
              {{ REPORT_STATUS_LABELS[row.status] }}
              · 由 {{ row.handledByName || `管理员 #${row.handledBy}` }} 于
              {{ formatRelativeTime(row.handledAt) }} 处理
              <span v-if="row.handleNote">· 备注：{{ row.handleNote }}</span>
            </p>
          </div>

          <div v-if="row.status === 0" class="report-actions">
            <el-input
              v-model="noteDraft[row.id]"
              size="small"
              :maxlength="200"
              placeholder="处理备注（可选）"
              class="note-input"
            />
            <div class="action-buttons">
              <el-button
                size="small"
                :loading="handlingId === row.id"
                @click="onHandleReport(row, 'dismiss')"
              >
                驳回
              </el-button>
              <el-button
                size="small"
                type="danger"
                plain
                :loading="handlingId === row.id"
                :disabled="!row.targetExists"
                :title="row.targetExists ? '删除这条内容' : '目标已不存在，只能驳回'"
                @click="onHandleReport(row, 'delete_target')"
              >
                删除内容
              </el-button>
            </div>
          </div>
        </article>
      </div>

      <el-pagination
        v-if="reportTotal > REPORT_PAGE_SIZE"
        :current-page="reportPage"
        :page-size="REPORT_PAGE_SIZE"
        :total="reportTotal"
        layout="prev, pager, next"
        small
        background
        @current-change="(p: number) => { reportPage = p; loadReports() }"
      />
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

/* ---------- 举报队列 ---------- */
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

/* 待处理数是这个卡片的核心指标：非零时用强调色，零值主动降调 */
.pending-badge {
  padding: 2px var(--space-3);
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--color-accent);
  background: var(--color-accent-soft);
  border: 1px solid var(--color-accent-border);
  border-radius: 999px;
  font-variant-numeric: tabular-nums;
}

.pending-badge.is-zero {
  color: var(--color-subtle-foreground);
  background: var(--color-neutral-soft);
  border-color: var(--color-border-hairline);
}

.report-list {
  display: flex;
  flex-direction: column;
  min-height: 60px;
}

.report-row {
  display: flex;
  gap: var(--space-4);
  align-items: flex-start;
  justify-content: space-between;
  flex-wrap: wrap;
  padding: var(--space-4) 0;
  border-top: 1px solid var(--color-border-subtle);
}

.report-row:first-child {
  border-top: none;
}

.report-main {
  flex: 1;
  min-width: 220px;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.report-target {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.report-link {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-foreground);
}

.report-link:hover {
  color: var(--color-accent);
}

.report-excerpt {
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  word-break: break-word;
}

/* 目标已删：明确的"已删除"而不是留白 —— 留白会被读成"这条举报没有目标" */
.report-gone {
  font-size: var(--text-sm);
  color: var(--color-subtle-foreground);
  text-decoration: line-through;
  text-decoration-color: var(--color-border);
}

.report-meta {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
}

.dot {
  opacity: 0.6;
}

.report-detail {
  font-size: var(--text-xs);
  line-height: 1.7;
  color: var(--color-muted-foreground);
  padding: var(--space-2) var(--space-3);
  background: var(--color-muted);
  border-radius: var(--radius-sm);
  word-break: break-word;
}

.report-trace {
  font-size: var(--text-xs);
  line-height: 1.7;
  color: var(--color-subtle-foreground);
}

.report-actions {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  width: 200px;
}

.note-input {
  width: 100%;
}

.action-buttons {
  display: flex;
  gap: var(--space-2);
}
</style>