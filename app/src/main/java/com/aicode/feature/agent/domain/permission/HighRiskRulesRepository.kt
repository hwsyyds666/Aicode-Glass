package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 高危拦截规则的持久化与内置预设。
 *
 * 存于 App 私有目录（`filesDir/aicode/high_risk_rules.json`）——容器内/真机工作区内的 AI 均无法篡改，
 * 契合授权层「规则绝不落盘到工作区」的安全原则（与全局授权规则同位置）。
 *
 * 首次启动写入全部预设规则；预设带版本号，后续版本新增预设会在加载时按 id 增量合并
 * （用户已有的开关状态与修改保留）。用户可增删改、单条开关，也可一键恢复预设。
 */
@Singleton
class HighRiskRulesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "HighRiskRules"
        const val RULES_FILE = "high_risk_rules.json"
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }

    @Serializable
    private data class HighRiskRuleFile(
        val presetVersion: Int = 0,
        val rules: List<HighRiskRule> = emptyList()
    )

    private val file: File
        get() = File(File(context.filesDir, "aicode"), RULES_FILE)

    private val state = MutableStateFlow<List<HighRiskRule>?>(null)
    private val mutex = Mutex()

    /** 规则流，供设置页订阅；首次订阅触发懒加载。 */
    val rulesFlow: Flow<List<HighRiskRule>> = flow {
        ensureLoaded()
        emitAll(state.filterNotNull())
    }

    /** 一次性读取当前规则（权限评估时用）。 */
    suspend fun getRulesOnce(): List<HighRiskRule> {
        ensureLoaded()
        return state.value ?: emptyList()
    }

    /** 新增一条用户规则（id 由调用方生成，建议 `user-<timestamp>`）。 */
    suspend fun addRule(rule: HighRiskRule) = edit { list ->
        if (list.none { it.id == rule.id }) list.add(rule)
    }

    /** 按 id 更新规则（编辑内容/开关）。 */
    suspend fun updateRule(rule: HighRiskRule) = edit { list ->
        val idx = list.indexOfFirst { it.id == rule.id }
        if (idx >= 0) list[idx] = rule
    }

    suspend fun removeRule(id: String) = edit { list ->
        list.removeAll { it.id == id }
    }

    /** 恢复预设：内置预设全量重置为默认（启用），用户自建规则保留。 */
    suspend fun resetToPresets() = edit { list ->
        list.removeAll { it.builtin }
        list.addAll(HighRiskRulePresets.RULES)
        FileLogger.i(TAG, "已恢复高危拦截规则预设")
    }

    // ── 内部 ──────────────────────────────────────────────────

    private suspend fun ensureLoaded() {
        if (state.value != null) return
        mutex.withLock {
            if (state.value != null) return
            state.value = withContext(Dispatchers.IO) { loadOrSeed() }
        }
    }

    /** 文件不存在写入预设；存在则按版本增量合并新预设（保留用户改动）。 */
    private fun loadOrSeed(): List<HighRiskRule> {
        if (!file.isFile) {
            val seed = HighRiskRulePresets.RULES
            writeToFile(HighRiskRuleFile(HighRiskRulePresets.VERSION, seed))
            return seed
        }
        val parsed = runCatching {
            JSON.decodeFromString<HighRiskRuleFile>(file.readText())
        }.getOrElse {
            FileLogger.w(TAG, "读取 ${file.path} 失败: ${it.message}，回退预设")
            return HighRiskRulePresets.RULES
        }
        if (parsed.presetVersion >= HighRiskRulePresets.VERSION) return parsed.rules
        // 版本迁移：1) 剔除已废弃预设；2) 调整过 pattern 的预设按新定义覆盖（保留用户自改的开关状态）；
        // 3) 再补入用户列表里不存在的新预设。用户自建规则不受影响。
        val removed = HighRiskRulePresets.REMOVED_IDS
        val updated = parsed.rules
            .filter { it.id !in removed }
            .map { old -> HighRiskRulePresets.UPDATES[old.id]?.copy(enabled = old.enabled) ?: old }
        val existingIds = updated.map { it.id }.toSet()
        val merged = updated + HighRiskRulePresets.RULES.filter { it.id !in existingIds }
        writeToFile(HighRiskRuleFile(HighRiskRulePresets.VERSION, merged))
        return merged
    }

    private suspend fun edit(mutate: (MutableList<HighRiskRule>) -> Unit) {
        ensureLoaded()
        mutex.withLock {
            val list = (state.value ?: emptyList()).toMutableList()
            mutate(list)
            withContext(Dispatchers.IO) {
                writeToFile(HighRiskRuleFile(HighRiskRulePresets.VERSION, list))
            }
            state.value = list
        }
    }

    private fun writeToFile(content: HighRiskRuleFile) {
        file.parentFile?.mkdirs()
        file.writeText(JSON.encodeToString(content))
    }
}

