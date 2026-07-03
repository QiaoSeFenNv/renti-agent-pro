# Implementation Plan: Complete Renti Agent Migration

## Overview

The old project already documents a mature V2 prototype. The new Spring/React migration must move from a minimal demo slice to compatible end-to-end functionality by preserving old API contracts and rebuilding the React admin/user screens against those contracts.

## Architecture Changes

- Backend: extend existing feature modules instead of adding a parallel framework.
- Backend: keep in-memory repositories for this migration pass, with deterministic fallbacks for external services.
- Backend: add modules/services for home subscriptions, admin operations, notifications, ingestion, audit/log placeholders, geocode/requirements/recommendation compatibility.
- Frontend: Gemini CLI rewrites React UI to cover old Home/City/Property/Admin feature surface while keeping JavaScript-only Vite conventions.
- Tests: expand Spring smoke tests and frontend tests; run Python Agent tests unchanged unless Agent contract changes.

## Phase 1: API Contract Completion

1. Update home/user controllers and services.
   - Add subscribe, confirm, unsubscribe, user notifications, imported listing write/delete.
   - Risk: medium; response shape compatibility matters.

2. Update admin controller and services.
   - Add users detail/settings/config/password/delete, notifications CRUD, logs, audit detail/replay, ingestion plugins/schedules/import/candidates, Qdrant points.
   - Risk: high; broad admin surface.

3. Update listing/search compatibility.
   - Add places resolve, location geocode, requirements parse, recommendations search, image proxy compatibility.
   - Risk: medium.

4. Keep Agent bridge HTTP/1.1 and Python Agent fallback.
   - Risk: medium; Spring-to-Uvicorn body delivery is sensitive.

## Phase 2: Frontend Migration via Gemini CLI

1. Give Gemini old Vue pages, old API list, current React structure, and backend contract target.
2. Gemini edits only `renti-agent-front`.
3. Codex reviews Gemini diff, fixes integration gaps if necessary.

## Phase 3: Test Agent Workflow

1. Run backend Maven tests and package.
2. Run Python Agent pytest.
3. Run frontend tests, audit, build.
4. Start Python Agent, Spring backend, and Vite frontend.
5. Smoke test public, login, map search, listing detail, admin login, admin panels, and Agent bridge.

## Success Criteria

- Every documented old API route has a new Spring route.
- React frontend renders complete front office and admin workflows.
- Tests/build/package pass.
- Runtime smoke confirms key flows.
