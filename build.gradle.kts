// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.android) apply false
}
val sourceCompatibility by extra(JavaVersion.VERSION_17)
val sourceCompatibility1 by extra(JavaVersion.VERSION_17)
