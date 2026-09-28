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
        // L'instrumentation est ce qui prouve l'ecran : le contraste d'un accent et la
        // langue appliquee ne se verifient pas dans un test JVM, qui n'a ni densite de
        // pixel ni contexte. Elle tourne sur l'appareil, en arriere-plan, sans que
        // l'ecran soit deverrouille.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.junit)

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
    implementation(libs.datastore.preferences)
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.code)
    implementation(libs.highlights)
    implementation(libs.coil.compose)
    implementation(libs.lucide)
    implementation(libs.unifiedpush.connector)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.client.mock)
}

tasks.withType<Test>().configureEach {
    listOf("tether.baseUrl", "tether.password", "tether.location").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

// ⚠️ **Aucun `buildTypes` n'existait : l'app n'avait donc QUE le profil debug**, sans R8, sans
// minification, sans optimisation. C'est le plus gros levier de performance disponible, et il
// etait invisible parce que l'app fonctionne — simplement lentement.
//
// ⚠️ La signature utilise la cle de debug : c'est un build **release** (optimise) mais signe
// localement, ce qui suffit pour installer et mesurer. Un vrai release exigerait une cle dediee.
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
