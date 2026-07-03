# Renti Agent Backend (v2)

地图驱动租房决策平台后端：Java 21 + Spring Boot 3.5 + PostgreSQL + Qdrant + Neo4j + Python LangGraph Agent 服务。

## 组成

- **Spring Boot 应用**（本目录）：全部业务 API（认证、城市/房源库、采集审核、RAG/图谱管理、后台管理），端口 8080
- **agent-service/**：Python LangChain + LangGraph AI 编排服务（DeepSeek），端口 8001，由 Spring Boot 通过 HTTP 调用；agent 工具通过 `/internal/agent-tools/*` 回调 Spring Boot

## 快速启动

```powershell
# 1. 本地 PostgreSQL（复用旧项目实例，端口 55432）
C:\PostgreSQL\16\bin\pg_ctl -D C:\Files\Rentti\renti-agent\.local-postgres\data -o "-p 55432 -c listen_addresses=127.0.0.1" start

# 2. Spring Boot（首次启动自动建表 + 导入种子数据：城市/房源/用户/平台配置）
C:\Files\Rentti\.tools\apache-maven-3.9.9\bin\mvn -s C:\Files\Rentti\.tools\maven-settings.xml spring-boot:run

# 3. Python Agent 服务
cd agent-service
uv run uvicorn app.main:app --host 127.0.0.1 --port 8001
```

默认管理员：`admin / admin123`（首次启动生成，请尽快修改）。

## 文档

- `docs/CONVENTIONS.md` 开发规范与模块划分
- `docs/API-CONTRACT.md` 全量 API 契约（旧版 → 新版映射）

## 测试

```powershell
mvn -s C:\Files\Rentti\.tools\maven-settings.xml test
```
