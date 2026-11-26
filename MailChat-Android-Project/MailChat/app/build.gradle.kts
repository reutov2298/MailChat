plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.mailchat.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.mailchat.app"
        manifestPlaceholders["appAuthRedirectScheme"] = "com.googleusercontent.apps.150613527215-b8pfo2q526mpvgihvlj03iq9kmjpdn9i"
        minSdk = 26
        targetSdk = 35
        // Material Design 3 new-chat interface.
        versionCode = 41
        versionName = "1.1.8"
        val oauthClientId = providers.gradleProperty("googleOAuthClientId").orElse(providers.environmentVariable("GOOGLE_OAUTH_CLIENT_ID")).getOrElse("REPLACE_WITH_GOOGLE_OAUTH_CLIENT_ID")
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$oauthClientId\"")
    }
    signingConfigs {
        create("mcPersistent") {
            val keystorePath = System.getenv("MC_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (!System.getenv("MC_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("mcPersistent")
            }
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("net.openid:appauth:0.11.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
