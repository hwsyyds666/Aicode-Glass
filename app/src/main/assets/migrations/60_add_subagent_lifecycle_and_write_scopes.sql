ALTER TABLE chat_sessions ADD COLUMN subagentStatus TEXT;
ALTER TABLE chat_sessions ADD COLUMN allowedWritePaths TEXT;
ALTER TABLE chat_sessions ADD COLUMN allowedReadPaths TEXT;
ALTER TABLE chat_sessions ADD COLUMN subagentCreatedCount INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chat_sessions ADD COLUMN superAgentEnabled INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chat_sessions ADD COLUMN dispatchMessageId TEXT;
UPDATE chat_sessions SET subagentStatus = 'COMPLETED' WHERE parentId IS NOT NULL;
UPDATE chat_sessions SET subagentCreatedCount = (
    SELECT COUNT(*) FROM chat_sessions AS child WHERE child.parentId = chat_sessions.id
);
