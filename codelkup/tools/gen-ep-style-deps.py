# -*- coding: utf-8 -*-
"""
把 Element Plus 全部组件的按需样式入口，写进 vite.config.ts 的 optimizeDeps.include。

为什么要"列全"而不是"用到才加"：
    unplugin-vue-components 的 ElementPlusResolver 在**转换阶段**才注入
    `element-plus/es/components/xxx/style/css` 这类 import，Vite 启动时的依赖扫描扫不到。
    于是 dev server 跑起来后，一旦打开某个页面触发了新组件的样式入口，
    Vite 就判定"发现新依赖" → 重新预构建 → 先写 deps_temp_xxxx，再**递归删**旧目录。
    本机安全策略会拦截超过 50 个目标的递归删除（SAFE_DELETE_BULK_CONFIRM_REQUIRED），
    这个异常直接把 dev server 打崩（报错栈在 node-safe-delete-shim.cjs）。
    所以：让它在首次预构建时就把全部样式入口纳入缓存，运行期不再"发现新依赖"。

用法（element-plus 升级后重跑一次即可）：
    python codelkup/tools/gen-ep-style-deps.py
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FRONTEND = os.path.normpath(os.path.join(HERE, "..", "codelkup-frontend"))
CONFIG = os.path.join(FRONTEND, "vite.config.ts")
EP_COMPONENTS = os.path.join(FRONTEND, "node_modules", "element-plus", "es", "components")

if not os.path.isdir(EP_COMPONENTS):
    sys.exit(f"[gen-ep] 找不到 {EP_COMPONENTS}，先在前端目录跑 npm i")

# 组件样式入口：真实文件是 <name>/style/css.mjs，import 路径按 element-plus 的
# exports 映射写成 <name>/style/css
names = sorted(
    d for d in os.listdir(EP_COMPONENTS)
    if not d.startswith("_") and os.path.isfile(os.path.join(EP_COMPONENTS, d, "style", "css.mjs"))
)
if not names:
    sys.exit("[gen-ep] 没扫到任何 style/css.mjs，检查 element-plus 版本")

raw = open(CONFIG, "rb").read()
nl = "\r\n" if b"\r\n" in raw else "\n"
src = raw.decode("utf-8")

# 数组起点：optimizeDeps 里唯一的 include 数组
anchor = nl + "    include: ["
i = src.index(anchor)
j = src.index(nl + "    ],", i)
head, old_body, tail = src[:i + len(anchor)], src[i + len(anchor):j], src[j:]

old_items = re.findall(r"'([^']+)'", old_body)
# 保留原有的非"组件样式"条目（vue / axios / element-plus 主入口等），顺序不变
keep = [x for x in old_items if not x.startswith("element-plus/es/components/")]

style_items = ["element-plus/es/components/%s/style/css" % n for n in names]
merged = keep + style_items

comment = nl + nl.join([
    "    // ⚠️ 下面这些 element-plus 样式入口必须**列全**（本段由 tools/gen-ep-style-deps.py 生成）。",
    "    //    ElementPlusResolver 在转换阶段才注入这些 import，Vite 启动扫描扫不到；",
    "    //    漏掉的那个会在运行期被当成\"新依赖\"→ 重新预构建 → 递归删旧缓存 →",
    "    //    被本机 safe-delete 策略拦截（>50 条目）→ dev server 直接崩。",
    "    //    实测漏 `drawer/style/css` 就是这样挂的。element-plus 升级后重跑生成脚本。",
])
body = comment + nl + nl.join("      '%s'," % x for x in merged)

new_src = head + body + tail
with open(CONFIG, "wb") as f:
    f.write(new_src.encode("utf-8"))

print("[gen-ep] 组件样式入口 %d 个，保留其它依赖 %d 个，行尾 %s" %
      (len(style_items), len(keep), repr(nl)))
print("[gen-ep] 已写回 %s" % CONFIG)