/**
 * 内置预设高危规则（[HighRiskRulePresets.VERSION] 递增触发对存量用户的增量合并）。
 *
 * 收录原则（v2 收紧口径后）：只拦「不可逆、影响系统状态、可外泄数据」三类里**后果重**的操作——
 * 磁盘擦除/格式化、系统开关机与属性、包与权限管理、SIGKILL、防火墙、管道执行、SSH/SCP 外传、
 * Git 历史破坏、公开发布、系统包卸载、账户与定时任务。
 *
 * v2 起**不拦**（日常开发高频、后果可控）：普通 `rm 文件`、`rm -f`、递归以外的一切删除、
 * `start/stop/su/sudo/doas`、`pkill/killall`、`nc/rsync/sftp`、语言包卸载
 * （npm/pip/yarn/pnpm/gem/cargo uninstall）、`git clean/restore/checkout --` 等
 * 工作区级回滚——这些在 RULE_BASED 挡位下改走「单次放行」而非强制拦截。
 */
object HighRiskRulePresets {

    const val VERSION = 2

    private const val CAT_DELETE = "删除与擦除"
    private const val CAT_DISK = "磁盘与分区"
    private const val CAT_SYSTEM = "系统控制"
    private const val CAT_PACKAGE = "包与权限管理"
    private const val CAT_PROCESS = "进程管理"
    private const val CAT_NETWORK = "网络与防火墙"
    private const val CAT_EXFIL = "下载执行与数据外发"
    private const val CAT_GIT = "Git 破坏性操作"
    private const val CAT_PUBLISH = "发布与包管理变更"
    private const val CAT_ACCOUNT = "账户与定时任务"

    private fun p(id: String, pattern: String, category: String, desc: String) =
        HighRiskRule(id = "preset.$id", pattern = pattern, matchType = HighRiskMatchType.PREFIX, category = category, description = desc)

    private fun r(id: String, pattern: String, category: String, desc: String) =
        HighRiskRule(id = "preset.$id", pattern = pattern, matchType = HighRiskMatchType.REGEX, category = category, description = desc)

    /**
     * 已从预设中除名、迁移时要从存量用户配置里清掉的 id（v1 → v2）。
     * 这些规则误伤面大或属日常开发高频操作，拦截收益低于打扰成本。
     */
    val REMOVED_IDS: Set<String> = setOf(
        "preset.start", "preset.stop",
        "preset.su", "preset.sudo", "preset.doas",
        "preset.nc", "preset.sftp", "preset.rsync",
        "preset.truncate",
        "preset.killall", "preset.pkill",
        "preset.git-clean", "preset.git-restore", "preset.git-checkout-discard",
        "preset.pip-uninstall", "preset.npm-uninstall", "preset.yarn-remove",
        "preset.pnpm-remove", "preset.gem-uninstall", "preset.cargo-uninstall"
    )

    /** v1 → v2 调整了定义（保留 id、迁移时覆盖 pattern/matchType，用户开关状态保留）。 */
    val UPDATES: Map<String, HighRiskRule> = mapOf(
        // v1 的 "rm" 拦所有删除；v2 只拦递归删除（-r/-R/--recursive 任一递归形态），
        // 普通 `rm 文件` 与 `rm -f 文件` 交回单次放行。
        "preset.rm" to r("rm", "^rm\\s+(-[a-zA-Z]*[rR][a-zA-Z]*\\s|--recursive(\\s|$))", CAT_DELETE, "递归删除目录（rm -r/-R/--recursive）")
    )

