# Renti Agent 前端设计规范（React 重构版）

> 所有页面 agent 动手前必读。旧版 Vue 代码在 `C:\Files\Rentti\renti-agent\frontend\src\`，
> 功能覆盖以旧版为准，**视觉全部重新设计**（禁止照抄旧版 class 和布局）。

## 1. 技术约定

- React 18 函数组件 + Hooks，纯 JavaScript（.jsx，禁止 TS 语法）
- 路由 React Router v6（`src/App.jsx` 已定义路由表，勿改路径）
- 样式：Tailwind（design tokens 见 `tailwind.config.js`：brand 蓝、ink 中性灰、shadow-card/float、rounded-2xl）
- 状态：登录态用 `src/store/authStore.js` / `adminAuthStore.js`（Zustand）；页面内部状态用 useState/useReducer
- **API 调用一律走 `src/services/*.js`，禁止在组件里直接 axios/fetch**
- 共享 UI：`src/components/ui/`（Button、Card/CardHeader/CardBody、TextField/SelectField、Badge、Modal、Spinner/LoadingBlock/EmptyState）——优先复用，缺什么先补到 ui/ 再用
- 地图：`src/hooks/useAmap.js`（已封装 loader + 实例生命周期）
- 每个异步请求管理 loading / error / data 三态；错误用页面内联提示（不要 alert）
- 图片可能为外站链接：用 `listingService.proxyImageUrl(url)` 包一层
- localStorage 仅存 UI 偏好（如上次选择的城市 `renti.lastCity`、工作台模式 `renti.workspaceMode`）；登录态靠 Cookie，不存 token

## 2. 设计语言（新版）

**关键词：清爽、留白、地图产品的工具感 + AI 产品的未来感。**

- 背景 `bg-ink-50`，内容承载在白色圆角卡片（shadow-card）
- 主色 brand-600 蓝，仅用于主行动点/高亮，不大面积铺色；渐变仅用于首页 hero 的辅助光斑（brand-400→sky-300 低饱和模糊圆）
- 字号克制：页面主标题 text-3xl~5xl/font-semibold，卡片标题 text-sm/font-semibold，正文 text-sm，辅助 text-xs；**禁止大标题+一句空话的组合，每个板块必须承载真实数据/功能**
- 圆角统一 rounded-2xl（按钮 rounded-full），边框 ring-1 ring-ink-100
- 动效：进入 animate-fade-up、hover 卡片浮起（Card hover 属性）；不加花哨动画
- 图标：内联 SVG（heroicons 风格，stroke 1.5）或 emoji 点缀，不引第三方图标库
- 布局：`max-w-7xl mx-auto px-4 sm:px-6`；移动端可用（栅格折叠即可，不追求完美）
- 中文文案自然、少而准；数字与关键信息优先展示

## 3. 站点信息架构

**用户端**（顶部导航 SiteHeader：logo「Renti Agent」/ 城市工作台 / 我的工作台 / 登录按钮或用户菜单）：

| 路由 | 页面 | 内容 |
|---|---|---|
| `/` | HomePage | Hero（一句价值主张 + 搜索/进入工作台入口 + heroBadge）、能力三卡（地图圈定/AI 解析/数据可信）、工作流程 3 步、城市快捷入口（热门 8 城）、邮箱订阅条、页脚 |
| `/login` `/register` | 登录/注册 | **独立页面**（旧版是弹窗，必须改）。左侧品牌区（渐变光斑+产品插画感文案）右侧表单卡；注册后进入验证码步骤（verify-email）再回登录 |
| `/cities` | CitySelectPage | 城市搜索 + 分页网格 + 模式选择（system_search 平台房源 / user_import 自有导入，来自 API modeOptions），选定后跳 `/city/:name?mode=` |
| `/city/:cityName` | CityWorkspacePage | **核心工作台**：左侧面板（搜索输入 + agent 对话结果 + 需求摘要 + 推荐列表分页）+ 右侧全高地图（markers/圈选/InfoWindow）。见 §4 |
| `/property/:listingId` | PropertyDetailPage | 房源详情：图集、核心参数、价值/环境/通勤评估卡、AI 深度分析（detail-analysis）、通勤小地图、数据来源卡、房源问答（property-chat 抽屉/分栏） |
| `/workspace` | UserWorkspacePage | 我的工作台：收藏列表、搜索历史、导入房源管理、通知中心、账号设置（工作台偏好+改密码）Tab 布局 |

**管理端**（`/admin` 左侧固定侧边栏布局 AdminLayout：分组导航 概览/数据接入/检索引擎/观测/平台）：

| 路由 | 页面 | 内容 |
|---|---|---|
| `/admin/login` | AdminLoginPage | 独立居中卡片登录（深色调，与用户端区分） |
| `/admin` | AdminOverviewPage | 指标卡网格（用户/房源/候选/审计等）+ 快捷入口 |
| `/admin/users` | AdminUsersPage | 用户表格 + 详情抽屉（设置/配置编辑、重置密码、删除） |
| `/admin/ingestion` | AdminIngestionPage | 概览统计条 + 爬虫插件卡片（运行/调度开关）+ 候选审核表（单条/批量 通过/驳回）+ 手动导入 JSON |
| `/admin/listings` | AdminListingsPage | 已发布房源表格（筛选 status/city/query）+ 编辑弹窗 + 下架 |
| `/admin/vector-store` | AdminVectorStorePage | Qdrant 状态卡 + RAG 配置表单 + 向量点浏览 + 语义搜索测试 + 重建索引 |
| `/admin/graph-store` | AdminGraphStorePage | Neo4j 状态/配置 + 只读 Cypher 控制台 + 同步房源按钮 |
| `/admin/audits` | AdminAuditsPage | 检索审计表 + 详情（请求/命中/耗时）+ 回放按钮 |
| `/admin/traces` | AdminTracesPage | Agent trace 表（状态/模式筛选）+ 步骤时间线详情 |
| `/admin/logs` | AdminLogsPage | 系统日志查询（kind/level/query） |
| `/admin/notifications` | AdminNotificationsPage | 公告 CRUD |
| `/admin/integrations` | AdminIntegrationsPage | 配置中心：LLM / RAG(Jina/Qdrant) / Neo4j 三段表单 + 工作台配置（modelOptions/pageSize） |

## 4. CityWorkspacePage 要点（对齐旧版 CityPage.vue 功能）

- 布局：`h-screen` 两栏，左 420px 面板（可折叠）+ 右侧地图铺满；移动端上下堆叠
- 搜索框支持两种提交：普通搜索（mapIntent）与 Agent 深度搜索（rentalSearch，UI 上一个开关或独立按钮）
- 结果区：summary 文案、toolTrace 步骤条（工具名+状态+摘要，可折叠）、warnings、推荐卡列表
- 推荐卡：图（proxyImageUrl）、标题、小区/商圈、租金/户型/面积、距离与通勤、tags、score、点击 → 地图聚焦 + InfoWindow；「详情」→ `/property/:id`；收藏按钮（userService.saveFavorite）
- 分页：每页 pageSize（用户设置 listingPageSize，默认 10）；marker 模式切换：当前页 / 前 100 条
- 地图交互：点击地图 → resolvePlace → 以该点为中心重搜（map_click source）；目标点 marker + 半径圈（AMap.Circle）；房源 markers 带价格标签，hover/点击联动列表
- 需求摘要卡：parsed 意图（预算/户型/半径/偏好）展示，可修改半径/排序后重搜
- 用户设置（radius/sort/mapStyle/autoOpen/pageSize）从 userService.getSettings 读取并可在面板快速调整
- mode=user_import 时：搜索范围切换为用户导入房源（getImportedListings 管理 + 导入表单），页面明显提示当前模式
- URL 状态：`?q=` 搜索词可分享；city 从路由参数取

## 5. 交互与可访问性

- 所有图片 alt；按钮 aria-label；表格空态用 EmptyState
- 关键破坏操作（删除/清空/下架/驳回）必须二次确认（Modal）
- 表单校验内联提示；提交按钮 loading 态
- 401 已由 apiClient 全局处理跳登录页，页面无需单独处理
