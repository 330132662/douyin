// 抖音获客助手 - 项目级构建脚本
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    // KSP：Room 注解处理（Kotlin 1.9.22 配套 1.9.22-1.0.18）
    id("com.google.devtools.ksp") version "1.9.22-1.0.18" apply false
}
