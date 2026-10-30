package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.reviewerModelDataStore by preferencesDataStore(
    name = "reviewer_model_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化「AI 命令审查器专用模型」选择（providerId + model 两字符串）。
 *
 * 「自主执行」挡位下，每条待授权命令先交审查器模型独立评审；未配置专用模型（providerId 为空）
 * 即跟随当前聊天模型。DataStore 用法与 TitleModelSettingsRepository 一致。
 */
@Singleton
class ReviewerModelSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) : ModelSelectionSettingsRepository(
    context.reviewerModelDataStore, "reviewer_provider_id", "reviewer_model"
) {

    /** 写入审查器专用模型（设空字符串即等同 [clear]）。 */
    suspend fun setReviewerModel(providerId: String, model: String) = setSelection(providerId, model)

    /** 清空配置——回退到「跟随当前聊天模型」。 */
    suspend fun clear() = clearSelection()

    /** 读取一次当前审查器专用 providerId（冷读用）。 */
    suspend fun getReviewerProviderId(): String = readProviderId()

    /** 读取一次当前审查器专用 model（冷读用）。 */
    suspend fun getReviewerModel(): String = readModel()
}
