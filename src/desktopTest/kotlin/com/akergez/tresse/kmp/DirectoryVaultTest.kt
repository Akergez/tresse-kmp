package com.akergez.tresse.kmp

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.pathString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DirectoryVaultTest {
    @Test
    fun fileVaultRoundTripsAndPrunesInternalAndIgnoredTrees() {
        val rootPath = createTempDirectory(prefix = "tresse-vault-test-")
        val root = File(rootPath.pathString)
        try {
            val vault = fileTresseVault(root) { it == "ignored" || it.startsWith("ignored/") }

            vault.writeText("notes/a.md", "hello")
            vault.writeBinary("bin/image.bin", byteArrayOf(1, 2, 3, 4, 5))
            vault.writeBinary("ignored/secret.txt", "hidden".encodeToByteArray())
            vault.storeWrite(".tresse/meta/id", byteArrayOf(9))
            vault.storeWrite(".tresse/content/blob", byteArrayOf(8))

            assertEquals(listOf("bin/image.bin", "notes/a.md"), vault.listFiles().map { it.path })
            assertContentEquals("ell".encodeToByteArray(), vault.read("notes/a.md", 1, 3))
            assertContentEquals(byteArrayOf(2, 3, 4), vault.read("bin/image.bin", 1, 3))
            assertEquals(listOf("id"), vault.storeList(".tresse/meta"))
            assertContentEquals(byteArrayOf(9), vault.storeRead(".tresse/meta/id"))

            vault.delete("notes/a.md")
            vault.storeDelete(".tresse/meta/id")

            assertEquals(listOf("bin/image.bin"), vault.listFiles().map { it.path })
            assertNull(vault.storeRead(".tresse/meta/id"))
            assertFalse(File(root, "notes/a.md").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
