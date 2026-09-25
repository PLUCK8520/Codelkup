<script setup lang="ts">
/**
 * 账号设置。
 *
 * 两块：资料编辑（昵称/简介/技术标签）+ 头像上传。
 * 邮箱只读展示 —— 改邮箱涉及重新验证，v2 规划，当前明确禁用而不是假装能改。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules, type UploadRawFile } from 'element-plus'
import { userApi } from '@/api/user'
import { useAuthStore } from '@/stores/auth'
import UserAvatar from '@/components/UserAvatar.vue'
import { formatDateTime } from '@/utils/format'
import type { ProfileUpdateRequest } from '@/types/api'

const auth = useAuthStore()
const router = useRouter()

const formRef = ref<FormInstance>()
const saving = ref(false)
const uploading = ref(false)
/** 本地预览用的 objectURL，未保存前不请求后端 */
const previewUrl = ref('')
const fileInputRef = ref<HTMLInputElement | null>(null)

/**
 * 本地表单模型。
 *
 * ⚠️ 刻意**不复用** `ProfileUpdateRequest` —— 那个类型是给"部分更新"用的，
 *    所有字段都是可选的（`nickname?: string`），因为接口允许只传要改的字段。
 *    但本页表单的三个字段在初始化时就都有值，运行期一定是 string。
 *    直接拿请求体的类型当表单类型，会让 `form.nickname` 被推断成
 *    `string | undefined`，提交时 `.trim()` 就报 TS18048。
 *
 * 分开定义后语义也更准确：**表单字段必填，请求体字段可选**，
 * 提交时再构造 ProfileUpdateRequest 即可（必填赋给可选是安全的）。
 */
interface ProfileForm {
  nickname: string
  bio: string
  techTags: string
}

const form = reactive<ProfileForm>({
  nickname: '',
  bio: '',
  techTags: '',
})

/** 与后端 @Size 约束对齐 */
const NICKNAME_MAX = 20
const BIO_MAX = 200
const TAGS_MAX = 100

/** 头像限制与后端 FileStorageService 一致：2MB，jpg/jpeg/png/webp */
const AVATAR_MAX_SIZE = 2 * 1024 * 1024
const AVATAR_ACCEPT = ['image/jpeg', 'image/png', 'image/webp']

const rules: FormRules = {
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { min: 2, max: NICKNAME_MAX, message: `昵称 2~${NICKNAME_MAX} 个字符`, trigger: 'blur' },
  ],
  bio: [{ max: BIO_MAX, message: `简介不超过 ${BIO_MAX} 个字符`, trigger: 'blur' }],
}

/** 原始资料快照，用于判断是否有改动（没有改动就不发请求） */
/**
 * 表单快照，用于「重置」与「有无改动」判断。
 *
 * ⚠️ 类型必须与表单一致（ProfileForm）而不是 ProfileUpdateRequest：
 *    它存的是表单当前值的**一份完整副本**，三个字段必然都有值。
 *    用可选字段的请求体类型会让 `original.bio` 变成 `string | undefined`，
 *    回填到表单时（onReset）就与必填的 form.bio 不兼容。
 */
let original: ProfileForm = { nickname: '', bio: '', techTags: '' }

onMounted(() => {
  const u = auth.user
  if (!u) {
    router.replace({ name: 'login', query: { redirect: '/settings' } })
    return
  }
  form.nickname = u.nickname ?? ''
  form.bio = u.bio ?? ''
  form.techTags = u.techTags ?? ''
  original = { nickname: form.nickname, bio: form.bio, techTags: form.techTags }
})

const dirty = computed(
  () =>
    form.nickname !== original.nickname ||
    form.bio !== original.bio ||
    form.techTags !== original.techTags,
)

/* ==================== 头像上传 ==================== */
function pickAvatar() {
  fileInputRef.value?.click()
}

function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  // 重置 input.value，否则选同一个文件不会再次触发 change
  input.value = ''
  if (file) doUpload(file)
}

