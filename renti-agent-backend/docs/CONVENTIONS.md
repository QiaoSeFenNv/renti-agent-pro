# Renti Agent Backend 开发规范（迁移版）

> 本文档是所有后端模块开发的统一约定。所有模块 agent 必须先读本文档再动手。

## 1. 技术栈与运行

- Java 21 + Spring Boot 3.5.3 + Maven（`pom.xml` 已就绪，勿改动依赖除非确有必要）
- PostgreSQL：`jdbc:postgresql://127.0.0.1:55432/renti_agent_v2`，用户 `renti`，无密码（trust）
- JPA（Hibernate，`ddl-auto: update`）——实体即 schema，不写 DDL 文件
- 虚拟线程已开启；HTTP 出站统一用 Spring `RestClient`（不引入 WebFlux）
- 构建：`C:/Files/Rentti/.tools/apache-maven-3.9.9/bin/mvn -s C:/Files/Rentti/.tools/maven-settings.xml compile`

## 2. 包结构（严格遵守）

```
com.renti.agent
├── RentiAgentApplication        # 已存在
├── common/
│   ├── config/                  # WebConfig、RentiProperties（已存在）
│   ├── exception/               # BusinessException、GlobalExceptionHandler（已存在）
│   ├── response/                # Result、PageResult（已存在）
│   └── util/                    # 通用工具（谁需要谁建，注意查重）
├── infrastructure/
│   ├── client/                  # 外部 API 客户端（AMap、Jina、Qdrant、Neo4j、DeepSeek、AgentService）
│   └── persistence/
│       ├── entity/              # 全部 JPA 实体（跨模块共享，集中存放）
│       └── repository/          # 全部 JpaRepository 接口
└── modules/
    ├── auth/                    # 用户认证 + 会话
    ├── user/                    # 用户工作台（设置/收藏/历史/导入房源/通知）
    ├── city/                    # 城市库 + 首页配置
    ├── listing/                 # 已发布房源 + 详情
    ├── ingestion/               # 采集/候选审核/爬虫插件/调度
    ├── search/                  # 地图意图、需求解析、推荐、房源分析
    ├── agent/                   # AI agent 接入层（调 Python 服务 + 降级链路 + trace）
    ├── rag/                     # 向量检索管理（Qdrant/Jina/索引）
    ├── graph/                   # Neo4j 图谱管理
    ├── admin/                   # 管理端（登录/overview/用户管理/日志）
    ├── notification/            # 公告通知
    ├── subscription/            # 邮箱订阅
    └── platform/                # 平台配置中心（system_integrations 等）
```

每个模块内：`api/`（Controller）、`application/`（Service + dto/）、`domain/`（领域逻辑，可选）。Repository 统一放 `infrastructure/persistence/repository`。

## 3. 编码规范要点

- DTO 一律 Java 21 `record`，放在模块的 `application/dto/`
- Controller 只做参数校验 + 调 Service；`@Slf4j` 记录关键操作
- 异常：抛 `BusinessException`（有 badRequest/unauthorized/notFound/upstream 工厂方法），禁止吞异常
- 多行字符串（SQL/JSON/提示词）用文本块 `"""`
- switch 用箭头表达式；局部变量类型明显时用 `var`
- **JSON 字段统一 camelCase**（前端同步重写，不需要兼容旧 snake_case）
- 时间统一 `OffsetDateTime`（UTC 存储，ISO-8601 输出）

## 4. 响应契约

**沿用旧 API 的"直接返回业务对象"风格**（不包 Result 信封——前端已按此对接）：

- 查询成功：直接返回 DTO/Map 结构，通常带 `ok: true`（与旧版一致，具体以各端点契约为准）
- 业务失败但 HTTP 200：返回 `{ok: false, code, summary}`（旧版行为，保留）
- HTTP 错误（401/404/500）：抛 BusinessException，全局处理器输出 `{code, message}`
- `Result`/`PageResult` 类保留给内部工具端点使用

## 5. 认证机制（与旧版对齐）

- 用户会话：HttpOnly Cookie `renti_session`，DB 表存 token（SHA-256 摘要），TTL 7 天
- 管理会话：HttpOnly Cookie `renti_admin_session`，TTL 12 小时
- 由 `modules/auth` 提供 `SessionAuthInterceptor` + `@CurrentUser`/`@CurrentAdmin` 参数解析器；受保护路径：`/api/user/**`、`/api/search/**`、`/api/agent/**`、`/api/listings/**`、`/api/places/**`、`/api/locations/**`、`/api/requirements/**`、`/api/recommendations/**` 需用户登录；`/api/admin/**`（除 login/session/logout）需管理员登录
- 未登录返回 401 `{code: "authentication_required", summary: "请先登录后再使用地图和房源接口。"}`；管理端 `{code: "admin_authentication_required", ...}`
- 密码 BCrypt（spring-security-crypto 已引入）
- 内部工具端点 `/internal/**`：校验请求头 `X-Internal-Token` 等于配置 `renti.security.internal-token`，不走会话

## 6. 外部集成配置

所有外部服务参数**先读数据库配置中心（platform 模块，key=system_integrations），空缺时回退 `RentiProperties`（环境变量）**。集成客户端从 `IntegrationSettingsService`（platform 模块提供）获取运行时配置。出站代理：Neo4j/Qdrant/Jina 等海外服务按配置走 `http://127.0.0.1:7897`（配置中心 proxyUrl 字段）。

## 7. 旧代码参考

旧 Python 实现位于 `C:\Files\Rentti\renti-agent\backend\app\`，**行为与响应结构以旧代码为准（字段名转 camelCase）**，但实现要按本规范重构，不得照抄结构。每个任务的委派说明中会指出对应旧文件。
