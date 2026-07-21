package com.jeff.gbxsync.sync

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Before any side is overwritten, the losing copy lands here
 * (app files dir, backups/<gameKey>/<timestamp>-<source>.sav). Keeps the
 * newest [MAX_PER_GAME] per game.
 */
class BackupManager(private val context: Context) {

    fun backup(gameKey: String, source: String, data: ByteArray): File {
        val dir = File(File(context.filesDir, "backups"), gameKey.take(16))
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "$stamp-$source.sav")
        file.writeBytes(data)
        dir.listFiles()
            ?.sortedByDescending { it.name }
            ?.drop(MAX_PER_GAME)
            ?.forEach { it.delete() }
        return file
    }

    fun backupsFor(gameKey: String): List<File> =
        File(File(context.filesDir, "backups"), gameKey.take(16))
            .listFiles()?.sortedByDescending { it.name } ?: emptyList()

    companion object {
        const val MAX_PER_GAME = 10
    }
}
