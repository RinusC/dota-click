package com.dotaidle.core.i18n

/** A localized pair used by content definitions (heroes, items, talents...). */
data class Loc(val en: String, val ru: String) {
    fun get(): String = if (I18n.language == "ru") ru else en
    override fun toString(): String = get()
}

object I18n {
    @Volatile
    var language: String = "en"

    val supported = listOf("en", "ru")

    fun t(key: String, vararg args: Any?): String {
        val table = if (language == "ru") Strings.ru else Strings.en
        val pattern = table[key] ?: Strings.en[key] ?: key
        return if (args.isEmpty()) pattern else String.format(java.util.Locale.US, pattern, *args)
    }
}

fun tr(key: String, vararg args: Any?): String = I18n.t(key, *args)
