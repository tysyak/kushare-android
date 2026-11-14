plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.kushare.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.kushare.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 9
        versionName = "0.6.0"
    }

    // Firma de release: la clave y sus contraseñas viven FUERA del repo, en
    // ~/.gradle/gradle.properties (KUSHARE_RELEASE_*). Sin ellas, el APK de
    // release sale sin firmar.
    val releaseStore = providers.gradleProperty("KUSHARE_RELEASE_STORE_FILE").orNull
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = providers.gradleProperty("KUSHARE_RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("KUSHARE_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("KUSHARE_RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // R8: quita código y recursos no usados (p. ej. los miles de
            // iconos de material-icons-extended) y ofusca.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            // Bindings de Kotlin generados por uniffi-bindgen a partir del
            // crate Rust en ../rust (ver README.md, sección "Compilar").
            // No se editan a mano.
            kotlin.directories += "src/main/java-generated"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Material 3 Expressive sigue marcado como experimental en la 1.5.
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Material 3 Expressive (MaterialExpressiveTheme, MaterialShapes,
    // LoadingIndicator, botones con morph de forma…) está en la 1.5.
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.material:material-icons-extended")

    // Requerido por el código generado por uniffi-bindgen para llamar al
    // cdylib de Rust (crates/kushare-mobile) vía JNI.
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // Cámara: webcam para el PC y escáner de QR (0.7).
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    // Lectura de QR y códigos de barras sin servicios de Google (Apache 2.0).
    implementation("com.google.zxing:core:3.5.3")
}
