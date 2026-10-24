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
        versionCode = 5
        versionName = "1.4.0"

        buildConfigField("String", "PHOTOS_CLIENT_ID", quotedProperty("PHOTOS_CLIENT_ID"))
        buildConfigField("String", "PHOTOS_CLIENT_SECRET", quotedProperty("PHOTOS_CLIENT_SECRET"))
        // Pagina del sito che riceve il codice OAuth dal telefono e lo rimanda alla TV (site/google/).
        buildConfigField("String", "PHOTOS_REDIRECT_URI", quotedProperty("PHOTOS_REDIRECT_URI"))
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
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            // Doppioni delle librerie SMB; le licenze sono in assets/licenses.
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE.txt",
            "META-INF/DEPENDENCIES",
            "META-INF/versions/9/module-info.class",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            // Classi per Java 9+ di BouncyCastle: Android usa quelle normali.
            "META-INF/versions/9/org/bouncycastle/**",
            "META-INF/versions/11/**",
        )
    }

    // Firma release opzionale: se RELEASE_STORE_FILE non è configurato l'AAB resta non firmato.
    val releaseStoreFile = ownerProperty("RELEASE_STORE_FILE")?.let(rootProject::file)
    signingConfigs {
        // Chiave di debug fissa e pubblica (password "android"), solo per le build di test: ogni APK
        // della CI si installa sopra il precedente senza disinstallare. Play non accetta build debug.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
    // Video nello slideshow: lettore, vista e lettura dei video di Google Foto.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("com.google.zxing:core:3.5.3")
    // Cartelle condivise (SMB 2/3) di PC e NAS, ed elenco delle condivisioni (DCE/RPC).
    implementation("com.hierynomus:smbj:0.14.0")
    implementation("com.rapid7.client:dcerpc:0.12.13")
    // Canale di FotoFesta nella schermata Home della TV e aggiornamenti dal Play Store.
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
