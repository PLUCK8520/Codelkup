<script setup lang="ts">
/**
 * 登录页（全屏，无导航）。
 *
 * 表单验证：前端只做"必填 / 格式"这类能在本地判定的校验，
 *          "邮箱是否存在 / 密码是否正确"一律交给后端 ——
 *          前端无法也不知道答案，而且后端做了防枚举（统一报"邮箱或密码错误"）。
 */
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import type { LoginRequest } from '@/types/api'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive<LoginRequest>({
  email: '',
  password: '',
})

const rules: FormRules<LoginRequest> = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, message: '密码至少 8 位', trigger: 'blur' },
  ],
}

async function onSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await auth.login({ email: form.email.trim(), password: form.password })
    ElMessage.success(`欢迎回来，${auth.user?.nickname || ''}`)

    // 登录后回到被拦截前的页面；没有则回首页
    const redirect = route.query.redirect as string | undefined
    router.replace(redirect || { name: 'home' })
  } catch {
    // 错误提示已由 request 拦截器统一弹出，这里只需恢复按钮状态
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-card">
      <!-- 品牌 -->
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

      <h1 class="auth-title">登录账号</h1>
      <p class="auth-sub">继续参与开发者技术讨论</p>

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
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
            show-password
            @keyup.enter="onSubmit"
          />
        </el-form-item>

        <el-button
          type="primary"
          class="auth-submit"
          :loading="loading"
          native-type="submit"
          @click="onSubmit"
        >
          登录
        </el-button>
      </el-form>

      <p class="auth-foot">
        还没有账号？
        <RouterLink :to="{ name: 'register', query: route.query }">立即注册</RouterLink>
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
    radial-gradient(900px 500px at 50% -10%, rgba(59, 130, 246, 0.14), transparent 70%),
    var(--color-background);
}

.auth-card {
  width: 100%;
  max-width: 400px;
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

@media (max-width: 480px) {
  .auth-card {
    padding: var(--space-6);
  }
}
</style>
