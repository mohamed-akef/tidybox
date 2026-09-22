plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    id("app.cash.sqldelight")
}

android {
    namespace = "app.tidybox"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.tidybox"
        minSdk = 26
        targetSdk = 37
        versionCode = 12
        versionName = "0.1.11"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release { isMinifyEnabled = false } // ponytail: R8 config is a release-prep chore, not a skeleton one
    }
}

kotlin { jvmToolchain(17) }

sqldelight {
    databases {
        create("TidyBoxDb") { packageName.set("app.tidybox.db") }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("app.cash.sqldelight:android-driver:2.1.0")
    implementation("net.zetetic:sqlcipher-android:4.9.0@aar")
    implementation("androidx.sqlite:sqlite-ktx:2.5.2")
    implementation("app.cash.sqldelight:coroutines-extensions:2.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("app.cash.sqldelight:sqlite-driver:2.1.0")
}
