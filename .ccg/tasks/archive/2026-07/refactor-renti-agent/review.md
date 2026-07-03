# Review: refactor-renti-agent

## External Model Review Status

- Claude Code CLI: unavailable per user instruction, not invoked further.
- Gemini CLI: initial invocation failed due workspace trust, retry reached upstream 429. No usable Gemini review output was produced.
- Fallback: local review performed with code-review-workflows checklists for Java/Spring, JavaScript/React, Python/FastAPI, and general security.

## Findings

### Critical

None.

### Warning

None remaining.

### Info

- Frontend form submit bug found during local review: shared `Button` defaulted to `type="button"`, and login/register/admin forms did not pass `type="submit"`. Fixed in `src/shared/ui/Button.jsx`, `LoginPage.jsx`, `RegisterPage.jsx`, and `AdminPage.jsx`.
- `npm audit` initially reported vulnerabilities from Vitest's transitive Vite/esbuild dev dependency chain. Upgraded Vitest to `^4.1.9`; `npm audit --audit-level=moderate` now reports zero vulnerabilities.
- Final runtime smoke found `GET /api/listings` had no root handler. Added a public paginated listings endpoint and smoke coverage.
- Final runtime smoke found Java 21 `HttpClient` default h2c negotiation caused Uvicorn to receive an empty POST body. Forced Spring-to-Python Agent calls to HTTP/1.1; Spring now receives Python `langgraph` responses.
- Spring backend uses in-memory repositories for this first migration slice. This is acceptable for the current empty-target migration, but production persistence must replace these stores with JPA/PostgreSQL/MySQL repositories and migrations.
- Admin default credentials are development defaults and should be overridden by `RENTAI_ADMIN_EMAIL` / `RENTAI_ADMIN_PASSWORD` in shared environments.

## Verification

- Frontend: `npm test` passed, 1 test.
- Frontend: `npm run build` passed.
- Frontend: `npm audit --audit-level=moderate` passed with 0 vulnerabilities.
- Backend: Maven tests passed with Java 21 and project-local Maven settings, 4 tests.
- Backend: `mvn -DskipTests package` passed after rebuilding the runnable Spring Boot jar.
- Python Agent: `python -m pytest -q` passed, 3 tests, 1 upstream LangGraph deprecation warning.
- Runtime smoke: Python Agent `8011`, Spring API `8080`, and Vite frontend `5173` are listening; Spring Agent bridge returns `mode=langgraph`.

## Verdict

APPROVE for first migration slice. No Critical or Warning issues remain after fixes.
