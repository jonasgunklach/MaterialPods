plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}

android {
    namespace = "app.materialpods"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "app.materialpods"
        // ponytail: 36 = Pixel A16 QPR3 / OnePlus; Samsung needs 37 (One UI 9) for rootless L2CAP.
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.glance:glance-appwidget:1.2.0")}
