package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.onboarding.presentation.onboardingTarget
import com.aicode.feature.settings.data.local.ModelSheetCollapseStore
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.modelMetadataKey
import com.aicode.feature.settings.data.repository.SubAgentModelChoice
import com.aicode.feature.settings.data.repository.SubAgentModelTier
import com.aicode.feature.agent.domain.model.ReasoningEffort
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDown
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Check
import compose.icons.feathericons.Camera
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Image
import compose.icons.feathericons.Minimize2
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Type
import compose.icons.feathericons.X
import compose.icons.feathericons.Zap
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.GitBranch

/**
 * 默认模型二级页：集中管理应用中的默认/特定用途模型设置（如识图模型、压缩模型）。
 */
@Composable
internal fun DefaultModelsSection(
    providers: List<AIProviderConfig>,
    visionProviderId: String,
    visionModel: String,
    compactionProviderId: String,
    compactionModel: String,
    titleProviderId: String,
    titleModel: String,
    imageGenProviderId: String,
    imageGenModel: String,
    reviewerProviderId: String,
    reviewerModel: String,
    fastSubAgentModel: SubAgentModelChoice,
    powerSubAgentModel: SubAgentModelChoice,
    maxSubAgentRunning: Int,
    maxSubAgentTotal: Int,
    subAgentTimeoutMinutes: Int,
    modelMetadata: Map<String, ModelMetadata>,
    onLoadMetadata: () -> Unit,
    onSelectVisionModel: (providerId: String, model: String) -> Unit,
    onClearVisionModel: () -> Unit,
    onSelectCompactionModel: (providerId: String, model: String) -> Unit,
    onClearCompactionModel: () -> Unit,
    onSelectTitleModel: (providerId: String, model: String) -> Unit,
    onClearTitleModel: () -> Unit,
    onSelectImageGenModel: (providerId: String, model: String) -> Unit,
    onClearImageGenModel: () -> Unit,
    onSelectReviewerModel: (providerId: String, model: String) -> Unit,
    onClearReviewerModel: () -> Unit,
    onSelectSubAgentModel: (tier: SubAgentModelTier, providerId: String, model: String) -> Unit,
    onClearSubAgentModel: (tier: SubAgentModelTier) -> Unit,
    onChangeSubAgentReasoning: (tier: SubAgentModelTier, effort: String) -> Unit,
    onSetMaxSubAgentRunning: (Int) -> Unit,
    onSetMaxSubAgentTotal: (Int) -> Unit,
    onSetSubAgentTimeoutMinutes: (Int) -> Unit,
    /** 上下文窗口统一值是否生效（仅用于副标题展示，开关本身在二级页里）。 */
    contextWindowEnabled: Boolean,
    /** 点击整行进入二级设置页（开关与三个窗口值都在二级页里配置）。 */
    onOpenContextWindow: () -> Unit,
) {
    var showVisionSheet by remember { mutableStateOf(false) }
    var showCompactionSheet by remember { mutableStateOf(false) }
    var showTitleSheet by remember { mutableStateOf(false) }
    var showImageGenSheet by remember { mutableStateOf(false) }
    var showReviewerSheet by remember { mutableStateOf(false) }
    var showFastSubAgentSheet by remember { mutableStateOf(false) }
    var showPowerSubAgentSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onLoadMetadata() }

    val visionValue = if (visionProviderId.isBlank() || visionModel.isBlank()) {
        stringResource(R.string.settings_vision_follow_chat)
    } else {
        visionModel
    }

    val compactionValue = if (compactionProviderId.isBlank() || compactionModel.isBlank()) {
        stringResource(R.string.settings_compaction_follow_chat)
    } else {
        compactionModel
    }

    val titleValue = if (titleProviderId.isBlank() || titleModel.isBlank()) {
        stringResource(R.string.settings_title_follow_chat)
    } else {
        titleModel
    }

    val imageGenValue = if (imageGenProviderId.isBlank() || imageGenModel.isBlank()) {
        stringResource(R.string.settings_image_gen_unconfigured)
    } else {
        imageGenModel
    }

    val reviewerValue = if (reviewerProviderId.isBlank() || reviewerModel.isBlank()) {
        stringResource(R.string.settings_reviewer_follow_chat)
    } else {
        reviewerModel
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Image,
                title = stringResource(R.string.settings_vision_model),
                onClick = { showVisionSheet = true },
                trailing = {
                    Text(
                        text = visionValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Minimize2,
                title = stringResource(R.string.settings_compaction_model),
                onClick = { showCompactionSheet = true },
                trailing = {
                    Text(
                        text = compactionValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Type,
                title = stringResource(R.string.settings_title_model),
                onClick = { showTitleSheet = true },
                trailing = {
                    Text(
                        text = titleValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Camera,
                title = stringResource(R.string.settings_image_gen_model),
                onClick = { showImageGenSheet = true },
                trailing = {
                    Text(
                        text = imageGenValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Shield,
                title = stringResource(R.string.settings_reviewer_model),
                onClick = { showReviewerSheet = true },
                trailing = {
                    Text(
                        text = reviewerValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = FeatherIcons.GitBranch,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(Spacing.md))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.settings_subagent_models_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(R.string.settings_subagent_models_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        SettingsGroup {
            SubAgentModelTierRow(
                icon = FeatherIcons.Zap,
                title = stringResource(R.string.settings_subagent_fast_title),
                description = stringResource(R.string.settings_subagent_fast_description),
                providers = providers,
                choice = fastSubAgentModel,
                onSelectModel = { showFastSubAgentSheet = true },
                onChangeEffort = { onChangeSubAgentReasoning(SubAgentModelTier.FAST, it) }
            )
            SettingsDivider()
            SubAgentModelTierRow(
                icon = FeatherIcons.Cpu,
                title = stringResource(R.string.settings_subagent_power_title),
                description = stringResource(R.string.settings_subagent_power_description),
                providers = providers,
                choice = powerSubAgentModel,
                onSelectModel = { showPowerSubAgentSheet = true },
                onChangeEffort = { onChangeSubAgentReasoning(SubAgentModelTier.POWER, it) }
            )
        }
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = FeatherIcons.Sliders,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(Spacing.md))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.settings_subagent_limits_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(R.string.settings_subagent_limits_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        SettingsGroup {
            SubAgentLimitRow(
                title = stringResource(R.string.settings_subagent_max_running),
                subtitle = stringResource(R.string.settings_subagent_max_running_description),
                value = maxSubAgentRunning,
                values = (1..8).toList(),
                displayValue = maxSubAgentRunning.toString(),
                displayOption = { it.toString() },
                onSelect = onSetMaxSubAgentRunning
            )
            SettingsDivider()
            SubAgentLimitRow(
                title = stringResource(R.string.settings_subagent_max_total),
                subtitle = stringResource(R.string.settings_subagent_max_total_description),
                value = maxSubAgentTotal,
                values = listOf(1, 2, 3, 4, 6, 8, 12, 16, 20, 24, 32),
                displayValue = maxSubAgentTotal.toString(),
                displayOption = { it.toString() },
                onSelect = onSetMaxSubAgentTotal
            )
            SettingsDivider()
            SubAgentLimitRow(
                title = stringResource(R.string.settings_subagent_timeout),
                subtitle = stringResource(R.string.settings_subagent_timeout_description),
                value = subAgentTimeoutMinutes,
                values = listOf(1, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180),
                displayValue = stringResource(R.string.settings_subagent_minutes_value, subAgentTimeoutMinutes),
                displayOption = { stringResource(R.string.settings_subagent_minutes_value, it) },
                onSelect = onSetSubAgentTimeoutMinutes
            )
        }

        // ── 上下文窗口统一高级设置 ──
        // 入口行只负责「进二级页」，真正的开关与三个窗口值都在二级页里设置——
        // 之前在外层也放了个开关，但必填校验会把它弹回关闭态（等于打不开），反而误导。
        SettingsGroupHeader(text = stringResource(R.string.ctx_window_group))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Sliders,
                title = stringResource(R.string.ctx_window_enable),
                subtitle = if (contextWindowEnabled) {
                    stringResource(R.string.ctx_window_row_active)
                } else {
                    stringResource(R.string.ctx_window_row_inactive)
                },
                onClick = onOpenContextWindow,
                trailing = {
                    Icon(
                        imageVector = FeatherIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
    }

    if (showVisionSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_vision_model),
            noModelsText = stringResource(R.string.vision_no_models),
            providers = providers,
            currentProviderId = visionProviderId,
            currentModel = visionModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectVisionModel(pid, model)
                showVisionSheet = false
            },
            onClear = {
                onClearVisionModel()
                showVisionSheet = false
            },
            onDismiss = { showVisionSheet = false },
        )
    }

    if (showCompactionSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_compaction_model),
            noModelsText = stringResource(R.string.compaction_no_models),
            providers = providers,
            currentProviderId = compactionProviderId,
            currentModel = compactionModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectCompactionModel(pid, model)
                showCompactionSheet = false
            },
            onClear = {
                onClearCompactionModel()
                showCompactionSheet = false
            },
            onDismiss = { showCompactionSheet = false },
        )
    }

    if (showTitleSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_title_model),
            noModelsText = stringResource(R.string.title_no_models),
            providers = providers,
            currentProviderId = titleProviderId,
            currentModel = titleModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectTitleModel(pid, model)
                showTitleSheet = false
            },
            onClear = {
                onClearTitleModel()
                showTitleSheet = false
            },
            onDismiss = { showTitleSheet = false },
        )
    }

    if (showImageGenSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_image_gen_model),
            noModelsText = stringResource(R.string.image_gen_no_models),
            providers = providers,
            currentProviderId = imageGenProviderId,
            currentModel = imageGenModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectImageGenModel(pid, model)
                showImageGenSheet = false
            },
            onClear = {
                onClearImageGenModel()
                showImageGenSheet = false
            },
            onDismiss = { showImageGenSheet = false },
        )
    }

    if (showReviewerSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_reviewer_model),
            noModelsText = stringResource(R.string.reviewer_no_models),
            providers = providers,
            currentProviderId = reviewerProviderId,
            currentModel = reviewerModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectReviewerModel(pid, model)
                showReviewerSheet = false
            },
            onClear = {
                onClearReviewerModel()
                showReviewerSheet = false
            },
            onDismiss = { showReviewerSheet = false },
        )
    }

    if (showFastSubAgentSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_subagent_fast_title),
            noModelsText = stringResource(R.string.settings_subagent_no_models),
            providers = providers,
            currentProviderId = fastSubAgentModel.providerId,
            currentModel = fastSubAgentModel.model,
            modelMetadata = modelMetadata,
            onSelect = { pid, model -> onSelectSubAgentModel(SubAgentModelTier.FAST, pid, model); showFastSubAgentSheet = false },
            onClear = { onClearSubAgentModel(SubAgentModelTier.FAST); showFastSubAgentSheet = false },
            onDismiss = { showFastSubAgentSheet = false },
        )
    }

    if (showPowerSubAgentSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_subagent_power_title),
            noModelsText = stringResource(R.string.settings_subagent_no_models),
            providers = providers,
            currentProviderId = powerSubAgentModel.providerId,
            currentModel = powerSubAgentModel.model,
            modelMetadata = modelMetadata,
            onSelect = { pid, model -> onSelectSubAgentModel(SubAgentModelTier.POWER, pid, model); showPowerSubAgentSheet = false },
            onClear = { onClearSubAgentModel(SubAgentModelTier.POWER); showPowerSubAgentSheet = false },
            onDismiss = { showPowerSubAgentSheet = false },
        )
    }
}

