package com.akergez.tresse.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TresseConfigFileTest {
    @Test
    fun configPathIsAtProjectRoot() {
        assertEquals("tresse.toml", TresseConfigFile.PATH)
    }

    @Test
    fun splitConfigRoundTrips() {
        val config = TresseConfig(
            repoId = "books",
            encryptionKey = "secret==",
            s3Endpoint = "https://storage.example.com",
            s3Region = "ru-central1",
            s3Bucket = "library",
            s3AccessKeyId = "access",
            s3SecretAccessKey = "key\"with\\escapes",
            ignorePatterns = listOf(".git", "cache,old"),
        )
        val shared = TresseConfigFile.serialize(config)
        val remotes = TresseConfigFile.serializeRemotes(config)
        assertTrue("[languages]" in shared)
        assertTrue("[languages.markdown]" in shared)
        assertTrue("[[remotes.origin]]" in remotes)
        assertEquals(config, TresseConfigFile.parse(shared, remotes))
    }
}
