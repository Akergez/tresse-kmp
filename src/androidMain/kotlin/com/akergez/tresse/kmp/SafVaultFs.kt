package com.akergez.tresse.kmp

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException

/**
 * A vault backed by a Storage Access Framework tree.
 *
 * SAF is not a filesystem, and this class is the adapter that makes it look like one.
 * The framework offers three operations: list a document's children, open a document by
 * id, create a child of a document. Everything else here exists because of what it does
 * not offer, so those absences are worth stating once — each one is load-bearing below.
 *
 *  - **No paths.** A document is reachable only by its id. Turning a name into an id
 *    means listing the parent and comparing display names, which is all that
 *    `DocumentFile.findFile` has ever done.
 *  - **No stable meaning in an id.** `COLUMN_DOCUMENT_ID` is opaque by contract. That
 *    the local provider encodes a path in it may be read as a hint, never as a fact.
 *  - **No positional read.** A stream starts at byte zero and goes forward.
 *  - **No trash flag.** A file deleted through the MediaStore trash is renamed, not
 *    unlinked, and keeps its display name — so a discarded document goes on competing
 *    for the path of its live replacement, and nothing in `DocumentsContract` tells
 *    the two apart. (Our own [delete] is a real unlink; the doppelgangers come from
 *    other apps.)
 *  - **No refusal on a taken name.** `createDocument` invents a free one instead.
 *
 * The workarounds, each named by the failure it exists to prevent. Details live on the
 * members themselves:
 *
 *  - [docIdCache] stands in for paths. Filled in bulk by [list] and incrementally by
 *    [write] and [ensureParentDirs]. Copy-on-write rather than a concurrent map: reads
 *    come off worker threads, writes are rare and batched, and replacing the reference
 *    is atomic. A sync lists the whole tree before it reads anything, so in practice
 *    every read is a cache hit.
 *  - [traverseToFind] and [findChildDocId] resolve a miss segment by segment, each step
 *    enumerating a whole directory over IPC. Slow by construction, and the only way.
 *  - [Row] keeps a document's id and its size in one object, both taken from a single
 *    cursor row. Sizing a file against one document and reading the bytes of another is
 *    how a sync commits a length it cannot deliver; pairing them makes that unspellable
 *    rather than merely unlikely.
 *  - [identifies] recognises a discarded document by disagreement between its id and its
 *    display name, instead of knowing what the platform calls one.
 *  - The `named` calibration in [list] and [findChildDocId] applies that rule only where
 *    ids carry names at all. Under a provider with opaque ids the rule would reject every
 *    child and empty the vault — which a sync would read as a mass deletion.
 *  - [list] refuses a path two surviving documents both claim. Nothing can tell them
 *    apart at that point, and picking one silently means reading, then overwriting, a
 *    file nobody asked for.
 *  - [readCached] slurps a whole file so that ranged reads can be served from memory.
 *    The commit engine walks each file twice in bounded windows; without this, every
 *    window would reopen the stream and skip to its offset. One entry, because the
 *    engine finishes a file before it moves on. [readAll] reads the cache but never
 *    fills it: it serves the object store, whose objects are each read once.
 *  - [write] verifies the display name the provider assigned to a document it just
 *    created, and treats a rename as proof that the file exists and the search that
 *    missed it is at fault. Its `application/octet-stream` mime type is deliberate too —
 *    a provider may extend a name to match a more specific type.
 *  - [ensureParentDirs] is `mkdir -p`, one round trip per segment.
 */
class SafVaultFs(context: Context, private val treeUri: Uri) : VaultFs {
    private val resolver = context.applicationContext.contentResolver
    private val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        ?: error("Invalid SAF tree URI")
    private val readCacheLock = Any()

    @Volatile private var docIdCache: Map<String, String> = emptyMap()
    @Volatile private var readCachePath: String? = null
    @Volatile private var readCacheBytes: ByteArray? = null

    override fun list(relDir: String): List<VaultFsEntry> {
        val directory = cleanPath(relDir)
        val docId = if (directory.isEmpty()) rootDocId else cachedOrFind(directory) ?: return emptyList()
        val rows = readChildren(docId)
        val named = rows.any { identifies(it.docId, it.entry.name) }
        // One path, one document. Size and id have to come from the same row: sizing a file
        // against one document and then reading the bytes of another is how a sync commits
        // a length it cannot deliver.
        val children = LinkedHashMap<String, Row>()
        for (row in rows) {
            if (named && !identifies(row.docId, row.entry.name)) continue
            val path = joinPath(directory, row.entry.name)
            // Whatever survives the identity rule must be unique, because past this point
            // nothing can tell the survivors apart. Picking one silently is how the wrong
            // file gets read and, worse, written; the path is simply not resolvable here.
            val clash = children.put(path, row)
            if (clash != null) {
                error("Ambiguous vault path $path: documents ${clash.docId} and ${row.docId} both claim it")
            }
        }
        if (children.isNotEmpty()) docIdCache = docIdCache + children.mapValues { it.value.docId }
        return children.values.map(Row::entry)
    }

