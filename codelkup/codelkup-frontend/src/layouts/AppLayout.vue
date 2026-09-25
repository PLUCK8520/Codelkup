<script setup lang="ts">
/**
 * 全局布局：顶部导航 + 内容区。
 *
 * 结构：Header（sticky）→ 主内容 → Footer
 * 响应式：<768px 时侧栏收起、导航折行（详见 style 中的断点）
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'
import UserAvatar from '@/components/UserAvatar.vue'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const notificationStore = useNotificationStore()

const keyword = ref('')
const mobileNavOpen = ref(false)

const searchPlaceholder = '搜索帖子，支持中文关键词…'

/** 提交搜索 → 跳转到搜索页。关键词放 URL，结果可分享、可刷新、可前进后退 */
function onSearch() {
  const q = keyword.value.trim()
  if (!q) {
    ElMessage.info('请输入搜索关键词')
    return
  }
  router.push({ name: 'search', query: { keyword: q } })
  mobileNavOpen.value = false
}

// 在搜索页时把输入框与 URL 同步（从详情页点返回时能回显上次搜的词）。
// 只在搜索页同步：否则用户在首页搜索框输入到一半、点了别的链接，
// 输入内容会被 URL 里的空值清掉。
watch(
  () => [route.name, route.query.keyword],
  () => {
    if (route.name === 'search') {
      keyword.value = String(route.query.keyword ?? '')
    }
  },
)

// 登录态变化时维护未读角标：
// 登录 → 拉一次；退出 → 清零（否则会把上个账号的数字带给下一个人）
watch(
  () => auth.isLoggedIn,
  (loggedIn) => {
    if (loggedIn) {
      notificationStore.refreshUnread()
    } else {
      notificationStore.reset()
    }
  },
  { immediate: true },
)

onMounted(() => {
  // 硬刷新后 auth 可能还在恢复中，这里补一次（fetchCurrentUser 完成后 isLoggedIn 会变）
  if (auth.isLoggedIn) {
    notificationStore.refreshUnread()
  }
})

async function onLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return // 用户取消
  }
  await auth.logout()
  ElMessage.success('已退出登录')
  router.push({ name: 'home' })
}

function onCreate() {
  if (!auth.isLoggedIn) {
    ElMessage.warning('请先登录后再发帖')
    router.push({ name: 'login', query: { redirect: '/publish' } })
    return
  }
  router.push({ name: 'post-create' })
}

const isActive = computed(() => (name: string) => route.name === name)
</script>

