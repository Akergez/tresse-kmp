package com.akergez.tresse.kmp

/**
 * Shareable config string: `tresse://<backend>?<params>`.
 *
 * A shared string says how to reach the repository and nothing else — the backend
 * coordinates and the credentials that open them. The rest of [TresseConfig] is
 * deliberately absent: ignore patterns and language strategies belong to the notes
 * directory and travel in its own tracked `tresse.toml`, so carrying them here would
 * let one replica's policy silently replace another's, and the `autoSync*` knobs are
 * device-local scheduling that means nothing on a second device. Adding a field to
 * [TresseConfig] does not add it here, which is the point of listing the parameters
 * by hand rather than serializing the object.
 *
 * A URI rather than an encoded blob. It is one line with no spaces, so it survives a
 * chat message, a QR code and a text field intact — and it is legible on the way out,
 * so the sender can see the secret they are about to hand over instead of trusting an
 * opaque string. Legible is not protected: every credential is in the clear, and the
 * channel still has to be a trusted one.
 *
 * Same wire format as the companion Obsidian plugin (`obsidian-plugin/src/config-share.ts`
 * in the tresse repo), so strings paste between the app and the plugin in either
 * direction. A change here is a change there.
 */
object TresseConfigShare {
    const val SCHEME = "tresse://"

    /** Is this a config string, as opposed to a `tresse.toml` body? See [TresseConfigString]. */
    fun isConfigString(input: String): Boolean = input.trim().startsWith(SCHEME)

    fun encode(config: TresseConfig): String = buildString {
        val s3 = config.storageType == "s3"
        append(SCHEME)
        append(if (s3) "s3" else "http")
        val params = if (s3) {
            listOf(
                "repo_id" to config.repoId,
                "endpoint" to config.s3Endpoint,
                "region" to config.s3Region,
                "bucket" to config.s3Bucket,
                "access_key_id" to config.s3AccessKeyId,
                "secret_access_key" to config.s3SecretAccessKey,
                "encryption_key" to config.encryptionKey,
            )
        } else {
            listOf(
                "url" to config.serverUrl,
                "repo_id" to config.repoId,
                "token" to config.token,
                "encryption_key" to config.encryptionKey,
            )
        }
        // A blank field is not worth spelling out, and omitting it keeps a half-filled
        // config from reading like a complete one.
        params.filter { it.second.isNotEmpty() }.forEachIndexed { index, (key, value) ->
            append(if (index == 0) '?' else '&')
            append(key)
            append('=')
            append(escape(value))
        }
    }

    fun decode(input: String): TresseConfig {
        val value = input.trim()
        require(value.startsWith(SCHEME)) { "not a Tresse config string (must start with $SCHEME)" }
        val body = value.removePrefix(SCHEME)
        val backend = body.substringBefore('?')
        require(backend == "s3" || backend == "http") {
            "unknown backend '$backend' in config string (expected 's3' or 'http')"
        }
        val params = mutableMapOf<String, String>()
        body.substringAfter('?', "").split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val separator = pair.indexOf('=')
            require(separator > 0) { "malformed parameter '$pair' in config string" }
            params[pair.substring(0, separator)] = unescape(pair.substring(separator + 1))
        }
        fun value(key: String) = params[key].orEmpty()
        return TresseConfig(
            storageType = backend,
            repoId = value("repo_id"),
            encryptionKey = value("encryption_key"),
            serverUrl = value("url"),
            token = value("token"),
            s3Endpoint = value("endpoint"),
            s3Region = value("region"),
            s3Bucket = value("bucket"),
            s3AccessKeyId = value("access_key_id"),
            s3SecretAccessKey = value("secret_access_key"),
        )
    }

    /**
     * Percent-encoding kept as small as it can be: `:` `/` and `=` stay literal, so a
     * server URL and a base64 key read as themselves and a value may carry its own `=`
     * padding (a pair splits on its first `=`, never the rest). Everything outside the
     * unreserved set is escaped, which is what stops `&` and `#` from cutting the string
     * in half. `+` is escaped for a different reason: it is legal in base64 keys, and
     * half the query parsers in the world would silently read it back as a space.
     */
    private fun escape(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val char = byte.toInt().toChar()
            val literal = byte >= 0 &&
                (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~:/=")
            if (literal) {
                append(char)
            } else {
                append('%')
                append(HEX[(byte.toInt() shr 4) and 0xF])
                append(HEX[byte.toInt() and 0xF])
            }
        }
    }

    private fun unescape(value: String): String {
        if ('%' !in value) return value
        val out = StringBuilder()
        // Escapes are collected as bytes, not characters: one non-ASCII character is
        // several of them, and decoding each on its own would produce replacement junk.
        val pending = mutableListOf<Byte>()
        fun flush() {
            if (pending.isNotEmpty()) {
                out.append(pending.toByteArray().decodeToString())
                pending.clear()
            }
        }
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%') {
                require(index + 2 < value.length) { "truncated percent escape in config string" }
                pending += ((hexDigit(value[index + 1]) shl 4) or hexDigit(value[index + 2])).toByte()
                index += 3
            } else {
                flush()
                out.append(char)
                index += 1
            }
        }
        flush()
        return out.toString()
    }

    private fun hexDigit(char: Char): Int = requireNotNull(char.digitToIntOrNull(16)) {
        "invalid percent escape '$char' in config string"
    }

    private const val HEX = "0123456789ABCDEF"
}
