plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "fr.relance.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.relance.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.7.0"
        // Adresse du catalogue publié depuis l'interface coach.
        buildConfigField("String", "CATALOG_URL", "\"" + (project.findProperty("relance.catalogUrl") ?: "https://relance-coach.netlify.app/api/catalog") + "\"")
    }

    // Clé de signature de développement, volontairement versionnée : elle garantit que
    // chaque APK construit depuis ce dépôt peut se mettre à jour par-dessus le précédent.
    // À remplacer par une clé privée avant toute diffusion publique.
    signingConfigs {
        getByName("debug") {
            storeFile = file("relance-dev.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // Le catalogue de programmes intégré, partagé avec le back-end (web/) : catalog/catalog.json.
    sourceSets {
        getByName("main") {
            resources.srcDir("../catalog")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Les tests d'interface enregistrent une capture de chaque écran dans app/build/shots.
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
            // Une JVM par classe de test : les données enregistrées par un test d'interface ne fuient pas dans le suivant.
            it.forkEvery = 1
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.40.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.40.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
