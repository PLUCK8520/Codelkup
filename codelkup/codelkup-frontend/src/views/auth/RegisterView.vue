<script setup lang="ts">
/**
 * 注册页（全屏，无导航）。
 *
 * 邮箱是否已被占用：用 blur 时异步查一次 `/auth/check-email`。
 * ⚠️ 这只是**提升体验的预校验**，不能替代后端唯一索引 ——
 *    两次请求之间仍可能被别人注册掉，最终以后端 409 为准。
 */
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { authApi } from '@/api/user'
import { useAuthStore } from '@/stores/auth'
import type { RegisterRequest } from '@/types/api'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const formRef = ref<FormInstance>()
const loading = ref(false)
const checkingEmail = ref(false)
/** 异步校验结果，null 表示未知/未校验 */
const emailTaken = ref<boolean | null>(null)

const form = reactive<RegisterRequest & { confirmPassword: string }>({
  email: '',
  password: '',
  nickname: '',
  confirmPassword: '',
})

/** 邮箱异步校验：先跑本地格式校验，再问后端 */
async function validateEmail(_rule: unknown, value: string, callback: (e?: Error) => void) {
  if (!value) return callback(new Error('请输入邮箱'))
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) {
    return callback(new Error('邮箱格式不正确'))
  }

  checkingEmail.value = true
  try {
    const res = await authApi.checkEmail(value.trim())
    emailTaken.value = res.taken
    callback(res.taken ? new Error('该邮箱已被注册') : undefined)
  } catch {
    // 校验接口挂了不该阻塞注册 —— 后端唯一索引才是最终防线
    emailTaken.value = null
    callback()
  } finally {
    checkingEmail.value = false
  }
}

function validateConfirm(_rule: unknown, value: string, callback: (e?: Error) => void) {
  if (!value) return callback(new Error('请再次输入密码'))
  if (value !== form.password) return callback(new Error('两次输入的密码不一致'))
  callback()
}

const rules: FormRules = {
  email: [{ validator: validateEmail, trigger: 'blur' }],
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { min: 2, max: 20, message: '昵称长度 2~20 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    {
      // 与后端强密码规则保持一致：至少 8 位且含字母和数字
      pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,}$/,
      message: '密码至少 8 位，且需同时包含字母和数字',
      trigger: 'blur',
    },
  ],
  confirmPassword: [{ validator: validateConfirm, trigger: 'blur' }],
}

async function onSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    // 注册接口直接返回 token，等于注册即登录
    await auth.register({
      email: form.email.trim(),
      password: form.password,
      nickname: form.nickname.trim(),
    })
    ElMessage.success('注册成功，欢迎加入 Codelkup！')

    const redirect = route.query.redirect as string | undefined
    router.replace(redirect || { name: 'home' })
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-card">
      <RouterLink :to="{ name: 'home' }" class="auth-brand">
        <span class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor"
               stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="16 18 22 12 16 6" />
            <polyline points="8 6 2 12 8 18" />
          </svg>
        </span>
        <span class="brand-name">Codelkup</span>
      </RouterLink>

      <h1 class="auth-title">创建账号</h1>
      <p class="auth-sub">加入开发者技术社区，开始分享与提问</p>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        class="auth-form"
        @submit.prevent="onSubmit"
      >
        <el-form-item label="邮箱" prop="email">
          <el-input
            v-model="form.email"
            type="email"
            placeholder="you@example.com"
            autocomplete="email"
            clearable
          >
            <template v-if="checkingEmail" #suffix>
              <span class="checking">校验中…</span>
            </template>
          </el-input>
          <p v-if="emailTaken === false" class="field-hint ok">邮箱可以使用</p>
        </el-form-item>

        <el-form-item label="昵称" prop="nickname">
          <el-input
            v-model="form.nickname"
            placeholder="2~20 个字符，将展示在你的帖子和评论旁"
            autocomplete="nickname"
            maxlength="20"
            show-word-limit
            clearable
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="至少 8 位，包含字母和数字"
            autocomplete="new-password"
            show-password
          />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="再次输入密码"
            autocomplete="new-password"
            show-password
          />
        </el-form-item>

        <!-- 只保留 native-type="submit"：与 LoginView 同因（重复绑定会让一次点击发两个请求） -->
        <el-button
          type="primary"
          class="auth-submit"
          :loading="loading"
          native-type="submit"
        >
          注册
        </el-button>
      </el-form>

      <p class="auth-foot">
        已有账号？
        <RouterLink :to="{ name: 'login', query: route.query }">直接登录</RouterLink>
      </p>
    </div>
  </div>
</template>

<style scoped>
.auth-page {
  display: grid;
  place-items: center;
  min-height: 100vh;
  padding: var(--space-6);
  background:
    /* ★ v4：删掉页内径向光晕，改用全站统一的色彩底衬（玻璃需要它） */
    var(--color-background);
}

.auth-card {
  width: 100%;
  max-width: 420px;
  padding: var(--space-8);
  background: var(--color-card);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
}

.auth-brand {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-6);
  font-size: var(--text-lg);
  font-weight: 700;
  color: var(--color-foreground);
  letter-spacing: -0.02em;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: var(--radius-md);
  background: var(--color-accent-solid);
  color: #fff;
}

.auth-title {
  font-size: var(--text-xl);
}

.auth-sub {
  margin-top: var(--space-2);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
}

.auth-form {
  margin-top: var(--space-6);
}

.auth-submit {
  width: 100%;
  margin-top: var(--space-2);
}

.auth-foot {
  margin-top: var(--space-5);
  text-align: center;
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
}

.checking {
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
}

.field-hint {
  margin-top: var(--space-1);
  font-size: var(--text-xs);
  line-height: 1.4;
  color: var(--color-subtle-foreground);
}

.field-hint.ok {
  color: var(--color-success);
}

/* Element Plus 的校验信息默认位置会挤开布局，这里收紧间距 */
.auth-form :deep(.el-form-item) {
  margin-bottom: var(--space-5);
}

@media (max-width: 480px) {
  .auth-card {
    padding: var(--space-6);
  }
}
</style>
