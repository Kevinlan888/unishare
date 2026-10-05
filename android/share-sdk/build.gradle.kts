plugins { id("com.android.library") }
android {
    namespace = "com.unishare.sdk"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "com.unishare.sdk.internal.PortTests"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake {
            arguments += listOf("-DBUILD_TESTING=OFF", "-DOPENSSL_ROOT_DIR=${rootDir.parentFile}/build-deps/vcpkg_installed/arm64-android", "-DOPENSSL_USE_STATIC_LIBS=TRUE", "-DUNISHARE_ANDROID_PORT_TESTS=${providers.gradleProperty("portTests").orElse("OFF").get()}")
            targets += "unishare_android"
            if (providers.gradleProperty("portTests").orElse("OFF").get() == "ON") targets += "unishare_android_probe"
        } }
    }
    externalNativeBuild { cmake { path = file("../../CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
}
