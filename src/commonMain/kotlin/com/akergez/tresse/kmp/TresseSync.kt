package com.akergez.tresse.kmp

import kotlinx.serialization.Serializable

/**
 * Full repository configuration for the content-addressed (CAS) sync engine.
 *
 * The credential bundle ([serverUrl], [repoId], [token], [encryptionKey], the
 * `s3*` fields) is shared between every replica — an Android device and the
 * companion Obsidian plugin drive the SAME remote store, so the wire fields here
 * mirror the plugin's config exactly. The `autoSync*` fields are device-local
 * scheduling knobs: they are carried in the shareable config string but are
 * deliberately NOT written into the notes directory's shared `config.toml`
 * (see [TresseConfigFile]); each device overlays its own from local storage.
 */
@Serializable
data class TresseConfig(
    val serverUrl: String = "",
    val repoId: String = "",
    val token: String = "",
    val encryptionKey: String = "",
    val storageType: String = "http",
    val s3Endpoint: String = "",
    val s3Region: String = "",
    val s3Bucket: String = "",
    val s3AccessKeyId: String = "",
    val s3SecretAccessKey: String = "",
    // No default patterns: what to ignore is host policy (e.g. an Obsidian vault
    // ignores `.obsidian`/`.git`). The engine only honors whatever the host supplies.
    val ignorePatterns: List<String> = emptyList(),
    val autoSyncEnabled: Boolean = true,
    val autoSyncIntervalMinutes: Long = 15,
    val autoSyncDebounceSeconds: Long = 10,
) {
    /**
     * Whether the credential bundle is complete enough to run a sync. An HTTP
     * remote needs a server URL and repo id; an S3 remote needs the bucket
     * coordinates and keys. The encryption key is always required.
     */
    fun isComplete(): Boolean = encryptionKey.isNotBlank() && when (storageType) {
        "s3" -> s3Endpoint.isNotBlank() && s3Bucket.isNotBlank() &&
            s3AccessKeyId.isNotBlank() && s3SecretAccessKey.isNotBlank()
        else -> serverUrl.isNotBlank() && repoId.isNotBlank()
    }
}

data class TresseFile(val path: String, val size: Long, val modifiedAtMillis: Long)

data class TresseSyncResult(val pushed: Int, val pulled: Int, val changed: Boolean)

/**
 * Live progress of a running sync. The whole commit → sync → restore flow is one
 * blocking `casSync` FFI call, but the engine drives the vault and remote hooks
 * throughout it, so these callbacks are the only honest source of progress on the
 * host: a local file read for the commit skeleton, an encrypted object pushed or
 * pulled, a notes-directory file written/removed during restore. Implementations
 * are invoked from the engine's IO thread and must be cheap and thread-safe.
 */
interface TresseSyncProgress {
    /** A local file was read (at offset 0) for the commit snapshot. */
    fun reportCommitRead() {}

    /** An encrypted object was pushed to the remote. */
    fun reportUpload() {}

    /** An encrypted object was pulled from the remote. */
    fun reportDownload() {}

    /** A notes-directory file was written or removed while restoring merged state. */
    fun reportApply() {}
}

/**
 * Filesystem/SAF boundary supplied by the host application. The sync engine reads
 * the tracked file universe through [listFiles]/[read] and materializes merged
 * state through [writeText]/[writeBinary]/[delete]; `.tresse/{meta,content}` — the
 * replica's own local CAS — is reached through the `store*` methods.
 *
 * [writeText] and [writeBinary] are kept distinct so a host that supports
 * optimistic-concurrency writes (read-current-version, write-with-version) can
 * apply it to text restores while binary blobs are overwritten unconditionally.
 * The default routes [writeText] through [writeBinary] for hosts that do not
 * distinguish the two.
 */
interface TresseVault {
    fun listFiles(): List<TresseFile>
    fun read(path: String, offset: Long, length: Int): ByteArray
    fun writeText(path: String, content: String) = writeBinary(path, content.encodeToByteArray())
    fun writeBinary(path: String, bytes: ByteArray)
    fun delete(path: String)
    fun storeList(directory: String): List<String>
    fun storeRead(path: String): ByteArray?
    fun storeWrite(path: String, bytes: ByteArray)
    fun storeDelete(path: String)
}

expect class TresseSync(vault: TresseVault) {
    suspend fun perform(config: TresseConfig, progress: TresseSyncProgress? = null): TresseSyncResult
    suspend fun generateEncryptionKey(): String
    suspend fun loadConfig(): TresseConfig?
    suspend fun saveConfig(config: TresseConfig)
}
