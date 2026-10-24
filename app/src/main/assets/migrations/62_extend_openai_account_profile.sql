ALTER TABLE `openai_accounts` ADD COLUMN `hostId` TEXT NOT NULL DEFAULT '';
ALTER TABLE `openai_accounts` ADD COLUMN `modelCatalogJson` TEXT NOT NULL DEFAULT '[]';
