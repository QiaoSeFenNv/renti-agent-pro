# Renti Agent Pro

地图驱动的 AI 租房决策平台 - 全栈重构版

## 技术栈

- **后端**：Java 21 + Spring Boot 3.5 + PostgreSQL 16
- **AI 服务**：Python + LangChain + LangGraph + DeepSeek
- **前端**：React 18 + Vite + Tailwind CSS
- **向量检索**：Qdrant + Jina Embeddings
- **图数据库**：Neo4j

## 快速启动

### 环境要求
- Java 21+
- Node.js 18+
- Python 3.11+
- PostgreSQL 16

### 一键启动（Windows）
```powershell
powershell -ExecutionPolicy Bypass -File renti-agent-backend\scripts\start-all.ps1
```

### 手动启动
```bash
# 1. 启动 PostgreSQL (端口 55432)
pg_ctl -D path/to/data start

# 2. 启动后端 (端口 8080)
cd renti-agent-backend
mvn spring-boot:run

# 3. 启动 AI 服务 (端口 8001)
cd renti-agent-backend/agent-service
uv run uvicorn app.main:app --host 127.0.0.1 --port 8001

# 4. 启动前端 (端口 5173)
cd renti-agent-front
npm install
npm run dev
```

## 访问地址

- 前端：http://localhost:5173
- 后端 API：http://127.0.0.1:8080
- 管理后台：http://localhost:5173/admin/login

## 默认账号

- 管理员：`admin / admin123`
- 新用户：注册后验证邮箱（开发模式自动返回验证码）

## 功能特性

- 🗺️ 高德地图驱动的房源可视化搜索
- 🤖 AI 自然语言需求理解与推荐
- 📊 多维度房源评分与风险提示
- 🔍 向量检索 + 图谱增强的语义搜索
- 💬 房源智能问答（基于 LLM）
- 🎯 通勤时间分析与生活配套评估
- 📈 管理后台：数据采集、审核、观测

## 文档

- [迁移报告](MIGRATION-REPORT.md)
- [后端架构规范](renti-agent-backend/docs/CONVENTIONS.md)
- [API 契约](renti-agent-backend/docs/API-CONTRACT.md)
- [前端设计规范](renti-agent-front/docs/DESIGN-SPEC.md)

## 许可证

MIT
