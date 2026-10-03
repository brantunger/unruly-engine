plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
}

// The plugins the convention plugins apply, by their plugin marker coordinates. Their versions are in
// gradle/libs.versions.toml, which settings.gradle.kts imports as the libs catalog.
dependencies {
    implementation(libs.vanniktech.publish.plugin)
    implementation(libs.japicmp.plugin)
    implementation(libs.module.info.plugin)
    implementation(libs.cyclonedx.plugin)
}
