package com.dotaidle.core.engine

/** One-shot notifications for the UI (floating texts, sounds). Drained every frame. */
sealed class GameEvent {
    data class LaneWave(val lane: Int, val gold: Double) : GameEvent()
    data class EnemyKilled(val index: Int, val bounty: Double) : GameEvent()
    data class AbilityCast(val slot: Int, val heroId: String) : GameEvent()
    data class GoldGained(val gold: Double) : GameEvent()
    data object AegisGained : GameEvent()
    data object AncientDestroyed : GameEvent()
}

/** Battle log line, localized lazily so switching language updates the log too. */
data class LogEntry(val time: Double, val text: () -> String)

/** Result of applying offline / "farm 4h" progress. */
data class IdleReport(
    val awaySeconds: Double,
    val creditedSeconds: Double,
    val gold: Double,
    val enemiesKilled: Int,
)

