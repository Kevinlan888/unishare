package com.unishare.app

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class MainActivityWindowTest {
    @Test
    fun main_activity_resizes_content_for_ime_instead_of_panning_window() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val adjustMode = activity.window.attributes.softInputMode and
                    WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST

                assertEquals(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    adjustMode,
                )
            }
        }
    }
}
