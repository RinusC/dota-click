package com.dotaidle.core

import com.dotaidle.core.util.NumberFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class NumberFormatTest {
    @Test
    fun formatsSuffixes() {
        assertEquals("0", NumberFormat.format(0.0))
        assertEquals("12.5", NumberFormat.format(12.57))
        assertEquals("999", NumberFormat.format(999.9))
        assertEquals("1.00K", NumberFormat.format(1000.0))
        assertEquals("1.23K", NumberFormat.format(1234.0))
        assertEquals("45.6M", NumberFormat.format(45_678_901.0))
        assertEquals("789B", NumberFormat.format(789_000_000_000.0))
        assertEquals("1.00T", NumberFormat.format(1e12))
        assertEquals("1.00aa", NumberFormat.format(1e15))
        assertEquals("999K", NumberFormat.format(999_999.0))
        assertEquals("-2.50K", NumberFormat.format(-2500.0))
        assertEquals("∞", NumberFormat.format(Double.POSITIVE_INFINITY))
    }

    @Test
    fun formatsWholeDoubleRange() {
        // must never throw or produce garbage up to Double.MAX_VALUE
        var v = 1.0
        while (v < 1e300) {
            val s = NumberFormat.format(v)
            check(s.isNotEmpty() && !s.contains("NaN")) { "bad format for $v: $s" }
            v *= 7.3
        }
    }

    @Test
    fun formatsDuration() {
        assertEquals("45s", NumberFormat.duration(45.0))
        assertEquals("1m 15s", NumberFormat.duration(75.0))
        assertEquals("8h 00m", NumberFormat.duration(8 * 3600.0))
    }
}
