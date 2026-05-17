import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.etti.clawhark"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.etti.clawhark"
        minSdk = 30
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.0"
    }

    signingConfigs {
        create("release") {
            val keyPropsFile = rootProject.file("keystore.properties")
            if (keyPropsFile.exists()) {
                val props = Properties().apply { load(keyPropsFile.inputStream()) }
                storeFile = file(props["storeFile"] as String)
                storePassword = props["storePassword"] as String
                keyAlias = props["keyAlias"] as String
                keyPassword = props["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
}

dependencies {
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
    
    // Compose for Wear OS
    implementation("androidx.compose.ui:ui:1.5.4")
    implementation("androidx.compose.material:material:1.5.4")
    implementation("androidx.wear.compose:compose-material:1.2.1")
    implementation("androidx.wear.compose:compose-foundation:1.2.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.5.4")
    
    // AWS SDK for S3
    implementation("com.amazonaws:aws-android-sdk-s3:2.77.0")
    implementation("com.amazonaws:aws-android-sdk-core:2.77.0")
}
