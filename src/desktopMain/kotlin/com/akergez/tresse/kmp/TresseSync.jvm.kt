package com.akergez.tresse.kmp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.tresse_ffi.casSync
import uniffi.tresse_ffi.generateKey
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual class TresseSync actual constructor(private val vault: TresseVault) {
    init { configureNativeLibrary() }

    actual suspend fun perform(config: TresseConfig, progress: TresseSyncProgress?): TresseSyncResult =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                require(config.encryptionKey.isNotBlank()) { "Tresse encryption key is required" }
                require(config.isComplete()) { "Tresse ${config.storageType} sync configuration is incomplete" }
                val local = VaultObjectStore(vault)
                val outcome = casSync(
                    encryptionKeyB64 = config.encryptionKey,
                    fileIndexJson = local.readIndex(),
                    local = local,
                    remote = CountingRemoteKv(remoteKvOf(config), progress),
                    source = VaultSource(vault, config, progress),
                    sink = VaultSink(vault, config, progress),
                )
                local.writeIndex(outcome.fileIndexJson)
                TresseSyncResult(
                    pushed = outcome.pushed.toInt(),
                    pulled = outcome.pulled.toInt(),
                    changed = outcome.committed || outcome.pulled > 0u ||
                        outcome.restored > 0u || outcome.removed > 0u,
                )
            }
        }

    actual suspend fun generateEncryptionKey(): String = withContext(Dispatchers.Default) { generateKey() }
    actual suspend fun loadConfig(): TresseConfig? = withContext(Dispatchers.IO) { TresseConfigFile.read(vault) }
    actual suspend fun saveConfig(config: TresseConfig) = withContext(Dispatchers.IO) { TresseConfigFile.write(vault, config) }

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
