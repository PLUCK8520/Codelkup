import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

/* ============================================================
   字体：自托管（@fontsource），不依赖任何外部 CDN
   ------------------------------------------------------------
   ⚠️ 2026-09-25 修订：此前 index.html 从 fonts.googleapis.com 加载字体，
      该域名在中国大陆无法直接访问 —— 结果是字体请求超时失败、
      浏览器静默回退到系统默认字体，设计时按 Inter/IBM Plex Sans
      调的排版全部落空（用户实际看到的是 Windows 默认的 Segoe UI + 雅黑）。
      同时 <link rel="preconnect"> 指向不可达域名还会拖慢首屏。

      改为从 npm 包引入：字体文件进 bundle，与站点同源加载，
      不再受任何外部网络环境影响。

   ⚠️ 必须在 tokens.css 之前引入：@fontsource 的 css 是 @font-face 声明，
      先声明后引用才不会有字体闪烁（FOUT）。

   ⚠️ 用 `latin-*` 入口而不是裸 `400.css`：
      裸入口会声明 inter 的**全部子集**（cyrillic / greek / vietnamese 等），
      虽然浏览器运行时靠 unicode-range 只会下载命中子集，
      但构建产物会把全部字形都打出来 —— 实测 dist 因此多出 40 个字体文件、
      膨胀到 3.1 MB。中文本来就不在 Inter 里（走系统字体栈），
      这里只需要 latin 子集。

   只引入实际用到的字重，避免打包无用的字形：
     IBM Plex Sans 400/500/600/700 —— 拉丁字母与数字（v5 起的界面主字体，
       IBM 为工程场景设计，机械感与横向节奏比 Inter 更有"工作台"气质）
     Inter 400/500/600/700 —— 保留为回退（万一 Plex 子集未加载）
     JetBrains Mono 400/500/600 —— 代码块 / 编号 / 数字
   ============================================================ */
import '@fontsource/ibm-plex-sans/latin-400.css'
import '@fontsource/ibm-plex-sans/latin-500.css'
import '@fontsource/ibm-plex-sans/latin-600.css'
import '@fontsource/ibm-plex-sans/latin-700.css'
import '@fontsource/inter/latin-400.css'
import '@fontsource/inter/latin-500.css'
import '@fontsource/inter/latin-600.css'
import '@fontsource/inter/latin-700.css'
import '@fontsource/jetbrains-mono/latin-400.css'
import '@fontsource/jetbrains-mono/latin-500.css'
import '@fontsource/jetbrains-mono/latin-600.css'

import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'

// ⚠️ 设计令牌必须在 Element Plus 样式**之后**引入，
//    否则我们对 Element 变量的覆写会被它的默认值盖掉
import './assets/styles/tokens.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 应用挂载前先校验登录态，避免"已登录用户闪一下未登录界面"
const auth = useAuthStore()
auth.fetchCurrentUser().finally(() => {
  app.mount('#app')
})
