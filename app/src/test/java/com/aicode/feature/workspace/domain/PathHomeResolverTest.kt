package com.aicode.feature.workspace.domain

import android.content.Context
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * PathHomeResolver：home 取源（远程/真机/本地/回退）与 ~ 前缀展开。
 * ExecutionModeHolder 用真实实现（纯内存，无 Android 依赖），RemoteSshConnection 用 mockk 控制 remoteHome，
 * Context 用 mockk 控制 filesDir（真机 ROOT 模式的 home）。
 */
class PathHomeResolverTest {

    private val remoteSshConnection: RemoteSshConnection = mockk()

    /** 真机 ROOT 模式下 home 即 App 私有 filesDir（期望值随平台路径分隔符走，Windows 下为 `C:\data\...`）。 */
    private val filesDirFile = File("/data/user/0/com.aicode")
    private val filesDirPath = filesDirFile.absolutePath
    private val context: Context = mockk {
        every { filesDir } returns filesDirFile
    }

    private fun newResolver(mode: ExecutionMode): PathHomeResolver {
        val holder = ExecutionModeHolder().apply { setMode(mode) }
        return PathHomeResolver(context, holder, remoteSshConnection)
    }

    // ---------- home()：取源与回退 ----------

    @Test
    fun home_localMode_containerHomeUnknown_fallsBackToRoot() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        every { remoteSshConnection.remoteHome } returns null
        resolver.containerHome = null
        assertEquals("/root", resolver.home())
    }

    @Test
    fun home_localMode_returnsContainerHome() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        // 远程 home 即使有值，本地模式也不采用
        every { remoteSshConnection.remoteHome } returns "/home/remote"
        resolver.containerHome = "/data/container-home"
        assertEquals("/data/container-home", resolver.home())
    }

    @Test
    fun home_remoteMode_remoteHomeUnknown_fallsBackToRoot() {
        val resolver = newResolver(ExecutionMode.REMOTE_SSH)
        every { remoteSshConnection.remoteHome } returns null
        // 本地 containerHome 即使有值，远程模式也不采用
        resolver.containerHome = "/data/container-home"
        assertEquals("/root", resolver.home())
    }

    @Test
    fun home_remoteMode_returnsRemoteHome() {
        val resolver = newResolver(ExecutionMode.REMOTE_SSH)
        every { remoteSshConnection.remoteHome } returns "/home/dev"
        assertEquals("/home/dev", resolver.home())
    }

    @Test
    fun home_deviceRootMode_returnsAppFilesDir() {
        val resolver = newResolver(ExecutionMode.DEVICE_ROOT)
        // 真机模式下不采用容器/远程 home
        every { remoteSshConnection.remoteHome } returns "/home/dev"
        resolver.containerHome = "/root"
        assertEquals(filesDirPath, resolver.home())
        assertEquals("$filesDirPath/.aicode", resolver.aicodeRoot())
        assertEquals("$filesDirPath/workspace", resolver.expandHome("~/workspace"))
    }

    // ---------- aicodeRoot()：执行环境 AI 配置根 ----------

    @Test
    fun aicodeRoot_localMode() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        resolver.containerHome = "/root"
        assertEquals("/root/.aicode", resolver.aicodeRoot())
    }

    @Test
    fun aicodeRoot_remoteMode_usesRemoteHome() {
        val resolver = newResolver(ExecutionMode.REMOTE_SSH)
        every { remoteSshConnection.remoteHome } returns "/home/dev/"
        assertEquals("/home/dev/.aicode", resolver.aicodeRoot())
    }

    // ---------- expandHome()：~ 展开 ----------

    @Test
    fun expandHome_tildeOnly_returnsHome() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        every { remoteSshConnection.remoteHome } returns null
        resolver.containerHome = "/root"
        assertEquals("/root", resolver.expandHome("~"))
    }

    @Test
    fun expandHome_tildeSlash_appendsToHome() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        resolver.containerHome = "/root"
        assertEquals("/root/workspace", resolver.expandHome("~/workspace"))
        assertEquals("/root/.aicode/skills", resolver.expandHome("~/.aicode/skills"))
    }

    @Test
    fun expandHome_homeEndsWithSlash_noDoubleSlash() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        resolver.containerHome = "/root/"
        assertEquals("/root/x", resolver.expandHome("~/x"))
    }

    @Test
    fun expandHome_remoteMode_usesRemoteHome() {
        val resolver = newResolver(ExecutionMode.REMOTE_SSH)
        every { remoteSshConnection.remoteHome } returns "/home/dev/"
        assertEquals("/home/dev/workspace", resolver.expandHome("~/workspace"))
    }

    @Test
    fun expandHome_otherPaths_unchanged() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        resolver.containerHome = "/root"
        assertEquals("/etc/hosts", resolver.expandHome("/etc/hosts"))
        assertEquals("src/Main.kt", resolver.expandHome("src/Main.kt"))
        assertEquals("", resolver.expandHome(""))
        assertEquals("~~/x", resolver.expandHome("~~/x"))
    }
}
