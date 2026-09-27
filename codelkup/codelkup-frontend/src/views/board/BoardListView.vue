<script setup lang="ts">
/**
 * 板块总览。
 *
 * 10 个板块此前只活在首页右栏那一列窄列表里：描述没地方放，新用户也不知道
 * 每个板块到底是干什么的。这里给它们一个正脸，点进去就是 `/boards/:slug`
 * 的帖子列表（复用首页那套列表，未新增接口）。
 *
 * 数据走 meta store：板块是低频字典数据，全站只请求一次，与首页共用缓存。
 *
 * ★ V0.4 视觉与交互改版（本轮）：
 *   1. **图标锚点** —— 后端 `BoardVO.icon` 里存的是一整套 lucide 图标名
 *      （chat / server / layout / binary-tree / sparkles / database / terminal /
 *        briefcase / git-branch / message-square），一直没用上。现在按名字渲染
 *      等宽的 stroke 图标：卡片有了视觉锚点，10 张卡不再是一堆同质文字块。
 *      图标**单色**（默认弱化色、hover 转强调色），不做每板块一色 ——
 *      同屏 10 个不同色相是廉价感最直接的来源，见 tokens.css 的 v3 说明。
 *   2. **信息层级**：名字（16/600）→ 描述（13 弱化，两行截断兜底）→ 计数
 *      （等宽数字 + 小字单位）。计数不再单独占一行，卡片矮了约 1/3，
 *      10 张（4 行）更容易一屏看全。
 *   3. **交互**：整卡可点，hover 同时给五个通道的反馈（底 / 边 / 上移 / 图标 / 箭头位移），
 *      不依赖单一颜色变化；键盘焦点沿用全局 `:focus-visible` 焦点环。
 *      ⚠️ 本轮顺带修掉两个**全局**缺陷（都落在 tokens.css，影响全站）：
 *        - `.anim-in` 用 `fill-mode: both` 会把动画结束态永久压在元素上（动画优先级
 *          高于普通声明），于是卡片自己的 hover 位移全部失效 —— `.post-card`
 *          在首页/搜索/问答/标签/关注/收藏六处都是"背景变了但不浮起来"。改为 backwards。
 *        - `:focus-visible` 里强制 `border-radius: var(--radius-sm)`，会让任何可聚焦
 *          卡片一聚焦圆角就从 14px 缩到 6px。该声明已删除（outline 本就会跟随元素圆角）。
 *   4. **骨架屏延迟 180ms**：板块是缓存字典，多数时候 20ms 内就回来了，
 *      一进页面就闪一层骨架反而更廉价（ui-ux-pro-max：avoid flashing for
 *      near-instant work）。超时才显示，且骨架与真实卡片同形，避免布局跳动。
 */
import { computed, onMounted, ref } from 'vue'
import { useMetaStore } from '@/stores/meta'
import EmptyState from '@/components/EmptyState.vue'
import type { BoardVO } from '@/types/api'

const meta = useMetaStore()
const loading = ref(true)
const error = ref('')

/* ==================== 图标 ==================== */

/**
 * 板块图标：key = 后端 `t_board.icon` 里存的 lucide 图标名。
 *
 * 每个条目是一组 `<path d>`（24×24 坐标系，只描边不填充）——
 * 用路径数组而不是拼 SVG 字符串 + v-html：模板里 v-for 出 `<path>` 即可，
 * 全程不碰 `innerHTML`（与项目"渲染一律不拼 HTML"的约定一致）。
 */
const ICON_PATHS: Record<string, string[]> = {
  // message-circle：对话气泡
  chat: [
    'M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8z',
  ],
  // server：两层机架 + 指示灯
  server: [
    'M4 3h16a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2',
    'M4 14h16a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2',
    'M6 6.5h.01',
    'M6 17.5h.01',
  ],
  // layout：面板分栏
  layout: [
    'M4 3h16a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1',
    'M3 9h18',
    'M9 21V9',
  ],
  // network：根节点 + 两个子节点（对应"算法与数据结构"）
  'binary-tree': [
    'M9 2h6a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1',
    'M2 16h6a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H2a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1',
    'M16 16h6a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-6a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1',
    'M12 9v3',
    'M5 16v-2a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v2',
  ],
  // sparkles：一大一小两颗星
  sparkles: [
    'M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z',
    'M18.5 15.5l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z',
  ],
  // database：三层圆柱
  database: [
    'M12 2c4.97 0 9 1.34 9 3s-4.03 3-9 3-9-1.34-9-3 4.03-3 9-3',
    'M3 5v14c0 1.66 4.03 3 9 3s9-1.34 9-3V5',
    'M3 12c0 1.66 4.03 3 9 3s9-1.34 9-3',
  ],
  // terminal：提示符 + 下划线
  terminal: ['M4 17l6-6-6-6', 'M12 19h8'],
  // briefcase：公文包
  briefcase: [
    'M4 7h16a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2',
    'M16 7V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v2',
  ],
  // git-branch：分支
  'git-branch': [
    'M6 3v12',
    'M18 3a3 3 0 1 1 0 6 3 3 0 0 1 0-6',
    'M6 15a3 3 0 1 1 0 6 3 3 0 0 1 0-6',
    'M18 9a9 9 0 0 1-9 9',
  ],
  // message-square：方形消息
  'message-square': ['M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z'],
  // 兜底：四宫格（后端将来加了新板块而 icon 没配时，不至于空一块）
  fallback: ['M4 4h6v6H4z', 'M14 4h6v6h-6z', 'M14 14h6v6h-6z', 'M4 14h6v6H4z'],
}

