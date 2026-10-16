import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

/** Valore da local.properties, poi da gradle.properties / -P, poi da variabile d'ambiente (CI). */
fun ownerProperty(name: String): String? =
    localProperties.getProperty(name)?.takeIf(String::isNotBlank)
        ?: providers.gradleProperty(name).orNull?.takeIf(String::isNotBlank)
        ?: providers.environmentVariable(name).orNull?.takeIf(String::isNotBlank)

fun quotedProperty(name: String): String {
    val value = ownerProperty(name).orEmpty()
    return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

android {
    namespace = "it.fotofesta.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.fotofesta.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.3.0"

        buildConfigField("String", "PHOTOS_CLIENT_ID", quotedProperty("PHOTOS_CLIENT_ID"))
        buildConfigField("String", "PHOTOS_CLIENT_SECRET", quotedProperty("PHOTOS_CLIENT_SECRET"))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // La pagina che i telefoni degli invitati aprono è la stessa per Android TV e Apple TV.
    sourceSets.getByName("main").assets.srcDir("../shared/assets")

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }

    // Firma release opzionale: se RELEASE_STORE_FILE non è configurato l'AAB resta non firmato.
    val releaseStoreFile = ownerProperty("RELEASE_STORE_FILE")?.let(rootProject::file)
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = ownerProperty("RELEASE_STORE_PASSWORD")
                keyAlias = ownerProperty("RELEASE_KEY_ALIAS")
                keyPassword = ownerProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    testOptions {
        // android.util.Log e simili restituiscono valori di default nei test JVM invece di fallire.
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.media:media:1.8.0")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
