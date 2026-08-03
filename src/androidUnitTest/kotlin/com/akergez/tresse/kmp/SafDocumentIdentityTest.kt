package com.akergez.tresse.kmp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule that stops a discarded document from taking the path of its live replacement.
 * Every id below is a shape a real provider produces. Two cases carry the whole design:
 * a trashed twin must be rejected, and a display name reported without its extension must
 * not be — the second is why agreement is tested one-sidedly rather than by equality.
 */
class SafDocumentIdentityTest {
    private fun identifies(docId: String, name: String) = SafVaultFs.identifies(docId, name)

    @Test
    fun `a document reported under its own name identifies`() {
        assertTrue(identifies("primary:Documents/Notes/notes.md", "notes.md"))
    }

    @Test
    fun `a document at the root of the tree identifies`() {
        assertTrue(identifies("primary:notes.md", "notes.md"))
    }

    @Test
    fun `an id spelled as a raw filesystem path identifies`() {
        assertTrue(identifies("raw:/storage/emulated/0/Documents/notes.md", "notes.md"))
    }

    @Test
    fun `a directory identifies`() {
        assertTrue(identifies("primary:Documents/Notes", "Notes"))
    }

    @Test
    fun `a display name with the extension dropped still identifies`() {
        assertTrue(identifies("primary:Documents/Notes/notes.md", "notes"))
    }

    @Test
    fun `a trashed twin does not identify`() {
        assertFalse(identifies("primary:Documents/Notes/.trashed-1754-notes.md", "notes.md"))
    }

    @Test
    fun `a trashed twin of a name that already contains dots does not identify`() {
        assertFalse(identifies("primary:Documents/.trashed-1754-my.notes.md", "my.notes.md"))
    }

    @Test
    fun `a pending twin does not identify`() {
        assertFalse(identifies("primary:Documents/.pending-1-notes.md", "notes.md"))
    }

    @Test
    fun `another file in the same directory does not identify`() {
        assertFalse(identifies("primary:Documents/Notes/other.md", "notes.md"))
    }

    @Test
    fun `a name that is merely a prefix of the document does not identify`() {
        assertFalse(identifies("primary:Documents/abc.md", "a"))
        assertFalse(identifies("primary:Documents/notes.md", "note"))
    }

    @Test
    fun `an opaque id identifies nothing`() {
        assertFalse(identifies("12345", "notes.md"))
        assertFalse(identifies("document:9af31", "notes.md"))
    }
}
