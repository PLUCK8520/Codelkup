<script setup lang="ts">
/**
 * 发帖 / 编辑页（同一组件，靠路由区分）。
 *
 *   /publish           → 新建
 *   /posts/:id/edit    → 编辑（进入时先拉详情回填）
 *
 * 编辑权限：前端只负责"不显示入口"，真正的鉴权在后端 ——
 * 非作者改他人帖子会拿到 40302，这里不做重复判断。
 *
 * 草稿（M2-10 本地 → M4-15 本地 + 云端双层）：
 *   本地 localStorage 是写前日志（离线可用、无需登录）；
 *   服务端草稿是跨设备副本 —— 登录后编辑器每次保存会同时推一份到 /api/drafts，
 *   进入时取两侧中较新的一份提示恢复（规则见 useDraft.pickNewerDraft）。
 *   从草稿箱点「继续编辑」会带 `?resume=1`：意图明确，不再弹确认框、直接恢复。
 *   未登录 / 服务端不可用时自动退回纯本地模式（整条远端链路不参与）。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { postApi } from '@/api/post'
import { draftApi } from '@/api/draft'
import { aiApi } from '@/api/ai'
import { useMetaStore } from '@/stores/meta'
import { useAuthStore } from '@/stores/auth'
import {
  DRAFT_VERSION,
  useDraft,
  recallDraftDecision,
  rememberDraftDecision,
  type DraftFields,
  type DraftPayload,
  type DraftRemote,
} from '@/composables/useDraft'
import { formatRelativeTime } from '@/utils/format'
import MarkdownEditor from '@/components/MarkdownEditor.vue'
import type { PostCreateRequest, SimilarQuestionVO } from '@/types/api'

const route = useRoute()
const router = useRouter()
const meta = useMetaStore()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const loadingDetail = ref(false)

/** 有 id 参数即为编辑模式 */
const editId = computed(() => (route.params.id ? Number(route.params.id) : null))
const isEdit = computed(() => editId.value !== null)
const pageTitle = computed(() => (isEdit.value ? '编辑帖子' : '发布帖子'))

const form = reactive<{
  title: string
  content: string
  boardId: number | undefined
  type: number
  tags: string[]
}>({
  title: '',
  content: '',
  boardId: undefined,
  // `?type=1` = 从「创作 → 提个问题」进来，预选「提问」（下方 watch 负责后续变化）
  type: route.query.type === '1' ? 1 : 0,
  tags: [],
})

/** 标签输入框的临时值（回车/逗号确认才入 tags 数组） */
const tagInput = ref('')

const TYPE_OPTIONS = [
  { value: 0, label: '讨论' },
  { value: 1, label: '提问' },
]

const rules: FormRules = {
  title: [
    { required: true, message: '请输入标题', trigger: 'blur' },
    { min: 4, max: 120, message: '标题长度 4~120 个字符', trigger: 'blur' },
  ],
  boardId: [{ required: true, message: '请选择板块', trigger: 'change' }],
  content: [
    { required: true, message: '请输入正文内容', trigger: 'blur' },
    { min: 10, max: 50000, message: '正文长度 10~50000 个字符', trigger: 'blur' },
  ],
}

/**
 * 顶栏「创作 ▾」会把意图带进 query：`/publish?type=1` = 提个问题。
 *
 * 为什么不是"只在 setup 里读一次"：用户已经在 /publish 页面上时再点一次
 * 「创作 → 提个问题」是**同一路由只换 query**，组件不会重建，只读一次等于没反应。
 * 用户显式点了"提问"，就以他的选择为准（编辑他人帖子时不动类型）。
 */
watch(
  () => route.query.type,
  (v) => {
    if (!isEdit.value) form.type = v === '1' ? 1 : 0
  },
)

/* ==================== 标签输入 ==================== */
/** ⚠️ 上限 5 与后端 PostCreateRequest 的 @Size(max = 5) 对齐 */
const MAX_TAGS = 5

