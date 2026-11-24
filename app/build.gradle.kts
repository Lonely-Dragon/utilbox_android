plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.android)
}

import java.util.Properties

android {
    namespace = "com.sakura.ubox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sakura.ubox"
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
    implementation(libs.paddleocr4android)
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

    @TaskAction
    fun enable() {
        val localProps = Properties()
        val localFile = project.rootProject.file("local.properties")
        if (localFile.exists()) localFile.inputStream().use { localProps.load(it) }
        val sdkDir = localProps.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
        val adb = sdkDir?.let { "$it${File.separator}platform-tools${File.separator}adb" } ?: "adb"
        execOperations.exec {
            commandLine(
                adb, "shell", "settings", "put", "secure",
                "enabled_accessibility_services",
                "com.sakura.ubox/com.sakura.ubox.service.UBoxAccessibilityService"
            )
        }
        execOperations.exec {
            commandLine(adb, "shell", "settings", "put", "secure", "accessibility_enabled", "1")
        }
        println("已开启 UBox 无障碍服务")
    }
}

val enableAccessibility = tasks.register<EnableAccessibilityTask>("enableAccessibilityDebug") {
    group = "ubox"
    description = "重装后重新开启 UBox 无障碍服务（需要已连接设备/模拟器）"
}

// 每次 debug 重装完成后自动重新开启无障碍，避免手动操作
tasks.matching { it.name.startsWith("install") && it.name.endsWith("Debug") }
    .configureEach { finalizedBy(enableAccessibility) }