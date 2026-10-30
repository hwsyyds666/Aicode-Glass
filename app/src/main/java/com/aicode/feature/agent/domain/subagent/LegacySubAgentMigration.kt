package com.aicode.feature.agent.domain.subagent

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.data.repository.SubAgentModelSettingsRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

data class LegacySubAgentFile(val path: String, val modifiedFromBuiltin: Boolean)

data class LegacySubAgentMigrationResult(
    val files: List<LegacySubAgentFile>,
    val migratedExploreModel: String? = null,
    val scanComplete: Boolean
) {
    val hasLegacyDefinitions: Boolean get() = files.any {
        it.modifiedFromBuiltin || !it.path.substringAfterLast('/').equals("explore.md", ignoreCase = true)
    }
}

/** Detects legacy user definitions without deleting or loading them, and imports only Explore's model choice. */
@Singleton
class LegacySubAgentMigration @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val modelSettings: SubAgentModelSettingsRepository,
    private val providers: AIProviderRepository,
    @param:ApplicationContext private val context: Context
) {
    suspend fun run(workspaceRoot: String): LegacySubAgentMigrationResult {
        // Keep scanning definitions when the legacy model preference was already migrated: the first
        // app start can happen before a project workspace is selected, and later workspaces may still
        // contain old project-scoped definitions that must be reported (but never loaded or deleted).
        val modelMigrationComplete = modelSettings.isLegacyMigrationComplete()
        val roots = buildList {
            add("~/.aicode/agents")
            workspaceRoot.takeIf(String::isNotBlank)?.let { add("${it.trimEnd('/')}/.aicode/agents") }
        }.distinct()
        val template = runCatching {
            context.assets.open("agents/explore.md").bufferedReader().use { it.readText() }
        }.getOrDefault("")
        val templateHash = sha256(normalizeLines(template))
        val discovered = mutableListOf<LegacySubAgentFile>()
        val legacyExploreContents = mutableListOf<String>()
        var complete = true

        for (root in roots) {
            val paths = try {
                if (!fileAccess.isDirectory(root)) continue
                fileAccess.listFiles(root)
                    .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
                    .map { "${root.trimEnd('/')}/${it.name}" }
            } catch (error: Exception) {
                complete = false
                FileLogger.w(TAG, "旧子代理目录扫描失败: $root (${error.message})")
                continue
            }
            val contents = runCatching { fileAccess.readFiles(paths) }.getOrElse {
                complete = false
                FileLogger.w(TAG, "旧子代理文件读取失败: $root (${it.message})")
                emptyMap()
            }
            paths.forEach { path ->
                val body = contents[path] ?: return@forEach
                val modified = templateHash.isEmpty() || sha256(normalizeLines(body)) != templateHash
                discovered += LegacySubAgentFile(path, modified)
                if (path.substringAfterLast('/').equals("explore.md", ignoreCase = true) ||
                    frontmatterValue(body, "name").equals("explore", ignoreCase = true)
                ) legacyExploreContents += body
            }
        }

        var migratedModel: String? = null
        if (complete && !modelMigrationComplete) {
            val explore = legacyExploreContents.firstOrNull { frontmatterValue(it, "model").isNotBlank() }
            if (explore != null) {
                val rawModel = frontmatterValue(explore, "model")
                val rawProvider = frontmatterValue(explore, "provider")
                val allProviders = providers.getAllProviders().first()
                val provider = allProviders.firstOrNull { it.id.equals(rawProvider, ignoreCase = true) }
                    ?: allProviders.firstOrNull { it.name.equals(rawProvider, ignoreCase = true) }
                    ?: allProviders.firstOrNull { rawModel in it.models }
                if (provider != null) {
                    val reasoning = frontmatterValue(explore, "reasoningEffort").ifBlank { "medium" }
                    modelSettings.migrateLegacyExploreChoice(provider.id, rawModel, reasoning)
                    migratedModel = "${provider.name}/$rawModel/$reasoning"
                } else {
                    FileLogger.w(TAG, "旧 Explore 模型未迁移：找不到供应商 rawProvider=$rawProvider model=$rawModel")
                }
            }
            modelSettings.completeLegacyMigration()
        }

        FileLogger.i(
            TAG,
            "旧子代理定义盘点: ${discovered.joinToString { "${it.path} (modified=${it.modifiedFromBuiltin})" }.ifBlank { "none" }}; " +
                "exploreModel=${migratedModel ?: "none"}; complete=$complete"
        )
        return LegacySubAgentMigrationResult(discovered.distinctBy { it.path }, migratedModel, complete)
    }

    private fun frontmatterValue(text: String, key: String): String {
        val stripped = text.removePrefix("---")
        val closingDelimiter = stripped.indexOf("---")
        val frontmatter = if (closingDelimiter >= 0) stripped.substring(0, closingDelimiter) else ""
        val match = Regex("(?m)^\\s*${Regex.escape(key)}\\s*:\\s*(.*?)\\s*$", RegexOption.IGNORE_CASE)
            .find(frontmatter)?.groupValues?.getOrNull(1).orEmpty()
        return match.trim().trim('"', '\'')
    }

    private fun normalizeLines(value: String) = value.replace("\r\n", "\n").trim()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object { const val TAG = "LegacySubAgentMigration" }
}
