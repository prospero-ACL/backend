# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Spring Boot backend for "Prospero RAG ACL" — a RAG (retrieval-augmented generation) web app whose
knowledge base is novel trilogies, where what the LLM may draw on is gated by the requesting user's
clearance. Access control is enforced by **Postgres** (one login role per clearance tier plus
Row-Level Security), not by Java: application code never filters chunks itself, so a bug in it
cannot leak a document. The thesis argument and the staged history behind this design are in
`REFACTOR.md`.

## Commands

```bash
./mvnw spring-boot:run              # run the app (listens on :8000)
./mvnw test                         # offline suite: boots the app against a Testcontainers Postgres
./mvnw test -Dtest=CorpusReadIsolationTest#plebianRetrievesOnlyBookOne   # a single test method
OPENAI_API_KEY=... ./mvnw test -Dgroups=llm -DexcludedGroups=   # the real-OpenAI suite (~1 min, costs money)
./mvnw clean package                # build the jar (target/backend-0.0.1-SNAPSHOT.jar)
./mvnw clean package -DskipTests    # build without running tests (used by Dockerfile.localdev)
```

Tests need a running Docker daemon (Testcontainers starts `pgvector/pgvector:pg16`; Flyway builds it
from V1, so the roles and RLS under test are exactly the migrations'). Nothing in `../infra/` needs to
be up, and no env vars are needed for the offline suite.

Java 25 is required. Lombok is used throughout (`@Data`,
`@RequiredArgsConstructor`, etc.) — the annotation processor is wired into
`maven-compiler-plugin` in `pom.xml`.

### Local environment

The datasource URL in `application.properties` points at host `pgvector` (a Docker network alias
defined in `../infra/docker-compose-localdev.yml`), not `localhost` — running this app outside that
compose network (e.g. `./mvnw spring-boot:run` directly) requires either overriding
`spring.datasource.url` or adding a `pgvector` entry to `/etc/hosts` pointing at wherever Postgres
actually is. See the `../infra/` section under Architecture for the full local stack setup.

Required env vars (all referenced via `${...}` in `application.properties`, no defaults):
`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`, `JWT_SECRET`,
`POSTGRES_USER`, `POSTGRES_PASSWORD`, `OPENAI_API_KEY`, and the three tier-role passwords
`POSTGRES_PLEBIAN_PASSWORD`, `POSTGRES_EQUES_PASSWORD`, `POSTGRES_PATRICIAN_PASSWORD` — sourced
from `../infra/env.localdev` when running via the infra compose stack.

The base package is `com.prospero_acl.backend` (with an underscore) — `com.prospero-acl.backend`
is not a legal Java package name, per `HELP.md`.

## Architecture

This is the backend app. It is a Spring Boot 4.0.6 application with Spring
Security, Spring Data JPA, Spring AI and Flyway.

The frontend is located at `../frontend/` — a Vite + React + TypeScript app
(Mantine UI, Redux Toolkit) that runs on port 5173 by default.

The infra is at `../infra/` — it holds the docker-compose setup and env files
for all three repos (backend, frontend, Postgres/pgvector). All env variables
live in `../infra/env.localdev`, not in this repo. To start the whole stack:

```bash
../infra/startup.sh dev     # rebuild and start, keeping the Postgres volume
../infra/startup.sh clean   # same, but wipes postgres_data and node_modules first
```

To rebuild only this service: `docker compose -f docker-compose-localdev.yml -p prospero-acl up -d
--build backend` from `../infra/` (with `env.localdev` sourced, as `startup.sh` does). The project
name matters — without `-p prospero-acl` Compose attaches a different, empty volume.

Inside `../infra/documents/` there are `.drawio` files (use case diagram,
class diagram) describing the project architecture.

### Schema ownership: Flyway

Flyway owns all DDL (`src/main/resources/db/migration/`); Hibernate runs with `ddl-auto=validate`
and only checks that entities match. Any entity change therefore needs a new `V<n>__*.sql`.

- `baseline-on-migrate=true`: the long-lived local database was originally built by Hibernate and is
  stamped at V1 rather than rebuilt, while a fresh volume is built from `V1__baseline.sql`. Both must
  converge on the same schema. **Constraint names differ between the two** (Hibernate generated
  `fk4hbg…`-style names on the old database), so a migration must never refer to a pre-V5 constraint
  by name — look it up in `pg_constraint` instead, as `V5__report_to_conversation.sql` does.
- Spring Boot 4 split Flyway's auto-configuration into `spring-boot-starter-flyway`; with only
  `flyway-core` on the classpath the `spring.flyway.*` properties are silently ignored.
- Tier-role passwords reach `V2` as Flyway placeholders, so they are never in a checksummed file.

### Auth flow

OAuth2 login (Google/GitHub) is handled by Spring Security, but the app does **not** use
server-side sessions (`SessionCreationPolicy.STATELESS`). Instead:

1. `Security.oAuth2SuccessHandler()` runs after a successful OAuth2 login, extracts provider
   attributes via `ExtractedUserInfoFactory` (provider-specific mapping — GitHub vs Google have
   different attribute keys), upserts a `User` row via `UserService.findOrCreateUser`, and issues a
   JWT (`JwtService.generateToken`) whose **subject is the OAuth `providerId`**, not the internal
   `User.id` UUID. That JWT is set as an httpOnly `access_token` cookie and the browser is redirected
   to `app.frontend-url`.
2. On every subsequent request, `JwtAuthFilter` reads that cookie, validates the JWT, and sets
   `SecurityContextHolder`'s principal to the **providerId string** (not the user's UUID).
