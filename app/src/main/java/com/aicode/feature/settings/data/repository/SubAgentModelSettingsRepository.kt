package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.subAgentModelDataStore by preferencesDataStore(
    name = "subagent_model_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

enum class SubAgentModelTier { FAST, POWER }

data class SubAgentModelChoice(
    val providerId: String = "",
    val model: String = "",
    val reasoningEffort: String = "medium"
) {
    val followsParent: Boolean get() = providerId.isBlank() || model.isBlank()
}

/** Two eagerly persisted model tiers shared by every built-in subagent dispatch path. */
@Singleton
class SubAgentModelSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val legacyMigrationKey = booleanPreferencesKey("legacy_explore_model_migrated")
    private val fastConfiguredKey = booleanPreferencesKey("fast_configured")
    private val powerConfiguredKey = booleanPreferencesKey("power_configured")
    private fun key(tier: SubAgentModelTier, field: String) = stringPreferencesKey("${tier.name.lowercase()}_$field")

    fun choiceFlow(tier: SubAgentModelTier): Flow<SubAgentModelChoice> = context.subAgentModelDataStore.data.map { prefs ->
        SubAgentModelChoice(
            providerId = prefs[key(tier, "provider_id")].orEmpty(),
            model = prefs[key(tier, "model")].orEmpty(),
            reasoningEffort = prefs[key(tier, "reasoning")].orEmpty().ifBlank { if (tier == SubAgentModelTier.POWER) "high" else "medium" }
        )
    }

    suspend fun getChoice(tier: SubAgentModelTier): SubAgentModelChoice = choiceFlow(tier).first()

    suspend fun setChoice(tier: SubAgentModelTier, providerId: String, model: String, reasoningEffort: String) {
        context.subAgentModelDataStore.edit { prefs ->
            prefs[if (tier == SubAgentModelTier.FAST) fastConfiguredKey else powerConfiguredKey] = true
            prefs[key(tier, "provider_id")] = providerId
            prefs[key(tier, "model")] = model
            prefs[key(tier, "reasoning")] = reasoningEffort.lowercase()
                .takeIf { value -> com.aicode.feature.agent.domain.model.ReasoningEffort.entries.any { it.apiValue == value } }
                ?: if (tier == SubAgentModelTier.POWER) "high" else "medium"
        }
    }

    suspend fun clearChoice(tier: SubAgentModelTier) {
        context.subAgentModelDataStore.edit { prefs ->
            prefs[if (tier == SubAgentModelTier.FAST) fastConfiguredKey else powerConfiguredKey] = true
            prefs.remove(key(tier, "provider_id"))
            prefs.remove(key(tier, "model"))
            prefs.remove(key(tier, "reasoning"))
        }
    }

    suspend fun isLegacyMigrationComplete(): Boolean = context.subAgentModelDataStore.data.first()[legacyMigrationKey] == true

    suspend fun completeLegacyMigration() {
        context.subAgentModelDataStore.edit { it[legacyMigrationKey] = true }
    }

    /** Migrate Explore's legacy model only when the user has not already configured the fast tier. */
    suspend fun migrateLegacyExploreChoice(providerId: String, model: String, reasoningEffort: String) {
        context.subAgentModelDataStore.edit { prefs ->
            if (prefs[fastConfiguredKey] != true && providerId.isNotBlank() && model.isNotBlank()) {
                prefs[key(SubAgentModelTier.FAST, "provider_id")] = providerId
                prefs[key(SubAgentModelTier.FAST, "model")] = model
                prefs[key(SubAgentModelTier.FAST, "reasoning")] = normalizeReasoning(reasoningEffort, SubAgentModelTier.FAST)
                prefs[fastConfiguredKey] = true
            }
            prefs[legacyMigrationKey] = true
        }
    }

    suspend fun shouldShowLegacyNoticeOnce(): Boolean {
        var shouldShow = false
        context.subAgentModelDataStore.edit { prefs ->
            val shown = booleanPreferencesKey("legacy_definition_notice_shown")
            if (prefs[shown] != true) {
                prefs[shown] = true
                shouldShow = true
            }
        }
        return shouldShow
    }

    private fun normalizeReasoning(value: String, tier: SubAgentModelTier): String =
        value.trim().lowercase().takeIf { candidate ->
            com.aicode.feature.agent.domain.model.ReasoningEffort.entries.any { it.apiValue == candidate }
        } ?: if (tier == SubAgentModelTier.POWER) "high" else "medium"
}
