# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Spring Boot backend for "Prospero RAG ACL" — a RAG (retrieval-augmented generation) web app where
document retrieval is gated by an access-control model tied to each user's security clearance. Not
just a REST CRUD API: the core feature is that answers from the LLM are constrained to documents the
requesting user is authorized to see.

## Commands

```bash
./mvnw spring-boot:run              # run the app (listens on :8000)
./mvnw test                         # run all tests
./mvnw test -Dtest=BackendApplicationTests#contextLoads   # run a single test method
./mvnw clean package                # build the jar (target/backend-0.0.1-SNAPSHOT.jar)
./mvnw clean package -DskipTests    # build without running tests (used by Dockerfile.localdev)
```

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
`POSTGRES_USER`, `POSTGRES_PASSWORD`, `OPENAI_API_KEY` — sourced from `../infra/env.localdev` when
running via the infra compose stack.

The base package is `com.prospero_acl.backend` (with an underscore) — `com.prospero-acl.backend`
is not a legal Java package name, per `HELP.md`.

## Architecture

This is the backend app. It is a Spring Boot 4.0.6 application with Spring
Security, Spring Data JPA, and Spring AI.

The frontend is located at `../frontend/` — a Vite + React + TypeScript app
(Mantine UI, Redux Toolkit) that runs on port 5173 by default.

The infra is at `../infra/` — it holds the docker-compose setup and env files
for all three repos (backend, frontend, Postgres/pgvector). All env variables
(`JWT_SECRET`, OAuth client IDs/secrets, `POSTGRES_*`, `OPENAI_API_KEY`) live
in `../infra/env.localdev`, not in this repo. To start the whole stack:

```bash
../infra/startup.sh dev
```

This tears down and recreates the `postgres_data` and `node_modules` Docker
volumes on every run (see the script) — don't run it if you want to keep local
Postgres data between runs.

Inside `../infra/documents/` there are `.drawio` files (use case diagram,
class diagram) describing the project architecture.

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
3. Anywhere in a controller/service you see `authentication.getName()`, it yields the OAuth
   providerId — you must go through `UserRepo.findByProviderId(...)` / `UserService.findByProviderId`
   to get the actual `User` entity. This is the pattern used in `AuthController.getUserMe` and
   `ReportService.createReport`.

CORS is locked to the single origin `app.frontend-url` (`http://localhost:5173` by default) with
credentials enabled, so the frontend must send cookies with requests, not bearer headers.

### RAG + ACL pipeline

There are two separate storage layers that don't share a foreign-key relationship:

- **JPA/Postgres entities** (`model/`): `User`, `Report`, `UserPrompt`, `LlmReply`, `ReportChunk`.
  A `Report` is a conversation: it owns an ordered list of `UserPrompt`s and `LlmReply`s
  (ordered by `position`).
- **pgvector store** (`repo/VectorDatabase.java`): holds embedded document chunks as Spring AI
  `Document`s with metadata (`owner`, `filename`, `uploadedAt`, and a `privacy` tier used for ACL
  filtering). It is _not_ a JPA entity, which is why `ReportChunk.chunkId` exists as a bare UUID
  column instead of a real relation — see the comment in that class.

Flow for asking a question (`ReportService.createReport`):

1. Resolve the calling user from `authentication.getName()` (the providerId — see Auth flow above).
2. Create a `Report` (status `DRAFT`) and a `UserPrompt` at position 1.
3. Compute which document privacy tiers the user's `SecurityLevel` (`LOW`/`MEDIUM`/`HIGH`) is allowed
   to see (`resolveAllowedTiers`): `LOW` → `public` only, `MEDIUM` → `+restricted`, `HIGH` →
   `+elevated`. This tier list is independent of the request.
4. Build a Spring AI `Filter.Expression` from those tiers, further AND'd with an `owner == userId`
   clause when the request's `DocumentScope` is `RESTRICTED` (i.e. "only search my own docs").
   `DocumentScope` (request-level: `PUBLIC`/`RESTRICTED`/`ELEVATED`) and `SecurityLevel` (user-level
   clearance) are two different enums that both influence the filter — don't conflate them.
5. `RAGService.query` runs the prompt through a Spring AI `ChatClient` with a
   `QuestionAnswerAdvisor` bound to the vector store, passing the filter expression so retrieval is
   scoped before the LLM ever sees a chunk.
6. Persist the reply as an `LlmReply` at position 1 and return `ReportResponseDTO`.

The total number of questions the user can ask is three. So the first question
creates the report and the next two modify the output of the same report. When
the no of questions exceeds three is marked as `ReportStatus.COMPLETED` so the
frontend can disable the "Ask" button.

`DocumentService.saveDocument` is the ingestion path: splits text with `TokenTextSplitter`
(chunk size 500, min 50 chars) and writes to the vector store with owner/filename/uploadedAt
metadata. `getDocumentsByUser` lists distinct filenames for a user by similarity-searching with an
empty query and a high `topK`, then de-duping chunks down to one row per filename — it's a listing
hack, not a real search.

### Adding a new authenticated endpoint

Follow the existing controller pattern (`MainController`, `AuthController`): inject
`Authentication`, call `.getName()` to get the providerId, and resolve the `User` via
`UserRepo`/`UserService` if you need the entity. New endpoints are authenticated by default (see
`Security.defaultSilterChain` — only `/oauth2/**` is `permitAll()`).
