package com.akergez.tresse.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A config string is the credential bundle and nothing else. The cases that matter are
 * the ones asserting what is *absent*: policy leaking into a shared string is how one
 * replica's ignore rules end up replacing another's.
 */
class TresseConfigShareTest {
    private val s3 = TresseConfig(
        storageType = "s3",
        repoId = "books",
        encryptionKey = "Zm9vYmFy+12345==",
        s3Endpoint = "https://storage.example.com",
        s3Region = "ru-central1",
        s3Bucket = "library",
        s3AccessKeyId = "YCAJE7x",
        s3SecretAccessKey = "YCP7vQ+ab&cd",
        ignorePatterns = listOf(".git", ".obsidian"),
        languages = mapOf("markdown" to "tree-sitter"),
        autoSyncEnabled = false,
        autoSyncIntervalMinutes = 42,
        autoSyncDebounceSeconds = 7,
    )

    private val http = TresseConfig(
        storageType = "http",
        serverUrl = "https://tresse.example.com/api",
        repoId = "notes",
        token = "abc123",
        encryptionKey = "Zm9vYmFy+12345==",
    )

    @Test
    fun s3ConfigRoundTripsItsCredentials() {
        val decoded = TresseConfigShare.decode(TresseConfigShare.encode(s3))
        assertEquals("s3", decoded.storageType)
        assertEquals("books", decoded.repoId)
        assertEquals("https://storage.example.com", decoded.s3Endpoint)
        assertEquals("ru-central1", decoded.s3Region)
        assertEquals("library", decoded.s3Bucket)
        assertEquals("YCAJE7x", decoded.s3AccessKeyId)
        assertEquals("YCP7vQ+ab&cd", decoded.s3SecretAccessKey)
        assertEquals("Zm9vYmFy+12345==", decoded.encryptionKey)
    }

    @Test
    fun httpConfigRoundTripsItsCredentials() {
        val decoded = TresseConfigShare.decode(TresseConfigShare.encode(http))
        assertEquals("http", decoded.storageType)
        assertEquals("https://tresse.example.com/api", decoded.serverUrl)
        assertEquals("notes", decoded.repoId)
        assertEquals("abc123", decoded.token)
        assertEquals("Zm9vYmFy+12345==", decoded.encryptionKey)
    }

    @Test
    fun policyAndSchedulingNeverReachTheString() {
        val encoded = TresseConfigShare.encode(s3)
        assertFalse(".git" in encoded)
        assertFalse(".obsidian" in encoded)
        assertFalse("markdown" in encoded)
        assertFalse("tree-sitter" in encoded)
        assertFalse("42" in encoded)
        assertFalse("auto" in encoded)
    }

    @Test
    fun decodingLeavesPolicyAndSchedulingAtTheirDefaults() {
        val decoded = TresseConfigShare.decode(TresseConfigShare.encode(s3))
        assertEquals(emptyList(), decoded.ignorePatterns)
        assertEquals(TresseConfig().languages, decoded.languages)
        assertEquals(TresseConfig().autoSyncEnabled, decoded.autoSyncEnabled)
        assertEquals(TresseConfig().autoSyncIntervalMinutes, decoded.autoSyncIntervalMinutes)
        assertEquals(TresseConfig().autoSyncDebounceSeconds, decoded.autoSyncDebounceSeconds)
    }

    @Test
    fun theStringIsOneReadableLine() {
        val encoded = TresseConfigShare.encode(s3)
        assertTrue(encoded.startsWith("tresse://s3?"))
        assertFalse('\n' in encoded)
        assertFalse(' ' in encoded)
        // A URL and a base64 key have to read as themselves.
        assertTrue("endpoint=https://storage.example.com" in encoded)
        assertTrue("region=ru-central1" in encoded)
    }

    @Test
    fun charactersThatWouldCutTheStringAreEscaped() {
        val encoded = TresseConfigShare.encode(s3)
        // '+' survives base64 keys but not query parsers; '&' would start a new parameter.
        assertTrue("encryption_key=Zm9vYmFy%2B12345==" in encoded)
        assertTrue("secret_access_key=YCP7vQ%2Bab%26cd" in encoded)
    }

    @Test
    fun valuesWithSpacesAndNonAsciiRoundTrip() {
        val config = http.copy(token = "два слова", repoId = "a b")
        val encoded = TresseConfigShare.encode(config)
        assertFalse(' ' in encoded)
        val decoded = TresseConfigShare.decode(encoded)
        assertEquals("два слова", decoded.token)
        assertEquals("a b", decoded.repoId)
    }

    @Test
    fun blankFieldsAreOmitted() {
        val encoded = TresseConfigShare.encode(http.copy(token = ""))
        assertFalse("token=" in encoded)
        assertEquals("", TresseConfigShare.decode(encoded).token)
    }

    @Test
    fun aStringThatIsNotAConfigStringIsRejected() {
        assertFalse(TresseConfigShare.isConfigString("[ignore]\npatterns = []"))
        assertFailsWith<IllegalArgumentException> { TresseConfigShare.decode("tresse1:eyJhIjoxfQ") }
        assertFailsWith<IllegalArgumentException> { TresseConfigShare.decode("tresse://ftp?url=x") }
        assertFailsWith<IllegalArgumentException> { TresseConfigShare.decode("tresse://s3?bucket") }
    }

    @Test
    fun configStringAndTomlBodyBothDecodeThroughTheSameEntryPoint() {
        val fromString = TresseConfigString.decode(TresseConfigShare.encode(s3))
        assertEquals("library", fromString.s3Bucket)

        val fromFile = TresseConfigString.decode(TresseConfigFile.serialize(s3))
        assertEquals(listOf(".git", ".obsidian"), fromFile.ignorePatterns)
        assertEquals("", fromFile.encryptionKey)
    }
}
