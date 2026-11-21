// AACP/ATT/BLE protocol layer, copied verbatim from LibrePods (android/rewrite @ 2e244d3), GPL-3.0.
// Package names kept as me.kavishdevar.librepods.core so upstream fixes merge cleanly.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.protobuf")
    id("kotlin-parcelize")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java")
                create("kotlin")
            }
        }
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "kotlinx.coroutines.FlowPreview",
            "kotlinx.serialization.ExperimentalSerializationApi"
        )
    }
}

android {
    namespace = "me.kavishdevar.librepods.core"
    compileSdk = 37
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    sourceSets {
        getByName("main") { res.directories += "src/main/res-apple" }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.11.0")
    implementation("com.google.protobuf:protobuf-kotlin:4.36.2")
}
