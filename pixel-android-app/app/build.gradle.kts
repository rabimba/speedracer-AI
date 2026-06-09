plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "com.trustableai.koru"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.trustableai.koru"
    minSdk = 29
    targetSdk = 35
    versionCode = 1
    versionName = "0.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    testInstrumentationRunnerArguments["timeout_msec"] = "900000"

    buildConfigField("String", "DEFAULT_MODEL_VERSION", "\"gemma-4-e2b-it\"")
    buildConfigField("String", "DEFAULT_MODEL_CHECKSUM", "\"181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c\"")
    buildConfigField("String", "DEFAULT_MODEL_FILENAME", "\"gemma-4-E2B-it.litertlm\"")
    buildConfigField("String", "DEFAULT_MODEL_URL", "\"https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm\"")
    buildConfigField("String", "DEFAULT_NPU_MODEL_CHECKSUM", "\"62faebcfd101acb841c33249530430397e031eb17d4dd3d2a71193d135705f27\"")
    buildConfigField("String", "DEFAULT_NPU_MODEL_FILENAME", "\"gemma-4-E2B-it_Google_Tensor_G5.litertlm\"")
    buildConfigField("String", "DEFAULT_NPU_MODEL_URL", "\"https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it_Google_Tensor_G5.litertlm\"")
    buildConfigField("String", "MODEL_DEV_ROOT", "\"/data/local/tmp/koru/models\"")
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
    debug {
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-debug"
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions {
    jvmTarget = "17"
    freeCompilerArgs += listOf("-Xskip-metadata-version-check")
  }

  buildFeatures {
    buildConfig = true
    compose = true
  }

  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
  }
}

dependencies {
  val cameraxVersion = "1.4.2"
  val composeBom = platform("androidx.compose:compose-bom:2025.01.00")

  implementation(composeBom)
  androidTestImplementation(composeBom)
  implementation("androidx.core:core-ktx:1.15.0")
  implementation("androidx.activity:activity-compose:1.10.1")
  implementation("androidx.activity:activity-ktx:1.10.1")
  implementation("androidx.camera:camera-camera2:$cameraxVersion")
  implementation("androidx.camera:camera-core:$cameraxVersion")
  implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
  implementation("androidx.camera:camera-view:$cameraxVersion")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
  implementation("com.github.mik3y:usb-serial-for-android:3.10.0")
  implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")
  implementation("com.google.mediapipe:tasks-genai:0.10.27")

  debugImplementation("androidx.compose.ui:ui-tooling")
  debugImplementation("androidx.compose.ui:ui-test-manifest")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20240303")
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")
  androidTestImplementation("androidx.test.ext:junit:1.3.0")
  androidTestImplementation("androidx.test:runner:1.7.0")
  androidTestImplementation("androidx.test:rules:1.7.0")
  androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
  androidTestImplementation("androidx.test.espresso:espresso-idling-resource:3.7.0")
}
