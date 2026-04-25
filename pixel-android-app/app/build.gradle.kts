plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
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

    buildConfigField("String", "DEFAULT_MODEL_VERSION", "\"gemma-4-e2b-it\"")
    buildConfigField("String", "DEFAULT_MODEL_CHECKSUM", "\"ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42\"")
    buildConfigField("String", "DEFAULT_MODEL_FILENAME", "\"gemma-4-E2B-it.litertlm\"")
    buildConfigField("String", "DEFAULT_MODEL_URL", "\"https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm\"")
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
  }

  buildFeatures {
    buildConfig = true
  }
}

dependencies {
  implementation("androidx.core:core-ktx:1.15.0")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.activity:activity-ktx:1.10.1")
  implementation("androidx.constraintlayout:constraintlayout:2.2.0")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
  implementation("androidx.webkit:webkit:1.12.1")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
  implementation("com.google.mediapipe:tasks-genai:0.10.27")

  testImplementation("junit:junit:4.13.2")
}
