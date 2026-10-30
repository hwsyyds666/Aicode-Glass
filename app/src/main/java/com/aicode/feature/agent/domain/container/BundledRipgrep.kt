package com.aicode.feature.agent.domain.container

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 随包内置的静态 ripgrep 二进制。
 *
 * 为什么需要：真机（DEVICE_ROOT）执行模式跑在 Android 的 `/system/bin/sh` 里，既没有 rg
 * 也没有任何包管理器（apk/apt-get/dnf/yum/pacman 全部缺失），`RipgrepProvisioner` 的自动
 * 安装在真机上必然失败，search 只能退到 toybox grep——选项残缺、正则能力弱，rg 专有参数
 * （`-uu`、`--glob` 等）要么报错要么静默 0 结果。容器模式没有这个问题（Alpine 有 apk），
 * 所以内置二进制只在真机通道启用。
 *
 * 打包方式：静态链接的 musl 版 rg 以 `librg.so` 放进 `jniLibs/<abi>/`。Android 10+ 禁止
 * 执行应用私有数据目录里的文件（W^X），但 APK 内原生库会被解压到 `nativeLibraryDir`
 * （`/data/app/.../lib/<abi>`，非应用可写），那里可以正常 exec；`.so` 只是文件名约定，
 * ELF 可执行体改名不影响运行。清单里显式声明 `android:extractNativeLibs="true"`，
 * 确保系统真的把它解压出来（否则只做页对齐映射，无法作为可执行文件调用）。
 */
@Singleton
class BundledRipgrep @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "BundledRipgrep"
        const val LIB_NAME = "librg.so"
    }

    /** 内置 rg 的绝对路径；未随包分发、尚未解压或不可执行时返回 null。 */
    @Volatile
    private var cachedPath: String? = null

    @Volatile
    private var resolved = false

    fun binaryPath(): String? {
        if (resolved) return cachedPath
        val path = resolve()
        cachedPath = path
        resolved = true
        FileLogger.i(TAG, "内置 rg 解析结果: ${path ?: "不可用"}")
        return path
    }

    private fun resolve(): String? {
        val dir = context.applicationInfo.nativeLibraryDir ?: return null
        val file = File(dir, LIB_NAME)
        if (!file.isFile) return null
        if (!file.canExecute()) {
            FileLogger.w(TAG, "内置 rg 存在但不可执行: ${file.absolutePath}")
            return null
        }
        return file.absolutePath
    }
}
