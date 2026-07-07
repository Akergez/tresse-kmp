package com.akergez.tresse.kmp

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import uniffi.tresse_ffi.FfiRemoteKv
import uniffi.tresse_ffi.TresseException

/**
 * Explicit-key remote object store the CAS sync engine lists, reads and writes.
 *
 * Byte-for-byte the same wire protocol as the companion plugin's `RemoteKv`
 * (`obsidian-plugin/src/storage.ts`): keys are `meta/<hex>` and `content/<hex>`,
 * values are opaque sealed ciphertext (encryption happens inside the Rust engine,
 * above this layer). That identity is what lets an Android device and the plugin
 * share one remote store.
 */
internal fun remoteKvOf(config: TresseConfig): FfiRemoteKv =
    if (config.storageType == "s3") S3RemoteKv(config) else HttpRemoteKv(config)

private val REMOTE_KEY_REGEX = Regex("^(meta|content)/[0-9a-fA-F]{64}$")

private fun fail(message: String): Nothing = throw TresseException.General(message)

// ---------------------------------------------------------------------------
// HTTP backend (original Tresse server)
// ---------------------------------------------------------------------------

private class HttpRemoteKv(private val config: TresseConfig) : FfiRemoteKv {
    /** `prefix` is `"meta/"` or `"content/"`; server lists bare ids under the class. */
    override fun list(prefix: String): List<String> {
        val cls = prefix.trimEnd('/')
        val ids = request("GET", "objects/${segment(config.repoId)}/$cls").decodeToString().parseStringArray()
        return ids.map { "$prefix$it" }.distinct().sorted()
    }

    override fun get(key: String): ByteArray =
        request("GET", "objects/${segment(config.repoId)}/$key")

    override fun put(key: String, value: ByteArray) {
        request("POST", "objects/${segment(config.repoId)}/$key", value)
    }

    override fun delete(key: String) {
        request("DELETE", "objects/${segment(config.repoId)}/$key")
    }

    private fun request(method: String, path: String, body: ByteArray? = null): ByteArray {
        val base = config.serverUrl.trimEnd('/')
        val connection = URL("$base/$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        if (config.token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${config.token}")
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }
        val status = connection.responseCode
        val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        if (status !in 200..299) fail("Tresse HTTP $method $path failed ($status): ${bytes.decodeToString().take(200)}")
        return bytes
    }

    private fun segment(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun String.parseStringArray(): List<String> = trim().removePrefix("[").removeSuffix("]")
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { token ->
            token.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
        }
}

// ---------------------------------------------------------------------------
// S3-compatible backend (AWS SigV4, path-style addressing)
// ---------------------------------------------------------------------------

private class S3RemoteKv(config: TresseConfig) : FfiRemoteKv {
    private val endpoint = config.s3Endpoint.trimEnd('/')
    private val region = config.s3Region.trim().ifBlank { "us-east-1" }
    private val bucket = config.s3Bucket.trim()
    private val accessKeyId = config.s3AccessKeyId.trim()
    private val secretAccessKey = config.s3SecretAccessKey
    private val repoPrefix = config.repoId.trim().let { if (it.isBlank()) "" else "${it.trimEnd('/')}/" }

    /** `prefix` is `"meta/"` or `"content/"`; returns explicit `meta|content/<hex>` keys. */
    override fun list(prefix: String): List<String> {
        val keys = mutableListOf<String>()
        val storagePrefix = "$repoPrefix$prefix"
        var token: String? = null
        do {
            val query = sortedMapOf("list-type" to "2", "prefix" to storagePrefix)
            if (token != null) query["continuation-token"] = token
            val (status, body) = send("GET", "/$bucket", query, null)
            val text = body.decodeToString()
            if (status !in 200..299) fail("S3 remote list failed ($status): ${text.take(200)}")
            val page = parseListObjectsV2(text)
            for (rawKey in page.keys) {
                if (rawKey.startsWith(repoPrefix)) {
                    val explicit = rawKey.substring(repoPrefix.length)
                    if (explicit.startsWith(prefix) && REMOTE_KEY_REGEX.matches(explicit)) keys += explicit
                }
            }
            token = if (page.truncated) page.nextToken else null
        } while (token != null)
        return keys.distinct().sorted()
    }

    override fun get(key: String): ByteArray {
        val (status, body) = send("GET", "/$bucket/$repoPrefix$key", emptyMap(), null)
        if (status !in 200..299) fail("S3 remote get $key failed ($status)")
        return body
    }

    override fun put(key: String, value: ByteArray) {
        val (status, body) = send("PUT", "/$bucket/$repoPrefix$key", emptyMap(), value)
        if (status !in 200..299) fail("S3 remote put $key failed ($status): ${body.decodeToString().take(200)}")
    }

    override fun delete(key: String) {
        val (status, body) = send("DELETE", "/$bucket/$repoPrefix$key", emptyMap(), null)
        if (status !in 200..299) fail("S3 remote delete $key failed ($status): ${body.decodeToString().take(200)}")
    }

