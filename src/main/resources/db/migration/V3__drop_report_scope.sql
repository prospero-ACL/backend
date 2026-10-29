-- The per-report DocumentScope was an input to the Java filter expression that RLS has replaced;
-- callers no longer choose what the retrieval sees, their clearance does.
ALTER TABLE report DROP COLUMN scope;
