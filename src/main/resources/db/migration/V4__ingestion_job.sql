-- Embedding three novels takes minutes, so an upload becomes a tracked job the client polls
-- instead of a request held open. Doubles as an audit trail of who ingested what.
CREATE TABLE ingestion_job (
    id             uuid                        NOT NULL,
    trilogy        varchar(255)                NOT NULL,
    status         varchar(255)                NOT NULL,
    books_total    integer                     NOT NULL,
    books_done     integer                     NOT NULL,
    chunks_written integer                     NOT NULL,
    error          text,
    owner_id       uuid                        NOT NULL,
    created_at     timestamp(6) with time zone NOT NULL,
    updated_at     timestamp(6) with time zone NOT NULL,
    CONSTRAINT ingestion_job_pkey PRIMARY KEY (id),
    CONSTRAINT ingestion_job_owner_fkey FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT ingestion_job_status_check
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED'))
);
