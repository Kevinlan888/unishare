plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.unishare.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.unishare.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isIncludeAndroidResources = true
    buildTypes {
        getByName("debug") {
            // Filter dependency JNI libraries too, not just the SDK's native build.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

// Inspect the evaluated Android DSL; source text (including comments) is not evidence.
val abiContractFile = layout.buildDirectory.file("reports/abi-contract.properties")
val appAndroid = extensions.getByType<com.android.build.api.dsl.ApplicationExtension>()
val abiContractValues = listOf("debug", "release").flatMap { variant ->
    listOf(
        "app.$variant" to providers.provider {
            appAndroid.buildTypes.getByName(variant).ndk.abiFilters.sorted().joinToString(",")
        },
        "sdk.$variant" to providers.provider {
            project(":share-sdk").extensions.getByType<com.android.build.api.dsl.LibraryExtension>()
                .buildTypes.getByName(variant).ndk.abiFilters.sorted().joinToString(",")
        },
    )
}.toMap()
val writeAbiContract = tasks.register("writeAbiContract") {
    group = "verification"
    description = "Writes evaluated app/SDK debug/release ABI filters for contract tests."
    abiContractValues.forEach { (name, value) -> inputs.property(name, value) }
    outputs.file(abiContractFile)
    doLast {
        val report = abiContractFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(abiContractValues.toSortedMap().entries.joinToString("\n", postfix = "\n") {
            (name, value) -> "$name=${value.get()}"
        })
    }
}
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(writeAbiContract)
    inputs.file(abiContractFile).withPropertyName("evaluatedAbiFilters")
    systemProperty("unishare.abiContractFile", abiContractFile.get().asFile.absolutePath)
}

dependencies {
    implementation(project(":share-sdk"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
