plugins { id("com.android.application") }

android {
    namespace = "com.example.braillekb"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.braillekb"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes { release { isMinifyEnabled = false } }
}

dependencies {
    implementation("androidx.customview:customview:1.1.0")
    implementation("androidx.core:core:1.13.1")
}
