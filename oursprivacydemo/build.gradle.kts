import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use(::load)
}
val recorderUrl = project.findProperty("recorderUrl") as String?
    ?: localProps.getProperty("RECORDER_URL", "")
val demoToken = project.findProperty("demoToken") as String?
    ?: localProps.getProperty("OURSPRIVACY_TOKEN", "")

android {
    namespace = "com.oursprivacy.oursprivacydemo"
    compileSdk = 36
    lint {
        baseline = file("lint-baseline.xml")
    }

    defaultConfig {
        applicationId = "com.oursprivacy.oursprivacydemo"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "OURSPRIVACY_TOKEN", "\"$demoToken\"")
        buildConfigField("String", "RECORDER_URL", "\"$recorderUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

val usePublished = project.findProperty("usePublished") == "true"
val sdkVersion = rootProject.properties["VERSION_NAME"] as String? ?: "2.0.0-alpha.0"
val publishedSdkVersion = project.findProperty("publishedSdkVersion") as String? ?: sdkVersion

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.runtime)
    implementation(libs.navigation.compose)
    if (usePublished) {
        implementation("com.oursprivacy:oursprivacy-android:$publishedSdkVersion")
    } else {
        implementation(project(":oursprivacy-android"))
    }
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
