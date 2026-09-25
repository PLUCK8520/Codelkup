import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ElMessage } from 'element-plus'

/**
 * 路由表。
 *
 * meta 约定：
 * - requiresAuth: 需要登录
 * - requiresAdmin: 需要管理员
 * - title: 页面标题
 * - hideHeader: 不显示顶部导航（登录/注册这类全屏页用）
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'home',
    component: () => import('@/views/home/HomeView.vue'),
    meta: { title: '首页' },
  },
  {
    path: '/posts/:id(\\d+)',
    name: 'post-detail',
    component: () => import('@/views/post/PostDetailView.vue'),
    meta: { title: '帖子详情' },
  },
  {
    path: '/publish',
    name: 'post-create',
    component: () => import('@/views/post/PostEditorView.vue'),
    meta: { title: '发布帖子', requiresAuth: true },
  },
  {
    path: '/posts/:id(\\d+)/edit',
    name: 'post-edit',
    component: () => import('@/views/post/PostEditorView.vue'),
    meta: { title: '编辑帖子', requiresAuth: true },
  },
  {
    path: '/boards/:slug',
    name: 'board',
    component: () => import('@/views/home/HomeView.vue'),
    meta: { title: '板块' },
  },
  {
    path: '/search',
    name: 'search',
    component: () => import('@/views/search/SearchView.vue'),
    meta: { title: '搜索' },
  },
  {
    path: '/notifications',
    name: 'notifications',
    component: () => import('@/views/notification/NotificationView.vue'),
    meta: { title: '通知', requiresAuth: true },
  },
  {
    path: '/users/:id(\\d+)',
    name: 'user-profile',
    component: () => import('@/views/user/UserProfileView.vue'),
    meta: { title: '个人主页' },
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('@/views/user/SettingsView.vue'),
    meta: { title: '账号设置', requiresAuth: true },
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { title: '登录', hideHeader: true },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/auth/RegisterView.vue'),
    meta: { title: '注册', hideHeader: true },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/error/NotFoundView.vue'),
    meta: { title: '页面不存在' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  // 切换页面回到顶部；浏览器前进后退保留位置
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition ?? { top: 0 }
  },
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // 首次进入应用时校验一次登录态（token 可能已过期/被拉黑）
  if (!auth.initialized) {
    await auth.fetchCurrentUser()
  }

  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    ElMessage.warning('请先登录')
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (to.meta.requiresAdmin && !auth.isAdmin) {
    ElMessage.error('需要管理员权限')
    return { name: 'home' }
  }

  // 已登录时不该再看到登录/注册页
  if ((to.name === 'login' || to.name === 'register') && auth.isLoggedIn) {
    return { name: 'home' }
  }

  return true
})

router.afterEach((to) => {
  const base = 'Codelkup'
  document.title = to.meta.title ? `${to.meta.title} · ${base}` : base
})

export default router
