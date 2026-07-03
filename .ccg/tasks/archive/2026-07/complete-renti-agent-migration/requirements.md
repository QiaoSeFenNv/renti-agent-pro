# Requirements: Complete Renti Agent Migration

## Goal

Migrate all old `renti-agent` Vue + FastAPI user-facing and admin-facing capabilities into:

- `renti-agent-front`: React 18 + JavaScript + Vite.
- `renti-agent-backend`: Java 21 + Spring Boot + Maven.
- `renti-agent-backend/agent-service`: Python FastAPI Agent-only service.

## Scope

- Preserve old public routes: `/`, `/city/:cityName?`, `/property/:listingId?`, `/admin`.
- Preserve old user flows: login, register, email verification, logout, change password, settings, notifications, favorites, history, imported listings, map target search, map intent search, listing detail, property insight, property chat.
- Preserve old home flows: city list, dynamic home config, double opt-in subscribe, confirm, unsubscribe, stats.
- Preserve old admin flows: overview, users, user detail/settings/config/password/delete, notifications, platform config, system integrations, logs, agent traces, user interactions, retrieval audits/replay, listing ingestion, listing management, RAG/Qdrant panel, Neo4j graph panel.
- Keep Python Agent responsible only for Agent planning/insight endpoints and bridge through Spring.
- Degrade external integrations safely when Qdrant, Neo4j, Jina, DeepSeek, or Amap credentials are absent.

## Constraints

- Frontend implementation is delegated to Gemini CLI per user request.
- Backend implementation is owned by Codex in Java/Spring.
- Real Codex `spawn_agent` tooling is not available in this desktop session; work is split into isolated orchestration streams instead.
- Claude Code CLI is unavailable per user instruction.
- Existing root is not a Git repository, so CCG archive commit cannot be performed unless a repository is initialized externally.

## Success Criteria

- New frontend exposes the complete user and admin UI surface from the old Vue app.
- New backend provides every old documented API endpoint with compatible response shapes.
- Automated tests cover representative public, user, admin, listing ingestion, RAG/graph, and Agent flows.
- `npm test`, `npm run build`, Maven tests/package, and Python Agent tests pass.
- Local runtime smoke confirms frontend, Spring backend, and Python Agent are reachable and key flows work.
