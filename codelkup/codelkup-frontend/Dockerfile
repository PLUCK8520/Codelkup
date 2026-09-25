# ============================================================
# Codelkup 前端镜像（多阶段构建）
#
# 阶段 1 用 Node 把 Vue 构建成静态文件，阶段 2 只带 nginx + dist。
# 运行镜像里没有 Node、没有 node_modules、没有源码，
# 体积从 500MB+ 降到 50MB 上下。
# ============================================================

# ---------------- 阶段 1：构建 ----------------
FROM node:20-alpine AS build

WORKDIR /build

# 与后端同理：先只拷依赖清单，把 npm ci 单独做成一层。
# 之后改前端代码不会触发重新装依赖。
# 用 npm ci 而不是 npm install —— ci 严格按 lock 文件装，结果可复现；
# install 可能悄悄升级次要版本，导致"本地能构建、CI 挂了"。
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# npm run build = vue-tsc -b && vite build
# 类型检查不过就直接构建失败 —— 这是有意的：镜像构建本身就是一道质量闸门，
# 不让带类型错误的产物进入可交付镜像。
RUN npm run build

# ---------------- 阶段 2：运行 ----------------
FROM nginx:1.25-alpine

# 自定义 server 块：SPA 路由回退 + 反向代理到后端
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 只把构建产物拷过来，源码与 node_modules 都不进运行镜像
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null 2>&1 || exit 1

CMD ["nginx", "-g", "daemon off;"]
