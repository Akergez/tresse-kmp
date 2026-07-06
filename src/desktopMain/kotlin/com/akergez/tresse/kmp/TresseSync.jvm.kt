package com.akergez.tresse.kmp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.tresse_ffi.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual class TresseSync actual constructor(private val vault: TresseVault) {
    init { configureNativeLibrary() }

    actual suspend fun perform(config: TresseConfig): TresseSyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(config.isComplete()) { "Incomplete tresse sync configuration" }
            val local = VaultObjectStore(vault)
            val outcome = casSync(config.encryptionKey, local.readIndex(), local, remoteKvOf(config), VaultSource(vault, config), VaultSink(vault, config))
            local.writeIndex(outcome.fileIndexJson)
            TresseSyncResult(outcome.pushed.toInt(), outcome.pulled.toInt(), outcome.committed || outcome.pulled > 0u || outcome.restored > 0u || outcome.removed > 0u)
        }
    }

    actual suspend fun generateEncryptionKey(): String = withContext(Dispatchers.Default) { generateKey() }

    private fun configureNativeLibrary() {
        if (System.getProperty("uniffi.component.tresse_ffi.libraryOverride") != null) return
        val mapped = System.mapLibraryName("tresse_ffi").replace(".jnilib", ".dylib")
        val input = javaClass.getResourceAsStream("/native/$mapped") ?: error("Missing tresse native library: $mapped")
        val target = Files.createTempFile("tresse-ffi-", mapped.substringAfterLast('.', ".bin")).toFile()
        target.deleteOnExit()
        input.use { Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        System.setProperty("uniffi.component.tresse_ffi.libraryOverride", target.absolutePath)
    }

    private companion object { val mutex = Mutex() }
}

private fun ignored(path: String, config: TresseConfig): Boolean = path == ".tresse" || path.startsWith(".tresse/") || config.ignorePatterns.any { it.isNotBlank() && (path == it || path.startsWith("$it/")) }
private val textExtensions = setOf("md", "txt", "json", "js", "ts", "tsx", "jsx", "css", "html", "toml", "yaml", "yml", "xml", "py", "rs", "go", "c", "cpp", "h", "hpp", "sh", "bat")
private class VaultSource(private val vault: TresseVault, private val config: TresseConfig) : FfiVaultSource {
    private val files by lazy { vault.listFiles() }
    override fun listFiles() = files.filterNot { ignored(it.path, config) }.distinctBy { it.path }.sortedBy { it.path }.map { FfiVaultFile(it.path, it.path.substringAfterLast('.', "").lowercase() !in textExtensions, it.modifiedAtMillis.coerceAtLeast(0).toULong(), it.size.coerceAtLeast(0).toULong()) }
    override fun len(path: String) = files.firstOrNull { it.path == path }?.size?.coerceAtLeast(0)?.toULong() ?: 0u
    override fun read(path: String, offset: ULong, len: ULong) = vault.read(path, offset.toLong(), len.coerceAtMost(Int.MAX_VALUE.toULong()).toInt())
}
private class VaultSink(private val vault: TresseVault, private val config: TresseConfig) : FfiRestoreSink {
    override fun writeText(path: String, content: String) { if (!ignored(path, config)) vault.write(path, content.encodeToByteArray()) }
    override fun writeBinary(path: String, data: ByteArray) { if (!ignored(path, config)) vault.write(path, data) }
    override fun remove(path: String) { if (!ignored(path, config)) vault.delete(path) }
}
private class VaultObjectStore(private val vault: TresseVault) : FfiObjectStore {
    fun readIndex() = vault.storeRead(".tresse/file-index.json")?.decodeToString()
    fun writeIndex(value: String) = vault.storeWrite(".tresse/file-index.json", value.encodeToByteArray())
    override fun list(`class`: String) = vault.storeList(".tresse/$`class`")
    override fun get(`class`: String, id: String) = vault.storeRead(".tresse/$`class`/$id")
    override fun put(`class`: String, id: String, bytes: ByteArray) = vault.storeWrite(".tresse/$`class`/$id", bytes)
    override fun delete(`class`: String, id: String) = vault.storeDelete(".tresse/$`class`/$id")
}
