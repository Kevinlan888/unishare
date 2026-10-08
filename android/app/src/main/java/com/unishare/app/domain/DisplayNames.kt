package com.unishare.app.domain

/** Display metadata only; never return a path or invisible direction/control characters. */
fun sanitizedDisplayName(raw: String): String = raw.replace('\\', '/').substringAfterLast('/')
    .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }.trim().take(128)

fun isValidDeviceName(raw: String): Boolean = raw.trim().let { name ->
    name.isNotEmpty() && name.length <= 64 && raw.none {
        it == '/' || it == '\\' || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
    }
}
