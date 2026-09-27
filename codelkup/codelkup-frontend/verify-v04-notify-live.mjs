/**
 * 前端验收脚本（通知列表实时刷新的触发契约，V0.4 补充）。
 *
 * 钉死 store 上"哪些变化算推送、哪些不算"这条边界 —— 它是通知中心
 * 「静默重拉」（NotificationView 监听 pushedAt）的唯一触发依据：
 *   1. 只有"推送到达"（setUnread）推进 pushedAt —— 列表据此重拉；
 *   2. 本地乐观操作（decrement / reset / increment）**不得**推进 ——
 *      否则"用户点一下、列表整体重拉"，变成自己刷新自己的无谓抖动；
 *   3. setUnread 的负值是钳制语义（角标不为负），且仍算一次推送。
 *
 * 为什么值得单独钉：这条边界的错误形态很隐蔽 —— pushedAt 多推一格，
 * 页面上只是"偶尔多一次请求"，人眼几乎看不出来；但一旦把本地操作也算成推送，
 * 每次已读点击都会触发一次列表重拉（网络面板里才看得见）。浏览器侧的真实链路
 * （SSE → 列表出现新通知，约 1.5s）由本轮浏览器实测覆盖，本脚本只钉触发条件本身。
 *
 * 运行：node verify-v04-notify-live.mjs
 * 退出码：0 = 全通过，1 = 有失败用例
 */
import { createServer as createViteServer } from './node_modules/vite/dist/node/index.js'
import { createPinia, setActivePinia } from 'pinia'

let pass = 0
let fail = 0
const failures = []

function check(name, ok, detail = '') {
  if (ok) {
    pass++
    console.log(`✓ PASS  ${name}`)
  } else {
    fail++
    failures.push(name)
    console.log(`✗ FAIL  ${name}${detail ? '  ->  ' + detail : ''}`)
  }
}

/* ============ 加载真实源码 ============ */
const vite = await createViteServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'silent',
})
const { useNotificationStore } = await vite.ssrLoadModule('/src/stores/notification.ts')

setActivePinia(createPinia())
const store = useNotificationStore()

/* ================= A. 推送推进 pushedAt ================= */
console.log('\n[A] 推送（setUnread）推进 pushedAt')
const t0 = store.pushedAt
store.setUnread(5)
check('setUnread 写入绝对值', store.unread === 5, store.unread)
check('setUnread 推进 pushedAt', store.pushedAt === t0 + 1, store.pushedAt)
store.setUnread(3)
check('第二次推送再次推进（列表会再拉一次）', store.pushedAt === t0 + 2, store.pushedAt)

/* ================= B. 本地操作不推进 ================= */
console.log('\n[B] 本地乐观操作不得推进 pushedAt')
const t1 = store.pushedAt
store.decrement()
check('decrement 减少未读数', store.unread === 2, store.unread)
check('decrement 不推进 pushedAt（不自我刷新）', store.pushedAt === t1, store.pushedAt)
store.increment(2)
check('increment 回滚不推进 pushedAt', store.pushedAt === t1, store.pushedAt)
store.reset()
check('reset 清零未读数', store.unread === 0, store.unread)
check('reset 不推进 pushedAt', store.pushedAt === t1, store.pushedAt)

/* ================= C. 钳制语义 ================= */
console.log('\n[C] 负值钳制且仍算一次推送')
store.setUnread(-1)
check('setUnread 负值钳到 0（角标不为负）', store.unread === 0, store.unread)
check('钳制后仍算一次推送', store.pushedAt === t1 + 1, store.pushedAt)

await vite.close()

console.log('\n==========================================================')
if (fail === 0) {
  console.log(`通知列表实时刷新契约：${pass}/${pass + fail} 通过`)
} else {
  console.log(`通知列表实时刷新契约：${pass}/${pass + fail} 通过，失败 ${fail} 项：`)
  for (const name of failures) console.log(`  - ${name}`)
}
console.log('==========================================================')
process.exit(fail === 0 ? 0 : 1)