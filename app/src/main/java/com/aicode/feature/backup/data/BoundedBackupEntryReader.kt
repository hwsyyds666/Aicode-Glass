package com.aicode.feature.backup.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads a single declared archive entry while enforcing a hard in-memory limit. */
internal object BoundedBackupEntryReader {
    fun read(input: InputStream, entryName: String, declaredSize: Long, maxBytes: Int): ByteArray {
        require(maxBytes >= 0) { "备份条目 $entryName 的大小上限无效" }
        if (declaredSize < 0 || declaredSize > maxBytes) {
            throw IllegalArgumentException(
                "备份条目 $entryName 过大（声明 ${declaredSize.coerceAtLeast(0)} 字节，上限 $maxBytes 字节）"
            )
        }
        val out = ByteArrayOutputStream(declaredSize.toInt().coerceAtMost(INITIAL_BUFFER_BYTES))
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > maxBytes) {
                throw IllegalArgumentException("备份条目 $entryName 超过 $maxBytes 字节上限，文件可能损坏")
            }
            out.write(buffer, 0, count)
        }
        if (total.toLong() != declaredSize) {
            throw IllegalArgumentException("备份条目 $entryName 长度不完整（声明 $declaredSize，实际 $total 字节）")
        }
        return out.toByteArray()
    }

    private const val BUFFER_BYTES = 8 * 1024
    private const val INITIAL_BUFFER_BYTES = 16 * 1024
}
