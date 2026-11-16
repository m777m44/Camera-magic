plugins {
    id("com.android.library")
}

android {
    namespace = "com.mahdi.camera.hdr"
    compileSdk = 35
    defaultConfig {
        minSdk = 23
        targetSdk = 35 // Test APK baseline; the consuming application chooses its own target.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    sourceSets.getByName("main").assets.srcDir("../assets")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint {
        abortOnError = true
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
