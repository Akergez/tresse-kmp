package com.akergez.tresse.kmp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.tresse_ffi.*
import uniffi.tresse_ffi.forcePull as ffiForcePull
import uniffi.tresse_ffi.supportedLanguages as ffiSupportedLanguages

actual class TresseSync actual constructor(private val vault: TresseVault) {
    actual suspend fun perform(config: TresseConfig, progress: TresseSyncProgress?): TresseSyncResult =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                require(config.encryptionKey.isNotBlank()) { "Tresse encryption key is required" }
                require(config.isComplete()) { "Tresse ${config.storageType} sync configuration is incomplete" }
                val local = VaultObjectStore(vault)
                val outcome = ignoreRulesOf(config).use { ignore ->
                    casSync(
                        encryptionKeyB64 = config.encryptionKey,
                        fileIndexJson = local.readIndex(),
                        local = local,
                        remote = CountingRemoteKv(remoteKvOf(config), progress),
                        source = VaultSource(vault, ignore, progress),
                        sink = VaultSink(vault, ignore, progress),
                    )
                }
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
                val outcome = ignoreRulesOf(config).use { ignore ->
                    ffiForcePull(
                        encryptionKeyB64 = config.encryptionKey,
                        local = local,
                        remote = CountingRemoteKv(remoteKvOf(config), progress),
                        source = VaultSource(vault, ignore, progress),
                        sink = VaultSink(vault, ignore, progress),
                    )
                }
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

    private companion object {
        /** Serializes concurrent commit/sync/restore transactions. */
        val mutex = Mutex()
    }
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
