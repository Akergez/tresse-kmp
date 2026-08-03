package com.akergez.tresse.kmp

/**
 * Single entry point for turning user-supplied config text into a [TresseConfig].
 * Accepts either the shareable `tresse://` string (see [TresseConfigShare]) or the
 * raw `tresse.toml` body (see [TresseConfigFile]) — the same dual form the companion
 * plugin accepts, so a string exported anywhere can be pasted here.
 *
 * The two carry different things and neither is the whole config: a config string is
 * the credential bundle with no policy in it, a `tresse.toml` body is policy with no
 * credentials. Callers that persist the result must merge it into what they already
 * have rather than replace it, or importing credentials will blank the ignore rules.
 */
object TresseConfigString {
    fun decode(input: String): TresseConfig {
        val text = input.trim()
        return if (TresseConfigShare.isConfigString(text)) TresseConfigShare.decode(text)
        else TresseConfigFile.parse(text)
    }
}