function addTag() {
  const raw = tagInput.value.trim().replace(/^#/, '')
  if (!raw) return

  if (form.tags.length >= MAX_TAGS) {
    ElMessage.warning(`最多添加 ${MAX_TAGS} 个标签`)
    tagInput.value = ''
    return
  }
  // 后端 t_tag 的 uk_name 是 utf8mb4_unicode_ci（大小写不敏感），
  // 所以前端去重也必须忽略大小写，否则会出现"Java"和"java"两个视觉重复的标签
  if (form.tags.some((t) => t.toLowerCase() === raw.toLowerCase())) {
    ElMessage.warning('标签已存在')
    tagInput.value = ''
    return
  }
  form.tags.push(raw)
  tagInput.value = ''
}

function removeTag(tag: string) {
  form.tags = form.tags.filter((t) => t !== tag)
}

function onTagKeydown(e: KeyboardEvent) {
  // 回车或逗号都视为确认；注意中文输入法组词时不能触发
  if (e.key === 'Enter' || e.key === ',') {
    if ((e as KeyboardEvent & { isComposing?: boolean }).isComposing) return
    e.preventDefault()
    addTag()
    return
  }
  // 空输入时按退格删掉最后一个标签，符合常见输入框习惯
  if (e.key === 'Backspace' && !tagInput.value && form.tags.length) {
    form.tags.pop()
  }
}

/** 失焦时若输入框有内容也确认掉，避免用户以为已添加 */
function onTagBlur() {
  if (tagInput.value.trim()) addTag()
}

/* ==================== 草稿（本地 + 云端） ==================== */

/** 草稿归属：新建稿与各帖的编辑稿互不干扰（同一字符串同时是本地键与远端 scope） */
const draftScope = () => (isEdit.value ? `edit:${editId.value}` : 'new')

/**
 * 服务端草稿通道 —— 只有登录后才存在。
 *
 * `/publish` 与 `/posts/:id/edit` 都要求登录（游客会被守卫拦到登录页，
 * 浏览器实测确认），所以正常路径下这里的 remote 一定存在；
 * 之所以仍保留"不传 remote"的形态，是为了让 useDraft 保持"纯本地也能跑"的性质
 * （验收脚本、将来可能的匿名草稿都用得上），而不是把登录依赖写死进 composable。
 *
 * 真正会在生产里走到的降级是**请求失败**：会话中途失效、后端波动、限流 ——
 * 那些情况下远端这一路静默失败（silent），本地草稿照常落盘，用户不会被迫停止写作。
 */
const remoteDraftSync: DraftRemote | undefined = auth.isLoggedIn
  ? {
      load: async (scope) => {
        const d = await draftApi.get(scope)
        if (!d) return null
        return {
          version: DRAFT_VERSION,
          title: d.title ?? '',
          content: d.content ?? '',
          boardId: d.boardId ?? null,
          type: d.type ?? 0,
          tags: d.tags ?? [],
          // ⚠️ 用服务端给的 epoch 与本地时间比新旧，不要解析 updateTime 字符串：
          //    它不带时区标记，在非 +08 环境按本地时区解析会整体偏移，导致误判
          updatedAt: d.updateTimeEpoch ?? 0,
        }
      },
      save: async (scope, payload) => {
        await draftApi.save({
          scope,
          title: payload.title,
          content: payload.content,
          boardId: payload.boardId ?? undefined,
          type: payload.type,
          tags: payload.tags,
        })
      },
      clear: (scope) => draftApi.remove(scope),
    }
  : undefined

const draftCtl = useDraft({
  scope: draftScope,
  snapshot: (): DraftFields => ({
    title: form.title,
    content: form.content,
    boardId: form.boardId ?? null,
    type: form.type,
    tags: [...form.tags],
  }),
  remote: remoteDraftSync,
})

/** 把草稿内容灌回表单（本地草稿与云端草稿共用） */
function applyDraft(d: DraftPayload) {
  form.title = d.title
  form.content = d.content
  form.type = d.type
  form.tags = [...d.tags]
  if (d.boardId !== null) form.boardId = d.boardId
}

/**
 * 恢复提示。
 *
 * 内容与服务端回填结果一致时不必打扰用户，直接清掉（可能是"恢复后没改又离开"，
 * 也可能是编辑稿与帖子内容一致）。
 *
 * `?resume=1`（从草稿箱点「继续编辑」进来）表示用户意图已经明确 ——
 * 不再弹确认框直接恢复，并把该参数从 URL 抹掉：否则刷新页面会再次静默覆盖
 * 用户当前的输入，而"静默覆盖"恰恰是最不该有的行为。
 *
 * ★ 会话态（本轮）：用户对"某一版草稿"表过态之后，本会话内不再重复询问 ——
 *   - 选过「恢复」→ 再次进入时**静默恢复**（不回问，也不让内容白留在草稿里）；
 *   - 关掉对话框（还没决定）→ 保留草稿、不再打扰；
 *   草稿被继续编辑出新版本、或换了一个标签页会话时，照常询问
 *   （指纹规则见 useDraft.draftDecisionKey）。此前"关掉对话框 → 再进又弹"
 *   是备注 20 ⑤ 记录的已知边界，这里关闭它。
 */
async function offerDraftRestore(pending: DraftPayload | null) {
  if (!pending) return

  const sameAsLoaded =
    pending.title === form.title &&
    pending.content === form.content &&
    pending.type === form.type
  if (sameAsLoaded) {
    draftCtl.clear()
    return
  }

  if (route.query.resume === '1') {
    applyDraft(pending)
    // 显式意图也计入会话态：否则"继续编辑后离开、再从创作进入"会被同一版草稿再问一次
    rememberDraftDecision(draftScope(), pending, 'restored')
    const { resume: _resume, ...rest } = route.query
    router.replace({ query: rest })
    ElMessage.success('草稿已恢复')
    return
  }

  const decided = recallDraftDecision(draftScope(), pending)
  if (decided === 'restored') {
    applyDraft(pending)
    ElMessage.success('草稿已恢复')
    return
  }
  if (decided === 'kept') return

  try {
    await ElMessageBox.confirm(
      `检测到 ${formatRelativeTime(new Date(pending.updatedAt).toISOString())}自动保存的草稿，是否恢复？`,
      '恢复草稿',
      {
        confirmButtonText: '恢复',
        cancelButtonText: '丢弃',
        type: 'info',
        // 区分"点丢弃"与"直接关掉对话框"：前者清草稿，后者保留（用户只是还没决定）
        distinguishCancelAndClose: true,
      },
    )
    applyDraft(pending)
    rememberDraftDecision(draftScope(), pending, 'restored')
    ElMessage.success('草稿已恢复')
  } catch (action) {
    if (action === 'cancel') {
      // 「丢弃」= 明确不要了：草稿被删除，无状态可记（下次没有草稿可问）
      draftCtl.clear()
    } else {
      // 直接关掉对话框 = "还没决定"：保留草稿，但记住"本会话别再问了"
      rememberDraftDecision(draftScope(), pending, 'kept')
    }
  }
}

/* ==================== 数据加载 ==================== */
onMounted(async () => {
  await meta.loadBoards()

  if (isEdit.value) {
    loadingDetail.value = true
    try {
      const detail = await postApi.detail(editId.value!)
      if (detail.canEdit === false) {
        ElMessage.error('你没有权限编辑这篇帖子')
        router.replace({ name: 'post-detail', params: { id: editId.value } })
        return
      }
      form.title = detail.title
      form.content = detail.content
      form.boardId = detail.boardId
      form.type = detail.type
      form.tags = (detail.tags ?? []).map((t) => t.name)
    } catch {
      router.replace({ name: 'home' })
      return
    } finally {
      loadingDetail.value = false
    }
  } else if (meta.boards.length) {
    // 新建时默认选中第一个板块，省一次点击
    form.boardId = meta.boards[0].id
  }

  // 初始回填完成 → 询问是否恢复草稿 → 再开始自动保存。
  // 顺序不能反：先 start() 的话，上面这次回填会被当成"用户输入"写盘，
  // 反而把上次真正的草稿覆盖掉（useDraft 的基线比较能挡住一部分，
  // 但"先决策后开闸"语义更清楚）。
  // M4-15：候选来自本地与云端两份，peekNewest 取较新的一份；
  // 未登录时 remote 不参与，行为与 M2-10 完全一致。
  const pending = await draftCtl.peekNewest()
  await offerDraftRestore(pending)
  draftCtl.start()
})

/* ==================== 相似问题提示（M3-9 落点；M3-5 的 /ai/similar-questions）==================== */

/**
 * 边写标题边问"站内有没有人说过这件事"。
 *
 * 四条刻意的约束：
 *  1. **只在新建稿**：编辑已有帖子时，返回的第一条必然是它自己（"与你相似的是你自己"），毫无意义；
 *  2. **静默请求**：这是辅助信息，失败（含 42901 限流）的正确表现是"没有提示"，
 *     而不是在写作过程中弹提示条 —— 口径与草稿自动保存一致；
 *  3. **去抖 800ms + 记住上次问过的文本**：按输入节拍算，一次编辑会话可能几十次调用；
 *     同一段文本不重复问，额度花在真正的新内容上（后端另有 300 次/小时的兜底）；
 *  4. **点提示开新标签页**：当前页的草稿一个字都不能动 —— 这正是"新标签页"存在的理由。
 */
const hints = ref<SimilarQuestionVO[]>([])
let hintTimer: ReturnType<typeof setTimeout> | null = null
/** 上次真正发出去的文本（在定时器里赋值：中间态的输入不该被记为"问过"） */
let lastAskedTitle = ''

watch(
  () => form.title,
  (title) => {
    if (isEdit.value) return
    const text = title.trim()
    if (text === lastAskedTitle) return

    if (hintTimer) clearTimeout(hintTimer)
    if (text.length < 4) {
      // 太短问不出东西（后端 <2 字直接返回空）；标题清空时顺手把旧提示也清掉，避免过时
      if (!text) {
        hints.value = []
        lastAskedTitle = ''
      }
      return
    }
    hintTimer = setTimeout(async () => {
      lastAskedTitle = text
      try {
        hints.value = await aiApi.similarQuestions(text)
      } catch {
        hints.value = [] // 静默降级：拿不到就当站内没有相似讨论
      }
    }, 800)
  },
)

onBeforeUnmount(() => {
  if (hintTimer) clearTimeout(hintTimer)
})

/* ==================== 提交 ==================== */
async function onSubmit() {
  if (!formRef.value) return

  // 标签输入框里还有没确认的内容，先收编，避免用户以为加上了
  if (tagInput.value.trim()) addTag()

  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  if (form.content.trim().length < 10) {
    ElMessage.warning('正文至少 10 个字符')
    return
  }

  submitting.value = true
  try {
    const payload: PostCreateRequest = {
      title: form.title.trim(),
      content: form.content,
      boardId: form.boardId!,
      type: form.type,
      tags: form.tags,
    }

    const result = isEdit.value
      ? await postApi.update(editId.value!, payload)
      : await postApi.create(payload)

    // 发布成功 → 草稿的使命结束。必须在跳转前清掉：
    // clear() 会把基线重置为当前表单，这样下面 replace 触发的
    // 离开拦截与卸载落盘都不会把内容再写回 localStorage
    draftCtl.clear()
    allowLeave = true
    ElMessage.success(isEdit.value ? '修改已保存' : '发布成功')
    router.replace({ name: 'post-detail', params: { id: result.id } })
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

/** 离开守卫的免打扰开关：程序性跳转（发布成功、主动放弃）不再弹确认 */
let allowLeave = false

/**
 * 离开编辑页前的确认。
 *
 * 用路由守卫而不是只在"取消"按钮上做确认：用户可能直接点顶栏导航、
 * 或者按浏览器后退离开 —— 那些路径根本不会经过按钮。
 * 已输入的内容会由 useDraft 在卸载时落盘，所以这里只需让用户知情。
 */
onBeforeRouteLeave(async () => {
  if (allowLeave || !draftCtl.isDirty()) return true
  try {
    await ElMessageBox.confirm(
      auth.isLoggedIn
        ? '已输入的内容会自动保存为草稿，并同步到云端 —— 换台设备登录后也能接着写。'
        : '已输入的内容会自动保存为草稿，下次进入本页可以继续编辑。',
      '确认离开？',
      { confirmButtonText: '离开', cancelButtonText: '继续编辑', type: 'warning' },
    )
    return true
  } catch {
    return false
  }
})

/** 离开前确认 —— 避免用户误点导航丢草稿 */
async function onCancel() {
  if (draftCtl.isDirty()) {
    try {
      await ElMessageBox.confirm('放弃未保存的修改？', '确认放弃', {
        confirmButtonText: '放弃',
        cancelButtonText: '继续编辑',
        type: 'warning',
      })
    } catch {
      return
    }
  }
  // 用户明确放弃 → 草稿一并清掉，否则下次进来又会提示恢复
  draftCtl.clear()
  allowLeave = true
  if (isEdit.value) {
    router.push({ name: 'post-detail', params: { id: editId.value } })
  } else {
    router.push({ name: 'home' })
  }
}
</script>

<template>
  <div class="container-narrow editor-wrap">
    <header class="editor-head">
      <h1 class="editor-title">{{ pageTitle }}</h1>
      <p class="editor-sub">
        支持 Markdown 语法，右侧实时预览。代码块请标注语言以获得正确高亮样式。
      </p>
    </header>

    <div v-if="loadingDetail" class="editor-loading" aria-busy="true">正在加载帖子内容…</div>

    <el-form
      v-else
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      class="editor-form"
      @submit.prevent="onSubmit"
    >
      <!-- 标题 -->
      <el-form-item label="标题" prop="title">
        <el-input
          v-model="form.title"
          placeholder="一句话讲清你要讨论什么（4~120 字）"
          maxlength="120"
          show-word-limit
          size="large"
        />
      </el-form-item>

      <!-- 相似问题提示（M3-9 落点 / M3-5 接口）：站内已有相似讨论时给三条入口。
           ⚠️ 一律 target="_blank" —— 当前页还写着草稿，绝不能被这次点击带走 -->
      <div v-if="!isEdit && hints.length" class="similar-hints">
        <span class="similar-hints-label">站内已有相似讨论：</span>
        <ul class="similar-hints-list">
          <li v-for="h in hints" :key="h.postId">
            <router-link
              :to="{ name: 'post-detail', params: { id: h.postId } }"
              target="_blank"
              rel="noopener"
              class="similar-hint"
            >
              {{ h.title }}
              <span v-if="h.boardName" class="similar-hint-board">{{ h.boardName }}</span>
            </router-link>
          </li>
        </ul>
      </div>

      <!-- 板块 + 类型 -->
      <div class="field-row">
        <el-form-item label="板块" prop="boardId" class="field-grow">
          <el-select v-model="form.boardId" placeholder="选择板块" class="full-width">
            <el-option
              v-for="board in meta.boards"
              :key="board.id"
              :label="board.name"
              :value="board.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="类型" class="field-fixed">
          <el-radio-group v-model="form.type">
            <el-radio-button v-for="t in TYPE_OPTIONS" :key="t.value" :value="t.value">
              {{ t.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
      </div>

      <!-- 标签 -->
      <el-form-item label="标签（最多 5 个，回车确认）">
        <div class="tag-field">
          <span v-for="tag in form.tags" :key="tag" class="tag-chip">
            #{{ tag }}
            <button
              type="button"
              class="chip-remove"
              :aria-label="`移除标签 ${tag}`"
              @click="removeTag(tag)"
            >
              ×
            </button>
          </span>

          <input
            v-model="tagInput"
            class="tag-input"
            type="text"
            :placeholder="form.tags.length >= MAX_TAGS ? '已达上限' : '如 java、spring-boot…'"
            :disabled="form.tags.length >= MAX_TAGS"
            @keydown="onTagKeydown"
            @blur="onTagBlur"
          />
        </div>
        <p class="field-hint">标签用于聚合同主题讨论，大小写不敏感（java 与 Java 视为同一个）</p>
      </el-form-item>

      <!-- 正文 -->
      <el-form-item label="正文" prop="content">
        <MarkdownEditor v-model="form.content" :min-rows="18" />
      </el-form-item>

      <!-- 操作 -->
      <div class="editor-actions">
        <el-button type="primary" size="large" :loading="submitting" @click="onSubmit">
          {{ isEdit ? '保存修改' : '发布' }}
        </el-button>
        <el-button size="large" @click="onCancel">取消</el-button>
      </div>
    </el-form>
  </div>
</template>

<style scoped>
.editor-wrap {
  padding-bottom: var(--space-8);
}

/* ---------- 相似问题提示（M3-9）---------- */
.similar-hints {
  margin: calc(var(--space-2) * -1) 0 var(--space-4);
  padding: var(--space-3);
  background: var(--color-muted);
  border: 1px solid var(--color-border-subtle);
  border-left: 3px solid var(--color-accent);
  border-radius: var(--radius-md);
}

.similar-hints-label {
  font-size: var(--text-xs);
  color: var(--color-muted-foreground);
}

.similar-hints-list {
  list-style: none;
  margin-top: var(--space-2);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.similar-hint {
  display: inline-flex;
  align-items: baseline;
  gap: var(--space-2);
  font-size: var(--text-sm);
  color: var(--color-foreground);
  text-decoration: none;
}

.similar-hint:hover {
  color: var(--color-accent);
}

.similar-hint-board {
  flex-shrink: 0;
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
}

.editor-head {
  margin-bottom: var(--space-5);
}

.editor-title {
  font-size: var(--text-2xl);
  letter-spacing: -0.02em;
}

.editor-sub {
  margin-top: var(--space-2);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
}

.editor-loading {
  padding: var(--space-8);
  text-align: center;
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  background: var(--color-card);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
}

.editor-form {
  padding: var(--space-6);
  background: var(--color-card);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
}

/* 板块与类型同一行；窄屏自动换行 */
.field-row {
  display: flex;
  gap: var(--space-5);
  flex-wrap: wrap;
}

.field-grow {
  flex: 1;
  min-width: 220px;
}

.field-fixed {
  flex: 0 0 auto;
}

.full-width {
  width: 100%;
}

/* ---------- 标签输入 ---------- */
.tag-field {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  width: 100%;
  min-height: 40px;
  padding: var(--space-1) var(--space-2);
  background: var(--color-muted);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  transition: border-color var(--transition-fast);
}

.tag-field:focus-within {
  border-color: var(--color-ring);
}

.tag-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: 2px var(--space-2);
  font-size: var(--text-xs);
  color: var(--color-accent);
  background: var(--color-accent-soft);
  border: 1px solid var(--color-accent-border);
  border-radius: var(--radius-sm);
}

.chip-remove {
  padding: 0;
  width: 14px;
  height: 14px;
  display: grid;
  place-items: center;
  font-size: 14px;
  line-height: 1;
  color: inherit;
  background: none;
  border: none;
  border-radius: var(--radius-full);
  opacity: 0.7;
}

.chip-remove:hover {
  opacity: 1;
  /* 拖拽/激活态的加浓底：用 accent 的描边色当底（同色系，透明度更重一档） */
  background: var(--color-accent-border);
}

.tag-input {
  flex: 1;
  min-width: 140px;
  padding: var(--space-2);
  font-family: inherit;
  font-size: var(--text-sm);
  color: var(--color-foreground);
  background: none;
  border: none;
  outline: none;
}

.tag-input::placeholder {
  color: var(--color-subtle-foreground);
}

.field-hint {
  margin-top: var(--space-2);
  font-size: var(--text-xs);
  line-height: 1.5;
  color: var(--color-subtle-foreground);
}

/* ---------- 操作区 ---------- */
.editor-actions {
  display: flex;
  gap: var(--space-3);
  margin-top: var(--space-6);
  padding-top: var(--space-5);
  border-top: 1px solid var(--color-border-subtle);
}

@media (max-width: 640px) {
  .editor-form {
    padding: var(--space-4);
  }

  .editor-title {
    font-size: var(--text-xl);
  }

  .editor-actions {
    flex-direction: column-reverse;
  }

  .editor-actions .el-button {
    width: 100%;
    margin-left: 0;
  }
}
</style>
