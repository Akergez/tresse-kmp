package com.akergez.tresse.kmp

data class VaultFsEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modifiedAtMillis: Long,
)

interface VaultFs {
    fun list(relDir: String): List<VaultFsEntry>
    fun readRange(relPath: String, offset: Long, length: Int): ByteArray
    fun readAll(relPath: String): ByteArray?
    fun write(relPath: String, bytes: ByteArray)
    fun delete(relPath: String)
}

class DirectoryVault(
    private val fs: VaultFs,
    private val isIgnored: (String) -> Boolean = { false },
) : TresseVault {
    override fun listFiles(): List<TresseFile> = buildList {
        fun walk(directory: String) {
            fs.list(directory).forEach { entry ->
                val path = joinPath(directory, entry.name)
                if (path == ".tresse" || path.startsWith(".tresse/") || isIgnored(path)) return@forEach
                if (entry.isDir) {
                    walk(path)
                } else {
                    add(TresseFile(path, entry.size, entry.modifiedAtMillis))
                }
            }
        }
        walk("")
    }.distinctBy(TresseFile::path).sortedBy(TresseFile::path)

    override fun read(path: String, offset: Long, length: Int): ByteArray =
        fs.readRange(cleanPath(path), offset, length)

    override fun writeBinary(path: String, bytes: ByteArray) {
        val relPath = cleanPath(path)
        if (relPath == ".tresse" || relPath.startsWith(".tresse/") || isIgnored(relPath)) return
        fs.write(relPath, bytes)
    }

    override fun delete(path: String) {
        val relPath = cleanPath(path)
        if (relPath == ".tresse" || relPath.startsWith(".tresse/") || isIgnored(relPath)) return
        fs.delete(relPath)
    }

    override fun storeList(directory: String): List<String> =
        fs.list(cleanPath(directory)).filterNot(VaultFsEntry::isDir).map(VaultFsEntry::name)

    override fun storeRead(path: String): ByteArray? = fs.readAll(cleanPath(path))

    override fun storeWrite(path: String, bytes: ByteArray) {
        fs.write(cleanPath(path), bytes)
    }

    override fun storeDelete(path: String) {
        fs.delete(cleanPath(path))
    }
}

internal fun cleanPath(path: String): String {
    val parts = path.replace('\\', '/').split('/').filter(String::isNotBlank)
    require(parts.none { it == "." || it == ".." }) { "invalid vault path: $path" }
    return parts.joinToString("/")
}

private fun joinPath(parent: String, child: String): String =
    if (parent.isEmpty()) cleanPath(child) else "${cleanPath(parent)}/${cleanPath(child)}"
