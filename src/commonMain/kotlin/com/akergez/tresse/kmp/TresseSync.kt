package com.akergez.tresse.kmp

data class TresseConfig(
    val repoId: String,
    val encryptionKey: String,
    val storageType: String = "s3",
    val serverUrl: String = "",
    val token: String = "",
    val s3Endpoint: String = "",
    val s3Region: String = "us-east-1",
    val s3Bucket: String = "",
    val s3AccessKeyId: String = "",
    val s3SecretAccessKey: String = "",
    val ignorePatterns: List<String> = emptyList(),
) {
    fun isComplete(): Boolean = encryptionKey.isNotBlank() && repoId.isNotBlank() && when (storageType) {
        "s3" -> s3Endpoint.isNotBlank() && s3Bucket.isNotBlank() &&
            s3AccessKeyId.isNotBlank() && s3SecretAccessKey.isNotBlank()
        else -> serverUrl.isNotBlank()
    }
}

data class TresseFile(val path: String, val size: Long, val modifiedAtMillis: Long)
data class TresseSyncResult(val pushed: Int, val pulled: Int, val changed: Boolean)

/** Filesystem/SAF boundary supplied by the host application. */
interface TresseVault {
    fun listFiles(): List<TresseFile>
    fun read(path: String, offset: Long, length: Int): ByteArray
    fun write(path: String, bytes: ByteArray)
    fun delete(path: String)
    fun storeList(directory: String): List<String>
    fun storeRead(path: String): ByteArray?
    fun storeWrite(path: String, bytes: ByteArray)
    fun storeDelete(path: String)
}

expect class TresseSync(vault: TresseVault) {
    suspend fun perform(config: TresseConfig): TresseSyncResult
    suspend fun generateEncryptionKey(): String
    suspend fun loadConfig(): TresseConfig?
    suspend fun saveConfig(config: TresseConfig)
}
