# Prospero RAG ACL Backend App

## Description

This is a Java Spring Boot project that acts as the backend for the Prospero RAG ACL web
application: a retrieval-augmented generation (RAG) chat over a knowledge base of novel trilogies,
where what the LLM may draw on depends on the user's clearance. It is a REST API that stores
conversations, ingests trilogies into a pgvector store, and queries the OpenAI API.

The point of the project is **where** access control lives: it is enforced by Postgres roles and
Row-Level Security, not by application code, so a bug in the Java side cannot leak a document.

## Authentication and Authorization

Login is via Google or GitHub OAuth2 (Spring Security). On success the app issues a stateless JWT
in an httpOnly `access_token` cookie (1 hour expiry) — no server-side sessions, no bearer header.
Logout clears that cookie; there is no server-side token revocation, so a copied token stays valid
until it naturally expires.

Each user has a clearance: `PLEBIAN`, `EQUES` or `PATRICIAN`. New users start as `PLEBIAN`. Users
cannot change their own clearance — it decides which database role their queries run as — so it is
assigned by an administrator with `../infra/set-clearance.sh`.

## ACL Implementation

Clearance maps to a book's position in its trilogy:

|           | Book 1 | Book 2 | Book 3 |
| --------- | ------ | ------ | ------ |
| Plebian   | ✓      |        |        |
| Eques     | ✓      | ✓      |        |
| Patrician | ✓      | ✓      | ✓      |

- Everyone sees every trilogy **title**; only the text behind it is gated.
- Nobody picks documents for a question — retrieval automatically uses everything the user is
  cleared to read.
- There is no per-document ownership or visibility.
- Only patricians can upload a trilogy.

How it is enforced:

1. Each clearance has its own non-superuser Postgres login role (`postgres_plebian`,
   `postgres_eques`, `postgres_patrician`).
2. Row-Level Security on `vector_store` gives each role one policy over the chunk's `book`
   metadata. Anything without a `book` label is invisible to every tier (fail closed). Only
   `postgres_patrician` may insert.
3. On every request the backend looks up the caller's clearance and runs vector searches on a
   connection pool logged in as the matching role. The application's own connection (used for
   users and conversations) is never used to read chunks.

`../infra/verify-acl.sh` checks the policies directly in SQL, with no Java involved.

**What this does not prove:** RLS guarantees that retrieval cannot return forbidden text. It cannot
stop the model answering from what it memorised during training — and well-known novels are
memorised. That is handled by prompting and evaluation; see `REFACTOR.md` §4.

## Document insertion

A patrician uploads a trilogy as **a title plus exactly three PDFs**, one per book position. The
upload returns immediately (`202`) with an ingestion job; the text is extracted, split into ~500
token chunks, embedded and stored in the background, and the client polls the job for progress.
Uploading a trilogy that already exists replaces it.

## Conversations

Questions are asked in free-form conversations with no question limit. The most recent turns are
sent back to the model as history, so follow-up questions work.

---

### na pernei mono tou ta sosta docs

### diaforetika "tipou" document, (kanonismoi, FEK)

### o owner vlepei panta

### No user CRUD

### apo 500 to mikrotera docs
