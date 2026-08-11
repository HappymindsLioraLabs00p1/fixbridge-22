-- The precise repair state, alongside the coarse conversation_status.
--
-- Stored as varchar rather than a native enum on purpose. The other status columns use PostgreSQL
-- enums because their value sets are stable business vocabulary; this one mirrors an application
-- state machine that is expected to gain states. Adding a value to a PG enum needs ALTER TYPE,
-- which cannot run inside a transaction on older servers and so turns a routine state addition
-- into a migration with downtime characteristics. A varchar keeps that change to application code,
-- where the state machine already validates transitions.

ALTER TABLE repair_conversations
    ADD COLUMN IF NOT EXISTS repair_state varchar(40) NOT NULL DEFAULT 'NEW';

COMMENT ON COLUMN repair_conversations.repair_state IS
    'Precise state from the AI service state machine. conversation_status stays the coarse, stable value clients switch on.';