<template>
  <div class="app-shell">
    <!-- ==================== 顶部导航 ==================== -->
    <header class="app-header">
      <div class="container header-inner">
        <!-- Logo -->
        <router-link :to="{ name: 'home' }" class="brand">
          <span class="brand-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor"
                 stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="16 18 22 12 16 6" />
              <polyline points="8 6 2 12 8 18" />
            </svg>
          </span>
          <span class="brand-name">Codelkup</span>
        </router-link>

        <!-- 主导航 -->
        <nav class="main-nav" :class="{ open: mobileNavOpen }">
          <router-link :to="{ name: 'home' }" class="nav-link"
                       :class="{ active: isActive('home') }">首页</router-link>
          <router-link to="/?sort=hot" class="nav-link">热门</router-link>
          <router-link to="/?sort=active" class="nav-link">最新回复</router-link>
        </nav>

        <!-- 搜索 -->
        <div class="search-box">
          <el-input
            v-model="keyword"
            :placeholder="searchPlaceholder"
            clearable
            @keyup.enter="onSearch"
          >
            <template #prefix>
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor"
                   stroke-width="2" stroke-linecap="round">
                <circle cx="11" cy="11" r="8" />
                <path d="m21 21-4.35-4.35" />
              </svg>
            </template>
          </el-input>
        </div>

        <!-- 右侧操作区 -->
        <div class="header-actions">
          <el-button type="primary" class="btn-publish" @click="onCreate">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor"
                 stroke-width="2.4" stroke-linecap="round" style="margin-right: 5px">
              <path d="M12 5v14M5 12h14" />
            </svg>
            发布
          </el-button>

          <!-- 通知铃铛（仅登录后显示）。角标数字由 aria-hidden 隐藏，
               未读数已写进按钮的 aria-label，避免屏幕阅读器重复播报 -->
          <button
            v-if="auth.isLoggedIn"
            type="button"
            class="notify-btn"
            :aria-label="
              notificationStore.unread > 0
                ? `通知，${notificationStore.unread} 条未读`
                : '通知，无未读'
            "
            @click="router.push({ name: 'notifications' })"
          >
            <svg
              viewBox="0 0 24 24"
              width="18"
              height="18"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
              <path d="M13.73 21a2 2 0 0 1-3.46 0" />
            </svg>
            <span
              v-if="notificationStore.unread > 0"
              class="notify-badge"
              aria-hidden="true"
            >{{ notificationStore.unread > 99 ? '99+' : notificationStore.unread }}</span>
          </button>

          <!-- 已登录 -->
          <el-dropdown v-if="auth.isLoggedIn" trigger="click" placement="bottom-end">
            <button class="user-trigger" type="button" aria-label="用户菜单">
              <UserAvatar :src="auth.user?.avatar" :name="auth.user?.nickname" :size="30" />
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="router.push({ name: 'user-profile', params: { id: auth.user!.id } })">
                  我的主页
                </el-dropdown-item>
                <el-dropdown-item @click="router.push({ name: 'settings' })">
                  账号设置
                </el-dropdown-item>
                <el-dropdown-item divided @click="onLogout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

          <!-- 未登录 -->
          <template v-else>
            <el-button text class="btn-ghost" @click="router.push({ name: 'login' })">登录</el-button>
            <el-button class="btn-register" @click="router.push({ name: 'register' })">注册</el-button>
          </template>

          <!-- 移动端菜单按钮 -->
          <button class="nav-toggle" type="button" aria-label="展开导航"
                  @click="mobileNavOpen = !mobileNavOpen">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor"
                 stroke-width="2" stroke-linecap="round">
              <path d="M3 6h18M3 12h18M3 18h18" />
            </svg>
          </button>
        </div>
      </div>
    </header>

    <!-- ==================== 主内容 ==================== -->
    <main class="app-main">
      <slot />
    </main>

    <!-- ==================== 页脚 ==================== -->
    <footer class="app-footer">
      <div class="container footer-inner">
        <span>Codelkup · 开发者技术社区</span>
        <span class="footer-meta">Spring Boot 3.5 · Vue 3 · MySQL ngram 全文检索</span>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

/* ---------- Header ---------- */
.app-header {
  position: sticky;
  top: 0;
  z-index: 100;
  /* 半透明 + 模糊：滚动时内容从下方穿过，比不透明纯色条更有层次。
     底部边界用极暗的 border-subtle，避免一条突兀的亮线。 */
  background: rgba(18, 27, 46, 0.82);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--color-border-subtle);
}

.header-inner {
  display: flex;
  align-items: center;
  gap: var(--space-5);
  height: 58px;
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-lg);
  font-weight: 700;
  color: var(--color-foreground);
  letter-spacing: -0.02em;
  flex-shrink: 0;
}

.brand:hover {
  color: var(--color-foreground);
}

/* 品牌标识：纯色方块 → 渐变块 + 外发光。
   这是页面上最重要的"品牌资产"，值得一点存在感；
   glow 用的是 accent 色，与全局强调色体系同源。 */
.brand-mark {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: var(--radius-md);
  background: linear-gradient(145deg, var(--color-accent) 0%, #6366f1 100%);
  color: #fff;
  box-shadow: 0 4px 16px -5px var(--color-accent-glow);
  transition: box-shadow var(--transition-base), transform var(--transition-fast);
}

.brand:hover .brand-mark {
  box-shadow: 0 6px 22px -5px var(--color-accent-glow);
  transform: translateY(-1px);
}

.main-nav {
  display: flex;
  align-items: center;
  gap: var(--space-1);
}

.nav-link {
  position: relative;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  font-size: var(--text-base);
  color: var(--color-muted-foreground);
  transition: color var(--transition-fast), background var(--transition-fast);
  white-space: nowrap;
}

.nav-link:hover {
  color: var(--color-foreground);
  background: var(--color-muted);
}

/* 当前项用"底部短线"标记而不是整块背景：
   背景块和 hover 态长得一样，用户分不清"选中"和"悬停"。
   短线是独立的视觉通道，两种状态可以同时表达。 */
.nav-link.active {
  color: var(--color-foreground);
  background: var(--color-accent-soft);
}

.nav-link.active::after {
  content: '';
  position: absolute;
  left: var(--space-3);
  right: var(--space-3);
  bottom: -1px;
  height: 2px;
  border-radius: var(--radius-full);
  background: var(--color-accent);
}

.search-box {
  flex: 1;
  max-width: 340px;
  margin-left: auto;
}

.search-box :deep(.el-input__wrapper) {
  background: var(--color-muted);
  box-shadow: none;
  border-radius: var(--radius-md);
}

.search-box :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--color-ring) inset;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-shrink: 0;
}

