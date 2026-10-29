-- A Report was a conversation capped at three questions; the cap is gone, so it is renamed for
-- what it is. RENAME rather than create-and-copy keeps existing rows and their foreign keys intact.
ALTER TABLE report RENAME TO conversation;
ALTER TABLE conversation RENAME CONSTRAINT report_pkey TO conversation_pkey;

-- DRAFT / IN_PROGRESS / COMPLETED only ever tracked progress towards the cap.
ALTER TABLE conversation DROP COLUMN status;

ALTER TABLE user_prompt RENAME COLUMN report_id TO conversation_id;
ALTER TABLE llm_reply RENAME COLUMN report_id TO conversation_id;

-- Never written and never read.
DROP TABLE report_chunks;

-- A baselined database carries Hibernate's generated foreign-key names (fk4hbg…), a fresh one
-- carries V1's, so the keys are dropped by lookup and recreated under one name for both.
DO $$
DECLARE
    fk record;
BEGIN
    FOR fk IN
        SELECT conrelid::regclass AS tbl, conname
        FROM pg_constraint
        WHERE contype = 'f'
          AND conrelid IN ('conversation'::regclass, 'user_prompt'::regclass, 'llm_reply'::regclass)
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', fk.tbl, fk.conname);
    END LOOP;
END $$;

ALTER TABLE conversation
    ADD CONSTRAINT conversation_owner_fkey FOREIGN KEY (owner_id) REFERENCES users (id);
ALTER TABLE user_prompt
    ADD CONSTRAINT user_prompt_conversation_fkey
        FOREIGN KEY (conversation_id) REFERENCES conversation (id);
ALTER TABLE llm_reply
    ADD CONSTRAINT llm_reply_conversation_fkey
        FOREIGN KEY (conversation_id) REFERENCES conversation (id);

-- Prompts and replies are paired by position, so a position may appear at most once per side.
ALTER TABLE user_prompt
    ADD CONSTRAINT user_prompt_conversation_position_key UNIQUE (conversation_id, "position");
ALTER TABLE llm_reply
    ADD CONSTRAINT llm_reply_conversation_position_key UNIQUE (conversation_id, "position");
