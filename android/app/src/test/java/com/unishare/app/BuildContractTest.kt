package com.unishare.app

import com.unishare.app.presentation.theme.DarkColorScheme
import com.unishare.app.presentation.theme.LightColorScheme
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BuildContractTest {
    @Test
    fun manifest_declares_expected_package() {
        val configuration = Properties().apply {
            requireNotNull(BuildContractTest::class.java.getResourceAsStream("/com/android/tools/test_config.properties"))
                .use { load(it) }
        }
        val manifest = parseXml(File(configuration.getProperty("android_merged_manifest")))
        assertEquals("com.unishare.app", manifest.documentElement.getAttribute("package"))
        val activity = manifest.getElementsByTagName("activity").item(0).attributes
        assertEquals("com.unishare.app.MainActivity", activity.getNamedItem("android:name").nodeValue)
    }

    @Test
    fun english_and_chinese_core_strings_exist() {
        val expected = mapOf(
            "values" to listOf("Share", "Devices", "History", "Settings"),
            "values-zh-rCN" to listOf("分享", "设备", "历史", "设置"),
        )
        for ((locale, labels) in expected) {
            val nodes = parseXml(File("src/main/res/$locale/strings.xml")).getElementsByTagName("string")
            val strings = (0 until nodes.length).associate { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
            assertEquals("UniShare", strings["app_name"])
            assertEquals(labels, listOf("nav_share", "nav_devices", "nav_history", "nav_settings").map(strings::get))
        }
    }

    @Test
    fun theme_tokens_define_light_and_dark_palettes() {
        assertNotEquals(LightColorScheme.surface, DarkColorScheme.surface)
        assertNotEquals(LightColorScheme.onSurface, DarkColorScheme.onSurface)
        assertNotEquals(LightColorScheme.primary, LightColorScheme.onPrimary)
        assertNotEquals(DarkColorScheme.primary, DarkColorScheme.onPrimary)
    }

    @Test
    fun app_variants_filter_dependency_abis_to_the_native_sdk_abis() {
        fun filters(script: File, variant: String): Set<String> {
            val block = Regex(
                "(?s)(?:getByName\\(\"$variant\"\\)|\\b$variant)\\s*\\{.*?ndk\\s*\\{\\s*abiFilters\\s*\\+=\\s*listOf\\(([^)]*)\\)",
            ).find(script.readText())
            return block?.groupValues?.get(1)?.let { values ->
                Regex("\"([^\"]+)\"").findAll(values).map { it.groupValues[1] }.toSet()
            }.orEmpty()
        }
        val expected = mapOf(
            "debug" to setOf("arm64-v8a", "x86_64"),
            "release" to setOf("arm64-v8a", "armeabi-v7a", "x86_64"),
        )
        for ((variant, abis) in expected) {
            assertEquals("SDK $variant ABIs", abis, filters(File("../share-sdk/build.gradle.kts"), variant))
            assertEquals("App $variant must not package dependency-only ABIs", abis, filters(File("build.gradle.kts"), variant))
        }
    }

    private fun parseXml(file: File) = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(file)
}
