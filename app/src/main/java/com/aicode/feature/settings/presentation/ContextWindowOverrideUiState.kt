package com.aicode.feature.settings.presentation

/**
 * 「上下文窗口统一高级设置」页的 UI 快照。
 *
 * [enabled] 为实际生效状态——必填项（总上下文窗口、输出窗口）不齐时恒为 false，
 * 存储层与业务层各有一重兜底，UI 无需自行判定。
 */
data class ContextWindowOverrideUiState(
    val enabled: Boolean = false,
    val contextTokens: Int = 0,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null
)
