-- Baseline: the schema exactly as Hibernate's ddl-auto=update left it, verified against pg_dump.
-- Quirks are preserved on purpose (singular "report" table, metadata json, enum check constraints);
-- later migrations change them, so a baselined database and a fresh one converge on the same state.

CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS users (
    id              uuid                        NOT NULL,
    provider_id     varchar(255)                NOT NULL,
    provider        varchar(255)                NOT NULL,
    name            varchar(255),
    email           varchar(255),
    avatar_url      varchar(255),
    theme           varchar(255),
    security_level  varchar(255)                NOT NULL,
    created_at      timestamp(6) with time zone NOT NULL,
    updated_at      timestamp(6) with time zone NOT NULL,
    CONSTRAINT users_pkey PRIMARY KEY (id),
    CONSTRAINT users_provider_id_key UNIQUE (provider_id),
    CONSTRAINT users_security_level_check
        CHECK (security_level IN ('PLEBIAN', 'EQUES', 'PATRICIAN'))
);

CREATE TABLE IF NOT EXISTS report (
    id         uuid                        NOT NULL,
    owner_id   uuid                        NOT NULL,
    status     varchar(255)                NOT NULL,
    scope      varchar(255)                NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT report_pkey PRIMARY KEY (id),
    CONSTRAINT report_owner_fkey FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT report_status_check CHECK (status IN ('DRAFT', 'IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT report_scope_check CHECK (scope IN ('PUBLIC', 'RESTRICTED', 'ELEVATED'))
);

CREATE TABLE IF NOT EXISTS user_prompt (
    id         uuid                        NOT NULL,
    report_id  uuid                        NOT NULL,
    "position" integer                     NOT NULL,
    text       text                        NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT user_prompt_pkey PRIMARY KEY (id),
    CONSTRAINT user_prompt_report_fkey FOREIGN KEY (report_id) REFERENCES report (id)
);

CREATE TABLE IF NOT EXISTS llm_reply (
    id         uuid                        NOT NULL,
    report_id  uuid                        NOT NULL,
    "position" integer                     NOT NULL,
    text       text                        NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT llm_reply_pkey PRIMARY KEY (id),
    CONSTRAINT llm_reply_report_fkey FOREIGN KEY (report_id) REFERENCES report (id)
);

CREATE TABLE IF NOT EXISTS report_chunks (
    id        uuid NOT NULL,
    report_id uuid NOT NULL,
    chunk_id  uuid NOT NULL,
    CONSTRAINT report_chunks_pkey PRIMARY KEY (id),
    CONSTRAINT report_chunks_report_fkey FOREIGN KEY (report_id) REFERENCES report (id)
);

-- Owned by Spring AI's PgVectorStore until now; Flyway takes over so the table's shape,
-- and the RLS policies added in Stage 2, live in version control.
CREATE TABLE IF NOT EXISTS vector_store (
    id        uuid DEFAULT uuid_generate_v4() NOT NULL,
    content   text,
    metadata  json,
    embedding vector(1536),
    CONSTRAINT vector_store_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS spring_ai_vector_index
    ON vector_store USING hnsw (embedding vector_cosine_ops);