    private fun readChildren(parentDocId: String): List<Row> {
        val rows = mutableListOf<Row>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        resolver.query(childrenUri, LISTING_PROJECTION, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val childDocId = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val mimeType = cursor.getString(2) ?: continue
                rows += Row(
                    docId = childDocId,
                    entry = VaultFsEntry(
                        name = name,
                        isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                        modifiedAtMillis = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                        size = if (cursor.isNull(4)) 0L else cursor.getLong(4),
                    ),
                )
            }
        }
        return rows
    }

    override fun readRange(relPath: String, offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val path = cleanPath(relPath)
        val bytes = readCached(path) ?: return ByteArray(0)
        val start = offset.coerceIn(0L, bytes.size.toLong()).toInt()
        val end = (start.toLong() + length).coerceAtMost(bytes.size.toLong()).toInt()
        return if (start >= end) ByteArray(0) else bytes.copyOfRange(start, end)
    }

    private fun readCached(path: String): ByteArray? {
        readCacheBytes?.takeIf { readCachePath == path }?.let { return it }
        return synchronized(readCacheLock) {
            readCacheBytes?.takeIf { readCachePath == path } ?: readAllUncached(path)?.also {
                readCachePath = path
                readCacheBytes = it
            }
        }
    }

    override fun readAll(relPath: String): ByteArray? {
        val path = cleanPath(relPath)
        readCacheBytes?.takeIf { readCachePath == path }?.let { return it }
        return readAllUncached(path)
    }

    private fun readAllUncached(path: String): ByteArray? {
        val docId = cachedOrFind(path) ?: return null
        return try {
            resolver.openInputStream(docUri(docId))?.use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            // A cached id outlived its document: the file was removed outside the app.
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
            val created = DocumentsContract.createDocument(
                resolver,
                docUri(parentDocId),
                "application/octet-stream",
                parts.last(),
            ) ?: error("Cannot create SAF document: $path")
            // A provider does not refuse a name that is taken, it invents a free one and
            // hands back "notes (1).md". Writing there would leave the real file untouched
            // while the cache swore the path had been written, so the rename is the proof
            // that the file exists and the search that missed it is the thing at fault.
            val assigned = displayNameOf(created)
            if (assigned != null && assigned != parts.last()) {
                runCatching { DocumentsContract.deleteDocument(resolver, created) }
                error("SAF renamed $path to \"$assigned\" on create: the path exists but did not resolve")
            }
            created
        }
        // "t" truncates: without it a shorter write leaves the tail of the old content.
        resolver.openOutputStream(uri, "rwt")?.use { it.write(bytes) }
            ?: error("Cannot write SAF document: $path")
        docIdCache = docIdCache + (path to DocumentsContract.getDocumentId(uri))
        clearReadCache(path)
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
        clearReadCache(path)
    }

    private fun clearReadCache(path: String) {
        if (readCachePath != path) return
        synchronized(readCacheLock) {
            if (readCachePath == path) {
                readCachePath = null
                readCacheBytes = null
            }
        }
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

    /** Same rule the listing applies, so a cache miss resolves to the same document. */
    private fun findChildDocId(parentDocId: String, name: String): String? {
        val rows = readChildren(parentDocId)
        val named = rows.any { identifies(it.docId, it.entry.name) }
        return rows.firstOrNull { it.entry.name == name && (!named || identifies(it.docId, name)) }?.docId
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

    private fun displayNameOf(uri: Uri): String? =
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun joinPath(parent: String, child: String): String =
        if (parent.isEmpty()) cleanPath(child) else "${cleanPath(parent)}/${cleanPath(child)}"

    private data class Row(val docId: String, val entry: VaultFsEntry)

    internal companion object {
        /**
         * Does this document id still name the document it is reported under? Android does not
         * unlink a deleted file — it renames the file and keeps the document alive, so the id
         * says `.trashed-1754-notes.md` while the display name says `notes.md`, and the discard
         * competes with its live replacement for the same path. Rather than know what the
         * platform calls a discarded file, ask whether the two agree: a document that presents
         * one name and identifies itself as another is not the file that was asked for.
         *
         * Agreement is deliberately one-sided. A provider may report a display name with the
         * extension dropped, so the id is allowed to carry more than the name — but only after
         * it, never before. That asymmetry is the whole test: a shortened name loses its tail,
         * a discarded one gains a head, and only the second means a different document.
         *
         * Providers whose ids carry no path at all (opaque or numeric) agree with nothing, so
         * they answer false uniformly and the first row seen simply keeps the path.
         *
         * Lives on the companion so the rule can be tested without a `Context`; the callers
         * see it exactly as they did when it was a member.
         */
        internal fun identifies(docId: String, name: String): Boolean {
            val tail = docId.substringAfterLast('/').substringAfterLast(':')
            return tail == name || tail.startsWith("$name.")
        }

        private val LISTING_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
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
