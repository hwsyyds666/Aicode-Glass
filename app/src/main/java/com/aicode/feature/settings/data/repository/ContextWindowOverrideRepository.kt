package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.contextWindowOverrideDataStore by preferencesDataStore(
    name = "context_window_override_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 全局「上下文窗口统一值」的持久化。
 *
 * 开启后，这里的数值作为所有模型的上限：更大的模型会被限制到该窗口，更小的模型仍按自身能力
 * 运行；模型没有已知窗口时交给服务端的真实超限响应触发恢复。关闭则回到各模型自己的值。
 *
 * 必填约定：总上下文窗口与输出窗口为必填（业务侧保证）——缺少任一项都视为配置不完整，
 * 上层会据此自动关闭开关，避免留下「开关开着但没有生效数值」的歧义状态。输入窗口可选，
 * 留空表示输入上限 = 总窗口 - 输出预留（由 [com.aicode.feature.settings.domain.model.ModelContextPolicy] 计算）。
 */
@Singleton
class ContextWindowOverrideRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("override_enabled")
        val CONTEXT_TOKENS = intPreferencesKey("context_tokens")
        val INPUT_TOKENS = intPreferencesKey("input_tokens")
        val OUTPUT_TOKENS = intPreferencesKey("output_tokens")
    }

    /**
     * 当前快照流。为 UI 与策略层统一出口：
     * 开关关闭时 [ContextWindowOverride.enabled] 为 false（数值仍保留着，便于再次开启时回填）。
     */
    val overrideFlow: Flow<ContextWindowOverride> =
        context.contextWindowOverrideDataStore.data.map { prefs -> prefs.toOverride() }

    /** 一次性读取当前快照（策略层冷读用）。 */
    suspend fun getOverride(): ContextWindowOverride =
        context.contextWindowOverrideDataStore.data.first().toOverride()

    /**
     * 保存配置，但保留当前开关状态；编辑已关闭的设置不会意外启用全局覆盖。
     *
     * @return false 表示数值越界或输入/输出预算超过总窗口，原配置不变。
     */
    suspend fun save(contextTokens: Int, inputTokens: Int?, outputTokens: Int?): Boolean {
        if (!ContextWindowOverridePolicy.isValid(contextTokens, inputTokens, outputTokens)) return false
        context.contextWindowOverrideDataStore.edit { prefs ->
            val wasEnabled = prefs[Keys.ENABLED] ?: false
            prefs[Keys.CONTEXT_TOKENS] = contextTokens
            if (inputTokens != null) {
                prefs[Keys.INPUT_TOKENS] = inputTokens
            } else {
                prefs.remove(Keys.INPUT_TOKENS)
            }
            if (outputTokens != null) {
                prefs[Keys.OUTPUT_TOKENS] = outputTokens
            } else {
                prefs.remove(Keys.OUTPUT_TOKENS)
            }
            prefs[Keys.ENABLED] = wasEnabled
        }
        return true
    }

    /** 关闭开关，回到各模型自己的上下文窗口；已填数值保留以便重新开启。 */
    suspend fun setEnabled(enabled: Boolean) {
        if (enabled) {
            // 重新开启前先自检：必填仍齐备才允许打开
            val current = getOverride()
            if (!ContextWindowOverridePolicy.isValid(current.contextTokens, current.inputTokens, current.outputTokens)) return
        }
        context.contextWindowOverrideDataStore.edit { prefs ->
            prefs[Keys.ENABLED] = enabled
        }
    }

    private fun Preferences.toOverride(): ContextWindowOverride {
        val enabled = this[Keys.ENABLED] ?: false
        val contextTokens = this[Keys.CONTEXT_TOKENS] ?: 0
        val inputTokens = this[Keys.INPUT_TOKENS]?.takeIf { it > 0 }
        val outputTokens = this[Keys.OUTPUT_TOKENS]?.takeIf { it > 0 }
        // 存储层兜底：必填不齐时即便 ENABLED 被写成 true 也不生效，避免出现「开着但没值」的状态。
        val effective = enabled && ContextWindowOverridePolicy.isValid(contextTokens, inputTokens, outputTokens)
        return ContextWindowOverride(effective, contextTokens, inputTokens, outputTokens)
    }
}

/**
 * 上下文窗口统一值的快照。
 *
 * @param enabled 是否生效（已满足必填项才为 true）。
 * @param contextTokens 总上下文窗口。
 * @param inputTokens 输入上限，null 表示由策略层按「总窗口 - 输出预留」推导。
 * @param outputTokens 输出上限。
 */
data class ContextWindowOverride(
    val enabled: Boolean,
    val contextTokens: Int,
    val inputTokens: Int?,
    val outputTokens: Int?
)

/** Defends persisted values and their cross-field relationship before affecting request budgeting. */
object ContextWindowOverridePolicy {
    const val MAX_TOKEN_VALUE = 10_000_000

    fun isValid(contextTokens: Int, inputTokens: Int?, outputTokens: Int?): Boolean {
        if (contextTokens !in 1..MAX_TOKEN_VALUE) return false
        if (outputTokens == null || outputTokens !in 1..contextTokens) return false
        if (inputTokens != null && inputTokens !in 1..contextTokens) return false
        return inputTokens == null || inputTokens.toLong() + outputTokens.toLong() <= contextTokens.toLong()
    }
}
