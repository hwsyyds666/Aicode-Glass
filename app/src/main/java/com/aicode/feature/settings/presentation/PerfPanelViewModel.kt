package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import com.aicode.core.perf.PerfFilesInfo
import com.aicode.core.perf.PerfMonitor
import com.aicode.core.perf.PerfSample
import com.aicode.core.perf.PerfSystem
import com.aicode.core.perf.PerfTotals
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject

/**
 * 性能面板：直接转发 [PerfMonitor] 的状态流，操作也全部委托。
 * 聚合（模块均值/峰值）故意放在 UI 层现算——采样流每秒只变一次，计算量可忽略，
 * 换来的是聚合口径改动不需要动 ViewModel。
 */
@HiltViewModel
class PerfPanelViewModel @Inject constructor(
    private val perf: PerfMonitor
) : ViewModel() {

    val enabled: StateFlow<Boolean> = perf.enabled
    val module: StateFlow<String> = perf.module
    val samples: StateFlow<List<PerfSample>> = perf.samples
    val totals: StateFlow<PerfTotals> = perf.totals
    val system: StateFlow<PerfSystem> = perf.system
    val filesInfo: StateFlow<PerfFilesInfo> = perf.filesInfo

    fun setEnabled(on: Boolean) = perf.setEnabled(on)

    suspend fun exportRaw(): File = perf.exportRawFile()

    suspend fun exportCsv(): File = perf.exportSummaryCsv()

    fun clearHistory() = perf.clearHistory()
}
