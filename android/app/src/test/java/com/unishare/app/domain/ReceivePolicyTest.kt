package com.unishare.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceivePolicyTest {
    @Test
    fun trusted_text_is_auto_accepted() {
        assertEquals(ReceiveDecision.AUTO_ACCEPT, ReceivePolicy.decide(true, ContentKind.TEXT))
    }

    @Test
    fun trusted_image_and_file_require_confirmation() {
        assertEquals(ReceiveDecision.PROMPT, ReceivePolicy.decide(true, ContentKind.IMAGE))
        assertEquals(ReceiveDecision.PROMPT, ReceivePolicy.decide(true, ContentKind.FILE))
    }

    @Test
    fun every_untrusted_kind_requires_confirmation() {
        for (kind in ContentKind.entries) {
            assertEquals(ReceiveDecision.PROMPT, ReceivePolicy.decide(false, kind))
        }
    }
}
