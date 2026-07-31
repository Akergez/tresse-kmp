package com.akergez.tresse.kmp

/** Shared repository configuration plus device-local remote configuration. */
object TresseConfigFile {
    const val PATH = "tresse.toml"
    const val REMOTES_PATH = ".tresse/remotes.toml"

    fun read(vault: TresseVault): TresseConfig? {
        val shared = readTrackedFile(vault, PATH) ?: return null
        val remotes = vault.storeRead(REMOTES_PATH)?.decodeToString().orEmpty()
        return parse(shared.decodeToString(), remotes)
    }

    fun write(vault: TresseVault, config: TresseConfig) {
        vault.writeText(PATH, serialize(config))
        vault.storeWrite(REMOTES_PATH, serializeRemotes(config).encodeToByteArray())
    }

    private fun readTrackedFile(vault: TresseVault, path: String): ByteArray? {
        val file = vault.listFiles().firstOrNull { it.path == path } ?: return null
        require(file.size <= Int.MAX_VALUE) { "Config file is too large: $path" }
        return vault.read(path, 0, file.size.toInt())
    }

    fun serialize(config: TresseConfig): String = buildString {
        appendLine("[ignore]")
        appendLine("patterns = [${config.ignorePatterns.joinToString(", ") { quote(it) }}]")
        appendLine()
        appendLine("[languages]")
        appendLine()
        config.languages?.toSortedMap()?.forEach { (language, strategy) ->
            appendLine("[languages.$language]")
            appendLine("strategy = ${quote(strategy)}")
            appendLine()
        }
    }

    fun serializeRemotes(config: TresseConfig): String = buildString {
        appendLine("[[remotes.origin]]")
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

    fun parse(text: String, remotesText: String = ""): TresseConfig {
        require(!Regex("""(?m)^\s*(?:\[remote]|\[\[remotes(?:\.[A-Za-z0-9_-]+)?]])\s*$""").containsMatchIn(text)) {
            "remotes must be configured in .tresse/remotes.toml"
        }
        require(!Regex("""(?m)^\s*\[remote]\s*$""").containsMatchIn(remotesText)) {
            "the legacy [remote] format is not supported"
        }
        val strings = mutableMapOf<String, String>()
        var ignores = emptyList<String>()
        val languages = mutableMapOf<String, String>()
        var section = ""
        var remoteCount = 0
        "$text\n$remotesText".lineSequence().forEachIndexed { index, raw ->
            val line = stripComment(raw).trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line.startsWith("[[") && line.endsWith("]]")) {
                section = line.substring(2, line.length - 2)
                require(!section.startsWith("remotes") || section == "remotes.origin") {
                    "the only supported remote name is `origin`"
                }
                if (section == "remotes.origin") remoteCount += 1
                return@forEachIndexed
            }
            if (line.startsWith('[') && line.endsWith(']')) {
                section = line.substring(1, line.length - 1)
                return@forEachIndexed
            }
            val separator = line.indexOf('=')
            require(separator > 0) { "invalid TOML at line ${index + 1}" }
            val key = "$section.${line.substring(0, separator).trim()}"
            val value = line.substring(separator + 1).trim()
            if (key == "ignore.patterns") ignores = parseStringArray(value, index + 1)
            else if (section.startsWith("languages.") && line.substring(0, separator).trim() == "strategy") {
                languages[section.removePrefix("languages.")] = parseString(value, index + 1)
            } else strings[key] = parseString(value, index + 1)
        }
        require(remoteCount <= 1) { "only one remote named `origin` is supported" }
        fun value(key: String) = strings[key].orEmpty()
        return TresseConfig(
            serverUrl = value("remotes.origin.url"),
            repoId = value("remotes.origin.repo_id"),
            token = value("remotes.origin.token"),
            encryptionKey = value("remotes.origin.encryption_key"),
            storageType = if (value("remotes.origin.storage_type") == "s3") "s3" else "http",
            s3Endpoint = value("remotes.origin.s3_endpoint"),
            // Left as stored; the S3 backend defaults a blank region at use-site.
            s3Region = value("remotes.origin.s3_region"),
            s3Bucket = value("remotes.origin.s3_bucket"),
            s3AccessKeyId = value("remotes.origin.s3_access_key_id"),
            s3SecretAccessKey = value("remotes.origin.s3_secret_access_key"),
            ignorePatterns = ignores,
            languages = languages.takeIf { it.isNotEmpty() },
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