/**
 * 通用单选弹层：与模型选择弹窗（[ModelSelectionSheet]）同一套底部弹层规范，
 * 替代早年散落的 DropdownMenu（锚定式小浮层，与设置页其余交互完全两种语言）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> OptionPickerSheet(
    title: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = true,
        containerColor = settingsPageBackground()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = Spacing.md)
            )
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                options.forEach { option ->
                    val isSelected = option == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onSelect(option) }
                            .padding(horizontal = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = optionLabel(option),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = FeatherIcons.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubAgentLimitRow(
    title: String,
    subtitle: String,
    value: Int,
    values: List<Int>,
    displayValue: String,
    displayOption: @Composable (Int) -> String,
    onSelect: (Int) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    SettingsRow(
        icon = FeatherIcons.Sliders,
        title = title,
        subtitle = subtitle,
        onClick = { showPicker = true },
        trailing = {
            Text(
                text = displayValue,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
    )
    if (showPicker) {
        OptionPickerSheet(
            title = title,
            options = values,
            selected = value,
            optionLabel = { option -> displayOption(option) },
            onSelect = { picked ->
                onSelect(picked)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

@Composable
private fun SubAgentModelTierRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    providers: List<AIProviderConfig>,
    choice: SubAgentModelChoice,
    onSelectModel: () -> Unit,
    onChangeEffort: (String) -> Unit
) {
    var showEffortPicker by remember { mutableStateOf(false) }
    val providerName = providers.firstOrNull { it.id == choice.providerId }?.name
    val modelLabel = if (choice.followsParent) {
        stringResource(R.string.settings_subagent_follow_chat)
    } else {
        listOfNotNull(providerName, choice.model).joinToString(" · ").ifBlank { choice.model }
    }
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = description,
        onClick = onSelectModel,
        trailing = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = modelLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 思考强度：与模型并列的独立入口，弹层与设置页其余选择器统一
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { showEffortPicker = true }
                            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Cpu,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            text = stringResource(
                                R.string.settings_subagent_effort_value,
                                stringResource(reasoningEffortLabel(choice.reasoningEffort))
                            ),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    )
    if (showEffortPicker) {
        OptionPickerSheet(
            title = stringResource(R.string.subagent_editor_effort),
            options = ReasoningEffort.entries.filter { it != ReasoningEffort.NONE },
            selected = ReasoningEffort.entries.firstOrNull { it.apiValue == choice.reasoningEffort }
                ?: ReasoningEffort.MEDIUM,
            optionLabel = { effort -> stringResource(reasoningEffortLabel(effort.apiValue)) },
            onSelect = { effort ->
                onChangeEffort(effort.apiValue)
                showEffortPicker = false
            },
            onDismiss = { showEffortPicker = false }
        )
    }
}

private fun reasoningEffortLabel(value: String): Int = when (value.lowercase()) {
    "minimal" -> R.string.chat_reasoning_effort_minimal
    "low" -> R.string.chat_reasoning_effort_low
    "high" -> R.string.chat_reasoning_effort_high
    "xhigh" -> R.string.chat_reasoning_effort_xhigh
    "max" -> R.string.chat_reasoning_effort_max
    else -> R.string.chat_reasoning_effort_medium
}

/**
 * 模型选择弹窗：风格与拉取模型弹窗保持一致（iOS 胶囊搜索框、提供商分组卡片、能力 Tag）。
 * 识图模型、压缩模型与主页聊天模型共用此组件，仅文案不同；右上角「重置」清除专用模型配置（回退跟随聊天模型），主页场景传 null 不显示。
 *
 * Provider 保持原相对顺序（稳定排序）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSelectionSheet(
    title: String,
    noModelsText: String,
    providers: List<AIProviderConfig>,
    currentProviderId: String,
    currentModel: String,
    modelMetadata: Map<String, ModelMetadata>,
    onSelect: (providerId: String, model: String) -> Unit,
    onClear: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val collapseStore = remember { ModelSheetCollapseStore(context.applicationContext) }
    var searchQuery by remember { mutableStateOf("") }
    var collapsedProviderIds by remember { mutableStateOf(collapseStore.collapsedProviderIds()) }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 关掉拖拽手势：用户反馈「点开后快速往上拖动，选择器自己收回去」。
        // 关闭改为点遮罩 / 返回键 / 右上角关闭按钮，滚动选模型时不会再被误关。
        sheetGesturesEnabled = false,
        dragHandle = null,
        containerColor = settingsPageBackground()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                onClear?.let { onClear ->
                    Text(
                        text = stringResource(R.string.common_reset),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onClear() }
                    )
                }
                // 拖拽手势已关，给一个明确的关闭按钮
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = FeatherIcons.X,
                        contentDescription = stringResource(R.string.common_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            ModelSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = stringResource(R.string.provider_filter_models_hint)
            )

            val regularProviders = remember(providers) {
                providers.filter {
                    it.isEnabled &&
                        it.models.isNotEmpty()
                }
            }
            val hasAnyContent = regularProviders.isNotEmpty()
            if (!hasAnyContent) {
                SettingsGroup {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 360.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = noModelsText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.lg)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 360.dp, max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    // 其余 provider 保持原相对顺序
                    regularProviders.forEach { provider ->
                        val filteredModels = provider.models.filter {
                            searchQuery.isBlank() || it.contains(searchQuery, ignoreCase = true)
                        }
                        if (filteredModels.isNotEmpty()) {
                            val expanded = searchQuery.isNotBlank() || provider.id !in collapsedProviderIds
                            item(key = "header_${provider.id}") {
                                CollapsibleGroupHeader(
                                    text = "${provider.name} (${filteredModels.size})",
                                    expanded = expanded,
                                    onToggle = {
                                        val updated = if (provider.id in collapsedProviderIds) {
                                            collapsedProviderIds - provider.id
                                        } else {
                                            collapsedProviderIds + provider.id
                                        }
                                        collapsedProviderIds = updated
                                        collapseStore.save(updated)
                                    }
                                )
                            }
                            if (expanded) {
                                item(key = "card_${provider.id}") {
                                    SettingsGroup {
                                        filteredModels.forEachIndexed { index, model ->
                                            if (index > 0) SettingsDivider()
                                            ModelSelectionRow(
                                                model = model,
                                                selected = provider.id == currentProviderId && model == currentModel,
                                                metadata = modelMetadata[modelMetadataKey(provider.id, model)],
                                                tierTag = null,
                                                onClick = { onSelect(provider.id, model) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelSelectionRow(
    model: String,
    selected: Boolean,
    metadata: ModelMetadata?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 档位标签（「免费可用」/「付费专属」），非账号模型为 null。 */
    tierTag: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Spacing.sm, horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModelLogoIcon(modelName = model, size = 20.dp)
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            ModelMetadataTags(metadata, tierTag)
        }
        if (selected) {
            Spacer(Modifier.width(Spacing.sm))
            Icon(
                imageVector = FeatherIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ModelMetadataTags(metadata: ModelMetadata?, tierTag: String? = null) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 档位标签排最前：免费/付费归属比能力 Tag 更影响账号选择
        if (tierTag != null) {
            val freeTierLabel = stringResource(R.string.model_tag_free_tier)
            ModelTag(text = tierTag, isHighlight = tierTag == freeTierLabel)
        }
        if (metadata != null) {
            if (metadata.supportsVision) {
                ModelTag(text = "Image", isHighlight = true)
            }
            if (metadata.supportsTools) {
                ModelTag(text = "Tools")
            }
            val input = metadata.inputTokens?.takeIf { it > 0 } ?: metadata.contextTokens.takeIf { it > 0 }
            if (input != null) {
                ModelTag(text = formatTokenLimit(input), icon = FeatherIcons.ArrowUp)
            }
            metadata.outputTokens?.takeIf { it > 0 }?.let { output ->
                ModelTag(text = formatTokenLimit(output), icon = FeatherIcons.ArrowDown)
            }
        }
    }
}

@Composable
private fun ModelTag(
    text: String,
    isHighlight: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val backgroundColor = if (isHighlight) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isHighlight) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = textColor
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = textColor
            )
        }
    }
}

private fun formatTokenLimit(tokens: Int): String =
    when {
        tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
        tokens >= 1_000_000 -> "${tokens / 1_000_000.0}".trimDecimal() + "M"
        tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
        tokens >= 1_000 -> "${tokens / 1_000.0}".trimDecimal() + "K"
        else -> tokens.toString()
    }

private fun String.trimDecimal(): String =
    replace(Regex("(\\.\\d)\\d+"), "$1").removeSuffix(".0")
