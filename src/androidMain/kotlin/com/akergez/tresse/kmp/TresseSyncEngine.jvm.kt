package com.akergez.tresse.kmp

import uniffi.tresse_ffi.FfiObjectStore
import uniffi.tresse_ffi.FfiRemoteKv
import uniffi.tresse_ffi.FfiRestoreSink
import uniffi.tresse_ffi.FfiVaultFile
import uniffi.tresse_ffi.FfiVaultSource
import uniffi.tresse_ffi.TresseException

/** Compile this transaction's ignore rules; see [TresseIgnore]. Owns a native handle. */
internal fun ignoreRulesOf(config: TresseConfig): TresseIgnore =
    TresseIgnore(config.ignorePatterns)

/**
 * Text-vs-binary classification. MUST match the companion plugin's `TEXT_EXTENSIONS`
 * exactly: the two replicas have to agree on every file's File/Binary kind, or their
 * commit skeletons diverge and they ping-pong.
 */
internal val TEXT_EXTENSIONS = setOf(
    "md", "txt", "json", "js", "ts", "tsx", "jsx", "css", "html", "toml",
    "yaml", "yml", "xml", "py", "rs", "go", "c", "cpp", "h", "hpp", "sh", "bat",
)

internal fun isTextPath(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

/**
 * Read side of the vault for the commit engine: `len(path)` supplies each file's
 * size for chunk planning, then `read(path, offset, len)` streams the file back in
 * bounded ranges. Nothing is buffered — a range request goes straight to a
 * position-read, so a gigabyte file never lands in memory at once.
 */
internal class VaultSource(
    private val vault: TresseVault,
    private val ignore: TresseIgnore,
    private val progress: TresseSyncProgress?,
) : FfiVaultSource {
    private val files by lazy { vault.listFiles() }

    override fun listFiles(): List<FfiVaultFile> = try {
        files.asSequence()
            // The list is flat — every entry is a file. The matcher walks each path's
            // own ancestors, so an excluded directory takes its subtree with it.
            .filterNot { ignore.isIgnored(it.path, false) }
            .distinctBy(TresseFile::path)
            .sortedBy(TresseFile::path)
            .map {
                FfiVaultFile(
                    path = it.path,
                    binary = !isTextPath(it.path),
                    mtimeMs = it.modifiedAtMillis.coerceAtLeast(0).toULong(),
                    size = it.size.coerceAtLeast(0).toULong(),
                )
            }.toList()
    } catch (error: Throwable) {
        throw TresseException.General("listing vault failed: ${error.message}")
    }

    override fun len(path: String): ULong =
        files.firstOrNull { it.path == path }?.size?.coerceAtLeast(0)?.toULong() ?: 0u

    /**
     * The commit engine asks only for ranges it has already sized through [len], so it is
     * entitled to every byte it asks for and treats anything less as a corrupt working
     * tree. Swallowing a failure into an empty array therefore does not degrade the read —
     * it relabels the real fault as a length mismatch, which is why a failing sync used to
     * report a truncated file no matter what actually went wrong. Both the failure and the
     * mismatch are raised here instead, carrying the file, the window, and the size the
     * listing promised, so the log names the cause rather than its disguise.
     */
    override fun read(path: String, offset: ULong, len: ULong): ByteArray {
        // Count one read per file, at its first range. Counting in len() double-counts:
        // the commit engine sizes a text file up to twice per pass. A file's content is
        // actually pulled starting at offset 0 exactly once.
        if (offset == 0uL) progress?.reportCommitRead()
        val wanted = len.coerceAtMost(Int.MAX_VALUE.toULong()).toInt()
        val bytes = try {
            vault.read(path, offset.toLong(), wanted)
        } catch (error: Throwable) {
            throw TresseException.General(
                "reading $path at $offset (+$wanted) failed: ${error::class.simpleName}: ${error.message}",
            )
        }
        if (bytes.size != wanted) {
            val listed = files.firstOrNull { it.path == path }?.size
            throw TresseException.General(
                "short read of $path at $offset: asked $wanted, got ${bytes.size}, listing said size=$listed",
            )
        }
        return bytes
    }
}

/**
 * Write side of the vault for restore. Text and binary go through the vault's two
 * distinct write paths so a host may version-check text writes while overwriting
 * binaries blindly; tombstoned paths are deleted (deletions made on another device
 * propagate here).
 */
internal class VaultSink(
    private val vault: TresseVault,
    private val ignore: TresseIgnore,
    private val progress: TresseSyncProgress?,
) : FfiRestoreSink {
    override fun writeText(path: String, content: String) {
        if (ignore.isIgnored(path, false)) return
        try {
            vault.writeText(path, content)
            progress?.reportApply()
        } catch (error: Throwable) {
            throw TresseException.General("writing $path failed: ${error.message}")
        }
    }

    override fun writeBinary(path: String, data: ByteArray) {
        if (ignore.isIgnored(path, false)) return
        try {
            vault.writeBinary(path, data)
            progress?.reportApply()
        } catch (error: Throwable) {
            throw TresseException.General("writing $path failed: ${error.message}")
        }
    }

    override fun remove(path: String) {
        if (ignore.isIgnored(path, false)) return
        runCatching { vault.delete(path) }
        progress?.reportApply()
    }
}

/**
 * The replica's own local content-addressed store: `.tresse/{meta,content}`. This is
 * the single local source of truth (the replica's `.git`), shared with any other
 * client on the device. Objects are stored plaintext under a filename equal to their
 * content id (the Rust side derives and verifies the id).
 */
internal class VaultObjectStore(private val vault: TresseVault) : FfiObjectStore {
    fun readIndex(): String? = vault.storeRead(".tresse/file-index.json")?.decodeToString()
    fun writeIndex(value: String) = vault.storeWrite(".tresse/file-index.json", value.encodeToByteArray())

    override fun list(`class`: String): List<String> = try {
        vault.storeList(".tresse/$`class`")
    } catch (error: Throwable) {
        throw TresseException.General("listing .tresse/$`class` failed: ${error.message}")
    }

    // null → the object is absent; the engine reads it from the remote instead.
    override fun get(`class`: String, id: String): ByteArray? = try {
        vault.storeRead(".tresse/$`class`/$id")
    } catch (error: Throwable) {
        throw TresseException.General("reading .tresse/$`class`/$id failed: ${error.message}")
    }

    override fun put(`class`: String, id: String, bytes: ByteArray) = try {
        vault.storeWrite(".tresse/$`class`/$id", bytes)
    } catch (error: Throwable) {
        throw TresseException.General("writing .tresse/$`class`/$id failed: ${error.message}")
    }

    override fun delete(`class`: String, id: String) = try {
        vault.storeDelete(".tresse/$`class`/$id")
    } catch (error: Throwable) {
        throw TresseException.General("deleting .tresse/$`class`/$id failed: ${error.message}")
    }
}

/**
 * Wraps the real remote store to count object transfers as the engine performs them:
 * every `put` is one object pushed, every `get` one pulled. This is the only honest
 * source of live progress — the FFI is one blocking call, but it drives these methods
 * throughout the sync.
 */
internal class CountingRemoteKv(
    private val inner: FfiRemoteKv,
    private val progress: TresseSyncProgress?,
) : FfiRemoteKv {
    override fun list(prefix: String): List<String> = inner.list(prefix)
    override fun get(key: String): ByteArray = inner.get(key).also { progress?.reportDownload() }
    override fun put(key: String, value: ByteArray) {
        inner.put(key, value)
        progress?.reportUpload()
    }
    override fun delete(key: String) = inner.delete(key)
}
