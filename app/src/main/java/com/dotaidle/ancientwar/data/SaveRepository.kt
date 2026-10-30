package com.dotaidle.ancientwar.data

import android.content.Context
import com.dotaidle.core.model.GameState
import com.dotaidle.core.save.SaveCodec
import java.io.File

/**
 * Data layer: stores the JSON save in internal storage.
 * Writes go to a temp file first and the previous save is kept as a backup,
 * so a crash mid-write never loses progress.
 */
class SaveRepository(context: Context) {
    private val dir = context.filesDir
    private val main = File(dir, "save.json")
    private val backup = File(dir, "save.bak.json")
    private val temp = File(dir, "save.tmp")

    fun load(): GameState? = SaveCodec.decode(main.readOrNull()) ?: SaveCodec.decode(backup.readOrNull())

    @Synchronized
    fun write(json: String) {
        temp.writeText(json)
        if (main.exists()) main.copyTo(backup, overwrite = true)
        if (!temp.renameTo(main)) {
            main.writeText(json)
            temp.delete()
        }
    }

    @Synchronized
    fun delete() {
        main.delete()
        backup.delete()
        temp.delete()
    }

    private fun File.readOrNull(): String? = try {
        if (exists()) readText() else null
    } catch (e: Exception) {
        null
    }
}
