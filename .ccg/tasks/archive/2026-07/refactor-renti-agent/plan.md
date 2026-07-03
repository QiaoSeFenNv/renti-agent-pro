# Implementation Plan: Renti Agent Migration

## Overview

Create a new multi-project implementation from the old Vue/FastAPI prototype. The migration keeps the product's core behavior visible and testable while introducing the target architecture: React JavaScript frontend, Spring Boot API backend, and a Python FastAPI agent service.

## Architecture Changes

- `renti-agent-front`: Vite React app, feature-oriented structure, API service layer, auth context, route pages, shared UI primitives, responsive app/dashboard styling.
- `renti-agent-backend`: Maven Spring Boot app with modules for home, auth, user workspace, listing/search, admin, and agent bridge. Uses Java records for DTOs and in-memory repositories for the first migration slice.
- `renti-agent-backend/agent-service`: Python FastAPI service containing agent-only LangGraph/LangChain-facing logic with deterministic fallback responses.

## Implementation Steps

### Phase 1: Backend Foundation

1. Create Maven/Spring Boot project files.
   - Files: `renti-agent-backend/pom.xml`, `src/main/resources/application.yml`
   - Risk: Medium, because local Maven/JDK setup must be verified.

2. Create common API infrastructure.
   - Files: `common/response/Result.java`, `common/response/PageResult.java`, `common/exception/*`, `common/config/WebConfig.java`
   - Risk: Low.

3. Create domain modules and seed repository.
   - Files: `modules/listing/*`, `modules/auth/*`, `modules/user/*`, `modules/admin/*`, `modules/agent/*`
   - Risk: Medium, because contracts must stay close to old API fields.

4. Add Spring tests for health, auth, search, and agent fallback.
   - Files: `src/test/java/.../ApiSmokeTests.java`
   - Risk: Low.

### Phase 2: Python Agent Service

1. Create FastAPI agent service.
   - Files: `agent-service/app/main.py`, `agent-service/app/agent/*`, `agent-service/requirements.txt`
   - Risk: Medium, because optional LangGraph dependencies must degrade gracefully.

2. Add Python tests.
   - Files: `agent-service/tests/test_agent_service.py`
   - Risk: Low.

### Phase 3: React Frontend

1. Create Vite React JavaScript app.
   - Files: `package.json`, `vite.config.js`, `index.html`, `src/main.jsx`, `src/App.jsx`
   - Risk: Low.

2. Add shared services and auth state.
   - Files: `src/services/api.js`, `src/features/auth/*`, `src/shared/*`
   - Risk: Medium, because all API calls must use the normalized backend contract.

3. Build user-facing pages.
   - Files: `src/pages/HomePage.jsx`, `LoginPage.jsx`, `RegisterPage.jsx`, `CityPage.jsx`, `PropertyPage.jsx`
   - Risk: Medium, due to map/search/detail interaction.

4. Build admin module pages.
   - Files: `src/pages/AdminPage.jsx`, `src/features/admin/*`
   - Risk: Medium, due to dense data UI.

5. Add focused frontend tests and run build.
   - Files: `src/**/*.test.jsx`
   - Risk: Low.

### Phase 4: Verification and Review

1. Run npm install/build/test for frontend.
2. Install Maven locally if needed; run Maven tests for backend.
3. Run Python tests for agent service.
4. Review `git diff` or directory diff manually because root is not a Git repo.
5. Record review results in `.ccg/tasks/refactor-renti-agent/review.md`.

## Risks and Mitigations

- Full old parity is too large for a single empty-project migration.
  - Mitigation: deliver a clean first slice with compatible API names and replaceable repositories.
- External model review is unavailable.
  - Mitigation: record the tool failure, run local tests, and perform manual checklist review.
- Java PATH may not refresh after winget installation.
  - Mitigation: use explicit `JAVA_HOME` and project-local Maven.
- Real external services may be unavailable.
  - Mitigation: Python and Spring agent logic both provide deterministic fallback responses.

## Success Criteria

- [ ] `renti-agent-front` builds with Vite.
- [ ] `renti-agent-backend` compiles and tests with Maven.
- [ ] `renti-agent-backend/agent-service` tests pass.
- [ ] Login/register are route-based pages.
- [ ] `/api/search/map-target`, `/api/search/map-intent`, `/api/listings/{id}`, `/api/agent/*`, and core admin endpoints are available.
- [ ] User and admin UI use the same visual system and are responsive.
