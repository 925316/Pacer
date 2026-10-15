plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
}

tasks.register("cleanAll") {
    group = "build"
    description = "Remove all module build directories."
    doLast {
        rootProject.subprojects.forEach { sub ->
            delete(sub.layout.buildDirectory)
        }
    }
}
