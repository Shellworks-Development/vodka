plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "dev.vodka"
  compileSdk = 35

  defaultConfig {
    applicationId = "dev.vodka"
    minSdk = 28
    targetSdk = 28
    versionCode = 1
    versionName = "0.1.0-alpha"

    ndk {
      abiFilters += "arm64-v8a"
    }

    externalNativeBuild {
      cmake {
        cppFlags += listOf("-std=c++17", "-fno-exceptions", "-fno-rtti")
        arguments += "-DANDROID_STL=c++_shared"
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  buildFeatures {
    viewBinding = true
  }

  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions {
    jvmTarget = "17"
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.material)
  implementation(libs.androidx.lifecycle.runtime.ktx)
}
