package com.akergez.tresse.kmp

/** Shared repository configuration stored next to CAS data. */
object TresseConfigFile {
    const val PATH = ".tresse/config.toml"

    fun read(vault: TresseVault): TresseConfig? =
        vault.storeRead(PATH)?.decodeToString()?.let(::parse)

    fun write(vault: TresseVault, config: TresseConfig) {
        vault.storeWrite(PATH, serialize(config).encodeToByteArray())
    }

    fun serialize(config: TresseConfig): String = buildString {
        appendLine("[ignore]")
        appendLine("patterns = [${config.ignorePatterns.joinToString(", ") { quote(it) }}]")
        appendLine()
        appendLine("[remote]")
        appendLine("storage_type = ${quote(if (config.storageType == "s3") "s3" else "http")}")
        appendLine("url = ${quote(config.serverUrl)}")
        appendLine("token = ${quote(config.token)}")
        appendLine("repo_id = ${quote(config.repoId)}")
        appendLine("encryption_key = ${quote(config.encryptionKey)}")
        appendLine("s3_endpoint = ${quote(config.s3Endpoint)}")
        appendLine("s3_region = ${quote(config.s3Region)}")
        appendLine("s3_bucket = ${quote(config.s3Bucket)}")
        appendLine("s3_access_key_id = ${quote(config.s3AccessKeyId)}")
        appendLine("s3_secret_access_key = ${quote(config.s3SecretAccessKey)}")
    }

    fun parse(text: String): TresseConfig {
        val strings = mutableMapOf<String, String>()
        var ignores = emptyList<String>()
        var section = ""
        text.lineSequence().forEachIndexed { index, raw ->
            val line = stripComment(raw).trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line.startsWith('[') && line.endsWith(']')) {
                section = line.substring(1, line.length - 1)
                return@forEachIndexed
            }
            val separator = line.indexOf('=')
            require(separator > 0) { "invalid TOML at line ${index + 1}" }
            val key = "$section.${line.substring(0, separator).trim()}"
            val value = line.substring(separator + 1).trim()
            if (key == "ignore.patterns") ignores = parseStringArray(value, index + 1)
            else strings[key] = parseString(value, index + 1)
        }
        fun value(key: String) = strings[key].orEmpty()
        return TresseConfig(
            serverUrl = value("remote.url"),
            repoId = value("remote.repo_id"),
            token = value("remote.token"),
            encryptionKey = value("remote.encryption_key"),
            storageType = if (value("remote.storage_type") == "s3") "s3" else "http",
            s3Endpoint = value("remote.s3_endpoint"),
            // Left as stored; the S3 backend defaults a blank region at use-site.
            s3Region = value("remote.s3_region"),
            s3Bucket = value("remote.s3_bucket"),
            s3AccessKeyId = value("remote.s3_access_key_id"),
            s3SecretAccessKey = value("remote.s3_secret_access_key"),
            ignorePatterns = ignores,
        )
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    private fun parseString(input: String, line: Int): String {
        require(input.length >= 2 && input.first() == '"' && input.last() == '"') {
            "expected string at line $line"
        }
        val out = StringBuilder()
        var escaped = false
        input.substring(1, input.length - 1).forEach { char ->
            if (escaped) {
                out.append(when (char) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> char })
                escaped = false
            } else if (char == '\\') escaped = true else out.append(char)
        }
        require(!escaped) { "invalid escape at line $line" }
        return out.toString()
    }

    private fun parseStringArray(input: String, line: Int): List<String> {
        require(input.startsWith('[') && input.endsWith(']')) { "expected array at line $line" }
        val body = input.substring(1, input.length - 1).trim()
        if (body.isEmpty()) return emptyList()
        val values = mutableListOf<String>()
        var start = 0
        var quoted = false
        var escaped = false
        body.forEachIndexed { index, char ->
            if (quoted && char == '\\' && !escaped) { escaped = true; return@forEachIndexed }
            if (char == '"' && !escaped) quoted = !quoted
            if (char == ',' && !quoted) { values += parseString(body.substring(start, index).trim(), line); start = index + 1 }
            escaped = false
        }
        values += parseString(body.substring(start).trim(), line)
        return values
    }

    private fun stripComment(line: String): String {
        var quoted = false
        var escaped = false
        line.forEachIndexed { index, char ->
            if (quoted && char == '\\' && !escaped) { escaped = true; return@forEachIndexed }
            if (char == '"' && !escaped) quoted = !quoted
            if (char == '#' && !quoted) return line.substring(0, index)
            escaped = false
        }
        return line
    }
}
