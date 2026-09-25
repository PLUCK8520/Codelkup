<script setup lang="ts">
/**
 * 应用根组件。
 *
 * 布局判断逻辑：登录/注册/404 用全屏无导航布局，其余页面套 AppLayout。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'

const route = useRoute()
const useLayout = computed(() => !route.meta.hideHeader)
</script>

<template>
  <AppLayout v-if="useLayout">
    <router-view v-slot="{ Component }">
      <!-- 同一路由组件切换（如翻页）保留实例不重挂载，减少闪烁 -->
      <component :is="Component" />
    </router-view>
  </AppLayout>

  <router-view v-else />
</template>
