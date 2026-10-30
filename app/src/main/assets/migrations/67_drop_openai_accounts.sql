DELETE FROM ai_providers WHERE id LIKE 'openai_account_%';
DELETE FROM llm_call_records WHERE providerId LIKE 'openai_account_%';
UPDATE chat_sessions SET providerId = NULL, model = NULL WHERE providerId LIKE 'openai_account_%';
DROP TABLE IF EXISTS openai_accounts;
