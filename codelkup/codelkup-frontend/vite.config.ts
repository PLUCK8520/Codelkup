import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    // 按需自动引入 Element Plus 组件与 API，避免整包引入（体积从 ~1MB 降到几百 KB）
    AutoImport({
      imports: ['vue', 'vue-router', 'pinia'],
      resolvers: [ElementPlusResolver()],
      dts: 'src/types/auto-imports.d.ts',
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      // 本地通用组件也自动注册，模板里不必逐个 import
      dirs: ['src/components'],
      dts: 'src/types/components.d.ts',
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  // ⚠️ 依赖预打包缓存默认放在 node_modules/.vite。Vite 在"发现新依赖需要重新
  //    优化"时会先写 deps_temp_xxxx、再递归删掉旧目录；本机安全策略会拦截
  //    node_modules 下的递归删除（SAFE_DELETE_BULK_CONFIRM_REQUIRED），
  //    直接把 dev server 打崩。换成项目根的 .vite-cache 只是把问题挪了位置。
  //    真正的解法是让 Vite 不再做"发现即重优化"：
  //      - 用 `vite optimize` 预先烤好 deps
  //      - 启动加 --force（强制重建一次，之后命中缓存）
  //    缓存目录仍放在 node_modules 之外，便于手动清理。
  cacheDir: fileURLToPath(new URL('./.vite-cache', import.meta.url)),
  optimizeDeps: {
    // Element Plus 的消息/弹窗等按需引入会在运行期才被发现，导致触发重优化。
    // 这里显式列出，让它们在首次预打包时就进入缓存。
    include: [
      'vue',
      'vue-router',
      'pinia',
      'axios',
      'dayjs',
      'marked',
      'dompurify',
      'element-plus',
      'element-plus/es',
      'element-plus/es/components/base/style/css',
      'element-plus/es/components/button/style/css',
      'element-plus/es/components/input/style/css',
      'element-plus/es/components/form/style/css',
      'element-plus/es/components/form-item/style/css',
      'element-plus/es/components/select/style/css',
      'element-plus/es/components/option/style/css',
      'element-plus/es/components/dropdown/style/css',
      'element-plus/es/components/dropdown-menu/style/css',
      'element-plus/es/components/dropdown-item/style/css',
      'element-plus/es/components/message/style/css',
      'element-plus/es/components/message-box/style/css',
      'element-plus/es/components/alert/style/css',
      'element-plus/es/components/pagination/style/css',
      'element-plus/es/components/radio-group/style/css',
      'element-plus/es/components/radio-button/style/css',
      'element-plus/es/components/tooltip/style/css',
      'element-plus/es/components/upload/style/css',
    ],
  },
  server: {
    // ⚠️ 端口刻意避开 5173：那是另一个项目常用的默认端口，两个项目同时开着会出现
    //    "打开 localhost:5173 不知道是谁"的混乱；而且 favicon 是按**同源**缓存的，
    //    同端口复用会让浏览器继续显示上一个项目缓存的图标。
    //    strictPort = 端口被占时直接报错，而不是静默切到 5274 —— 静默切换比报错更难排查。
    port: 5273,
    strictPort: true,
    // 代理到后端 8085：前端用相对路径 /api 请求，开发期免去跨域配置
    // ⚠️ 后端 CORS 白名单已放行 http://localhost:5273，直连也可以，
    //    但走代理能让前端代码在生产/开发环境保持一致（都用 /api）
    proxy: {
      '/api': {
        target: 'http://localhost:8085',
        changeOrigin: true,
        // ⚠️ 必须把浏览器带的 Origin 一并处理掉，否则开发期会假跨域：
        //    浏览器对 POST 一定会发 Origin: http://localhost:5273，而 changeOrigin 又把
        //    Host 改写成 localhost:8085 —— 后端看到"Origin ≠ 请求自身来源"就按 CORS 处理，
        //    一旦白名单没跟上端口（改端口后忘了同步配置/重打包），登录等写接口就集体 403
        //    "Invalid CORS request"，而 GET 不带 Origin 所以看起来"只有登录坏了"（实测踩过）。
        //    代理存在的意义就是让开发环境像生产一样同源（nginx 同源反代时浏览器根本不发 Origin），
        //    所以这里把 Origin 去掉，让后端按普通同源请求处理。
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.removeHeader('origin')
          })
        },
      },
      '/uploads': {
        target: 'http://localhost:8085',
        changeOrigin: true,
      },
    },
  },
  build: {
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        // 拆包：Element Plus 体积大，单独成块，避免主包过大影响首屏
        manualChunks: {
          'element-plus': ['element-plus'],
          markdown: ['marked', 'dompurify'],
        },
      },
    },
  },
})
