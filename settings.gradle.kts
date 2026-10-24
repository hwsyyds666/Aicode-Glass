pluginManagement {
    repositories {
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // PrismalAGSL（Liquid Glass）本地镜像：AAR 已 vendor 进 <root>/prismalrepo，
        // 坐标与 JitPack 完全一致（com.github.styropyr0:PrismalAGSL:v1.0.4），
        // 不依赖 JitPack 的按需现编。详见 prismalrepo/.../PrismalAGSL-v1.0.4.pom 顶部注释。
        maven { url = uri(File(settingsDir, "prismalrepo")) }
        // Termux 终端组件（terminal-emulator / terminal-view）只在 JitPack 发布，腾讯镜像没有
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
    }
}
rootProject.name = "app"

include(":app")
include(":macrobenchmark")
include(":terminal-emulator")
include(":terminal-view")

