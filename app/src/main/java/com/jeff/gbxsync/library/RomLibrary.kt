package com.jeff.gbxsync.library

import android.content.Context
import android.net.Uri
import com.jeff.gbxsync.cart.AgbHeader
import com.jeff.gbxsync.cart.DmgHeader
import com.jeff.gbxsync.storage.SafFiles
import java.security.MessageDigest
import kotlinx.serialization.Serializable

enum class RomSystem { GB, GBA }

@Serializable
data class RomEntry(
    val uriString: String,
    val parentDocumentId: String,
    val displayName: String,
    val size: Long,
    val lastModified: Long,
    val system: RomSystem,
    val title: String,
    /** SHA-256 of the first 0x180 bytes — matches a cart's header read exactly. */
    val headerHash: String,
    val saveFileUriString: String? = null,
    val saveFileLastModified: Long = 0,
) {
    val uri: Uri get() = Uri.parse(uriString)
    val saveFileName: String
        get() = displayName.substringBeforeLast('.') + ".sav"
}

/** Scans the ROM folder, parses headers, and finds the matching entry for a cart. */
class RomLibrary(private val context: Context) {

    /**
     * Full scan of the tree. Reads the first 0x180 bytes of each new/changed
     * ROM; [previous] entries with matching size+mtime are reused untouched.
     */
    fun scan(treeUri: Uri, previous: List<RomEntry>): List<RomEntry> {
        val cache = previous.associateBy { it.uriString }
        val all = SafFiles.listTree(context, treeUri)
        val files = all.filter { !it.isDirectory }
        val savFilesByNameInDir = files
            .filter { it.displayName.endsWith(".sav", ignoreCase = true) }
            .associateBy { it.parentDocumentId + "/" + it.displayName.lowercase() }

        val out = mutableListOf<RomEntry>()
        for (f in files) {
            val ext = f.displayName.substringAfterLast('.', "").lowercase()
            val system = when (ext) {
                "gb", "gbc", "sgb", "dmg" -> RomSystem.GB
                "gba", "agb" -> RomSystem.GBA
                else -> null
            } ?: continue

            val savKey = f.parentDocumentId + "/" +
                (f.displayName.substringBeforeLast('.') + ".sav").lowercase()
            val sav = savFilesByNameInDir[savKey]

            val cached = cache[f.uri.toString()]
            if (cached != null && cached.size == f.size && cached.lastModified == f.lastModified) {
                out += cached.copy(
                    saveFileUriString = sav?.uri?.toString(),
                    saveFileLastModified = sav?.lastModified ?: 0,
                )
                continue
            }

            val header = SafFiles.readBytes(context.contentResolver, f.uri, 0x180) ?: continue
            if (header.size < 0x180) continue
            val title = when (system) {
                RomSystem.GB -> runCatching { DmgHeader.parse(header).title }.getOrNull()
                RomSystem.GBA -> runCatching { AgbHeader.parse(header).title }.getOrNull()
            } ?: f.displayName.substringBeforeLast('.')

            out += RomEntry(
                uriString = f.uri.toString(),
                parentDocumentId = f.parentDocumentId,
                displayName = f.displayName,
                size = f.size,
                lastModified = f.lastModified,
                system = system,
                title = title.ifBlank { f.displayName.substringBeforeLast('.') },
                headerHash = sha256(header),
                saveFileUriString = sav?.uri?.toString(),
                saveFileLastModified = sav?.lastModified ?: 0,
            )
        }
        return out
    }

    companion object {
        fun sha256(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data)
                .joinToString("") { "%02x".format(it) }

        /**
         * Cart → library match. Exact header-bytes hash first (same game and
         * revision), then title as a fallback for renamed/other-revision dumps.
         */
        fun match(
            entries: List<RomEntry>,
            system: RomSystem,
            cartHeaderBytes: ByteArray,
            cartTitle: String,
        ): List<RomEntry> {
            val hash = sha256(cartHeaderBytes.copyOf(0x180))
            val exact = entries.filter { it.system == system && it.headerHash == hash }
            if (exact.isNotEmpty()) return exact
            if (cartTitle.isBlank()) return emptyList()
            return entries.filter {
                it.system == system && it.title.equals(cartTitle, ignoreCase = true)
            }
        }
    }
}
