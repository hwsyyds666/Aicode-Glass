CREATE TABLE IF NOT EXISTS `openai_accounts` (
    `id` TEXT NOT NULL,
    `providerId` TEXT NOT NULL,
    `subject` TEXT NOT NULL,
    `email` TEXT NOT NULL,
    `displayName` TEXT NOT NULL,
    `clientId` TEXT NOT NULL,
    `encryptedAccessToken` TEXT NOT NULL,
    `encryptedRefreshToken` TEXT NOT NULL,
    `encryptedIdToken` TEXT NOT NULL,
    `scopes` TEXT NOT NULL,
    `accessTokenExpiresAt` INTEGER NOT NULL,
    `status` TEXT NOT NULL DEFAULT 'CONNECTED',
    `lastError` TEXT,
    `createdAt` INTEGER NOT NULL,
    `updatedAt` INTEGER NOT NULL,
    PRIMARY KEY(`id`)
);
CREATE UNIQUE INDEX IF NOT EXISTS `index_openai_accounts_providerId` ON `openai_accounts` (`providerId`);
CREATE UNIQUE INDEX IF NOT EXISTS `index_openai_accounts_subject_clientId` ON `openai_accounts` (`subject`, `clientId`);
