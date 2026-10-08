package com.unishare.app.presentation.format

import android.content.Context
import com.unishare.app.R
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

object DisplayFormatters {
    fun timestamp(context: Context, millis: Long): String = DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM, DateFormat.SHORT, locale(context),
    ).format(Date(millis))

    fun bytes(context: Context, bytes: Long): String {
        var value = bytes.coerceAtLeast(0).toDouble()
        val units = intArrayOf(R.string.unit_bytes, R.string.unit_kib, R.string.unit_mib, R.string.unit_gib, R.string.unit_tib)
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
        val number = NumberFormat.getNumberInstance(locale(context)).apply { maximumFractionDigits = if (unit == 0) 0 else 1 }
        return context.getString(R.string.formatted_bytes, number.format(value), context.getString(units[unit]))
    }
    private fun locale(context: Context): Locale = context.resources.configuration.locales[0]
}
