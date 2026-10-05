plugins { id("com.android.application") version "9.2.0" }
android {
    namespace = "com.unishare.interop"
    compileSdk = 36
    defaultConfig { applicationId = "com.unishare.interop"; minSdk = 26; targetSdk = 36 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets.getByName("main").assets.directories.add("../build-fixtures")
}
dependencies {
    implementation(files(providers.gradleProperty("consumedAar").get()))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
}
