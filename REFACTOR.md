# Refactor roadmap — DB-enforced ACL, trilogy corpus, free-form conversations

> Master plan. Each stage below is sized to become its own smaller implementation plan.
> Status legend: `TODO` / `IN PROGRESS` / `DONE`.

## 1. Why this refactor exists

Today the whole access-control model lives in Java. `service/AclFilter.java` builds a Spring AI
filter-expression **string**, which PgVectorStore appends as a bare `AND` onto its similarity query.
If that string is ever wrong, the database returns out-of-clearance rows — there is no second line of
defence. The ACL also mixes two dimensions (clearance tier *and* per-document ownership), which is
what previously made native Postgres roles a poor fit.

The new product model removes the ownership dimension, and that changes everything:

- The knowledge base becomes **novel trilogies** (The Lord of the Rings, the first three Dune books, …).
- Access is a fixed hierarchy by book position: **PLEBIAN → book 1**, **EQUES → books 1+2**,
  **PATRICIAN → all three**.
- Per-document visibility is **gone**. Everyone sees the trilogy *titles*; nobody chooses what is used
  for prompting. Retrieval automatically uses everything the caller is cleared to read.
- Only **PATRICIAN** users upload, via a form that takes **exactly 3 PDFs + a trilogy title**.
- The 3-question capped "Report" becomes a **free-form Conversation**.

With ownership gone, clearance is a fixed 3-level hierarchy — precisely what Postgres role membership
plus Row-Level Security expresses natively, with no per-user session variables. So the ACL moves out
of Java and into the database: each tier gets a real Postgres login role, and RLS policies attached to
those roles decide which rows exist at all. A bug in application code then cannot leak a document.

### Why novels

Results are verifiable by inspection. Anyone can check whether a Helm's Deep answer is consistent with
the tier that produced it; nobody can do that for a 150-page ΦΕΚ. The intended demonstration:

> **"Who is Aragorn?"**
> - **plebian** → "Aragorn is the leader of the rangers of the north."
> - **eques** → "Aragorn is a ranger and the defender of Helm's Deep."
> - **patrician** → "Aragorn is king of Gondor and Arnor, he defended Helm's Deep and led the rangers
>   of the north."

The same architecture applied to scientific books with paywalled chapters is the obvious real-world
extension; scientific text was avoided here only because chemistry/maths/theology output is hard to
verify by eye.

## 2. Target architecture

### Roles and policies (Flyway-managed)

Three non-superuser `LOGIN` roles: `postgres_plebian`, `postgres_eques`, `postgres_patrician`.
`vector_store` stays owned by the app role. `SELECT` granted to all three, `INSERT` only to
`postgres_patrician`. RLS enabled, one policy per role:

```sql
ALTER TABLE vector_store ENABLE ROW LEVEL SECURITY;

CREATE POLICY vs_plebian   ON vector_store FOR SELECT TO postgres_plebian
  USING (metadata ->> 'book' = '1');
CREATE POLICY vs_eques     ON vector_store FOR SELECT TO postgres_eques
  USING (metadata ->> 'book' IN ('1','2'));
CREATE POLICY vs_patrician ON vector_store FOR SELECT TO postgres_patrician
  USING (true);
CREATE POLICY vs_ingest    ON vector_store FOR INSERT TO postgres_patrician
  WITH CHECK (true);
```

### Two DataSources — not one routing DataSource for everything

- **`@Primary appDataSource`** — the existing role. Used by Hibernate/JPA (`users`, `conversations`,
  prompts, replies) and by Flyway. These tables are not tier-gated.
- **`vectorDataSource`** — an `AbstractRoutingDataSource` over three Hikari pools (one per tier role),
  keyed by a `ThreadLocal` holding the caller's `SecurityLevel`. **Only** the `JdbcTemplate` handed to
  the `PgVectorStore` bean uses it.

Routing everything would break JPA, because the tier roles have no privileges on the entity tables.
The `ThreadLocal` is populated by a `OncePerRequestFilter` running after `JwtAuthFilter` and **must**
be cleared in a `finally` block — request threads are pooled and a leaked value is a privilege leak.
Set explicit pool sizes (≈5 each): three pools at Hikari's default of 10, plus the app pool,
approaches Postgres's default `max_connections`.

