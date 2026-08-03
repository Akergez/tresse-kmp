package com.akergez.tresse.kmp

import java.io.File
import java.io.RandomAccessFile

class FileVaultFs(private val root: File) : VaultFs {
    override fun list(relDir: String): List<VaultFsEntry> {
        val dir = resolve(relDir)
        return dir.listFiles()?.map {
            VaultFsEntry(
                name = it.name,
                isDir = it.isDirectory,
                size = if (it.isFile) it.length() else 0L,
                modifiedAtMillis = it.lastModified(),
            )
        } ?: emptyList()
    }

    override fun readRange(relPath: String, offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val file = resolve(relPath)
        if (!file.isFile) return ByteArray(0)
        RandomAccessFile(file, "r").use { input ->
            val start = offset.coerceIn(0L, input.length())
            val count = length.toLong().coerceAtMost(input.length() - start).toInt()
            if (count <= 0) return ByteArray(0)
            val bytes = ByteArray(count)
            input.seek(start)
            val read = input.read(bytes)
            return if (read < count) bytes.copyOf(read.coerceAtLeast(0)) else bytes
        }
    }

    override fun readAll(relPath: String): ByteArray? {
        val file = resolve(relPath)
        return if (file.isFile) file.readBytes() else null
    }

    override fun write(relPath: String, bytes: ByteArray) {
        val file = resolve(relPath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    override fun delete(relPath: String) {
        resolve(relPath).delete()
    }

    private fun resolve(relPath: String): File {
        val clean = cleanPath(relPath)
        val target = if (clean.isEmpty()) root else File(root, clean)
        val rootPath = root.canonicalFile.toPath()
        val targetPath = target.canonicalFile.toPath()
        require(targetPath.startsWith(rootPath)) { "path escapes vault root: $relPath" }
        return target
    }
}

/**
 * The vault compiles the ignore patterns itself. Hosts hand over the patterns and
 * nothing else — not the config they came from, which carries tokens and keys a
 * directory walk has no business seeing, and not a predicate, which would make the
 * host decide whether a path is a directory. Only the walk knows that.
 */
fun fileTresseVault(root: File, ignorePatterns: List<String> = emptyList()): TresseVault =
    DirectoryVault(FileVaultFs(root), ignorePatterns.takeIf { it.isNotEmpty() }?.let(::TresseIgnore))
