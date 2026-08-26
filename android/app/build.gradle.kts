import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val rootEnvFile = rootDir.parentFile.resolve(".env.local").takeIf { it.exists() }
    ?: rootDir.parentFile.resolve(".env").takeIf { it.exists() }

val rootEnvProps = Properties().apply {
    rootEnvFile?.inputStream()?.use { load(it) }
}

val defaultLocalUrl = "http://10.0.2.2:54321"
val defaultLocalAnonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM0MTI4MDB9.CRAS_LOCAL_DEV_ANON_KEY"

val supabaseUrl = rootEnvProps.getProperty("SUPABASE_URL")
    ?: rootEnvProps.getProperty("VITE_SUPABASE_URL")
    ?: defaultLocalUrl

val supabaseAnonKey = rootEnvProps.getProperty("SUPABASE_ANON_KEY")
    ?: rootEnvProps.getProperty("VITE_SUPABASE_ANON_KEY")
    ?: defaultLocalAnonKey

// Public Firebase client configuration. Server-side FCM credentials never
// reach the client (ADR 0007); these values are the public app registration.
val firebaseProjectId = rootEnvProps.getProperty("FIREBASE_PROJECT_ID") ?: ""
val firebaseApplicationId = rootEnvProps.getProperty("FIREBASE_APPLICATION_ID") ?: ""
val firebaseApiKey = rootEnvProps.getProperty("FIREBASE_API_KEY") ?: ""
val firebaseGcmSenderId = rootEnvProps.getProperty("FIREBASE_GCM_SENDER_ID") ?: ""
val firebaseStorageBucket = rootEnvProps.getProperty("FIREBASE_STORAGE_BUCKET") ?: ""

android {
    namespace = "com.cras.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cras.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$firebaseProjectId\"")
        buildConfigField("String", "FIREBASE_APPLICATION_ID", "\"$firebaseApplicationId\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"$firebaseApiKey\"")
        buildConfigField("String", "FIREBASE_GCM_SENDER_ID", "\"$firebaseGcmSenderId\"")
        buildConfigField("String", "FIREBASE_STORAGE_BUCKET", "\"$firebaseStorageBucket\"")
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.firebase:firebase-messaging:25.1.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
