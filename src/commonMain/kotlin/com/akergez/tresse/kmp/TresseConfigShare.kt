package com.akergez.tresse.kmp

import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Shareable config string: `tresse1:` + base64url(JSON(config)). Carries the full
 * credential bundle (repo id, encryption key, backend secrets), so it must only be
 * shared over a trusted channel. Same wire format as the Obsidian plugin's
 * config-share string (obsidian-plugin/src/config-share.ts in the tresse repo), so
 * strings can be pasted between the app and the plugin interchangeably.
 */
@OptIn(ExperimentalEncodingApi::class)
object TresseConfigShare {
    const val PREFIX = "tresse1:"

    fun encode(config: TresseConfig, json: Json): String =
        PREFIX + Base64.UrlSafe.encode(json.encodeToString(config).encodeToByteArray()).trimEnd('=')

    fun decode(input: String, json: Json): TresseConfig {
        val value = input.trim()
        require(value.startsWith(PREFIX)) { "not a Tresse config string (missing '$PREFIX' prefix)" }
        val payload = value.removePrefix(PREFIX)
        require(payload.isNotBlank()) { "empty config payload" }
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        return try {
            json.decodeFromString(Base64.UrlSafe.decode(padded).decodeToString())
        } catch (error: Exception) {
            throw IllegalArgumentException("config string is corrupt (invalid base64 or JSON)", error)
        }
    }
}
