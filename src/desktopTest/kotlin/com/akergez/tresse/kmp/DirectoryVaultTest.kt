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
    fun configIsWrittenAsTrackedRootFile() {
        val rootPath = createTempDirectory(prefix = "tresse-config-test-")
        val root = File(rootPath.pathString)
        try {
            val vault = fileTresseVault(root)

            TresseConfigFile.write(vault, TresseConfig(repoId = "notes"))

            assertEquals("notes", TresseConfigFile.read(vault)?.repoId)
            assertEquals(listOf("tresse.toml"), vault.listFiles().map { it.path })
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * A hidden directory that the patterns re-include has to survive the walk. The
     * re-inclusion is directory-only, so it only fires if the walker reports what it
     * actually found; a walker that calls everything a file prunes the directory and
     * never reaches the files the patterns were written to keep.
     */
    @Test
    fun walkHonoursDirectoryOnlyPatterns() {
        val rootPath = createTempDirectory(prefix = "tresse-dir-pattern-test-")
        val root = File(rootPath.pathString)
        try {
            run {
                val patterns = listOf(".*", "!/.obsidian/", "!/.obsidian/**", "/.obsidian/cache")
                val vault = fileTresseVault(root, patterns)

                File(root, ".obsidian").mkdirs()
                File(root, ".obsidian/appearance.json").writeText("{}")
                File(root, ".obsidian/cache").writeText("stale")
                File(root, ".git").mkdirs()
                File(root, ".git/HEAD").writeText("ref")
                File(root, "notes").mkdirs()
                File(root, "notes/a.md").writeText("hello")

                assertEquals(
                    listOf(".obsidian/appearance.json", "notes/a.md"),
                    vault.listFiles().map { it.path },
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /** Reading the config must not depend on the walk, nor on what the walk hides. */
    @Test
    fun configIsReadableEvenWhenEverythingIsIgnored() {
        val rootPath = createTempDirectory(prefix = "tresse-config-hidden-test-")
        val root = File(rootPath.pathString)
        try {
            TresseConfigFile.write(fileTresseVault(root), TresseConfig(repoId = "notes"))
            val vault = fileTresseVault(root, listOf("**"))

            assertEquals("notes", TresseConfigFile.read(vault)?.repoId)
            assertEquals(emptyList(), vault.listFiles().map { it.path })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fileVaultRoundTripsAndPrunesInternalAndIgnoredTrees() {
        val rootPath = createTempDirectory(prefix = "tresse-vault-test-")
        val root = File(rootPath.pathString)
        try {
            val vault = fileTresseVault(root, listOf("/ignored"))

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
