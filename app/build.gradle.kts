import java.net.URI

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// The GitHub Pages site that serves docs/. Shared app links point at <base>/s/?id=<uuid>.
val shareBaseUrl = providers.gradleProperty("scrollcast.shareBaseUrl")
    .getOrElse("https://example.github.io/scroll-cast-radio")
    .trimEnd('/')
val shareUri = URI(shareBaseUrl)

// Releases set this from the git tag (v1.2.3 → 1.2.3); versionCode is derived from it.
val appVersionName = providers.gradleProperty("appVersionName").getOrElse("0.2.0")
val appVersionCode = appVersionName.substringBefore('-').split('.')
    .map { it.toIntOrNull() ?: 0 }
    .let { (it + listOf(0, 0, 0)).take(3) }
    .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

// Release signing comes from environment variables (GitHub Actions secrets).
val releaseStoreFile: String? = System.getenv("SIGNING_STORE_FILE")

android {
    namespace = "com.scrollcast.radio"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.scrollcast.radio"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "SHARE_BASE_URL", "\"$shareBaseUrl\"")
        buildConfigField(
            "String", "UPDATE_REPO",
            "\"${providers.gradleProperty("scrollcast.updateRepo").getOrElse("Glowing-Radiant/Scroll-cast-radio")}\"",
        )
        manifestPlaceholders["shareHost"] = shareUri.host
        manifestPlaceholders["sharePathPrefix"] = "${shareUri.path}/s"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Installs beside the release app, so GitHub updates never clash with dev builds.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.core)
  debugImplementation(libs.androidx.compose.ui.tooling)

  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.exoplayer.hls)
  implementation(libs.androidx.media3.session)

  implementation(libs.okhttp)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
