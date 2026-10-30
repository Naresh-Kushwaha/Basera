# Basera

Multi-tenant platform that helps students settle into a new city for college. **One tenant = one college.** Students find
verified rooms/PGs and mess/tiffin, share utilities problems, find roommates, chat in-app, track a budget, read a local guide
and get safety tools; owners list properties and services; college admins verify and moderate; a platform admin onboards colleges.

| Layer | Stack |
|---|---|
| Frontend | React 18, Vite, TypeScript, Tailwind (per-college theming via CSS variables), React Router, TanStack Query, react-hook-form + zod, Leaflet |
| Backend | Java 21, Spring Boot 3, Spring Security (JWT), Spring Data JPA + JdbcClient, Flyway, MapStruct, springdoc |
| Database | PostgreSQL 16 + PostGIS, **Row Level Security** for tenant isolation |
| Ops | Dockerfiles (nginx-unprivileged), docker-compose, Kubernetes manifests, GitHub Actions CI |

## What is in the repo

```
backend/        Spring Boot service (110 classes) + db/migration V1..V8 + tests
frontend/       React app: student shell, owner panel, college-admin panel, platform console
deploy/         docker-compose (db only, and full stack), Kubernetes manifests, deploy.sh, db role init
docs/           ARCHITECTURE.md, API.md (generated, 136 endpoints), PRODUCTION_CHECKLIST.md
scripts/        gen_api_doc.py
.github/        CI + Dependabot
```

### Features (all toggleable per college)
Stay · Food · Utilities · Reviews (only after an owner-confirmed stay/subscription and a minimum time) · Roommate finder with
explainable matching · in-app chat · Budget planner · Local guide (city content shared per city, checklist and costs per college) ·
Safety (trusted contacts, SOS helper, issue reports) · Notifications (in-app + email outbox) · Scam protection (reports,
auto-hide after 3 distinct reporters, owner suspension, verified-only filter) · Admin analytics, announcements, audit log.

## Run it

Prerequisites: Docker (and JDK 21 + Maven 3.9 + Node 22 if you want to run the parts outside containers).

### Whole stack in containers (demo)
```bash
cp .env.example .env            # edit the passwords and BASERA_JWT_SECRET
cd deploy
docker compose --env-file ../.env -f docker-compose.full.yml up --build
```
Open **http://gec-jabalpur.localhost:8081** (browsers resolve `*.localhost`; any slug works) and **http://admin.localhost:8081**
for the platform console. Emails are caught by Mailpit at http://localhost:8025. This stack uses the `dev` profile, which adds a
Google-free login. **Never deploy it.**

First steps: sign in on `admin.localhost:8081` with the dev login as SUPER_ADMIN → Cities → add a city → Colleges → onboard
`gec-jabalpur` (put your own email as first admin) → sign in at `gec-jabalpur.localhost:8081` as TENANT_ADMIN → invite an owner,
add checklist items → sign in as OWNER (invite first, or use "Make owner" on a student) and add a listing → approve it as admin.

### Develop with hot reload
```bash
docker compose --env-file .env -f deploy/docker-compose.yml up -d          # PostgreSQL + PostGIS only
set -a; source .env; set +a
(cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev)
(cd frontend && VITE_DEV_TENANT=gec-jabalpur VITE_DEV_LOGIN=true npm run dev)   # VITE_DEV_TENANT=platform for the admin console
```

## Tests
```bash
cd backend  && mvn verify        # unit + integration (Testcontainers needs Docker)
cd frontend && npm test && npm run build
```
Highlights: `RlsIsolationIT` proves tenant isolation in the database as the real runtime role; `TenantApiIT` and
`MarketplaceFlowIT` prove it through the whole stack; meta-tests fail the build if a new table forgets RLS; ArchUnit keeps the
RLS-bypassing pool confined; frontend tests cover single-flight token refresh, role guards and the Google avatar.

## Deploy to Kubernetes
See `deploy/deploy.sh` and `docs/PRODUCTION_CHECKLIST.md` (wildcard TLS, secrets, database roles, migrations as a Job, known gaps).

## What was actually verified while building this
Be clear-eyed about it:

| | Status |
|---|---|
| All 8 SQL migrations, role-init script (compose and Kubernetes Job), RLS policies | **Executed** on PostgreSQL 16.15 + PostGIS, including every SQL statement the services use, under the real non-bypass role |
| Frontend: type-check, 19 unit tests, production build, code splitting | **Executed** (all green) |
| nginx runtime-config script, YAML validity of all manifests | **Executed** |
| Java backend (110 classes) and its 9 test classes | **Parse-checked with `javac` only.** The build environment could not reach Maven Central, so the Spring code has **never been compiled or run**. Expect a few compile fixes on the first `mvn verify`. |
| Docker images, Kubernetes manifests against a real cluster, Google login, SMTP | **Not run** |

So: run `mvn verify` first and fix what it reports. The tests are written to catch exactly the multi-tenancy mistakes that matter.
