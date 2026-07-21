package com.jeff.gbxsync.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Thin SAF helpers. Enumeration uses direct child-document cursor queries
 * (one IPC per directory) instead of DocumentFile (one IPC per file).
 */
object SafFiles {

    data class Entry(
        val documentId: String,
        val parentDocumentId: String,
        val displayName: String,
        val size: Long,
        val lastModified: Long,
        val isDirectory: Boolean,
        val uri: Uri,
    )

    /** Recursively lists files under a persisted tree Uri (depth-limited). */
    fun listTree(context: Context, treeUri: Uri, maxDepth: Int = 4): List<Entry> {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val out = mutableListOf<Entry>()
        fun walk(docId: String, depth: Int) {
            if (depth > maxDepth) return
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val size = cursor.getLong(2)
                    val modified = cursor.getLong(3)
                    val mime = cursor.getString(4)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    out += Entry(
                        documentId = id,
                        parentDocumentId = docId,
                        displayName = name,
                        size = size,
                        lastModified = modified,
                        isDirectory = isDir,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                    )
                    if (isDir) walk(id, depth + 1)
                }
            }
        }
        walk(rootDocId, 0)
        return out
    }

    fun readBytes(resolver: ContentResolver, uri: Uri, limit: Int = Int.MAX_VALUE): ByteArray? =
        try {
            resolver.openInputStream(uri)?.use { input ->
                if (limit == Int.MAX_VALUE) {
                    input.readBytes()
                } else {
                    val buf = ByteArray(limit)
                    var have = 0
                    while (have < limit) {
                        val n = input.read(buf, have, limit - have)
                        if (n < 0) break
                        have += n
                    }
                    if (have == limit) buf else buf.copyOf(have)
                }
            }
        } catch (_: Exception) {
            null
        }

    /** Overwrites (or creates) a file next to [siblingParentDocId] in the tree. */
    fun writeFile(
        context: Context,
        treeUri: Uri,
        existingUri: Uri?,
        siblingParentDocId: String,
        fileName: String,
        data: ByteArray,
    ): Uri? {
        val resolver = context.contentResolver
        val target = existingUri ?: run {
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, siblingParentDocId)
            DocumentsContract.createDocument(
                resolver, parentUri, "application/octet-stream", fileName
            ) ?: return null
        }
        return try {
            // "wt" truncates — without it a shorter save leaves stale bytes behind.
            resolver.openOutputStream(target, "wt")?.use { it.write(data) } ?: return null
            target
        } catch (_: Exception) {
            null
        }
    }
}
