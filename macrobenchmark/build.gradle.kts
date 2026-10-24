plugins {
    id("com.android.test")
    kotlin("android")
}

android {
    namespace = "com.aicode.macrobenchmark"
    compileSdk = 37

    defaultConfig {
        // 与应用最低版本对齐；真正的设备基准建议使用 Android 10+ 的实体设备。
        minSdk = 26
        targetSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 应用有 universal/armsolo/x86solo 三种容器包，本基准固定测通用 Release 包。
        missingDimensionStrategy("container", "universal")
    }

    targetProjectPath = ":app"

    // com.android.test creates a debug-only test variant by default. AiCode intentionally enables
    // R8 for debug too, so the macrobenchmark must pair the release app with a shrunk release test
    // APK instead of accidentally benchmarking universalDebug.
    buildTypes {
        create("release") {
            isDebuggable = true
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    variantFilter {
        if (buildType.name == "debug") ignore = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
    // These are optional/compile-only from parts of the benchmark dependency graph, but R8 needs
    // the implementations present in the instrumentation APK when shrinking the Release variant.
    implementation("androidx.arch.core:core-runtime:2.2.0")
    implementation("androidx.arch.core:core-common:2.2.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.startup:startup-runtime:1.2.0")
    implementation("com.google.errorprone:error_prone_annotations:2.36.0")
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