/** icon 名可能为空或拼错，一律退到兜底图标 —— 页面不因为一个字段而缺角 */
function iconPaths(board: BoardVO): string[] {
  return ICON_PATHS[board.icon ?? ''] ?? ICON_PATHS.fallback
}

/* ==================== 计数行 ==================== */

/** 帖子总数：板块卡片上已有各自计数，这里给一个"站里有多少东西"的总印象 */
const totalPosts = computed(() => meta.boards.reduce((sum, b) => sum + (b.postCount ?? 0), 0))

/**
 * 计数行文案。
 *
 * ⚠️ 这里刻意**不在"没数据"的情形下显示数字**：
 *   请求失败 / 仍在加载时，`meta.boards` 都是空数组，直接插值会渲染成
 *   「0 个板块 · 共 0 篇讨论」—— 那是把"没拿到"说成了"没有"。
 *   数字 0 是一个结论，而我们对"到底有多少板块"此刻并没有结论。
 *   （失败时由下方 el-alert 说明原因并给重试入口。）
 */
const countText = computed(() => {
  if (loading.value) return '加载中…'
  if (error.value) return '板块列表暂时没取到'
  return `${meta.boards.length} 个板块 · 共 ${totalPosts.value} 篇讨论`
})

/* ==================== 加载 ==================== */

/** 骨架屏延迟：低于这个时长不显示（近瞬时完成时闪一层骨架更廉价） */
const SKELETON_DELAY = 180

const showSkeleton = ref(false)
let skeletonTimer: ReturnType<typeof setTimeout> | undefined

function scheduleSkeleton() {
  clearTimeout(skeletonTimer)
  skeletonTimer = setTimeout(() => {
    showSkeleton.value = true
  }, SKELETON_DELAY)
}

function clearSkeleton() {
  clearTimeout(skeletonTimer)
  skeletonTimer = undefined
  showSkeleton.value = false
}

/**
 * 加载板块。
 *
 * force=true 用于「重试」：绕开 meta store 的"已加载就不再请求"缓存。
 * ⚠️ 重试必须把 error/loading 一起复位 —— 否则第一次失败留下的告警
 *    永远不会消失，重试按钮看起来"点了没反应"。异常也必须在这里吃掉，
 *    不能让它变成未捕获的 promise rejection。
 */
async function load(force = false) {
  loading.value = true
  error.value = ''
  scheduleSkeleton()
  try {
    await meta.loadBoards(force)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '板块加载失败'
  } finally {
    clearSkeleton()
    loading.value = false
  }
}

// 强制刷新（force=true）：板块字典是 app 级缓存（首屏加载过一次就不再请求），
// 而本页计数行是"各板块 post_count 之和" —— 用缓存会得到可能过期的总数
// （别人发过帖后，本页必须重新取数才准）。代价只是一个小字典请求。
onMounted(() => load(true))
</script>

<template>
  <div class="container boards-page">
    <header class="page-head">
      <div>
        <h1 class="page-title">板块</h1>
        <p class="page-desc">{{ countText }}</p>
      </div>
    </header>

    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button text type="primary" @click="load(true)">重试</el-button>
    </el-alert>

    <!-- 骨架：与真实卡片同形（同网格、同高度），数据到达时不发生布局跳动 -->
    <div v-else-if="showSkeleton" class="board-grid" role="status" aria-busy="true">
      <span class="sr-only">正在加载板块…</span>
      <div v-for="i in 10" :key="i" class="board-card is-skeleton" aria-hidden="true">
        <span class="sk-chip" />
        <span class="sk-lines">
          <span class="sk-bar sk-bar-title" />
          <span class="sk-bar sk-bar-desc" />
        </span>
      </div>
    </div>

    <!-- 真的空目录（不是"还在加载"）：`!loading` 是必须的 —— 首次进站时缓存未命中，
         前 180ms 里 boards 就是空数组，少了这个条件会把"正在加载"说成"没有板块" -->
    <EmptyState
      v-else-if="!loading && !meta.boards.length"
      icon="empty"
      title="还没有可浏览的板块"
      description="板块由管理员配置，当前没有处于启用状态的板块 —— 可以先去首页看看最新讨论"
    />

    <ul v-else class="board-grid">
      <li v-for="(board, i) in meta.boards" :key="board.id">
        <RouterLink
          :to="{ name: 'board', params: { slug: board.slug } }"
          class="board-card anim-in"
          :style="{ '--i': Math.min(i, 8) }"
        >
          <!-- 图标：aria-hidden —— 板块名已经是可访问名称，图标只是视觉锚点 -->
          <span class="board-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor"
                 stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path v-for="(d, k) in iconPaths(board)" :key="k" :d="d" />
            </svg>
          </span>

          <span class="board-body">
            <span class="board-top">
              <span class="board-name">{{ board.name }}</span>
              <span class="board-count">
                <b>{{ board.postCount }}</b><i>篇</i>
              </span>
            </span>
            <span class="board-desc line-clamp-2">
              {{ board.description || '这个板块还没有简介' }}
            </span>
          </span>

          <span class="board-go" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor"
                 stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <path d="m9 18 6-6-6-6" />
            </svg>
          </span>
        </RouterLink>
      </li>
    </ul>
  </div>
