package com.akergez.tresse.kmp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.tresse_ffi.casSync
import uniffi.tresse_ffi.forcePull as ffiForcePull
import uniffi.tresse_ffi.generateKey
import uniffi.tresse_ffi.supportedLanguages as ffiSupportedLanguages
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

    actual suspend fun forcePull(config: TresseConfig, progress: TresseSyncProgress?): TresseSyncResult =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                require(config.encryptionKey.isNotBlank()) { "Tresse encryption key is required" }
                require(config.isComplete()) { "Tresse ${config.storageType} sync configuration is incomplete" }
                val local = VaultObjectStore(vault)
                val outcome = ffiForcePull(
                    encryptionKeyB64 = config.encryptionKey,
                    local = local,
                    remote = CountingRemoteKv(remoteKvOf(config), progress),
                    source = VaultSource(vault, config, progress),
                    sink = VaultSink(vault, config, progress),
                )
                local.writeIndex(outcome.fileIndexJson)
                TresseSyncResult(
                    pushed = outcome.pushed.toInt(),
                    pulled = outcome.pulled.toInt(),
                    changed = outcome.pulled > 0u || outcome.restored > 0u || outcome.removed > 0u,
                )
            }
        }

    actual suspend fun generateEncryptionKey(): String = withContext(Dispatchers.Default) { generateKey() }
    actual suspend fun loadConfig(): TresseConfig? = withContext(Dispatchers.IO) { TresseConfigFile.read(vault) }
    actual suspend fun saveConfig(config: TresseConfig) = withContext(Dispatchers.IO) { TresseConfigFile.write(vault, config) }

    private fun configureNativeLibrary() {
        if (System.getProperty("uniffi.component.tresse_ffi.libraryOverride") != null) return
        val mapped = System.mapLibraryName("tresse_ffi").replace(".jnilib", ".dylib")
        val platform = nativePlatform()
        val resource = "/native/$platform/$mapped"
        val input = javaClass.getResourceAsStream(resource)
            ?: error("Missing tresse native library: $resource")
        val target = Files.createTempFile("tresse-ffi-", mapped.substringAfterLast('.', ".bin")).toFile()
        target.deleteOnExit()
        input.use { Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        System.setProperty("uniffi.component.tresse_ffi.libraryOverride", target.absolutePath)
    }

    private fun nativePlatform(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val normalizedOs = when {
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("linux") -> "linux"
            os.contains("win") -> "windows"
            else -> error("Unsupported desktop OS: $os")
        }
        val normalizedArch = when (arch) {
            "amd64", "x86_64" -> "x86_64"
            "arm64", "aarch64" -> "aarch64"
            else -> error("Unsupported desktop architecture: $arch")
        }
        return "$normalizedOs-$normalizedArch"
    }

    private companion object { val mutex = Mutex() }
}

actual object TresseCapabilities {
    actual fun supportedLanguages(): List<TresseLanguage> = ffiSupportedLanguages().map { language ->
        TresseLanguage(
            id = language.id,
            description = language.description,
            extensions = language.extensions,
            strategies = language.strategies.map { strategy ->
                TresseParsingStrategy(
                    id = strategy.id,
                    description = strategy.description,
                    recommended = strategy.recommended,
                )
            },
        )
    }
}
