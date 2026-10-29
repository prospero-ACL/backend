-- Moves document access control out of Java and into Postgres.
--
-- Clearance is a fixed hierarchy over book position, so it can be expressed by role membership
-- alone: no per-user session variables are needed. Each tier gets a real login role, and RLS
-- policies attached to those roles decide which rows exist at all. Retrieval code can no longer
-- leak a document by building the wrong filter string, because the rows are not there to return.
--
-- The app's own role stays superuser and therefore bypasses RLS. That is deliberate: JPA and the
-- public trilogy-title listing need to see everything. Only the three tier roles below are
-- constrained.

-- RLS predicates and the supporting index need jsonb; Spring AI created the column as json but
-- has always inserted with a ?::jsonb cast, so no data is reinterpreted here.
ALTER TABLE vector_store
    ALTER COLUMN metadata TYPE jsonb USING metadata::jsonb;

-- Roles are cluster-scoped, not database-scoped: they survive a DROP DATABASE and would make a
-- re-run of this migration fail, hence the existence check.
DO $$
DECLARE
    role_name text;
BEGIN
    FOREACH role_name IN ARRAY ARRAY['postgres_plebian', 'postgres_eques', 'postgres_patrician']
        LOOP
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
                EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE', role_name);
            END IF;
        END LOOP;
END
$$;

ALTER ROLE postgres_plebian   PASSWORD '${plebian_password}';
ALTER ROLE postgres_eques     PASSWORD '${eques_password}';
ALTER ROLE postgres_patrician PASSWORD '${patrician_password}';

GRANT USAGE ON SCHEMA public TO postgres_plebian, postgres_eques, postgres_patrician;

-- Every tier may read; only patricians may ingest. The SELECT grant is the coarse gate, the
-- policies below are the row-level one, and both must pass.
GRANT SELECT ON vector_store TO postgres_plebian, postgres_eques, postgres_patrician;
GRANT INSERT, UPDATE, DELETE ON vector_store TO postgres_patrician;

ALTER TABLE vector_store ENABLE ROW LEVEL SECURITY;

-- With RLS enabled and no matching policy, a role sees nothing — so the default is deny and any
-- tier we forget to write a policy for fails closed.
CREATE POLICY vector_store_plebian_read ON vector_store
    FOR SELECT TO postgres_plebian
    USING (metadata ->> 'book' = '1');

CREATE POLICY vector_store_eques_read ON vector_store
    FOR SELECT TO postgres_eques
    USING (metadata ->> 'book' IN ('1', '2'));

CREATE POLICY vector_store_patrician_read ON vector_store
    FOR SELECT TO postgres_patrician
    USING (true);

CREATE POLICY vector_store_patrician_write ON vector_store
    FOR INSERT TO postgres_patrician
    WITH CHECK (true);

-- Chunks predating the trilogy model have no 'book' key, so the predicate yields NULL and they stay
-- invisible to every tier but patrician. Failing closed on unlabelled data is the behaviour we want.
CREATE INDEX IF NOT EXISTS vector_store_book_idx
    ON vector_store ((metadata ->> 'book'));