</template>

<style scoped>
/* 卡片网格：窄屏 1 列 → 中屏 2 列 → 宽屏 3 列（auto-fill 自适应，不写死媒体查询） */
.board-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(268px, 1fr));
  gap: var(--space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.board-card {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  height: 100%;
  padding: var(--space-4);
  /* 复用全局 .card 的玻璃语言（底色 + 发丝边 + 顶边内高光 + 模糊）：
     与帖子卡、侧栏卡保持一致，不另起一套观感 */
  background: var(--color-card);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--glass-highlight);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  transition: background var(--transition-fast), border-color var(--transition-fast),
    transform var(--transition-fast);
}

/* hover 同时改四个通道（底 / 边 / 图标 / 箭头位移），
   只靠"变个颜色"在暗色下太弱，也照顾不了色觉差异 */
.board-card:hover {
  background: var(--color-card-hover);
  border-color: var(--color-border);
  transform: translateY(-2px);
}

/* 聚焦样式完全交给全局 `:focus-visible`（焦点环 + 自动跟随元素自身圆角）。
   本轮顺带修掉了两处全局副作用，详见文件头注释与 tokens.css。 */

/* ---------- 图标锚点 ---------- */
.board-icon {
  display: grid;
  place-items: center;
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  background: var(--color-muted);
  color: var(--color-muted-foreground);
  transition: background var(--transition-fast), color var(--transition-fast);
}

.board-card:hover .board-icon {
  background: var(--color-accent-soft);
  color: var(--color-accent);
}

/* ---------- 主体 ---------- */
.board-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  min-width: 0; /* 让内部的 line-clamp 能真正截断，而不是把卡片撑宽 */
  flex: 1;
}

.board-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-2);
}

.board-name {
  font-size: var(--text-md);
  font-weight: 600;
  color: var(--color-foreground);
  letter-spacing: var(--tracking-heading);
}

/* 计数：数字用等宽表格数字（与其它列表页的计数对齐一致），单位弱化 */
.board-count {
  flex-shrink: 0;
  font-size: var(--text-sm);
  color: var(--color-subtle-foreground);
  font-variant-numeric: tabular-nums;
}

.board-count b {
  font-weight: 600;
  color: var(--color-muted-foreground);
}

.board-count i {
  margin-left: 2px;
  font-style: normal;
  font-size: var(--text-xs);
}

.board-desc {
  font-size: var(--text-sm);
  line-height: var(--leading-snug);
  color: var(--color-muted-foreground);
}

/* ---------- 箭头 ---------- */
.board-go {
  display: grid;
  place-items: center;
  flex-shrink: 0;
  align-self: center;
  color: var(--color-border);
  transition: color var(--transition-fast), transform var(--transition-fast);
}

.board-card:hover .board-go {
  color: var(--color-accent);
  transform: translateX(2px);
}

/* ---------- 骨架屏 ---------- */
.board-card.is-skeleton {
  gap: var(--space-3);
  pointer-events: none;
}

.sk-chip {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  background: var(--color-muted);
}

.sk-lines {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  flex: 1;
  padding-top: 3px;
}

.sk-bar {
  display: block;
  height: 11px;
  border-radius: var(--radius-sm);
  background: var(--color-muted);
}

.sk-bar-title {
  width: 42%;
}

.sk-bar-desc {
  width: 78%;
}

/* 骨架呼吸：只在允许动效时播放（reduced-motion 下全局规则会把时长压到 0.01ms） */
@media (prefers-reduced-motion: no-preference) {
  .board-card.is-skeleton .sk-chip,
  .board-card.is-skeleton .sk-bar {
    animation: board-sk-pulse 1.4s ease-in-out infinite;
  }
}

@keyframes board-sk-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.55;
  }
}

/* 无障碍：给屏幕阅读器一句"正在加载"，而不是逐个朗读灰块 */
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
  border: 0;
}

/* 窄屏：箭头会让内容挤，隐藏它（整卡仍可点，hover 反馈照旧） */
@media (max-width: 640px) {
  .board-go {
    display: none;
  }
}
</style>