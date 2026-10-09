package com.unishare.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class ResourceParityTest {
    @Test fun english_and_simplified_chinese_have_exact_keys_and_format_arguments() {
        fun entries(path: String): Map<String, String> {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path)).documentElement.childNodes
            val result = mutableMapOf<String, String>()
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
                assertNull("duplicate key $name", result.put(name, node.textContent))
            }
            return result
        }
        val en = entries("src/main/res/values/strings.xml")
        val zh = entries("src/main/res/values-zh-rCN/strings.xml")
        assertEquals(en.keys, zh.keys)
        val placeholder = Regex("%[0-9]+\\\$[dsf]")
        // Chinese has only 'other'; English also has 'one'. Quantity counts may differ.
        en.forEach { (key, value) -> assertEquals(key, placeholder.findAll(value).map { it.value }.toSet(), placeholder.findAll(zh.getValue(key)).map { it.value }.toSet()) }
        assertTrue("management copy is required", en.containsKey("settings_downloads_only"))
    }
}
