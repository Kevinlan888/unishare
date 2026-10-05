package com.unishare.sdk

import android.app.Activity
import java.util.concurrent.CountDownLatch

/** Empty test host supplies the real focus required by Android clipboard privacy. */
class ClipboardHostActivity : Activity() {
    companion object { @Volatile var focused = CountDownLatch(1) }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) focused.countDown()
    }
}
