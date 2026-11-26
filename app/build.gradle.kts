plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.android)
}

import java.util.Properties

android {
    namespace = "com.sakura.gamebox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sakura.gamebox"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // 16 KB 页设备要求 native so 在 APK 中未压缩、按 16 KB 对齐存放
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.ncnnandroidppocr)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// 开发辅助：重装后系统会重置无障碍开关，此任务一键重新开启
abstract class EnableAccessibilityTask : DefaultTask() {
    @get:javax.inject.Inject
    abstract val execOperations: org.gradle.process.ExecOperations

    @get:org.gradle.api.tasks.Input
    abstract val adbPath: org.gradle.api.provider.Property<String>

    @TaskAction
    fun enable() {
        val adb = adbPath.get()
        execOperations.exec {
            commandLine(
                adb, "shell", "settings", "put", "secure",
                "enabled_accessibility_services",
                "com.sakura.gamebox/com.sakura.gamebox.service.GameboxAccessibilityService"
            )
        }
        execOperations.exec {
            commandLine(adb, "shell", "settings", "put", "secure", "accessibility_enabled", "1")
        }
        // 允许 App 自己写回无障碍开关（强行停止/重启后静默恢复）；仅调试机可用。
        execOperations.exec {
            commandLine(
                adb, "shell", "pm", "grant", "com.sakura.gamebox",
                "android.permission.WRITE_SECURE_SETTINGS"
            )
            isIgnoreExitValue = true
        }
        println("已开启 Gamebox 无障碍服务")
    }
}

val enableAccessibility = tasks.register<EnableAccessibilityTask>("enableAccessibilityDebug") {
    group = "gamebox"
    description = "重装后重新开启 Gamebox 无障碍服务（需要已连接设备/模拟器）"
    val localProps = Properties()
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) localFile.inputStream().use { localProps.load(it) }
    val sdkDir = localProps.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
    adbPath.set(sdkDir?.let { "$it${File.separator}platform-tools${File.separator}adb" } ?: "adb")
}

// 每次 debug 重装完成后自动重新开启无障碍，避免手动操作
tasks.matching { it.name.startsWith("install") && it.name.endsWith("Debug") }
    .configureEach { finalizedBy(enableAccessibility) }