3. `ClearanceFilter` (next in the chain) resolves that user's clearance — see *Clearance routing*.
4. Anywhere in a controller/service you see `authentication.getName()`, it yields the OAuth
   providerId — you must go through `UserRepo.findByProviderId(...)` / `UserService.findByProviderId`
   to get the actual `User` entity (see `MainController.resolveUser`).

CORS is locked to the single origin `app.frontend-url` (`http://localhost:5173` by default) with
credentials enabled, so the frontend must send cookies with requests, not bearer headers.

`Security` permits `DispatcherType.ERROR`. Spring reports 404/405 by forwarding to `/error`, and
`JwtAuthFilter` (a `OncePerRequestFilter`) skips that forward, so without the permit every such
status reached authenticated clients as a misleading 401. Only the internal forward is opened — a
direct request to `/error` is still authenticated.

**Lazy-collection gotcha**: `User.conversations` is `@OneToMany(mappedBy = "owner")` (lazy by JPA
default). `User` is annotated `@Data`, but `conversations` carries
`@ToString.Exclude`/`@EqualsAndHashCode.Exclude` — **do not remove those** or reintroduce
logging/string-concatenation of a whole `User` entity. Nothing on the auth path is `@Transactional`,
so a `User` loaded via `UserRepo.findByProviderId(...)` is detached by the time
`oAuth2SuccessHandler` runs; touching the collection on it (e.g. via the default Lombok
`toString()`) throws `LazyInitializationException` — this previously crashed every _second_ login
for an existing user. `Conversation.prompts`/`replies` carry the same excludes, for a different
reason: without them Lombok recurses prompt → conversation → prompt.

**Logout** (`AuthController.logout`) clears `SecurityContextHolder`, invalidates any `HttpSession`
left over from the OAuth2 handshake (see the STATELESS note above — that session isn't cleaned up
automatically), and overwrites the `access_token` cookie with `Max-Age=0` using attributes that match
the login cookie exactly (httpOnly, `secure(false)`, `path("/")`) so the browser actually drops it. By
design there is no server-side token revocation/blocklist: a captured token remains cryptographically
valid until its natural 1-hour expiry even after logout — only the browser stops sending it.

### ACL model

Clearance is a fixed three-level hierarchy over a trilogy's **book position**. There is no
per-document ownership or visibility, and nobody chooses what is used for prompting: retrieval always
uses everything the caller is cleared to read.

| Clearance (`SecurityLevel`) | Postgres role        | Books retrievable |
| --------------------------- | -------------------- | ----------------- |
| `PLEBIAN`                   | `postgres_plebian`   | 1                 |
| `EQUES`                     | `postgres_eques`     | 1, 2              |
| `PATRICIAN`                 | `postgres_patrician` | 1, 2, 3           |

Everyone sees every trilogy **title**; only the chunks behind it are gated. Only patricians upload.

Enforcement lives in `V2__acl_roles_and_rls.sql`: RLS is enabled on `vector_store` with one
`SELECT` policy per tier role (`metadata ->> 'book' = '1'`, `IN ('1','2')`, `true`) and `INSERT`
granted to `postgres_patrician` only. Points that matter when touching any of it:

