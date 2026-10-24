package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.subAgentDataStore by preferencesDataStore(name = "subagent_prefs")

/** Bounded orchestration settings for child agents. Defaults deliberately cap both burst and total cost. */
@Singleton
class SubAgentSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val MAX_RUNNING_KEY = intPreferencesKey("max_running")
        val MAX_TOTAL_KEY = intPreferencesKey("max_total_per_parent")
        val TIMEOUT_MINUTES_KEY = intPreferencesKey("timeout_minutes")
        val SUPER_AGENT_HINT_SHOWN_KEY = booleanPreferencesKey("super_agent_hint_shown")
        const val DEFAULT_MAX_RUNNING = 3
        const val DEFAULT_MAX_TOTAL = 12
        const val DEFAULT_TIMEOUT_MINUTES = 20
    }

    val maxRunningFlow: Flow<Int> = context.subAgentDataStore.data.map { it[MAX_RUNNING_KEY].bounded(1, 8, DEFAULT_MAX_RUNNING) }
    val maxTotalPerParentFlow: Flow<Int> = context.subAgentDataStore.data.map { it[MAX_TOTAL_KEY].bounded(1, 32, DEFAULT_MAX_TOTAL) }
    val timeoutMinutesFlow: Flow<Int> = context.subAgentDataStore.data.map { it[TIMEOUT_MINUTES_KEY].bounded(1, 180, DEFAULT_TIMEOUT_MINUTES) }

    suspend fun maxRunning() = maxRunningFlow.first()
    suspend fun maxTotalPerParent() = maxTotalPerParentFlow.first()
    suspend fun timeoutMillis() = timeoutMinutesFlow.first() * 60_000L

    suspend fun setMaxRunning(value: Int) = context.subAgentDataStore.edit { it[MAX_RUNNING_KEY] = value.coerceIn(1, 8) }
    suspend fun setMaxTotalPerParent(value: Int) = context.subAgentDataStore.edit { it[MAX_TOTAL_KEY] = value.coerceIn(1, 32) }
    suspend fun setTimeoutMinutes(value: Int) = context.subAgentDataStore.edit { it[TIMEOUT_MINUTES_KEY] = value.coerceIn(1, 180) }

    suspend fun shouldShowSuperAgentHintOnce(): Boolean {
        var shouldShow = false
        context.subAgentDataStore.edit { prefs ->
            if (prefs[SUPER_AGENT_HINT_SHOWN_KEY] != true) {
                prefs[SUPER_AGENT_HINT_SHOWN_KEY] = true
                shouldShow = true
            }
        }
        return shouldShow
    }

    private fun Int?.bounded(min: Int, max: Int, fallback: Int): Int = this?.coerceIn(min, max) ?: fallback

}
