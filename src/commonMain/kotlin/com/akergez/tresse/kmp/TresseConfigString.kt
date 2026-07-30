package com.akergez.tresse.kmp

import kotlinx.serialization.json.Json

/**
 * Single entry point for turning user-supplied config text into a [TresseConfig].
 * Accepts either the shareable `tresse1:` string (see [TresseConfigShare]) or the
 * raw `tresse.toml` body (see [TresseConfigFile]) — the same dual form the companion
 * plugin accepts, so a string exported anywhere can be pasted here.
 */
object TresseConfigString {
    fun decode(input: String, json: Json = Json { ignoreUnknownKeys = true }): TresseConfig {
        val text = input.trim()
        return if (text.startsWith(TresseConfigShare.PREFIX)) TresseConfigShare.decode(text, json)
        else TresseConfigFile.parse(text)
    }
}
