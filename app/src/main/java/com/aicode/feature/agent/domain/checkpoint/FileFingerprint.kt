package com.aicode.feature.agent.domain.checkpoint

import com.aicode.feature.workspace.domain.FileAccessProvider

internal const val MISSING_FILE_FINGERPRINT = "MISSING"

/** Hashes a bounded file without loading its contents into heap memory. */
internal suspend fun FileAccessProvider.contentFingerprint(
    path: String,
    maxBytes: Long = SnapshotPolicy.MAX_SNAPSHOT_FILE_BYTES
): String? {
    if (!exists(path)) return MISSING_FILE_FINGERPRINT
    if (!isFile(path)) return null
    val size = fileSize(path)
    if (size < 0 || size > maxBytes) return null
    return sha256(path, maxBytes)
}
