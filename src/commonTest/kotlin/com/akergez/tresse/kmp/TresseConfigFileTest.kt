package com.akergez.tresse.kmp

import kotlin.test.Test
import kotlin.test.assertEquals

class TresseConfigFileTest {
    @Test
    fun configPathIsAtProjectRoot() {
        assertEquals("tresse.toml", TresseConfigFile.PATH)
    }

    @Test
    fun tresseTomlRoundTrips() {
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
        assertEquals(config, TresseConfigFile.parse(TresseConfigFile.serialize(config)))
    }
}