/** el-upload 的 before-upload 校验（这里是手写 input，逻辑等价） */
function validate(file: File): string | null {
  if (!AVATAR_ACCEPT.includes(file.type)) {
    return '只支持 JPG / PNG / WebP 格式'
  }
  if (file.size > AVATAR_MAX_SIZE) {
    return `图片不能超过 ${AVATAR_MAX_SIZE / 1024 / 1024} MB`
  }
  return null
}

async function doUpload(file: File) {
  const err = validate(file)
  if (err) {
    ElMessage.error(err)
    return
  }

  // 本地先预览，避免等网络往返才看到变化
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = URL.createObjectURL(file)

  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    const updated = await userApi.uploadAvatar(fd)
    auth.patchUser(updated)
    ElMessage.success('头像已更新')
  } catch {
    // 失败时撤销预览，回到真实头像（拦截器已提示原因）
    if (previewUrl.value) {
      URL.revokeObjectURL(previewUrl.value)
      previewUrl.value = ''
    }
  } finally {
    uploading.value = false
  }
}

/** 供 el-upload 类型兼容占位（未使用组件，仅保留类型引用避免 TS 报未使用） */
const _rawFileType = null as UploadRawFile | null

/* ==================== 保存资料 ==================== */
async function onSave() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  if (!dirty.value) {
    ElMessage.info('资料没有变化')
    return
  }

  saving.value = true
  try {
    const payload: ProfileUpdateRequest = {
      nickname: form.nickname.trim(),
      bio: form.bio?.trim() || undefined,
      techTags: form.techTags?.trim() || undefined,
    }
    const updated = await userApi.updateMe(payload)
    auth.patchUser(updated)
    original = { nickname: form.nickname, bio: form.bio, techTags: form.techTags }
    ElMessage.success('资料已保存')
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

function onReset() {
  form.nickname = original.nickname
  form.bio = original.bio
  form.techTags = original.techTags
  formRef.value?.clearValidate()
}
</script>

<template>
  <div class="container-narrow settings-wrap">
    <header class="settings-head">
      <h1 class="settings-title">账号设置</h1>
      <p class="settings-sub">管理你的公开资料与头像</p>
    </header>

    <!-- ==================== 头像 ==================== -->
    <section class="settings-card">
      <h2 class="card-title">头像</h2>

      <div class="avatar-row">
        <UserAvatar
          :src="previewUrl || auth.user?.avatar"
          :name="auth.user?.nickname"
          :seed="auth.user?.id"
          :size="80"
        />

        <div class="avatar-actions">
          <el-button :loading="uploading" @click="pickAvatar">
            {{ uploading ? '上传中…' : '更换头像' }}
          </el-button>
          <p class="avatar-hint">
            支持 JPG / PNG / WebP，不超过 2 MB。<br />
            建议使用正方形图片，非正方形会被居中裁切。
          </p>
        </div>

        <!-- 隐藏的真实文件输入 -->
        <input
          ref="fileInputRef"
          type="file"
          accept="image/jpeg,image/png,image/webp"
          class="file-input"
          @change="onFileChange"
        />
      </div>
    </section>

    <!-- ==================== 资料 ==================== -->
    <section class="settings-card">
      <h2 class="card-title">公开资料</h2>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        class="settings-form"
        @submit.prevent="onSave"
      >
        <el-form-item label="邮箱">
          <el-input :model-value="auth.user?.email" disabled>
            <template #suffix>
              <span class="readonly-badge">不可修改</span>
            </template>
          </el-input>
          <p class="field-hint">邮箱用于登录与找回密码，如需更换请等待后续版本支持。</p>
        </el-form-item>

        <el-form-item label="昵称" prop="nickname">
          <el-input
            v-model="form.nickname"
            :maxlength="NICKNAME_MAX"
            show-word-limit
            placeholder="展示在你的帖子和评论旁"
          />
        </el-form-item>

        <el-form-item label="个人简介" prop="bio">
          <el-input
            v-model="form.bio"
            type="textarea"
            :rows="3"
            :maxlength="BIO_MAX"
            show-word-limit
            resize="vertical"
            placeholder="简单介绍你自己，比如技术方向、正在做的事"
          />
        </el-form-item>

        <el-form-item label="技术标签">
          <el-input
            v-model="form.techTags"
            :maxlength="TAGS_MAX"
            placeholder="用逗号分隔，如：Java, Spring Boot, Vue"
          />
          <p class="field-hint">这些标签会展示在你的个人主页，方便同好找到你。</p>
        </el-form-item>

        <div class="form-actions">
          <el-button type="primary" :loading="saving" :disabled="!dirty" @click="onSave">
            保存修改
          </el-button>
          <el-button :disabled="!dirty" @click="onReset">重置</el-button>
        </div>
      </el-form>
    </section>

    <!-- ==================== 账号信息 ==================== -->
    <section v-if="auth.user" class="settings-card">
      <h2 class="card-title">账号信息</h2>
      <dl class="info-list">
        <div class="info-row">
          <dt>用户 ID</dt>
          <dd class="mono">{{ auth.user.id }}</dd>
        </div>
        <div class="info-row">
          <dt>注册时间</dt>
          <dd>{{ formatDateTime(auth.user.createTime) }}</dd>
        </div>
        <div class="info-row">
          <dt>账号状态</dt>
          <dd>
            <span v-if="auth.user.status === 1" class="status status-ok">正常</span>
            <span v-else class="status status-bad">已停用</span>
          </dd>
        </div>
      </dl>
    </section>
  </div>
</template>

<style scoped>
.settings-wrap {
  padding-bottom: var(--space-8);
}

.settings-head {
  margin-bottom: var(--space-5);
}

.settings-title {
  font-size: var(--text-2xl);
  letter-spacing: -0.02em;
}

.settings-sub {
  margin-top: var(--space-2);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
}

.settings-card {
  padding: var(--space-6);
  margin-bottom: var(--space-4);
  background: var(--color-card);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
}

.card-title {
  margin-bottom: var(--space-5);
  padding-bottom: var(--space-3);
  font-size: var(--text-md);
  font-weight: 600;
  color: var(--color-foreground);
  border-bottom: 1px solid var(--color-border-subtle);
}

/* ---------- 头像 ---------- */
.avatar-row {
  display: flex;
  align-items: center;
  gap: var(--space-5);
}

.avatar-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
}