- **RLS denies by default.** A chunk without a `book` key, or a tier with no policy, sees nothing —
  the fail-closed direction.
- **The app's own connection bypasses RLS.** It connects as the Postgres superuser (and table owner),
  which is what JPA, Flyway and admin work need. So anything reading chunks must go through the
  corpus connection, never `appJdbcTemplate`. The deliberate exceptions are
  `DocumentService.listTrilogies` (titles are public) and the ingestion worker's
  delete-before-reinsert.
- Roles are cluster-scoped and survive `DROP DATABASE`, hence the conditional creation in V2.
- `../infra/verify-acl.sh` proves the policies at SQL level (per-role visibility, fail-closed
  unlabelled chunks, patrician-only insert, no tier access to `users`). Re-run it after any change to
  roles, grants, policies or chunk metadata.

### Clearance routing

`CorpusDataSourceConfig` defines two DataSources:

- **`@Primary appDataSource`** — the superuser connection, used by JPA (`users`, `conversation`,
  prompts, replies, `ingestion_job`), Flyway, and `appJdbcTemplate`. These tables are not tier-gated.
- **`corpusDataSource`** — an `AbstractRoutingDataSource` over three Hikari pools, one per tier role
  (`app.corpus.pool-size`, 5 each, so all four pools stay under Postgres `max_connections`). Only
  `corpusJdbcTemplate`, and so only the `PgVectorStore` built in `repo/VectorDatabase.java`, uses it.
  Routing everything would break JPA, because tier roles have no privileges on the entity tables.

The routing key is `ClearanceContext`, a `ThreadLocal<SecurityLevel>` set by `ClearanceFilter` from
`UserService.getSecurityLevel` on every request (so a clearance change applies on the next request)
and cleared in a `finally` — request threads are pooled, and a leaked value would elevate the next
request on that thread. Easy things to get wrong:

- `ClearanceFilter` is registered explicitly in `Security` and is deliberately **not** a
  `@Component`: Boot would also register it outside the security chain, before authentication
  exists, and `OncePerRequestFilter` would then suppress the in-chain run that has it.
- The router **throws** when no clearance is set instead of defaulting to a tier. Nothing opens a
  corpus connection at startup, so this does not affect boot.
- Code off the request thread has no clearance. `TrilogyIngestionWorker` sets PATRICIAN itself; that
  elevation is bounded by the controller's patrician check and by only `postgres_patrician` holding
  `INSERT`.
- Spring AI's `PgVectorStoreAutoConfiguration` is excluded in `application.properties` — with two
  `JdbcTemplate`s it cannot choose one. Declaring any `JdbcOperations` bean also makes Boot's own
  `JdbcTemplate` back off, which is why `appJdbcTemplate` is declared explicitly.

### Conversations

A `Conversation` is a free-form, unbounded chat owned by one user: an ordered list of
`UserPrompt`s and `LlmReply`s, both keyed by `position` (`UNIQUE (conversation_id, position)`).
Endpoints under `/api/v1/conversations`: `POST /create`, `POST /{id}/continue`, `GET /{id}`, and
`GET /latest` (most recently updated, `204` if none). Responses are `{id, turns[]}`.

`ConversationService`:

1. Resolves the caller from `authentication.getName()` (the providerId); lookups by id are scoped
   to the owner, so another user's conversation is a 404.
2. Stores the prompt at `max(position) + 1` and calls `RAGService.query` with the replayed history.
3. Stores the reply at the same position and touches `updatedAt` explicitly — turns live in other
   tables, so otherwise `@UpdateTimestamp` never fires and `/latest` goes stale.

Prompts and replies are paired **by position, not list index**: a prompt without a reply renders as
`reply: null` and is left out of replayed history instead of throwing. Only the last
`app.conversation.history-turns` (6) answered turns are replayed to the model.

`RAGService.query` runs the prompt through a Spring AI `ChatClient` with a `QuestionAnswerAdvisor`
bound to the vector store. It passes **no filter expression** — the corpus connection's role already
limits what similarity search can return.

### Trilogy ingestion

