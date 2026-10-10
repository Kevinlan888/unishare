package com.unishare.app.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidDeviceNameProviderTest {
    @Test
    fun validSystemNameTakesPriorityAndIsTrimmed() {
        assertEquals("Pixel 10a", selectAndroidDeviceName("  Pixel 10a  ", "Fallback model"))
    }

    @Test
    fun blankSystemNameFallsBackToModel() {
        assertEquals("Pixel 10a", selectAndroidDeviceName(" \n\t ", "Pixel 10a"))
    }

    @Test
    fun invalidSystemNameFallsBackToModel() {
        assertEquals("Pixel 10a", selectAndroidDeviceName("Office/Phone", "Pixel 10a"))
    }

    @Test
    fun invalidSystemAndModelNamesFallBackToAndroid() {
        assertEquals("Android", selectAndroidDeviceName("Phone\\One", "Model/One"))
    }
}
