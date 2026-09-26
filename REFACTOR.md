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

### Stage 6 — Trilogy ingestion `TODO`
`POST /api/v1/documents` takes exactly 3 files, their positions, and `trilogyName`. PATRICIAN-only at
the controller (403) *and* by database grant. Raise multipart limits. Write the new metadata keys.
**Decision for this stage's own plan:** a synchronous upload will almost certainly exceed request
timeouts for three novels' worth of embedding calls — recommend an async job returning `202` plus a
status endpoint, with per-book upload as the simpler fallback.

### Stage 7 — Report → Conversation `TODO`
Rename entity/repo/service/DTOs (the HTTP paths are already `/api/v1/conversations/*`). Delete
`ReportStatus`, `ReportCompletedException` and its 409 mapping, `statusForPosition`, `Report.scope`,
`ReportChunk`. Replace `/conversations/draft` with `/conversations/latest`. Fix
`buildHistory`/`toResponseDTO`, which zip prompts to replies by list index and will throw
`IndexOutOfBoundsException` once the lists can desynchronise. Add a history window so unbounded
conversations do not grow the prompt without limit.

### Stage 8 — Security level lockdown `TODO`
Users must not change their own clearance: with DB roles it decides which Postgres role their queries
run as, making self-service assignment a privilege-escalation endpoint. Remove
`POST /api/v1/me/security-level`, the service and repo methods, and the profile slider. Keep
`GET /me/security-level` — the frontend needs it to gate the upload UI. Document the assignment path
(direct SQL or a seed migration).

### Stage 9 — Frontend `TODO`
Rename report→conversation (`shared/dto/chat.ts`, `config/api.ts`, `config/reducers/report.reducer.ts`,
`modules/chat/**`). Delete the document-picker modal trio, the `chunks`/`scope` request fields, the
`COMPLETED` gating, the 409 handler and the "3-question limit" copy. Rebuild the upload modal: trilogy
title, exactly 3 files, explicit position per file; no scope slider. Gate the upload control on
PATRICIAN — **no role-conditional rendering pattern exists today**, so this is new. Remove the profile
security-level slider.

### Stage 10 — Docs and verification `TODO`
Rewrite the ACL sections of `README.md` and `CLAUDE.md`; both still describe the owner-based matrix,
and `CLAUDE.md` cites a `resolveAllowedTiers` method that no longer exists.

## 6. Verification

The thesis claim is "the database refuses out-of-clearance rows", so it must be tested at that level.

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
