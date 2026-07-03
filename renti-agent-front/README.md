# Renti Agent Front (v2)

React 18 + Vite + Tailwind 重构版前端：地图驱动的 AI 租房决策工作台。

## 启动

```powershell
npm install
npm run dev     # http://127.0.0.1:5173（/api 代理到 127.0.0.1:8080）
```

构建：`npm run build`

## 路由

- `/` 首页 · `/login` `/register` 独立登录注册页
- `/cities` 城市选择 · `/city/:cityName` 地图工作台 · `/property/:listingId` 房源详情
- `/workspace` 个人工作台（收藏/历史/导入/通知/设置）
- `/admin/login` 与 `/admin/*` 管理后台（概览/用户/采集/房源/向量库/图谱/审计/trace/日志/公告/集成配置）

## 结构

```
src/
├── components/ui/    # 基础组件（Button/Card/Input/Badge/Modal/Feedback）
├── components/site/  # 站点级组件（Header/Footer）
├── layouts/          # SiteLayout / AdminLayout
├── pages/            # 页面（admin/ 子目录为后台）
├── routes/           # 守卫（RequireAuth/RequireAdmin）
├── services/         # API 层（axios 封装，禁止组件内直接请求）
├── store/            # Zustand（authStore/adminAuthStore）
├── hooks/            # useAmap 等
└── styles/           # Tailwind 入口
```

设计规范见 `docs/DESIGN-SPEC.md`。
