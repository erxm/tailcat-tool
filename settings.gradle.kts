// google() 不是为 Android 留的：Compose Multiplatform 的桌面构件自身依赖 androidx.*
// （runtime / collection / annotation），这些只发布在 Google Maven 上，去掉会直接解析失败。
// 本项目不需要 AGP，也不需要 Android SDK。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TailcatManager"

include(":core")
include(":desktop")
