# 前端 Nginx 镜像 — 多阶段构建。
# Jenkins 只需一条 `docker build`，宿主机无需安装 Node。
FROM node:20-alpine AS builder
WORKDIR /build

# 换腾讯云 npm 源（国内拉包快且稳）
RUN npm config set registry https://mirrors.tencentyun.com/npm/

# 先拷依赖描述，利用层缓存。
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# 构建出 dist/。VITE_API_BASE_URL 留空 → 前端走相对 /api，由 nginx 反代到 backend。
# 高德 JS key 用构建 ARG 注入（不写进代码仓库）。
ARG VITE_AMAP_JS_KEY
ARG VITE_AMAP_SECURITY_JS_CODE
ENV VITE_AMAP_JS_KEY=${VITE_AMAP_JS_KEY} \
    VITE_AMAP_SECURITY_JS_CODE=${VITE_AMAP_SECURITY_JS_CODE} \
    VITE_API_BASE_URL=""
RUN npm run build

FROM nginx:alpine
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /build/dist /usr/share/nginx/html

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