### Chunk metadata

`trilogy` (title), `book` (`"1"`/`"2"`/`"3"`), `title` (filename), `uploadedAt`, and `owner` —
**retained for system monitoring only, explicitly not an access-control input**. The `privacy` key and
all owner-based filtering disappear.

### What the `users` table is for now

Frontend support only: display name, avatar, theme, and the clearance level that selects the Postgres
role. It takes no part in chunking or prompting.

## 3. Critical constraints discovered during investigation

- **The app connects as the Postgres superuser** (`POSTGRES_USER=postgres`). Superusers and table
  owners **bypass RLS**. The tier roles must therefore be plain non-superuser, non-owner roles. The
  superuser connection keeps full visibility, which is what JPA and admin work need.
- **`vector_store.metadata` is `json`, not `jsonb`** (verified against the live database), while Spring
  AI's inserts cast `?::jsonb`. It is converted to `jsonb` in Stage 2, where RLS predicates and
  indexes need it. The conversion is a migration rather than part of the baseline, so that an
  already-baselined database and a freshly built one end up identical.
- **`repo/VectorDatabase.java` builds `PgVectorStore` manually**, bypassing the
  `spring.ai.vectorstore.pgvector.*` autoconfiguration, so schema-init ownership is currently
  ambiguous. Flyway owning the DDL resolves it.
- **`ddl-auto=update` never renames or drops.** A `Report` → `Conversation` rename under it would
  create a new empty table and orphan the old one. Flyway is not optional for this refactor.
- **Spring Boot 4 modularised auto-configuration.** Having `flyway-core` on the classpath no longer
  activates Flyway: `FlywayAutoConfiguration` now lives in the separate `spring-boot-flyway` module,
  supplied by `spring-boot-starter-flyway`. Without it the `spring.flyway.*` properties are read and
  **silently ignored** — the app boots normally and no schema history table is ever created. Expect
  the same trap for any other Boot 4 integration added later.
- **No test safety net exists** — `BackendApplicationTests.contextLoads()` and one frontend
  `Welcome.test.tsx`.
- **`ReportChunk` / `Report.chunks` / `ReportCreateDTO.chunks` are dead** — never written, never read.
  They get deleted rather than carried forward.
- **Scale shift.** Chunking three novels at 500 tokens yields on the order of 10³ chunks and a very
  large number of embedding calls. Two existing things break at that scale: the 20MB multipart limit,
  and `DocumentService.getDocumentsByUser`, which "lists" documents by running a similarity search
  with a single-space query and `topK(1000)`.

## 4. The grounding problem — read before trusting any demo

RLS proves that *retrieval* cannot return forbidden chunks. It says nothing about what the **LLM
already knows**. GPT-class models have memorised Tolkien and Herbert in detail, so a plebian-tier
question can be answered correctly from the model's own parametric memory with zero retrieved
context, and the demo would look broken — or worse, look fine while proving nothing.

These are two distinct threats and only the first is solved by the database:

| Threat | Mitigated by |
|---|---|
| Retrieval returns out-of-clearance chunks | Postgres RLS — provable at SQL level |
| Model answers from pretraining instead of context | Prompt design + evaluation — **not** RLS |

Required mitigations:

1. A system prompt that forbids outside knowledge and mandates an explicit refusal
   ("that is not in the material available to you") when the context does not contain the answer.
   The default `QuestionAnswerAdvisor` template is not strict enough; it needs to be customised.
2. **Canary evaluation.** Choose facts that appear *only* in book 2 or book 3 and ask them at plebian
   tier. A correct answer proves parametric leakage, not retrieval success. This is the acceptance
   test for the whole thesis claim, and it belongs in the automated suite, not just in manual demos.
   **Verify the fact really is exclusive** before drawing conclusions: during Stage 6 a plebian
   correctly answered a question aimed at book 3, which looked like leakage until a query showed
   book 1's bibliography cited the same work with its full author list. Confirm exclusivity with
   `SELECT metadata->>'book', count(*) ... WHERE content ILIKE '%<fact>%' GROUP BY 1` first. In a
   trilogy, later volumes recap earlier ones, so genuinely exclusive facts need choosing with care.
3. Log retrieved chunk ids per turn, so any answer can be traced to the chunks that justified it.

