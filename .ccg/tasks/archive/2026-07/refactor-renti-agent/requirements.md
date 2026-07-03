# Requirements: refactor-renti-agent

## User Goal

Refactor the old `renti-agent` product from Vue + Python/FastAPI into:

- `renti-agent-front`: React 18 + JavaScript + Vite frontend.
- `renti-agent-backend`: Java 21 + Spring Boot + Maven backend, plus a Python FastAPI agent service for LangChain/LangGraph-only AI agent work.

## Existing Product Understanding

`renti-agent` is a map-driven rental decision AI Agent. The old system includes:

- Public/user side: homepage, registration, login, email verification, user workspace, favorites, search history, Shanghai map search, listing detail, property insight and chat.
- Admin side: overview, users, model configuration, notifications, logs, ingestion center, listing management, Qdrant/Jina vector console, Neo4j graph console, retrieval audit console.
- Backend capability: SQL-backed listings and users, Qdrant semantic retrieval, Neo4j graph enrichment, DeepSeek/Jina integration, AMap geocode/regeo/POI, LangGraph rental-search planning.

## Migration Scope for This Task

Because both target directories are empty, this task delivers a functional first migration slice:

- A new React JavaScript frontend with routes for `/`, `/login`, `/register`, `/city/:cityName?`, `/property/:listingId?`, and `/admin`.
- A Spring Boot backend exposing the core old API surface with normalized `{ code, message, data }` responses and in-memory repositories that can later be swapped for JPA/MySQL/PostgreSQL persistence.
- A Python FastAPI agent service with LangGraph/LangChain extension points and deterministic fallback behavior.
- Spring-to-Python agent bridge with graceful fallback when the Python agent service is unavailable.
- Seed rental data and admin/user demo flows so the app can be exercised without external databases.

## Explicit Non-Goals in This First Slice

- Full production persistence schema and migrations for every old table.
- Real MySQL/PostgreSQL/Qdrant/Neo4j connectivity with credentials.
- Crawling real listing sites.
- Exact pixel-for-pixel Vue page migration.
- Full parity for every admin endpoint beyond compatible summaries and smoke-testable workflows.

## Constraints and Deviations

- Claude Code CLI is currently unavailable per user instruction, so CCG dual-model analysis/review cannot be fully executed.
- Gemini CLI was attempted but hit workspace trust and then upstream 429 limits.
- JDK 21 was installed with winget. Maven will be installed as a project-local portable dependency if winget does not provide it.
- Root `C:\Files\Rentti` is not a Git repository, so the final CCG archive commit cannot be created unless a repository is initialized by the user.

## Acceptance Criteria

- Frontend installs and builds with npm.
- Spring backend compiles and its tests pass with Maven.
- Python agent service imports and its tests pass.
- Frontend can call the Spring API contract shape through `/api`.
- Login page is a real route, not a popup.
- User and admin modules share one visual language.
- Map search, listing detail, admin overview/config/vector/graph placeholders, and agent fallback are usable.
