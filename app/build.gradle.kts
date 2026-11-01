plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.kanorin.gallery"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.kanorin.gallery"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Sideload builds for Kano's phones; same key on every build so updates install over.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true // Settings footer shows versionName
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Lint's K2 resolve crashes on the test sources (lintAnalyzeDebugUnitTest); the app's own sources are what ship.
    lint {
        ignoreTestSources = true
    }

    // MigrationTestHelper reads the exported schemas from assets; Robolectric reads debug's (release never has them).
    sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")
}

ksp {
    // Room writes each schema version here (exportSchema = true); keep it in git for migrations.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
