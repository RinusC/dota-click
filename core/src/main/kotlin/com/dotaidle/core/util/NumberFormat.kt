package com.dotaidle.core.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Formats big doubles as 1.23K / 45.6M / 789B / 1.00T / 1.00aa ... */
object NumberFormat {
    private val named = listOf("", "K", "M", "B", "T")

    fun suffix(group: Int): String {
        if (group < named.size) return named[group]
        // aa, ab, ... az, ba, ... (enough for the whole double range)
        val n = group - named.size
        val first = 'a' + (n / 26)
        val second = 'a' + (n % 26)
        return "$first$second"
    }

    fun format(value: Double): String {
        if (value.isNaN()) return "0"
        if (value.isInfinite()) return if (value > 0) "∞" else "-∞"
        val sign = if (value < 0) "-" else ""
        val a = abs(value)
        if (a < 1000.0) {
            return sign + if (a < 100.0 && a != floor(a)) {
                String.format(Locale.US, "%.1f", floor(a * 10) / 10)
            } else {
                floor(a).toLong().toString()
            }
        }
        var group = floor(log10(a) / 3.0).toInt()
        var mantissa = a / 10.0.pow(group * 3)
        // Truncate (never round up) so 999.99K never prints as "1000K".
        if (mantissa >= 1000.0) { mantissa /= 1000.0; group++ }
        if (mantissa < 1.0) { mantissa *= 1000.0; group-- }
        val text = when {
            mantissa < 10.0 -> String.format(Locale.US, "%.2f", floor(mantissa * 100) / 100)
            mantissa < 100.0 -> String.format(Locale.US, "%.1f", floor(mantissa * 10) / 10)
            else -> floor(mantissa).toLong().toString()
        }
        return sign + text + suffix(group)
    }

    /** 3725 s -> "1h 02m", 75 s -> "1m 15s". */
    fun duration(seconds: Double): String {
        val s = seconds.coerceAtLeast(0.0).toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
            m > 0 -> String.format(Locale.US, "%dm %02ds", m, sec)
            else -> "${sec}s"
        }
    }
}

fun Double.fmt(): String = NumberFormat.format(this)
