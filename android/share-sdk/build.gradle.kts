plugins { id("com.android.library") }
android {
    namespace = "com.unishare.sdk"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    testOptions { targetSdk = 36 }
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = if (providers.gradleProperty("publicTests").orElse("OFF").get() == "ON") "com.unishare.sdk.PublicApiTests" else "com.unishare.sdk.internal.PortTests"
        externalNativeBuild { cmake {
            arguments += listOf("-DBUILD_TESTING=OFF", "-DUNISHARE_ANDROID_DEPENDENCIES=${rootDir.parentFile}/build-deps", "-DOPENSSL_USE_STATIC_LIBS=TRUE", "-DANDROID_STL=c++_static")
            targets += "unishare_android"
        } }
    }
    externalNativeBuild { cmake { path = file("../../CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        getByName("debug") {
            ndk { abiFilters += "arm64-v8a" }
            externalNativeBuild { cmake {
                arguments += "-DUNISHARE_ANDROID_PORT_TESTS=${providers.gradleProperty("portTests").orElse("OFF").get()}"
                if (providers.gradleProperty("portTests").orElse("OFF").get() == "ON") targets += "unishare_android_probe"
            } }
        }
        getByName("release") {
            isMinifyEnabled = false
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
            externalNativeBuild { cmake { arguments += "-DUNISHARE_ANDROID_PORT_TESTS=OFF" } }
        }
    }
}
