// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    // [效果验证] Roborazzi 快照测试：baseline 固化参考包效果，自动 diff 防"声称做了没做"
    alias(libs.plugins.roborazzi) apply false
}
