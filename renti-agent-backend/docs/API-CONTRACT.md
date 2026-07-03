# API 契约总表（旧 → 新）

> 列出全部端点、归属模块、旧实现参考文件。响应结构以旧实现为准（字段转 camelCase）。
> 旧代码根目录：`C:\Files\Rentti\renti-agent\backend\app\`（下称 OLD）

## public 模块（无需登录）

| 方法 | 路径 | 说明 | 旧参考 |
|---|---|---|---|
| GET | /api/health | 健康检查 `{status:"ok"}` | main.py |
| GET | /api/cities, /api/home/cities | 城市分页列表（query/page/limit），含 modeOptions | main.py `get_cities_payload` + services/cities.py |
| GET | /api/home/config | 首页 heroBadge 配置 | services/home_config.py |
| GET | /api/assets/listing-image?url= | 房源图片代理（白名单域名、24h 缓存头） | services/image_proxy.py |
| POST | /api/auth/register | 注册（email/password/nickname）→ 发验证码 | services/auth.py |
| POST | /api/auth/verify-email | 邮箱验证码校验 | services/auth.py |
| POST | /api/auth/login | 登录，Set-Cookie `renti_session` | services/auth.py |
| GET/POST | /api/auth/session | 会话查询（Cookie 或 body.token） | services/auth.py |
| POST | /api/auth/logout | 登出并清 Cookie | services/auth.py |
| POST | /api/home/subscribe | 邮箱订阅 | services/subscriptions.py |
| GET | /api/home/subscribe/confirm?token= | 订阅确认 | services/subscriptions.py |
| GET | /api/home/subscribe/unsubscribe?token= | 退订 | services/subscriptions.py |
| GET | /api/home/subscribe/stats | 订阅统计 | services/subscriptions.py |

## user 模块（需登录）

| 方法 | 路径 | 说明 | 旧参考 |
|---|---|---|---|
| POST | /api/auth/change-password | 改密码 | services/auth.py |
| DELETE | /api/user/preferences | 清空用户偏好 | services/auth.py |
| GET/PUT | /api/user/settings | 地图工作台设置（modelProfile/radius/sort/mapStyle/autoOpenResults/saveSearchHistory/analysisFocus/listingPageSize） | services/user_workspace.py |
| GET | /api/user/notifications | 用户可见公告+已读状态 | services/notifications.py |
| POST | /api/user/notifications/read-all | 全部标记已读 | services/notifications.py |
| POST | /api/user/notifications/{id}/read | 单条已读 | services/notifications.py |
| GET/POST | /api/user/favorites | 收藏列表/保存收藏（listingId + listing 快照） | services/user_workspace.py |
| DELETE | /api/user/favorites/{listingId} | 取消收藏 | services/user_workspace.py |
| GET/DELETE | /api/user/history | 搜索历史列表/清空 | services/user_workspace.py |
| GET/POST/DELETE | /api/user/imported-listings?city= | 用户自有导入房源（user_import 模式） | services/user_workspace.py |

## search 模块（需登录）

| 方法 | 路径 | 说明 | 旧参考 |
|---|---|---|---|
| POST | /api/places/resolve | 地图选点 → SelectedPlace（含建议半径：metro 1200/商圈办公学校 2000/其他 1500） | main.py |
| POST | /api/locations/geocode | 地点文本 → 坐标（高德 geocode + POI 兜底） | services/locations.py |
| POST | /api/requirements/parse | 需求文本正则解析 → Requirement | services/requirements.py |
| POST | /api/recommendations/search | 基于 Requirement 的启发式推荐 top3 + markers | services/recommendations.py |
| POST | /api/search/map-intent | 核心：自然语言/地图点击 → 意图解析+周边房源推荐+markers+toolTrace（记 history/audit/interaction） | services/map_intent.py |
| POST | /api/search/map-target | 仅定位不搜房（center+pois） | services/map_intent.py |
| GET | /api/listings/{listingId} | 房源详情（含 PropertyDetail 结构） | services/listing_ingestion.py `published_listing_detail_payload` |
| POST | /api/listings/{listingId}/detail-analysis | 房源深度分析（缓存+LLM/规则） | services/property_analysis.py |

## agent 模块（需登录）

| 方法 | 路径 | 说明 | 旧参考 |
|---|---|---|---|
| POST | /api/agent/rental-search | LangGraph 租房搜索 agent（转发 Python 服务，降级走 search 模块规则链路） | services/agent/rental_search_agent.py、agent_graph/rental_search_graph.py |
| POST | /api/agent/property-insight | 房源洞察 agent | services/agent/property_insight_agent.py |
| GET/POST | /api/agent/property-chat/sessions?listingId= | 房源问答会话列表/创建 | services/agent/property_insight_agent.py |
| POST | /api/agent/property-chat/sessions/{id}/messages | 发消息（agent 回复） | 同上 |
| DELETE | /api/agent/property-chat/sessions?listingId= | 清空会话 | 同上 |

## admin 模块（需管理员）

| 方法 | 路径 | 说明 | 旧参考 |
|---|---|---|---|
| POST | /api/admin/login | 管理员登录，Cookie `renti_admin_session` | services/admin.py |
| GET | /api/admin/session | 会话查询 | services/admin.py |
| POST | /api/admin/logout | 登出 | services/admin.py |
| GET | /api/admin/overview | 平台总览统计 | services/admin.py |
| GET | /api/admin/users?limit= | 用户列表 | services/admin.py |
| GET | /api/admin/users/{id} | 用户详情（含设置/工作台配置） | services/admin.py |
| PUT | /api/admin/users/{id}/settings | 改用户设置 | services/admin.py |
| PUT/DELETE | /api/admin/users/{id}/config | 改/重置用户工作台配置 | services/admin.py |
| POST | /api/admin/users/{id}/password | 重置密码 | services/admin.py |
| DELETE | /api/admin/users/{id} | 删除用户 | services/admin.py |
| GET | /api/admin/logs?kind=&query=&level=&limit=&page= | 系统日志查询 | services/system_logs.py |
| GET | /api/admin/agent-traces?limit=&page=&userId=&status=&mode= | agent 执行 trace 列表 | services/agent/agent_trace.py |
| GET | /api/admin/agent-traces/{id} | trace 详情（404 包 detail） | 同上 |
| GET | /api/admin/user-interactions?limit=&page=&userId=&endpoint=&query= | 用户交互记录 | services/user_interactions.py |
| GET | /api/admin/user-interactions/{id} | 交互详情 | 同上 |
| GET | /api/admin/retrieval-audits?limit=&page=&userId=&endpoint= | 检索审计列表 | services/retrieval_audit.py |
| GET | /api/admin/retrieval-audits/{id} | 审计详情 | 同上 |
| POST | /api/admin/retrieval-audits/{id}/replay | 用当前配置回放审计样本 | 同上 |

## notification / platform 模块（需管理员）

| 方法 | 路径 | 说明 | 旧参考 |
|---|---|---|---|
| GET/POST | /api/admin/notifications | 公告列表/创建 | services/notifications.py |
| PUT/DELETE | /api/admin/notifications/{id} | 更新/删除 | 同上 |
| GET/PUT | /api/admin/config | 工作台配置（modelOptions、listingPageSize） | services/admin.py + platform_config.py |
| GET/PUT | /api/admin/system-integrations/config | 集成配置中心（llm/rag/neo4j 三段） | services/platform_config.py |

## ingestion / listing 模块（需管理员）

| 方法 | 路径 | 说明 | 旧参考 |
|---|---|---|---|
| GET | /api/admin/listing-ingestion/overview | 采集概览统计 | services/listing_ingestion.py |
| GET | /api/admin/listing-ingestion/crawler-plugins | 插件列表 | services/listing_crawler_plugins.py |
| POST | /api/admin/listing-ingestion/crawler-plugins/{pluginId}/run | 运行插件 | 同上 |
| GET | /api/admin/listing-ingestion/crawler-schedules | 调度列表 | 同上 |
| PUT | /api/admin/listing-ingestion/crawler-schedules/{pluginId} | 更新调度 | 同上 |
| POST | /api/admin/listing-ingestion/crawler-schedules/run-due | 触发到期任务 | 同上 |
| POST | /api/admin/listing-ingestion/import | 手动导入房源 JSON | services/listing_ingestion.py |
| POST | /api/admin/listing-ingestion/crawl/lianjia-shanghai | 链家上海爬取 | services/listing_crawlers.py |
| GET | /api/admin/listing-ingestion/candidates?status=&limit=&page= | 候选列表 | services/listing_ingestion.py |
| POST | /api/admin/listing-ingestion/candidates/{id}/approve\|reject | 审核 | 同上 |
| POST | /api/admin/listing-ingestion/candidates/bulk-approve\|bulk-reject | 批量审核 | 同上 |
| GET | /api/admin/listing-ingestion/listings | 已发布房源（同 /api/admin/listings） | 同上 |
| GET | /api/admin/listings?limit=&page=&status=&query=&city= | 房源管理列表 | 同上 |
| GET/PUT/DELETE | /api/admin/listings/{listingId} | 房源详情/编辑/下架 | 同上 |

## rag / graph 模块（需管理员）

| 方法 | 路径 | 说明 | 旧参考 |
|---|---|---|---|
| GET/PUT | /api/admin/rag/config | RAG 配置 | services/rag/config.py |
| GET | /api/admin/rag/qdrant/status | Qdrant 状态 | services/rag/qdrant_store.py |
| GET | /api/admin/rag/qdrant/points?city=&status=&limit=&offset= | 向量点浏览 | 同上 |
| POST | /api/admin/rag/qdrant/search | 语义搜索测试 {text,city,limit} | services/rag/* |
| POST | /api/admin/rag/qdrant/index-listings | 发布库 → 向量索引 {city,query,limit} | services/rag/listing_indexer.py |
| GET/PUT | /api/admin/graph/neo4j/config | Neo4j 配置 | services/graph/config.py |
| GET | /api/admin/graph/neo4j/status | 连接状态 | services/graph/neo4j_store.py |
| POST | /api/admin/graph/neo4j/query | 只读 Cypher 查询 | 同上 |
| POST | /api/admin/graph/neo4j/sync-listings | 发布库 → 图谱同步 {city,query,limit} | services/graph/listing_graph.py |

## internal（新增，Python agent 服务回调，X-Internal-Token 认证）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /internal/agent-tools/parse-requirement | 规则解析需求文本 |
| POST | /internal/agent-tools/geocode | 地点 → 坐标（高德） |
| POST | /internal/agent-tools/search-listings-sql | 主库条件检索（city/center/radius/budget/layout/limit） |
| POST | /internal/agent-tools/search-listings-vector | Qdrant 语义召回（text/city/limit） |
| POST | /internal/agent-tools/search-listings-graph | Neo4j 关系增强（listingIds/city） |
| POST | /internal/agent-tools/listing-detail | 房源详情（listingId） |

## 横切行为（对齐旧版）

- `/api/**` 每个请求写 API 日志（system_logs，method/path/status/durationMs/client/error）
- `/api/search/map-intent`、`/api/agent/rental-search`：记录 搜索历史 + 检索审计 + 用户交互
- `/api/requirements/parse`、`/api/recommendations/search`、`/api/search/map-target`、`/api/agent/property-*`：记录用户交互