`POST /api/v1/documents` (`multipart/form-data`: `files` ×3, a parallel `positions` list,
`trilogyName`) is patrician-only (`InsufficientClearanceException` → 403; Spring Security's
`AccessDeniedException` is avoided because the custom entry point would turn it into a 401).
`DocumentService.startIngestion` validates exactly three books at positions 1, 2, 3 (`400`
otherwise), persists an `IngestionJob` and returns **202** immediately; `TrilogyIngestionWorker`
does the work `@Async`. Clients poll `GET /api/v1/documents/ingestions/{id}` for
`status`/`booksDone`/`chunksWritten`; jobs are persisted, so they double as an audit trail.

The worker deletes any existing chunks for that trilogy first (re-upload **replaces**; without it a
second run doubles every chunk), extracts text with PDFBox, splits with `TokenTextSplitter` (chunk
size 500, min 50 chars) and writes chunks with metadata `trilogy`, `book` (`"1"`/`"2"`/`"3"` — a
string, to match the RLS predicates), `title` (filename), `uploadedAt` and `owner`. **`owner` is for
monitoring only and is not an access-control input.** A PDF with no extractable text or that cannot
be parsed fails the job (`status: FAILED`, `error` set) rather than the request. Multipart limits
are 50MB per file and 200MB per request.

`GET /api/v1/documents` returns trilogy titles via `SELECT DISTINCT metadata ->> 'trilogy'` on the
application connection — identical for every tier.

### Clearance assignment

Users cannot change their own clearance: it selects the Postgres role their queries run as, so a
self-service endpoint would be privilege escalation. `GET /api/v1/me/security-level` exists (the
frontend uses it to show the upload control); there is no write endpoint. New users start as
`PLEBIAN`. Clearance is assigned out of band with `../infra/set-clearance.sh` (no arguments lists
users; `<providerId|email> <PLEBIAN|EQUES|PATRICIAN>` assigns) and takes effect on the next request.

### Grounding — what RLS does not cover

RLS proves that *retrieval* cannot return out-of-clearance chunks. It says nothing about what the
model already knows: GPT-class models have memorised famous novels, so a plebian question can be
answered from parametric memory with no retrieved context at all. That threat is mitigated by
prompt design and evaluation, not by the database. See `REFACTOR.md` §4 — when testing tier
differences, confirm a "book-3-only" fact really is exclusive with a per-book `ILIKE` count before
reading anything into a plebian answer.

### Tests

All tests extend `support/PostgresIntegrationTest`: a full `@SpringBootTest` on a random port against
one shared container, with every required property supplied by `@DynamicPropertySource`.

- `acl/CorpusReadIsolationTest` — one test per tier through the app's real corpus path
  (`ClearanceContext` → router → tier pool → RLS); explicit `WHERE book = '3'` queries; each tier
  runs as its own non-superuser role; no clearance → connection refused.
- `acl/CorpusWritePrivilegeTest` — non-patrician inserts fail **on privileges** with the controller
  bypassed; patrician insert as a positive control; tier roles cannot read `users` or relabel a chunk.
- Both import `support/FixedEmbeddingConfig`, a constant-vector `EmbeddingModel`, so they run offline
  and similarity search returns exactly the rows the role can see.
- `acl/TrilogyEndToEndTest` (`@Tag("llm")`, excluded by default via `<excludedGroups>` in `pom.xml`)
  — real HTTP and real OpenAI: a patrician uploads a synthetic trilogy (PDFs built in the test),
  then each tier asks questions. Canaries live only in book 3: an invented name (retrieval leakage)
  and "Gondor" (parametric leakage — the model knows it from training). Every negative assertion has
  a patrician positive control. Setup asserts each canary term really is exclusive to its book.

When adding an ACL test, prove it can fail: both suites were checked by loosening the plebian policy
in V2 and by routing PLEBIAN to the patrician pool — each mutation must turn tests red.

### Adding a new authenticated endpoint

Follow the existing controller pattern (`MainController`, `AuthController`): inject
`Authentication`, call `.getName()` to get the providerId, and resolve the `User` via
`UserRepo`/`UserService` if you need the entity. New endpoints are authenticated by default (see
`Security.defaultSilterChain` — only `/oauth2/**` and the internal error dispatch are permitted).
If the endpoint touches chunks, use the `VectorStore` / `corpusJdbcTemplate` so RLS applies; if it
runs off the request thread, it must set and clear `ClearanceContext` itself.
