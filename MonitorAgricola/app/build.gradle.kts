import org.gradle.api.tasks.JavaExec


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.kapt") // ativa o KAPT
}

android {
    namespace = "com.example.monitoragricola"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.monitoragricola"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Se puder, considere usar Java 17; se deixar 11, tudo bem.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // BOM Compose + AndroidX básicos
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // UI “clássica” (XML) que você já usa
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.6.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Mapas & Geometria
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("org.locationtech.jts:jts-core:1.20.0")

    // Utilidades
    implementation("com.google.code.gson:gson:2.8.9")

    // Room (runtime + coroutines) + KAPT (compiler)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    //Recicle
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.3")

    // Testes
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

tasks.register<JavaExec>("roomTileStoreSelfTest") {
    group = "verification"
    description = "Executa verificação de preload sem tiles persistidos para RoomTileStore"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.example.monitoragricola.raster.store.RoomTileStoreSelfTest")
}

tasks.named("check") {
    dependsOn("roomTileStoreSelfTest")
}