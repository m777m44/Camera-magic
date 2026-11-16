plugins {
    id("com.android.application")
}

val appBuildNumber = providers.environmentVariable("MAHDI_VERSION_CODE").orElse("1").get().toInt()

android {
    namespace = "com.mahdi.camera"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mahdi.camera"
        minSdk = 23
        targetSdk = 35
        versionCode = appBuildNumber
        versionName = "1.0.$appBuildNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("main").assets.srcDir("src/main/assets")
    buildTypes {
        release {
            isMinifyEnabled = false
            // Signing happens separately with an owner-controlled, persistent key.
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint {
        abortOnError = true
        disable += "OldTargetApi"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
