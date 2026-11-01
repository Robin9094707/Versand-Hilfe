plugins { id("com.android.application") }
android {
    namespace = "fr.vinted"
    compileSdk = 36
    defaultConfig { applicationId = "fr.vinted"; minSdk = 30; targetSdk = 36; versionCode = 1; versionName = "fixture-only" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation("com.google.zxing:core:3.5.3") }
