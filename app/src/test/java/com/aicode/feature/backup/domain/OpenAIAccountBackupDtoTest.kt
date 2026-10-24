package com.aicode.feature.backup.domain

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIAccountBackupDtoTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `account backup contains profile metadata but no credential fields`() {
        val account = OpenAIAccountBackupDto(
            id = "account-1",
            providerId = "openai_account_account-1",
            clientId = "public-client-id",
            email = "person@example.com",
            displayName = "Person",
            hostId = "urn:uuid:2d7917e1-34a7-4f1e-9c10-2649a3fc7db4",
            modelCatalogJson = "[{\"slug\":\"gpt-6.1-sol\",\"display_name\":\"GPT 6.1\"}]"
        )

        val encoded = json.encodeToString(OpenAIAccountBackupDto.serializer(), account)

        assertTrue(encoded.contains("person@example.com"))
        assertTrue(encoded.contains("public-client-id"))
        assertFalse(encoded.contains("accessToken", ignoreCase = true))
        assertFalse(encoded.contains("refreshToken", ignoreCase = true))
        assertFalse(encoded.contains("idToken", ignoreCase = true))
        assertFalse(encoded.contains("secret-token"))
    }

    @Test
    fun `old metadata without account field remains readable`() {
        val oldMetadata = """{"schemaVersion":62,"createdAt":1}"""

        val decoded = json.decodeFromString(BackupMetadata.serializer(), oldMetadata)

        assertEquals(emptyList<OpenAIAccountBackupDto>(), decoded.openAIAccounts)
    }
}
