package com.aicode.feature.settings.presentation.component

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.settings.presentation.PerfPanelViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Database
import compose.icons.feathericons.Download
import compose.icons.feathericons.Trash2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 性能面板：实时占用（app CPU/内存/FPS）、整机占用（设备内存/存储 + app 累计）、
 * 按模块聚合、JSONL 持久化与导出。数据来自 [PerfPanelViewModel] 转发的 [com.aicode.core.perf.PerfMonitor]。
 *
 * 模块归属不打点侵入各界面——由 MainActivity 的导航路由映射写入采样点，
 * 聚合粒度 = 「用户当时在哪个页面」，足够定位「哪个界面吃资源」。
 */
@Composable
internal fun PerfPanelSection(viewModel: PerfPanelViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val moduleKey by viewModel.module.collectAsStateWithLifecycle()
    val samples by viewModel.samples.collectAsStateWithLifecycle()
    val totals by viewModel.totals.collectAsStateWithLifecycle()
    val system by viewModel.system.collectAsStateWithLifecycle()
    val filesInfo by viewModel.filesInfo.collectAsStateWithLifecycle()

    var exportStatus by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val latest = samples.lastOrNull()
    // 内存缓冲内的模块聚合。
    // 单次遍历累加、不产生中间集合：原实现 groupBy 之后对每个模块再做两次
    // list.map{}.average()，等于每秒制造 N 个中间 List 与成箱的 Float/Double——
    // 面板每秒刷新一次，这正是堆在 24↔136MB 反复锯齿（GC 抖动→掉帧）的推手。
    // 现在只有 O(模块数) 个结果对象。
    val moduleAgg = remember(samples) {
        val acc = java.util.LinkedHashMap<String, DoubleArray>()
        for (s in samples) {
            // [count, cpuSum, pssSum, maxPss, jankSum]
            val a = acc.getOrPut(s.module) { DoubleArray(5) }
            a[0] += 1.0
            a[1] += s.cpuPct.toDouble()
            a[2] += s.pssMb.toDouble()
            if (s.pssMb > a[3]) a[3] = s.pssMb.toDouble()
            a[4] += s.jank.toDouble()
        }
        acc.entries.map { (m, a) ->
            val n = a[0].coerceAtLeast(1.0)
            ModuleAgg(
                module = m,
                samples = a[0].toInt(),
                avgCpu = (a[1] / n).toFloat(),
                avgPss = (a[2] / n).toFloat(),
                maxPss = a[3].toFloat(),
                totalJank = a[4].toInt()
            )
        }.sortedByDescending { it.avgPss }
    }

    // 导出：系统文件选择器保存，结果写回 exportStatus 展示。
    val rawLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            exportStatus = runCatching {
                val file = viewModel.exportRaw()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } ?: error("openOutputStream failed")
                }
                context.getString(R.string.perf_export_done, file.name)
            }.getOrElse { context.getString(R.string.perf_export_failed, it.message ?: "") }
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            exportStatus = runCatching {
                val file = viewModel.exportCsv()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } ?: error("openOutputStream failed")
                }
                context.getString(R.string.perf_export_done, file.name)
            }.getOrElse { context.getString(R.string.perf_export_failed, it.message ?: "") }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg),
        contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // ── 采样总开关 ──
        item(key = "toggle") {
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = FeatherIcons.Activity,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.perf_sampling_toggle),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.perf_sampling_toggle_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(Spacing.md))
                    AppSwitch(checked = enabled, onCheckedChange = { viewModel.setEnabled(it) })
                }
            }
            Text(
                text = stringResource(R.string.perf_sampling_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.md, top = Spacing.xs)
            )
        }

        // ── 实时占用 ──
        item(key = "realtime") {
            SettingsGroupHeader(text = stringResource(R.string.perf_group_realtime))
            SettingsGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        PerfMetricCard(
                            label = stringResource(R.string.perf_cpu),
                            value = latest?.let { "%.1f%%".format(it.cpuPct) } ?: "-",
                            sub = stringResource(R.string.perf_cpu_sub),
                            modifier = Modifier.weight(1f)
                        )
                        PerfMetricCard(
                            label = stringResource(R.string.perf_pss),
                            value = latest?.let { "%.0f MB".format(it.pssMb) } ?: "-",
                            sub = stringResource(R.string.perf_pss_sub),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        PerfMetricCard(
                            label = stringResource(R.string.perf_heap),
                            value = latest?.let { "%.0f/%.0f MB".format(it.javaHeapMb, it.javaHeapMaxMb) } ?: "-",
                            sub = stringResource(R.string.perf_heap_sub),
                            modifier = Modifier.weight(1f)
                        )
                        PerfMetricCard(
                            label = stringResource(R.string.perf_fps),
                            value = latest?.let { "%d".format(it.fps) } ?: "-",
                            sub = latest?.let {
                                stringResource(R.string.perf_fps_sub, it.jank)
                            } ?: stringResource(R.string.perf_fps_sub, 0),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        PerfMetricCard(
                            label = stringResource(R.string.perf_rss),
                            value = latest?.let { "%.0f MB".format(it.rssMb) } ?: "-",
                            sub = stringResource(R.string.perf_rss_sub),
                            modifier = Modifier.weight(1f)
                        )
                        PerfMetricCard(
                            label = stringResource(R.string.perf_threads),
                            value = latest?.let { "%d".format(it.threads) } ?: "-",
                            sub = stringResource(R.string.perf_module_current, moduleLabel(moduleKey)),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // ── 整机占用 ──
        item(key = "device") {
            SettingsGroupHeader(text = stringResource(R.string.perf_group_device))
            SettingsGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        PerfMetricCard(
                            label = stringResource(R.string.perf_dev_mem),
                            value = "%.0f/%.0f MB".format(system.memAvailMb, system.memTotalMb),
                            sub = stringResource(R.string.perf_dev_mem_sub),
                            modifier = Modifier.weight(1f)
                        )
                        PerfMetricCard(
                            label = stringResource(R.string.perf_dev_storage),
                            value = "%.1f/%.1f GB".format(
                                system.storageAvailMb / 1024f,
                                system.storageTotalMb / 1024f
                            ),
                            sub = stringResource(R.string.perf_dev_storage_sub),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        PerfMetricCard(
                            label = stringResource(R.string.perf_total_cpu_time),
                            value = formatDuration(totals.totalCpuMs),
                            sub = stringResource(R.string.perf_total_cpu_time_sub),
                            modifier = Modifier.weight(1f)
                        )
                        PerfMetricCard(
                            label = stringResource(R.string.perf_peak_rss),
                            value = "%.0f MB".format(totals.peakRssMb),
                            sub = stringResource(R.string.perf_peak_rss_sub),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        PerfMetricCard(
                            label = stringResource(R.string.perf_frames),
                            value = "%d".format(totals.frames),
                            sub = stringResource(
                                R.string.perf_frames_sub,
                                totals.jankFrames,
                                jankRatio(totals.frames, totals.jankFrames)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        PerfMetricCard(
                            label = stringResource(R.string.perf_cores),
                            value = "%d".format(system.cpuCores),
                            sub = stringResource(R.string.perf_samples_count, totals.samples),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // ── 模块聚合 ──
        item(key = "modules") {
            SettingsGroupHeader(text = stringResource(R.string.perf_group_modules))
            if (moduleAgg.isEmpty()) {
                Text(
                    text = stringResource(R.string.perf_modules_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md)
                )
            } else {
                SettingsGroup {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        moduleAgg.forEach { agg ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = moduleLabel(agg.module),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.perf_module_sub,
                                            agg.samples,
                                            agg.totalJank
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "CPU %.1f%% · PSS %.0fMB".format(agg.avgCpu, agg.avgPss),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 记录与导出 ──
        item(key = "records") {
            SettingsGroupHeader(text = stringResource(R.string.perf_group_records))
            SettingsGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = FeatherIcons.Database,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = stringResource(
                                R.string.perf_records_summary,
                                filesInfo.count,
                                filesInfo.totalBytes / (1024 * 1024)
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (filesInfo.oldest > 0) {
                        val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
                        Text(
                            text = stringResource(
                                R.string.perf_records_range,
                                fmt.format(Date(filesInfo.oldest)),
                                fmt.format(Date(filesInfo.newest))
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        TextButton(onClick = { rawLauncher.launch("aicode-perf.jsonl") }) {
                            Icon(
                                FeatherIcons.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.perf_export_raw))
                        }
                        TextButton(onClick = { csvLauncher.launch("aicode-perf-summary.csv") }) {
                            Icon(
                                FeatherIcons.Cpu,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.perf_export_csv))
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showClearConfirm = true }) {
                            Icon(
                                FeatherIcons.Trash2,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.perf_clear),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    exportStatus?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.perf_clear_confirm_title)) },
            text = { Text(stringResource(R.string.perf_clear_confirm_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearHistory()
                    exportStatus = null
                }) { Text(stringResource(R.string.perf_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

private data class ModuleAgg(
    val module: String,
    val samples: Int,
    val avgCpu: Float,
    val avgPss: Float,
    val maxPss: Float,
    val totalJank: Int
)

@Composable
private fun moduleLabel(key: String): String = when (key) {
    "chat" -> stringResource(R.string.perf_module_chat)
    "editor" -> stringResource(R.string.perf_module_editor)
    "terminal" -> stringResource(R.string.perf_module_terminal)
    "browser" -> stringResource(R.string.perf_module_browser)
    "git" -> stringResource(R.string.perf_module_git)
    "settings" -> stringResource(R.string.perf_module_settings)
    else -> stringResource(R.string.perf_module_other)
}

@Composable
private fun PerfMetricCard(
    label: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.semanticColors.cardSurface
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/** 毫秒 → 「H小时M分S秒」/「M分S秒」/「S秒」。 */
private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0s"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return buildString {
        if (h > 0) append("${h}h")
        if (m > 0) append("${m}m")
        if (h == 0L) append("${sec}s")
    }
}

private fun jankRatio(frames: Long, jank: Long): String =
    if (frames <= 0) "0.0%" else "%.1f%%".format(jank * 100f / frames)
