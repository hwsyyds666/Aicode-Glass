package com.aicode.feature.workspace.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeviceRootListingTest {

    private val real = "/data/local/tmp/ws"

    private fun rec(type: String, size: String, mtime: String, mode: String, name: String) =
        "$type\t$size\t$mtime\t$mode\t$name\u0000"

    @Test
    fun `parseFindOutput_常规目录与文件`() {
        val output = rec("d", "4096", "1729165800.0123456789", "40755", "sub") +
            rec("f", "120", "1729165801.5", "644", "main.kt")
        val entries = DeviceRootListing.parseFindOutput(output, real)
        assertEquals(2, entries.size)

        val dir = entries[0]
        assertTrue(dir.isDirectory)
        assertEquals("sub", dir.name)
        assertEquals(1729165800000L, dir.lastModified)
        assertEquals("rwx", dir.permissions)
        assertEquals(File(real, "sub"), dir.localFile)

        val file = entries[1]
        assertFalse(file.isDirectory)
        assertEquals(120L, file.size)
        assertEquals("main.kt", file.name)
        assertEquals("rw-", file.permissions)
    }

    @Test
    fun `parseFindOutput_文件名含制表符换行与unicode`() {
        val output = rec("f", "1", "100.0", "644", "we\tird") +
            rec("f", "2", "100.0", "644", "line\nbreak") +
            rec("f", "3", "100.0", "644", "文档.txt")
        val entries = DeviceRootListing.parseFindOutput(output, real)
        assertEquals(listOf("we\tird", "line\nbreak", "文档.txt"), entries.map { it.name })
    }

    @Test
    fun `parseFindOutput_符号链接与非存在目标按非目录处理`() {
        val output = rec("l", "10", "100.0", "777", "link") +
            rec("N", "0", "100.0", "0", "dangling")
        val entries = DeviceRootListing.parseFindOutput(output, real)
        assertFalse(entries[0].isDirectory)
        assertFalse(entries[1].isDirectory)
    }

    @Test
    fun `parseFindOutput_空目录与空输出`() {
        assertTrue(DeviceRootListing.parseFindOutput("", real).isEmpty())
    }

    @Test
    fun `parseFindOutput_跳过非法与字段残缺记录`() {
        val output = "\u0000garbage\u0000" + rec("f", "1", "100.0", "644", "ok") +
            rec("f", "1", "100.0", "644", "")
        val entries = DeviceRootListing.parseFindOutput(output, real)
        assertEquals(listOf("ok"), entries.map { it.name })
    }

    @Test
    fun `parseFindOutput_数值缺失时给默认值`() {
        val entries = DeviceRootListing.parseFindOutput(rec("f", "x", "y", "zz", "f"), real)
        assertEquals(1, entries.size)
        assertEquals(0L, entries[0].size)
        assertEquals(0L, entries[0].lastModified)
        assertEquals("rwx", entries[0].permissions) // 无法解析时按 root 默认可读写执行
    }

    @Test
    fun `permissionsFromOctal_八进制转rwx`() {
        assertEquals("rwx", DeviceRootListing.permissionsFromOctal("755"))
        assertEquals("rw-", DeviceRootListing.permissionsFromOctal("644"))
        assertEquals("rw-", DeviceRootListing.permissionsFromOctal("600"))
        assertEquals("r--", DeviceRootListing.permissionsFromOctal("444"))
        assertEquals("---", DeviceRootListing.permissionsFromOctal("000"))
        assertEquals("rwx", DeviceRootListing.permissionsFromOctal("40755")) // 目录：取末三位
        assertEquals("rwx", DeviceRootListing.permissionsFromOctal("")) // 无法解析
        assertEquals("rwx", DeviceRootListing.permissionsFromOctal("9x9")) // 非法字符
    }

    @Test
    fun `parseLegacyOutput_旧格式兼容`() {
        val output = "d\t4096|1729165800|drwxr-xr-x\tsub\n" +
            "f\t120|1729165801|-rw-r--r--\tmain.kt\n" +
            "f\t0|0|-\twe\tird\n"
        val entries = DeviceRootListing.parseLegacyOutput(output, real)
        assertEquals(3, entries.size)
        assertTrue(entries[0].isDirectory)
        assertEquals(4096L, entries[0].size)
        assertEquals(1729165800000L, entries[0].lastModified)
        assertEquals("rwx", entries[0].permissions)
        assertFalse(entries[1].isDirectory)
        assertEquals("rw-", entries[1].permissions)
        assertEquals("we\tird", entries[2].name) // 名字含 tab 由旧协议拼回
        assertEquals("rwx", entries[2].permissions) // stat 缺失时默认
    }

    @Test
    fun `parseLegacyOutput_跳过空行与残缺行`() {
        val output = "\nf\t120|1729165801|-rw-r--r--\tmain.kt\nbad\n"
        val entries = DeviceRootListing.parseLegacyOutput(output, real)
        assertEquals(1, entries.size)
    }

    @Test
    fun `findScript_含单命令printf与路径引用`() {
        val script = DeviceRootListing.findScript("/data/local/tmp/my ws")
        assertTrue(script.startsWith("cd '/data/local/tmp/my ws'"))
        assertTrue(script.contains("find -L . -mindepth 1 -maxdepth 1"))
        assertTrue(script.contains("-printf '%y\\t%s\\t%T@\\t%m\\t%f\\0'"))
        assertTrue(script.contains("exit 2"))
    }

    @Test
    fun `legacyScript_仍可用作兜底`() {
        val script = DeviceRootListing.legacyScript("/data/local/tmp/ws")
        assertTrue(script.contains("for f in * .[!.]* ..?*;"))
        assertTrue(script.contains("stat -c '%s|%Y|%A'"))
    }
}
