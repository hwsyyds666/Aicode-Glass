-- 性能专项 17·P1-4：为 agent_messages 增加 (sessionId, timestamp) 复合索引。
-- 会话消息回放/分页查询（WHERE sessionId = ? ORDER BY timestamp DESC LIMIT n）可沿索引有序扫描，
-- 不再需要单列索引查出全部行后再做 TEMP B-TREE 排序。
-- 旧单列索引 index_agent_messages_sessionId 被新索引左前缀（sessionId）完全覆盖，
-- 先删后建：既避免重复索引的写放大，也与实体 Index 注解声明保持一致（Room 打开时会校验索引集合）。
DROP INDEX IF EXISTS index_agent_messages_sessionId;
CREATE INDEX IF NOT EXISTS index_agent_messages_sessionId_timestamp ON agent_messages(sessionId, timestamp);
