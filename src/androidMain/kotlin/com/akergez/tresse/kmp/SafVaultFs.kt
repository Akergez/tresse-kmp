package com.akergez.tresse.kmp

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.nio.ByteBuffer

class SafVaultFs(context: Context, private val treeUri: Uri) : VaultFs {
    private val resolver = context.applicationContext.contentResolver
    private val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        ?: error("Invalid SAF tree URI")

    @Volatile private var docIdCache: Map<String, String> = emptyMap()

    override fun list(relDir: String): List<VaultFsEntry> {
        val directory = cleanPath(relDir)
        val docId = if (directory.isEmpty()) rootDocId else cachedOrFind(directory) ?: return emptyList()
        val discovered = mutableMapOf<String, String>()
        val entries = mutableListOf<VaultFsEntry>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        resolver.query(childrenUri, LISTING_PROJECTION, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val childDocId = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val mimeType = cursor.getString(2) ?: continue
                val path = joinPath(directory, name)
                discovered[path] = childDocId
                entries += VaultFsEntry(
                    name = name,
                    isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                    modifiedAtMillis = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                    size = if (cursor.isNull(4)) 0L else cursor.getLong(4),
                )
            }
        }
        if (discovered.isNotEmpty()) docIdCache = docIdCache + discovered
        return entries
    }

    /**
     * A true positional read: seek to [offset] through the document's file descriptor and
     * pull at most [length] bytes. Only the requested range is ever resident, which is what
     * lets the sync chunk a gigabyte file without carrying it in memory — reading the whole
     * document and slicing it would defeat the point of asking for a range at all.
     */
    override fun readRange(relPath: String, offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val docId = cachedOrFind(cleanPath(relPath)) ?: return ByteArray(0)
        return try {
            resolver.openFileDescriptor(docUri(docId), "r")?.let { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    val channel = input.channel
                    val start = offset.coerceIn(0L, channel.size())
                    val count = length.toLong().coerceAtMost(channel.size() - start).toInt()
                    if (count <= 0) return@use ByteArray(0)
                    val buffer = ByteBuffer.allocate(count)
                    channel.position(start)
                    while (buffer.hasRemaining() && channel.read(buffer) >= 0) { /* fill */ }
                    if (buffer.hasRemaining()) buffer.array().copyOf(buffer.position()) else buffer.array()
                }
            } ?: ByteArray(0)
        } catch (_: FileNotFoundException) {
            ByteArray(0)
        }
    }

    override fun readAll(relPath: String): ByteArray? {
        val docId = cachedOrFind(cleanPath(relPath)) ?: return null
        return try {
            resolver.openInputStream(docUri(docId))?.use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    override fun write(relPath: String, bytes: ByteArray) {
        val path = cleanPath(relPath)
        val existing = cachedOrFind(path)
        val uri = if (existing != null) {
            docUri(existing)
        } else {
            val parts = path.split('/')
            val parentDocId = ensureParentDirs(parts.dropLast(1))
            DocumentsContract.createDocument(
                resolver,
                docUri(parentDocId),
                "application/octet-stream",
                parts.last(),
            ) ?: error("Cannot create SAF document: $path")
        }
        resolver.openOutputStream(uri, "rwt")?.use { it.write(bytes) }
            ?: error("Cannot write SAF document: $path")
        docIdCache = docIdCache + (path to DocumentsContract.getDocumentId(uri))
    }

    override fun delete(relPath: String) {
        val path = cleanPath(relPath)
        val docId = cachedOrFind(path) ?: return
        try {
            DocumentsContract.deleteDocument(resolver, docUri(docId))
        } catch (_: FileNotFoundException) {
            // Already gone.
        }
        docIdCache = docIdCache - path
    }

    private fun cachedOrFind(path: String): String? = docIdCache[path] ?: traverseToFind(path)

    private fun traverseToFind(path: String): String? {
        val clean = cleanPath(path)
        if (clean.isEmpty()) return rootDocId
        var docId = rootDocId
        for (segment in clean.split('/')) {
            docId = findChildDocId(docId, segment) ?: return null
        }
        return docId
    }

    private fun findChildDocId(parentDocId: String, name: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        resolver.query(childrenUri, CHILD_PROJECTION, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0)
                val childName = cursor.getString(1) ?: continue
                if (childName == name) return docId
            }
        }
        return null
    }

    private fun ensureParentDirs(segments: List<String>): String {
        if (segments.isEmpty()) return rootDocId
        var currentDocId = rootDocId
        var currentPath = ""
        for (segment in segments) {
            currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
            currentDocId = docIdCache[currentPath]
                ?: findChildDocId(currentDocId, segment)
                ?: run {
                    val uri = DocumentsContract.createDocument(
                        resolver,
                        docUri(currentDocId),
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        segment,
                    ) ?: error("Cannot create SAF directory: $currentPath")
                    val newDocId = DocumentsContract.getDocumentId(uri)
                    docIdCache = docIdCache + (currentPath to newDocId)
                    newDocId
                }
        }
        return currentDocId
    }

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun joinPath(parent: String, child: String): String =
        if (parent.isEmpty()) cleanPath(child) else "${cleanPath(parent)}/${cleanPath(child)}"

    private companion object {
        val LISTING_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
    }
}

/**
 * The vault compiles the ignore patterns itself. Hosts hand over the patterns and
 * nothing else — not the config they came from, which carries tokens and keys a
 * directory walk has no business seeing, and not a predicate, which would make the
 * host decide whether a path is a directory. Only the walk knows that.
 */
fun safTresseVault(
    context: Context,
    treeUri: Uri,
    ignorePatterns: List<String> = emptyList(),
): TresseVault = DirectoryVault(
    SafVaultFs(context, treeUri),
    ignorePatterns.takeIf { it.isNotEmpty() }?.let(::TresseIgnore),
)