    val RULES: List<HighRiskRule> = listOf(
        // ── 删除与擦除 ──
        r("rm", "^rm\\s+(-[a-zA-Z]*[rR][a-zA-Z]*\\s|--recursive(\\s|$))", CAT_DELETE, "递归删除目录（rm -r/-R/--recursive）"),
        p("shred", "shred", CAT_DELETE, "安全擦除文件，不可恢复"),

        // ── 磁盘与分区 ──
        p("dd", "dd", CAT_DISK, "直接读写磁盘/设备镜像，可毁数据"),
        r("mkfs", "mkfs(\\.[a-z0-9]+)?(\\s|$)", CAT_DISK, "格式化文件系统"),
        p("mkswap", "mkswap", CAT_DISK, "创建交换分区"),
        p("wipefs", "wipefs", CAT_DISK, "擦除设备文件系统签名"),
        p("fdisk", "fdisk", CAT_DISK, "修改磁盘分区表"),
        p("sfdisk", "sfdisk", CAT_DISK, "脚本化修改磁盘分区表"),
        p("parted", "parted", CAT_DISK, "修改磁盘分区"),
        p("blkdiscard", "blkdiscard", CAT_DISK, "丢弃块设备全部数据"),
        r("dev-write", ">\\s*/dev/(sd|mmcblk|nvme|block)", CAT_DISK, "重定向直写块设备"),

        // ── 系统控制 ──
        p("reboot", "reboot", CAT_SYSTEM, "重启设备"),
        p("shutdown", "shutdown", CAT_SYSTEM, "关机"),
        p("poweroff", "poweroff", CAT_SYSTEM, "关机断电"),
        p("halt", "halt", CAT_SYSTEM, "停机"),
        p("setenforce", "setenforce", CAT_SYSTEM, "关闭 SELinux 强制模式，削弱系统安全"),
        p("mount", "mount", CAT_SYSTEM, "挂载文件系统"),
        p("umount", "umount", CAT_SYSTEM, "卸载文件系统"),
        p("setprop", "setprop", CAT_SYSTEM, "修改 Android 系统属性"),
        p("svc", "svc", CAT_SYSTEM, "开关 WiFi/移动数据/电源"),
        p("settings-put", "settings put", CAT_SYSTEM, "修改系统全局设置"),
        p("device-config-put", "device_config put", CAT_SYSTEM, "修改设备配置参数"),

        // ── 包与权限管理 ──
        p("pm-uninstall", "pm uninstall", CAT_PACKAGE, "卸载应用"),
        p("pm-clear", "pm clear", CAT_PACKAGE, "清空应用数据"),
        p("pm-disable", "pm disable", CAT_PACKAGE, "禁用应用"),
        p("pm-grant", "pm grant", CAT_PACKAGE, "授予应用权限"),
        p("pm-revoke", "pm revoke", CAT_PACKAGE, "撤销应用权限"),
        p("appops-set", "appops set", CAT_PACKAGE, "修改应用 ops 权限"),
        r("chmod-recursive", "chmod\\s+(-[a-zA-Z0-9]*R|--recursive)", CAT_PACKAGE, "递归修改文件权限"),
        r("chown-recursive", "chown\\s+(-[a-zA-Z0-9]*R|--recursive)", CAT_PACKAGE, "递归修改文件所有者"),

        // ── 进程管理 ──
        r("kill-9", "kill\\s+(-[a-zA-Z]*9|-SIGKILL)\\b", CAT_PROCESS, "强制杀死进程（SIGKILL）"),

        // ── 网络与防火墙 ──
        p("iptables", "iptables", CAT_NETWORK, "修改防火墙规则"),
        p("ip6tables", "ip6tables", CAT_NETWORK, "修改 IPv6 防火墙规则"),
        p("nft", "nft", CAT_NETWORK, "修改 nftables 防火墙"),
        p("tc", "tc", CAT_NETWORK, "修改流量控制规则"),

        // ── 下载执行与数据外发 ──
        r("pipe-to-shell", "\\|\\s*(sudo\\s+)?(ba|z|fi|da)?sh\\b", CAT_EXFIL, "下载内容直接管道进 shell 执行（curl/wget … | sh）"),
        p("ssh", "ssh", CAT_EXFIL, "连接外部主机，可外传数据"),
        p("scp", "scp", CAT_EXFIL, "向外部主机复制文件"),

        // ── Git 破坏性操作 ──
        r("git-push-force", "git\\s+push\\s+.*(-f\\b|--force)", CAT_GIT, "强制推送，覆盖远端提交历史"),
        p("git-reset-hard", "git reset --hard", CAT_GIT, "硬重置，丢弃工作区与暂存区全部修改"),
        p("git-branch-force-delete", "git branch -D", CAT_GIT, "强制删除分支（含未合并提交）"),
        p("git-filter-branch", "git filter-branch", CAT_GIT, "重写整个提交历史"),

        // ── 发布与包管理变更 ──
        p("npm-publish", "npm publish", CAT_PUBLISH, "公开发布 npm 包，不可撤回"),
        p("yarn-publish", "yarn publish", CAT_PUBLISH, "公开发布 npm 包，不可撤回"),
        p("pnpm-publish", "pnpm publish", CAT_PUBLISH, "公开发布 npm 包，不可撤回"),
        p("cargo-publish", "cargo publish", CAT_PUBLISH, "公开发布 crate，不可撤回"),
        p("gem-push", "gem push", CAT_PUBLISH, "公开发布 gem"),
        p("twine-upload", "twine upload", CAT_PUBLISH, "上传发布 Python 包"),
        p("docker-push", "docker push", CAT_PUBLISH, "推送镜像到远端仓库"),
        r("apt-remove", "apt(-get)?\\s+(remove|purge|autoremove)", CAT_PUBLISH, "卸载系统软件包"),
        p("apk-del", "apk del", CAT_PUBLISH, "卸载软件包"),
        r("dnf-remove", "(dnf|yum)\\s+(remove|erase|autoremove)", CAT_PUBLISH, "卸载系统软件包"),
        p("pacman-remove", "pacman -R", CAT_PUBLISH, "卸载软件包"),

        // ── 账户与定时任务 ──
        p("crontab", "crontab", CAT_ACCOUNT, "修改定时任务（-r 直接清空）"),
        p("passwd", "passwd", CAT_ACCOUNT, "修改账户密码"),
        p("useradd", "useradd", CAT_ACCOUNT, "创建系统账户"),
        p("userdel", "userdel", CAT_ACCOUNT, "删除系统账户"),
        p("usermod", "usermod", CAT_ACCOUNT, "修改系统账户")
    )
}
