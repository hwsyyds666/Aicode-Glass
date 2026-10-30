package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.device.DeviceRootEngine
import java.io.File

/**
 * [DeviceRootFileAccess] 目录列举的脚本生成与输出解析（纯函数，无 Android 依赖，可单测）。
 *
 * 为什么重写：旧实现是「一个 su fork + 每项一次 stat fork」——100 项的目录约 100 次进程创建，
 * 在大目录上展开文件树要等数秒。现在一条 `find -printf` 拿回全部属性，进程创建从 N+1 降到 1。
 *
 * 输出协议（记录间 `\0` 分隔，字段间 `\t`）：`type \t size \t mtime \t mode \t name`。
 * 选 `\0` 是因为文件名可以含制表符甚至换行（旧实现按行+按 tab 切分会把这些名字解析错，
 * 这是已知限制，本次一并修掉）；name 是最后一段，内部含 tab 由解析端重新拼回。
 *
 * 兜底：`find -printf` 依赖 toybox/GNU find（Android 7+ 的 toybox 已支持）。极个别构建不支持时
 * 命令非零退出，调用方回退到 [legacyScript]（旧逐项 stat 语义），行为不变、只是回到慢路径。
 */
internal object DeviceRootListing {

    /** find 版脚本：cd 失败沿用旧约定的 exit 2（NoSuchFileException）。 */
    fun findScript(real: String): String =
        "cd ${DeviceRootEngine.shellQuote(real)} 2>/dev/null || exit 2; " +
            "find -L . -mindepth 1 -maxdepth 1 -printf '%y\\t%s\\t%T@\\t%m\\t%f\\0'"

    /** 旧版脚本（兜底）：cd 后遍历通配，逐项 stat 取 大小|时间|权限。 */
    fun legacyScript(real: String): String =
        "cd ${DeviceRootEngine.shellQuote(real)} 2>/dev/null || exit 2; " +
            "for f in * .[!.]* ..?*; do " +
            "[ -e \"\$f\" ] || [ -L \"\$f\" ] || continue; " +
            "if [ -d \"\$f\" ]; then t=d; elif [ -f \"\$f\" ]; then t=f; else t=o; fi; " +
            "sm=\$(stat -c '%s|%Y|%A' \"\$f\" 2>/dev/null); " +
            "printf '%s\\t%s\\t%s\\n' \"\$t\" \"\${sm:-0|0|-}\" \"\$f\"; " +
            "done"

    /**
     * 解析 [findScript] 的输出。[real] 是目录的真机绝对路径，用于回填 [FileEntry.localFile]。
     * 非法记录（字段不足、名为空）跳过；统计缺失的字段给 0，权限给 "rwx"（与旧实现默认一致）。
     */
    fun parseFindOutput(output: String, real: String): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        for (record in output.split('\u0000')) {
            if (record.isEmpty()) continue
            val p1 = record.indexOf('\t')
            val p2 = if (p1 >= 0) record.indexOf('\t', p1 + 1) else -1
            val p3 = if (p2 >= 0) record.indexOf('\t', p2 + 1) else -1
            val p4 = if (p3 >= 0) record.indexOf('\t', p3 + 1) else -1
            if (p1 < 0 || p2 < 0 || p3 < 0 || p4 < 0) continue
            val type = record.substring(0, p1)
            val size = record.substring(p1 + 1, p2).toLongOrNull() ?: 0L
            // %T@ 形如 "1729165800.0123456789"：只取整秒（毫秒精度对文件树与文件详情足够）
            val mtime = record.substring(p2 + 1, p3).substringBefore('.').toLongOrNull() ?: 0L
            val mode = record.substring(p3 + 1, p4)
            val name = record.substring(p4 + 1)
            if (name.isEmpty()) continue
            out += FileEntry(
                name = name,
                isDirectory = type == "d",
                size = size,
                lastModified = mtime * 1000,
                localFile = File(real, name),
                permissions = permissionsFromOctal(mode)
            )
        }
        return out
    }

    /**
     * 解析 [legacyScript] 的输出（旧格式：`type \t size|mtime|perm \t name`，行分隔）。
     * name 可含 tab（旧实现用 subList 拼回），此处保持同样语义。
     */
    fun parseLegacyOutput(output: String, real: String): List<FileEntry> {
        return output.lineSequence()
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val name = parts.subList(2, parts.size).joinToString("\t")
                if (name.isEmpty()) return@mapNotNull null
                val meta = parts[1].split('|')
                FileEntry(
                    name = name,
                    isDirectory = parts[0] == "d",
                    size = meta.getOrNull(0)?.toLongOrNull() ?: 0L,
                    lastModified = (meta.getOrNull(1)?.toLongOrNull() ?: 0L) * 1000,
                    localFile = File(real, name),
                    permissions = meta.getOrNull(2)?.takeIf { it.length >= 4 }?.substring(1, 4) ?: "rwx"
                )
            }
            .toList()
    }

    /**
     * `stat`/`find` 的八进制权限串（如 "644"、"40755"）→ owner 位 rwx 三串（如 "rw-"）。
     * 只取末三位的最高位（owner），与旧实现 `stat -c %A` 后取 `-rwx` 前三位的语义一致；
     * setuid/sticky 与 group/other 位对文件树与文件详情的展示无意义，不呈现。
     * 无法解析时返回 "rwx"（root 视角默认可读写执行）。
     */
    fun permissionsFromOctal(octal: String): String {
        val digits = octal.filter { it.isDigit() }.takeLast(3)
        if (digits.length < 3 || digits.any { it !in '0'..'7' }) return "rwx"
        val v = digits[0] - '0'
        return buildString {
            append(if (v and 4 != 0) 'r' else '-')
            append(if (v and 2 != 0) 'w' else '-')
            append(if (v and 1 != 0) 'x' else '-')
        }
    }
}
