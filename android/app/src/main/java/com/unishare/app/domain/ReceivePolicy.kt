package com.unishare.app.domain

object ReceivePolicy {
    fun decide(isTrusted: Boolean, kind: ContentKind): ReceiveDecision =
        if (isTrusted && kind == ContentKind.TEXT) ReceiveDecision.AUTO_ACCEPT else ReceiveDecision.PROMPT
}
