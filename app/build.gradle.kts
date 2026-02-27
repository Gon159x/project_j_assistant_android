import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val cloudflarePublicBaseUrl = System.getenv("CLOUDFLARE_PUBLIC_BASE_URL") ?: ""
val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH") ?: ""
val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: ""
val keyAliasEnv = System.getenv("ANDROID_KEY_ALIAS") ?: ""
val keyPasswordEnv = System.getenv("ANDROID_KEY_PASSWORD") ?: ""
val requiresReleaseSigning = gradle.startParameter.taskNames.any { task ->
    task.contains("Release", ignoreCase = true)
}

android {
    namespace = "com.proyectoj.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.proyectoj.assistant"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.0"
        buildConfigField("String", "CLOUDFLARE_PUBLIC_BASE_URL", "\"$cloudflarePublicBaseUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val hasSigningConfig = keystorePath.isNotBlank() &&
                keystorePassword.isNotBlank() &&
                keyAliasEnv.isNotBlank() &&
                keyPasswordEnv.isNotBlank()
            if (hasSigningConfig) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAliasEnv
                this.keyPassword = keyPasswordEnv
            } else if (requiresReleaseSigning) {
                throw GradleException(
                    "Missing Android release signing variables. Set ANDROID_KEYSTORE_PATH, " +
                        "ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD."
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
