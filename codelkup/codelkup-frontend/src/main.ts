import { createApp } from 'vue'
import { createPinia } from 'pinia'

/* ⚠️ 2026-09-29 性能清理的**实测结论**：Element Plus 保持全量注册。
   曾尝试"删掉 app.use(ElementPlus) 改走 vite 按需"（AutoImport/Components 已配），
   但打包实测：按需 chunk 999,700 B ≈ 全量 1,000,640 B —— 全站 160 处 el-* 已用到
   EP 的绝大部分组件，"按需"没有可省的余地，反而把注册方式换成一条较少被验证的路径。
   **回滚，保持全量**（真收益在别处：字体 -4 个 woff2 ≈95KB、首屏不再等一次 API 往返）。 */
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
// CSS 必须保持全量且在 tokens.css 之前：EP 主题是"CSS 变量 + 后到者胜"，
// tokens 里的 --el-* 覆写依赖这个顺序（按需 CSS 分散在组件模块、顺序不可控）。
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
     JetBrains Mono 400/500/600 —— 代码块 / 编号 / 数字
   （2026-09-29 性能清理：删掉 Inter 400/500/600/700 —— 它只是"万一 Plex 未加载"的
     回退，实测永远轮不到（同源字体必到），却全程带着 4 个 woff2 ≈95KB 进构建产物。）
   ============================================================ */
import '@fontsource/ibm-plex-sans/latin-400.css'
import '@fontsource/ibm-plex-sans/latin-500.css'
import '@fontsource/ibm-plex-sans/latin-600.css'
import '@fontsource/ibm-plex-sans/latin-700.css'
import '@fontsource/jetbrains-mono/latin-400.css'
import '@fontsource/jetbrains-mono/latin-500.css'
import '@fontsource/jetbrains-mono/latin-600.css'

import App from './App.vue'
import router from './router'

// ⚠️ 设计令牌必须在 Element Plus 样式**之后**引入，
//    否则我们对 Element 变量的覆写会被它的默认值盖掉
import './assets/styles/tokens.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 2026-09-29 性能清理：不再在挂载前等待 /users/me（省一个 RTT 的白屏）。
// 登录态校验由 router.beforeEach 的 `await auth.fetchCurrentUser()` 保证 ——
// 首次导航在首帧前完成，不会闪"未登录界面"；这里也不必重复请求。
app.mount('#app')
