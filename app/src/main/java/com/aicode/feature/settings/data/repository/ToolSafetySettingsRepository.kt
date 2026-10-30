package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.toolSafetyDataStore by preferencesDataStore(name = "tool_safety_prefs")

/**
 * 命令执行授权挡位（作用于 BUILD 模式下 shell 类工具的评估；PLAN 在更早的策略层已经只读硬拒绝；ASK 同样只读）。
 * - [AUTONOMOUS]：自主执行——由「AI 命令审查器」独立审核每条命令，审查通过直接放行；
 *   审查拒绝/不确定/失败一律转人工审批（拒绝时附审查器给出的危险说明）。
 * - [RULE_BASED]：规则拦截——命中高危拦截规则的命令弹人工确认，其余命令自动执行（默认）。
 * - [FULL_ACCESS]：完全权限——普通命令直接执行；删除命令仍逐次授权，受保护目标始终硬拦截。
 */
enum class CommandAuthLevel { AUTONOMOUS, RULE_BASED, FULL_ACCESS }

/**
 * 持久化「禁用安全拦截」开关，默认关闭。
 *
 * 开启后，通用命令拦截规则与 Shizuku 逐次确认关闭；BUILD 使用完全权限挡位，PLAN 的只读边界、
 * 删除命令的逐次授权和根目录、系统关键目录、工作区根目录等硬拦截始终有效，ASK 的只读硬拒绝也不受影响。
 */
@Singleton
class ToolSafetySettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private val DISABLED_KEY = booleanPreferencesKey("disable_safety_interception")
        private val AUTH_LEVEL_KEY = stringPreferencesKey("command_auth_level")
        private val REVIEW_TIMEOUT_SECONDS_KEY = intPreferencesKey("command_review_timeout_seconds")
        /**
         * AI 审查器默认等待时间。审查模型（尤其云端大模型）冷启动 + 排队经常超过 15s，
         * 超时即转人工会让自主模式频繁被打断，默认放宽到 50s。
         */
        const val DEFAULT_REVIEW_TIMEOUT_SECONDS = 50
        /** 支持的档位：历史值（10/20）不在表内会自动回退到默认 50s。 */
        private val SUPPORTED_REVIEW_TIMEOUT_SECONDS = setOf(15, 30, 50)
    }

    /** 当前开关流；未设置时回退到 false（默认保留安全拦截）。 */
    val disableSafetyInterceptionFlow: Flow<Boolean> =
        context.toolSafetyDataStore.data.map { it[DISABLED_KEY] ?: false }

    suspend fun setDisableSafetyInterception(disabled: Boolean) {
        context.toolSafetyDataStore.edit { it[DISABLED_KEY] = disabled }
    }

    /** 读取一次当前值（权限评估时用）。 */
    suspend fun isSafetyInterceptionDisabled(): Boolean = disableSafetyInterceptionFlow.first()

    /** 当前命令授权挡位流；未设置时回退 [CommandAuthLevel.RULE_BASED]（规则拦截）。 */
    val commandAuthLevelFlow: Flow<CommandAuthLevel> =
        context.toolSafetyDataStore.data.map { prefs ->
            prefs[AUTH_LEVEL_KEY]?.let { runCatching { CommandAuthLevel.valueOf(it) }.getOrNull() }
                ?: CommandAuthLevel.RULE_BASED
        }

    suspend fun setCommandAuthLevel(level: CommandAuthLevel) {
        context.toolSafetyDataStore.edit { it[AUTH_LEVEL_KEY] = level.name }
    }

    /** 读取一次当前挡位（权限评估时用）。 */
    suspend fun getCommandAuthLevel(): CommandAuthLevel = commandAuthLevelFlow.first()

    /** Autonomous reviewer timeout; invalid/old values fall back to the safe default. */
    val commandReviewTimeoutSecondsFlow: Flow<Int> = context.toolSafetyDataStore.data.map { prefs ->
        prefs[REVIEW_TIMEOUT_SECONDS_KEY]
            ?.takeIf { it in SUPPORTED_REVIEW_TIMEOUT_SECONDS }
            ?: DEFAULT_REVIEW_TIMEOUT_SECONDS
    }

    suspend fun getCommandReviewTimeoutSeconds(): Int = commandReviewTimeoutSecondsFlow.first()

    suspend fun setCommandReviewTimeoutSeconds(seconds: Int) {
        context.toolSafetyDataStore.edit { prefs ->
            prefs[REVIEW_TIMEOUT_SECONDS_KEY] = seconds
                .takeIf { it in SUPPORTED_REVIEW_TIMEOUT_SECONDS }
                ?: DEFAULT_REVIEW_TIMEOUT_SECONDS
        }
    }
}
