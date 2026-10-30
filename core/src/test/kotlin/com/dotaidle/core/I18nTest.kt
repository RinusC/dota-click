package com.dotaidle.core

import com.dotaidle.core.i18n.I18n
import com.dotaidle.core.i18n.Strings
import kotlin.test.Test
import kotlin.test.assertEquals

class I18nTest {
    private val spec = Regex("%[sd]")

    @Test
    fun everyStringFormatsInBothLanguages() {
        assertEquals(Strings.en.keys, Strings.ru.keys)
        for (lang in I18n.supported) {
            I18n.language = lang
            for (key in Strings.en.keys) {
                val pattern = (if (lang == "ru") Strings.ru else Strings.en)[key]!!
                val args = spec.findAll(pattern).map { if (it.value == "%d") 1 else "x" }.toList().toTypedArray()
                val enArgs = spec.findAll(Strings.en[key]!!).count()
                assertEquals(enArgs, args.size, "placeholder count differs for '$key' ($lang)")
                I18n.t(key, *args) // must not throw
            }
        }
        I18n.language = "en"
    }
}
