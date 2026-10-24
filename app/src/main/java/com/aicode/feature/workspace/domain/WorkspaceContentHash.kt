package com.aicode.feature.workspace.domain

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).toSha256Hex()

internal fun File.sha256Hex(): String = FileInputStream(this).use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().toSha256Hex()
}

internal fun MessageDigest.sha256Result(): String = digest().toSha256Hex()

private fun ByteArray.toSha256Hex(): String =
    "sha256:" + joinToString("") { byte -> "%02x".format(byte) }