## 5. Stages

### Stage 1 — Flyway foundation `DONE`
Enable Flyway; `ddl-auto=validate`; `V1__baseline.sql` reproducing the current schema **exactly as it
stands today**, verified against a `pg_dump` of the live database; disable Spring AI schema init.
`baseline-on-migrate=true` stamps the existing local database instead of rebuilding it, while a fresh
volume builds from V1 — so no data is destroyed to adopt Flyway.

The baseline deliberately keeps every quirk of the current schema (`metadata json`, the `report`
table's singular name, the `scope`/`status` check constraints). Later stages change them through
ordinary migrations, which keeps existing and fresh databases converging on the same state.
**Done when:** the app boots clean against both an existing and a wiped `postgres_data` volume.

### Stage 2 — Postgres ACL layer `DONE`
`V2__acl_roles_and_rls.sql` converts `metadata` to `jsonb`, creates `postgres_plebian` /
`postgres_eques` / `postgres_patrician`, grants `SELECT` to all three and write access to the
patrician role only, enables RLS, and adds one policy per tier plus an index on
`(metadata ->> 'book')`. Role passwords live in `infra/env.localdev` (gitignored) and reach the
migration as Flyway placeholders, so they are never hardcoded in a checksummed file; the `backend`
service already picks them up through `env_file`.

Two details worth remembering: roles are **cluster-scoped**, so they survive `DROP DATABASE` and the
migration has to create them conditionally; and RLS **denies by default**, so any tier whose policy
is missing sees nothing rather than everything.

Verified by `infra/verify-acl.sh` (11 checks, all passing) — it seeds one chunk per book, asserts
each role sees only its permitted books, that unlabelled chunks fail closed, that only patricians can
insert, and that no tier role can read `users`, relabel a chunk, or disable RLS. Re-run it after any
later change; no Java was involved in any of this.

### Stage 3 — Connection routing `DONE`
`ClearanceContext` (ThreadLocal) + `ClearanceFilter`, registered after `JwtAuthFilter`, resolve the
caller's clearance per request via the existing `UserService.getSecurityLevel`.
`CorpusDataSourceConfig` defines the `@Primary` application DataSource (JPA + Flyway) alongside a
`corpusDataSource` that routes to one Hikari pool per tier role, and `VectorDatabase.vectorStore` now
takes the qualified `corpusJdbcTemplate`.

Three things that are easy to get wrong here:
- `ClearanceFilter` is deliberately **not** a `@Component`. Spring Boot would otherwise also register
  it outside the security chain, where no authentication exists yet, and `OncePerRequestFilter` would
  then suppress the in-chain invocation that does.
- The router **throws** when no clearance is set rather than defaulting to a tier, so a missing
  context is a loud failure instead of a silent grant. Nothing opens a corpus connection at startup,
  so this does not affect boot.
- The ThreadLocal is cleared in a `finally` block; request threads are pooled, and a leaked value
  would elevate the next request on that thread.

**Verified end-to-end:** with the same JWT and the same `GET /api/v1/documents` call, changing only
the user's clearance returns books `1` / `1,2` / `1,2,3`. `pg_stat_activity` confirms three separate
pools connected as `postgres_plebian`, `postgres_eques` and `postgres_patrician`, and interleaving
patrician and plebian requests shows no clearance leaking between them.

### Stage 4 — Retire application-level ACL `DONE`
`AclFilter`, `DocumentScope` and `ResponseDocumentDTO` are deleted, along with
`buildFilterExpression`, the `QuestionAnswerAdvisor.FILTER_EXPRESSION` param and the `scope`/`chunks`
fields on `ReportCreateDTO`. `V3__drop_report_scope.sql` drops the now-meaningless column.
`DocumentService.listTrilogies()` replaces the similarity-search listing hack with
`SELECT DISTINCT metadata ->> 'trilogy'` on the **application** connection, and `GET /api/v1/documents`
returns trilogy titles.

Two configuration problems surfaced and were fixed rather than worked around:
- Declaring any `JdbcOperations` bean makes Boot's `JdbcTemplate` auto-configuration back off, so the
  application-side template is now declared explicitly as `appJdbcTemplate`.
- Spring AI's `PgVectorStoreAutoConfiguration` cannot choose between two `JdbcTemplate`s. It was only
  ever inert because `spring.main.allow-bean-definition-overriding=true` let our bean replace its
  own — the ambiguity noted in section 3. It is now excluded explicitly, that override flag is gone,
  and the inert `spring.ai.vectorstore.pgvector.*` properties were removed.

**Verified:** every tier gets the same trilogy titles from `GET /api/v1/documents` while the chunks
behind them stay gated (`books=1` / `1,2` / `1,2,3`), and `verify-acl.sh` still passes.

*Interim consequence:* uploads no longer write a `privacy` key and do not yet write `trilogy`/`book`,
so newly uploaded documents are invisible to every tier except patrician until Stage 6 lands. That is
the fail-closed direction, but it means upload is effectively inert in the meantime.

### Stage 5 — Remove the relevance classifier `DONE`
`RAGService.isRelevant`, the `RelevanceVerdict` record, `IrrelevantQueryException`, its 422 mapping
and both gate call sites are gone. Every prompt now goes straight to retrieval, which also removes
one LLM round-trip per question.

**Verified:** `POST /api/v1/conversations/create` returns 200 with a real reply. Asked as a plebian
against a corpus whose chunks carry no `book` key, the model answered *"I can't answer that from the
provided context"* — RLS returned nothing and the model refused instead of drawing on its own
knowledge, which is the behaviour section 4 depends on.

### Stage 6 — Trilogy ingestion `DONE`
`POST /api/v1/documents` takes `files` (exactly 3), a parallel `positions` list and `trilogyName`,
and returns **202** with an `IngestionJobDTO`. `TrilogyIngestionWorker` does the work `@Async`;
`GET /api/v1/documents/ingestions/{id}` reports `booksDone`/`chunksWritten`/`status`.
`V4__ingestion_job.sql` persists jobs, so progress survives a page refresh and doubles as an audit
trail of who ingested what. Multipart limits raised to 50MB per file / 200MB per request. Chunks now
carry `trilogy`, `book`, `title`, `uploadedAt` and `owner` (monitoring only).

Points worth remembering:
- The worker runs off the request thread, so it sets `ClearanceContext` to PATRICIAN explicitly. The
  elevation is bounded at both ends: the controller admits only patricians, and only
  `postgres_patrician` holds INSERT on `vector_store`.
- Re-uploading a trilogy **replaces** it (delete by `trilogy` first). Without that, a second run
  silently doubles every chunk and quietly corrupts any comparison of what each tier retrieves.
- Forbidding an upload uses a domain `InsufficientClearanceException` → 403. Spring Security's
  `AccessDeniedException` was translated into a **401** by the app's custom authentication entry
  point, which is misleading for an authenticated-but-unauthorised caller.

**Verified** with three RAG papers as a stand-in trilogy: 403 for a non-patrician, 400 for the wrong
book count, 202 then `PENDING → RUNNING → COMPLETED` (3 books, 93 chunks, ~15s), re-upload replacing
rather than duplicating, and per-tier visibility of 21 / 51 / 93 chunks.

### Stage 7 — Report → Conversation `DONE`
`Report` → `Conversation` across entity, repo, service and DTOs; `ReportStatus`,
`ReportCompletedException` and its 409 mapping, `statusForPosition` and `ReportChunk` are deleted.
`V5__report_to_conversation.sql` renames `report` → `conversation` and `report_id` →
`conversation_id`, drops `status` and `report_chunks`, and adds `UNIQUE (conversation_id, position)`
on both prompts and replies. `/conversations/draft` is replaced by `/conversations/latest` (most
recently updated conversation, `204` if none). The response is now `{id, turns}` — no `status`.
Prompts and replies are paired **by position**, not list index, so a prompt without a reply renders
with `reply: null` and is left out of replayed history; the next position is `max + 1`, not
`size + 1`. Only the last `app.conversation.history-turns` (6) answered turns are replayed.

Points worth remembering:
- **Baselined and fresh databases have different constraint names.** The live database was built by
  Hibernate, so its foreign keys are called `fk4hbg…`, not V1's `report_owner_fkey`. The first V5
  draft renamed them by V1's names and failed (Flyway rolled back cleanly). V5 now drops the foreign
  keys by catalogue lookup and recreates them under canonical names. Any later migration touching a
  pre-Flyway constraint has the same trap.
- With `status` gone, adding a turn no longer changes the `conversation` row, so `@UpdateTimestamp`
  never fires on its own. `addTurn` touches `updatedAt` explicitly; without it `/latest` goes stale.
- `Conversation.prompts`/`replies` are `@ToString.Exclude`/`@EqualsAndHashCode.Exclude`; the old
  `Report` let Lombok recurse prompt → report → prompt.

**Verified** against both a fresh database (V1 → V3 → V5 in a scratch DB) and the live baselined one,
which end up with identical constraints. Over HTTP: `/latest` 204 then 200; a fourth question
returns 200 (previously 409); the model recalls the first question from replayed history; an orphan
prompt injected by SQL returns `reply: null` instead of a 500 and the next turn takes position 6;
another user gets 404 on someone else's conversation. The history window cut-off itself was not
exercised (it needs more than 6 turns).

### Stage 8 — Security level lockdown `DONE` (backend; the profile slider goes in Stage 9)
Users must not change their own clearance: with DB roles it decides which Postgres role their queries
run as, making self-service assignment a privilege-escalation endpoint. `POST /api/v1/me/security-level`,
`UserService.updateSecurityLevel` and `UserRepo.updateSecurityLevelByProviderId` are removed.
`GET /me/security-level` stays — the frontend needs it to gate the upload UI.

**Assignment path:** `infra/set-clearance.sh`, run by whoever operates the database.
No arguments lists users; `set-clearance.sh <providerId|email> <PLEBIAN|EQUES|PATRICIAN>` assigns.
It validates the level, passes both arguments to psql as quoted variables (never spliced into SQL),
and exits non-zero for an unknown user. No restart is needed — `ClearanceFilter` reads clearance per
request, so the change applies to the user's very next call. New users still start as `PLEBIAN`
(entity default), so the default fails closed.

**Verified:** the old POST no longer changes anything; the script rejects an unknown level, an
unknown user and a quote-injection attempt (no rows changed); EQUES → PLEBIAN via the script is
reflected by `GET /me/security-level` on the next request, and restoring it works the same way.

*Fixed alongside, pre-existing and app-wide:* the removed POST first answered **401**, not 405.
Spring reports 405/404 (any `sendError` status) by forwarding to `/error`; `JwtAuthFilter` is a
`OncePerRequestFilter` and skips that ERROR dispatch, so the forward ran unauthenticated and the
custom entry point turned it into 401. `Security` now permits `DispatcherType.ERROR` — only the
internal forward, not the `/error` path, so a direct `GET /error` is still 401. Verified: authed
callers get real 404/405s; anonymous callers still get 401 for everything, so route existence is not
leaked.

### Stage 9 — Frontend `DONE`
Report → conversation across `shared/dto/chat.ts`, `config/api.ts`, the reducer (now
`conversation.reducer.ts`, persisted under the `conversation` key) and `modules/chat/**`. The
document-picker trio, `chunks`/`scope`, the `COMPLETED` gating, the 409 handler and the 3-question
copy are gone. `reply` is nullable to match Stage 7, rendered as "No reply was recorded".
Since conversations never end, chat gains a **New conversation** button; without it the latest
conversation would be re-adopted forever (`isStartingFresh` suppresses that adoption).

Documents page lists trilogy titles. The upload modal takes a trilogy title and three fixed
`Book 1/2/3` file slots, so the position of each file is explicit and "exactly three" holds by
construction. After the 202 the page polls `GET /documents/ingestions/{id}` every 2s, shows progress,
stops when the job ends and refreshes the title list on completion. Backend error bodies (400/403/422)
are shown verbatim in the modal.

The role-conditional rendering pattern is `shared/hooks/use-clearance.ts` (`useClearance()` →
`securityLevel`, `isPatrician`), used by the documents page to hide upload and by the read-only
profile page. It is a UX courtesy only — the controller and Postgres still enforce.

**Verified:** `npm test` (typecheck, format, oxlint, stylelint, vitest, build) passes. Every call the
UI makes was replayed through the Vite `/api` proxy: latest 204 → create → continue → get → latest;
upload as EQUES → 403 with readable body; as PATRICIAN → 202 then polling `RUNNING 1/3 → 2/3 →
COMPLETED 3/3, 93 chunks` with the corpus unchanged (21/30/42). **Not verified:** the rendered UI in a
browser — it needs a real OAuth login.

*Known gap:* the ingestion job id lives in component state, so leaving the documents page stops the
progress display (the job itself carries on server-side and the title appears once done).

### Stage 10 — Docs and verification `DONE`
`backend/CLAUDE.md` is rewritten around the current system: Flyway ownership (including the
baselined-vs-fresh constraint-name trap), the tier/role/book table and RLS, clearance routing and
its pitfalls, conversations, trilogy ingestion, clearance assignment, and the grounding caveat.
The owner-based matrix, `resolveAllowedTiers`, `DocumentScope`, the classifier and the
3-question limit are gone. `backend/README.md` explains the same model for a human reader.
Stale lines were also fixed in `frontend/CLAUDE.md`/`README.md` (report → conversation, persisted
slices, the `useClearance` pattern) and `infra/CLAUDE.md` (`dev` no longer wipes volumes — `clean`
does; the `-p prospero-acl` trap; the two scripts; how `env_file` and `environment:` interact).

Verification: every identifier and path the new docs cite was checked to exist. `verify-acl.sh`
passes 11/11 after Stages 7–9. Running it with Postgres stopped exposed a false pass — `denied()`
read any error as "allowed", so *patrician can insert* passed against no database — so the script
now checks reachability first and exits 2.

*Followed up:* the dead `EmptyDocumentException` and its 422 mapping were removed
(`UnreadablePdfException` → 422 stays — `MainController` still throws it), and section 6 is
implemented — see there.

## 6. Verification `DONE`

The thesis claim is "the database refuses out-of-clearance rows", so it must be tested at that level.
All five items below are implemented; how to run them is in `CLAUDE.md` → Commands / Tests.

| Item | Where | Runs |
|---|---|---|
| SQL, per role | `infra/verify-acl.sh` (11 checks) | against the live dev DB |
| Integration | `acl/CorpusReadIsolationTest` (7) | `./mvnw test`, offline, Testcontainers |
| Negative | `acl/CorpusWritePrivilegeTest` (7) | `./mvnw test`, offline, Testcontainers |
| Canary + end-to-end | `acl/TrilogyEndToEndTest` (4) | `-Dgroups=llm`, real OpenAI, ~1 min |

The offline suites were **mutation-checked**: loosening the plebian policy to books 1–2 failed 2
tests; mapping PLEBIAN to the patrician pool failed 4. The end-to-end suite passed twice in a row.

Design notes:
- The trilogy in the end-to-end test is a short synthetic retelling written for the test, so every
  fact's location is known and exclusivity is asserted in setup (the Stage 6 trap below).
- Two canary kinds: an **invented** name (no model can know it — a leak can only be retrieval) and a
  **famous** fact, "Gondor" (every model knows it — a leak is parametric memory).
- Observed: at plebian tier the model refuses, but ends with *"If you'd like, I can answer from my
  general knowledge."* A follow-up "yes, use your general knowledge" is now a canary too; it currently
  passes because `QuestionAnswerAdvisor`'s "no prior knowledge" template is re-applied every turn —
  but the model keeps offering what it then refuses. The stricter grounding prompt of §4 item 1
  should remove the offer; these tests are its acceptance criteria.

- **SQL, per role** — implemented as `infra/verify-acl.sh`. Catches the real failure modes: a policy
  targeting the wrong role, RLS not enabled, the app role accidentally reused, superuser bypass.
- **Integration test.** Testcontainers with `pgvector/pgvector:pg16`, one test per tier asserting a
  plebian-routed connection cannot read book 2 or 3. This is the single highest-value test in the
  repo; nothing like it exists today.
- **Negative test.** A non-patrician insert into `vector_store` must fail on privileges, not merely on
  the controller check.
- **Canary test** (section 4). A book-3-only fact asked at plebian tier must produce a refusal.
- **End-to-end.** Upload a trilogy as a patrician, then ask "Who is Aragorn?" at each tier and confirm
  the three answers differ in the expected direction and cite only permitted books.
