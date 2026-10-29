import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val appVersionName = (project.findProperty("versionName") as String?) ?: "0.1.0-dev"
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "com.blacksingularities.opensync"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.blacksingularities.opensync"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "UPDATE_REPO", "\"BlackSingularities/OpenSync\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storeType = "pkcs12"
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePath != null && file(keystorePath).exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += listOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            "/META-INF/DEPENDENCIES",
            "/META-INF/INDEX.LIST",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.01.00")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.documentfile:documentfile:1.1.0")

    // Cele kopii: SMB 2/3 (NAS), SFTP, WebDAV.
    implementation("com.hierynomus:smbj:0.14.0")
    // Lista udziałów SMB (SRVSVC przez DCE/RPC) – przeglądanie NAS-a zamiast wpisywania nazw.
    implementation("com.rapid7.client:dcerpc:0.12.13") {
        exclude(group = "com.hierynomus", module = "smbj")
        exclude(group = "org.bouncycastle")
    }
    implementation("com.hierynomus:sshj:0.39.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
