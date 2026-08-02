package com.akergez.tresse.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TresseIgnoreTest {
    /**
     * Also the load test for the desktop native library: nothing here constructs a
     * [TresseSync], so the rules have to bring the library up themselves.
     */
    @Test
    fun compilesGitignoreRulesAndKeepsTheLayoutDirOut() {
        TresseIgnore(listOf(".*", "!/.obsidian/", "!/.obsidian/**", "/.obsidian/cache")).use { ignore ->
            assertTrue(ignore.warnings().isEmpty())

            assertTrue(ignore.isIgnored(".git", true))
            assertTrue(ignore.isIgnored("notes/.DS_Store", false))
            assertFalse(ignore.isIgnored(".obsidian/appearance.json", false))
            assertTrue(ignore.isIgnored(".obsidian/cache", true))
            assertFalse(ignore.isIgnored("notes/visible.md", false))

            // The engine's own store is excluded whatever the host declares.
            assertTrue(ignore.isIgnored(".tresse/meta/abc", false))
        }
    }

    /** An excluded directory takes its subtree with it, flat file list or not. */
    @Test
    fun negationUnderAnExcludedAncestorIsReportedAsDead() {
        TresseIgnore(listOf("build/", "!build/keep.txt")).use { ignore ->
            assertEquals(1, ignore.warnings().size)
            assertTrue(ignore.isIgnored("build/keep.txt", false))
        }
    }
}
