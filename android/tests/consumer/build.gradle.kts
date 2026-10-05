plugins { id("com.android.application") version "9.2.0" }
val hostTarget = providers.gradleProperty("consumerTarget").orElse("36").get().toInt()
android {
    namespace = "com.unishare.consumer"
    compileSdk = 36
    defaultConfig { applicationId = "com.unishare.consumer"; minSdk = 26; targetSdk = hostTarget }
    if (hostTarget >= 37) sourceSets.getByName("main").manifest.srcFile("src/main/AndroidManifest37.xml")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes { getByName("release") {
        isMinifyEnabled = true
        signingConfig = signingConfigs.getByName("debug")
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "consumer-test-rules.pro")
    } }
}
dependencies {
    implementation(files(providers.gradleProperty("consumedAar").get()))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
}