.btn-publish {
  font-weight: 500;
}

.btn-ghost {
  color: var(--color-muted-foreground);
}

.btn-ghost:hover {
  color: var(--color-foreground);
}

.btn-register {
  background: var(--color-muted);
  border-color: var(--color-border);
  color: var(--color-foreground);
}

.btn-register:hover {
  background: var(--color-elevated);
  border-color: var(--color-secondary);
  color: var(--color-foreground);
}

.user-trigger {
  display: grid;
  place-items: center;
  padding: 2px;
  background: none;
  border: 2px solid transparent;
  border-radius: var(--radius-full);
  transition: border-color var(--transition-fast);
}

.user-trigger:hover {
  border-color: var(--color-border);
}

/* ---------- 通知铃铛 ---------- */
.notify-btn {
  position: relative;
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  color: var(--color-muted-foreground);
  background: none;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  transition: all var(--transition-fast);
}

.notify-btn:hover {
  color: var(--color-foreground);
  background: var(--color-muted);
  border-color: var(--color-border);
}

/* 角标用绝对定位而非行内元素：出现/消失时不会把铃铛位置推来推去 */
.notify-badge {
  position: absolute;
  top: -2px;
  right: -2px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  font-size: 10px;
  font-weight: 600;
  line-height: 16px;
  color: var(--color-on-destructive);
  text-align: center;
  background: var(--color-destructive);
  border: 1.5px solid var(--color-background);
  border-radius: var(--radius-full);
  font-variant-numeric: tabular-nums;
}

.nav-toggle {
  display: none;
  padding: var(--space-2);
  background: none;
  border: none;
  color: var(--color-muted-foreground);
  border-radius: var(--radius-md);
}

/* ---------- Main ---------- */
.app-main {
  flex: 1;
  padding: var(--space-6) 0 var(--space-12);
}

/* ---------- Footer ---------- */
.app-footer {
  border-top: 1px solid var(--color-border-subtle);
  padding: var(--space-6) 0;
  margin-top: auto;
}

.footer-inner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-4);
  font-size: var(--text-sm);
  color: var(--color-subtle-foreground);
  flex-wrap: wrap;
}

.footer-meta {
  color: var(--color-border);
}

/* ---------- 响应式：平板 ---------- */
@media (max-width: 1024px) {
  .main-nav {
    display: none;
  }
  .search-box {
    max-width: none;
  }
}

/* ---------- 响应式：手机 ---------- */
@media (max-width: 768px) {
  .header-inner {
    gap: var(--space-3);
  }

  .brand-name {
    display: none;
  }

  .nav-toggle {
    display: grid;
    place-items: center;
  }

  /* 移动端导航展开为下拉面板 */
  .main-nav.open {
    display: flex;
    position: absolute;
    top: 58px;
    left: 0;
    right: 0;
    flex-direction: column;
    align-items: stretch;
    gap: 0;
    padding: var(--space-2);
    background: var(--color-elevated);
    border-bottom: 1px solid var(--color-border);
    box-shadow: var(--shadow-md);
  }

  .search-box {
    order: 3;
    flex-basis: 100%;
  }

  .header-inner {
    flex-wrap: wrap;
    height: auto;
    padding-top: var(--space-2);
    padding-bottom: var(--space-2);
  }

  .btn-publish span {
    display: none;
  }

  .footer-inner {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