.avatar-hint {
  font-size: var(--text-xs);
  line-height: 1.6;
  color: var(--color-subtle-foreground);
}

/* 隐藏原生 file input —— 用按钮触发，保持一致外观 */
.file-input {
  display: none;
}

/* ---------- 表单 ---------- */
.settings-form :deep(.el-form-item) {
  margin-bottom: var(--space-5);
}

.readonly-badge {
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
  padding-right: var(--space-1);
}

.field-hint {
  margin-top: var(--space-2);
  font-size: var(--text-xs);
  line-height: 1.5;
  color: var(--color-subtle-foreground);
}

.form-actions {
  display: flex;
  gap: var(--space-3);
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-border-subtle);
}

/* ---------- 账号信息 ---------- */
.info-list {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.info-row {
  display: flex;
  justify-content: space-between;
  gap: var(--space-4);
  font-size: var(--text-sm);
}

.info-row dt {
  color: var(--color-subtle-foreground);
}

.info-row dd {
  margin: 0;
  color: var(--color-muted-foreground);
  text-align: right;
}

.mono {
  font-family: var(--font-mono);
  font-size: var(--text-xs);
}

.status {
  padding: 1px var(--space-2);
  font-size: var(--text-xs);
  border-radius: var(--radius-sm);
}

.status-ok {
  color: #86efac;
  background: rgba(16, 185, 129, 0.18);
}

.status-bad {
  color: #fca5a5;
  background: rgba(244, 63, 94, 0.18);
}

@media (max-width: 640px) {
  .settings-card {
    padding: var(--space-4);
  }

  .settings-title {
    font-size: var(--text-xl);
  }

  .avatar-row {
    flex-direction: column;
    align-items: flex-start;
  }

  .form-actions {
    flex-direction: column-reverse;
  }

  .form-actions .el-button {
    width: 100%;
    margin-left: 0;
  }
}
</style>