    /** Sign with SigV4 and dispatch. Path-style addressing, unsigned payload streaming is not used. */
    private fun send(method: String, path: String, query: Map<String, String>, body: ByteArray?): Pair<Int, ByteArray> {
        val host = URL(endpoint).host
        val amzDate = amzDate()
        val dateStamp = amzDate.substring(0, 8)
        val payloadHash = if (body != null) hex(sha256(body)) else EMPTY_SHA256

        val canonicalUri = awsUriEncode(path, encodeSlash = false)
        val canonicalQuery = query.keys.sorted().joinToString("&") { key ->
            "${awsUriEncode(key, encodeSlash = true)}=${awsUriEncode(query.getValue(key), encodeSlash = true)}"
        }
        val canonicalHeaders = "host:$host\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = listOf(
            method,
            canonicalUri,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString("\n")

        val scope = "$dateStamp/$region/s3/aws4_request"
        val stringToSign = listOf(
            "AWS4-HMAC-SHA256",
            amzDate,
            scope,
            hex(sha256(canonicalRequest.encodeToByteArray())),
        ).joinToString("\n")

        val signature = hex(hmac(signingKey(dateStamp), stringToSign.encodeToByteArray()))
        val authorization = "AWS4-HMAC-SHA256 Credential=$accessKeyId/$scope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"

        val url = endpoint + canonicalUri + if (canonicalQuery.isNotEmpty()) "?$canonicalQuery" else ""
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("Authorization", authorization)
        connection.setRequestProperty("x-amz-date", amzDate)
        connection.setRequestProperty("x-amz-content-sha256", payloadHash)
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }
        val status = connection.responseCode
        val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return status to bytes
    }

    private fun signingKey(dateStamp: String): ByteArray {
        val kDate = hmac("AWS4$secretAccessKey".encodeToByteArray(), dateStamp.encodeToByteArray())
        val kRegion = hmac(kDate, region.encodeToByteArray())
        val kService = hmac(kRegion, "s3".encodeToByteArray())
        return hmac(kService, "aws4_request".encodeToByteArray())
    }
}

// ---------------------------------------------------------------------------
// Crypto / encoding helpers
// ---------------------------------------------------------------------------

private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

private data class ListObjectsPage(
    val keys: List<String>,
    val truncated: Boolean,
    val nextToken: String?,
)

/** Parse the small, flat subset of S3 ListObjectsV2 XML without platform XML factories. */
private fun parseListObjectsV2(xml: String): ListObjectsPage {
    fun texts(tag: String): List<String> {
        val open = "<$tag>"
        val close = "</$tag>"
        val values = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = xml.indexOf(open, from)
            if (start < 0) break
            val bodyStart = start + open.length
            val end = xml.indexOf(close, bodyStart)
            if (end < 0) break
            values += xmlDecode(xml.substring(bodyStart, end))
            from = end + close.length
        }
        return values
    }
    return ListObjectsPage(
        keys = texts("Key"),
        truncated = texts("IsTruncated").firstOrNull().equals("true", ignoreCase = true),
        nextToken = texts("NextContinuationToken").firstOrNull(),
    )
}

private fun xmlDecode(value: String): String = buildString(value.length) {
    var index = 0
    while (index < value.length) {
        val amp = value.indexOf('&', index)
        if (amp < 0) {
            append(value.substring(index))
            break
        }
        append(value.substring(index, amp))
        val semi = value.indexOf(';', amp + 1)
        if (semi < 0) {
            append(value.substring(amp))
            break
        }
        val entity = value.substring(amp + 1, semi)
        val decoded = when (entity) {
            "amp" -> '&'
            "lt" -> '<'
            "gt" -> '>'
            "quot" -> '"'
            "apos" -> '\''
            else -> entity.toIntOrNull()?.toChar()
                ?: entity.removePrefix("#x").takeIf { entity.startsWith("#x") }?.toIntOrNull(16)?.toChar()
                ?: entity.removePrefix("#").takeIf { entity.startsWith("#") }?.toIntOrNull()?.toChar()
        }
        if (decoded == null) append('&').append(entity).append(';') else append(decoded)
        index = semi + 1
    }
}

private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    for (b in bytes) append("%02x".format(b.toInt() and 0xFF))
}

private fun amzDate(): String {
    val format = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date())
}

/** RFC 3986 / AWS canonical encoding. Slashes are preserved when [encodeSlash] is false. */
private fun awsUriEncode(input: String, encodeSlash: Boolean): String = buildString {
    for (b in input.encodeToByteArray()) {
        val value = b.toInt() and 0xFF
        val ch = value.toChar()
        val unreserved = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
            ch == '-' || ch == '_' || ch == '.' || ch == '~'
        when {
            unreserved -> append(ch)
            ch == '/' && !encodeSlash -> append(ch)
            else -> append('%').append("%02X".format(value))
        }
    }
}
