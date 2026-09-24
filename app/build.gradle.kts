plugins {
    alias(libs.plugins.android.application)
    // ⚠️ PAS de `kotlin.android` : AGP 9 l'integre (+ echoue si declare)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "sh.sk7.tether"
    compileSdk = 37              // aligné sur Task 0.3 step 4
    defaultConfig {
        applicationId = "sh.sk7.tether"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// ⚠️ `kotlinOptions` est DEPRECATED sur Kotlin 2.x -> bloc `kotlin {}`
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)          // ⚠️ ksp, PAS kapt
    implementation(libs.hilt.navigation.compose)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.code)
    implementation(libs.coil.compose)
    implementation(libs.lucide)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.client.mock)
}

tasks.withType<Test>().configureEach {
    listOf("tether.baseUrl", "tether.password", "tether.location").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
