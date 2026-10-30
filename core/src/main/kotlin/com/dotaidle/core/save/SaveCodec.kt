package com.dotaidle.core.save

import com.dotaidle.core.model.GameState
import kotlinx.serialization.json.Json

/** JSON (de)serialization of the save. Platform-agnostic; storage lives in the app's Data layer. */
object SaveCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
        coerceInputValues = true
    }

    fun encode(state: GameState): String = json.encodeToString(GameState.serializer(), state)

    /** Returns null for corrupt / empty input so the caller can fall back to a backup or new game. */
    fun decode(text: String?): GameState? {
        if (text.isNullOrBlank()) return null
        return try {
            json.decodeFromString(GameState.serializer(), text)
        } catch (e: Exception) {
            null
        }
    }
}
