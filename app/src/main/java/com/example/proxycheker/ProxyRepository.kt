package com.example.proxycheker

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

private const val CACHE_FILE_TELEGRAM = "cached_telegram_proxies.txt"
private const val CACHE_FILE_OTHER = "cached_other_proxies.txt"
private const val PREFS_NAME = "proxycheker_prefs"
private const val PREF_LAST_MODE = "last_mode"
private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 10_000
private const val DOWNLOAD_READ_TIMEOUT_MS = 10_000
private const val CHECK_CONCURRENCY = 24

private val SOURCE_SPECS = listOf(
    ProxySource(
        group = ProxyGroup.TG_RU,
        url = "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/proxy_ru.txt"
    ),
    ProxySource(
        group = ProxyGroup.TG_EU,
        url = "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/proxy_eu.txt"
    ),
    ProxySource(
        group = ProxyGroup.TG_WORLD,
        url = "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/proxy_all.txt"
    ),
    ProxySource(
        group = ProxyGroup.VMESS,
        url = "https://raw.githubusercontent.com/barry-far/V2ray-config/main/Splitted-By-Protocol/vmess.txt"
    ),
    ProxySource(
        group = ProxyGroup.VLESS,
        url = "https://raw.githubusercontent.com/barry-far/V2ray-config/main/Splitted-By-Protocol/vless.txt"
    ),
    ProxySource(
        group = ProxyGroup.TROJAN,
        url = "https://raw.githubusercontent.com/barry-far/V2ray-config/main/Splitted-By-Protocol/trojan.txt"
    ),
    ProxySource(
        group = ProxyGroup.SHADOWSOCKS,
        url = "https://raw.githubusercontent.com/barry-far/V2ray-config/main/Splitted-By-Protocol/ss.txt"
    ),
)

val ENABLED_PROXY_GROUPS: Set<ProxyGroup> = SOURCE_SPECS.map { it.group }.toSet()

class ProxyRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun readCachedSelection(): CachedSelection? = withContext(Dispatchers.IO) {
        val mode = ProxyMode.TELEGRAM
        val proxies = readCachedProxies(mode)
        if (proxies.isNotEmpty()) {
            rememberLastMode(mode)
            return@withContext CachedSelection(mode = mode, proxies = proxies)
        }

        null
    }

    suspend fun downloadAndCacheProxies(mode: ProxyMode): List<ProxyItem> = withContext(Dispatchers.IO) {
        val proxies = coroutineScope {
            SOURCE_SPECS.filter { it.group.mode == mode }
                .map { source ->
                    async(Dispatchers.IO) {
                        parseProxyItems(
                            rawText = downloadText(source.url),
                            group = source.group
                        )
                    }
                }
                .awaitAll()
                .flatten()
        }

        require(proxies.isNotEmpty()) {
            "Источники загрузились, но в них не нашлось ни одной ссылки."
        }

        saveProxies(mode, proxies)
        rememberLastMode(mode)
        proxies
    }

    suspend fun keepReachableProxies(
        source: List<ProxyItem>,
        mode: ProxyMode,
        timeoutMs: Int,
        onProgress: (checked: Int, total: Int) -> Unit,
    ): List<ProxyItem> = coroutineScope {
        if (source.isEmpty()) {
            saveProxies(mode, emptyList())
            return@coroutineScope emptyList()
        }

        val counter = AtomicInteger(0)
        val gate = Semaphore(CHECK_CONCURRENCY)
        val total = source.size

        val working = source.map { proxy ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val latencyMs = if (proxy.server != null && proxy.port != null) {
                        measureConnectLatency(proxy.server, proxy.port, timeoutMs)
                    } else {
                        null
                    }
                    val checked = counter.incrementAndGet()

                    withContext(Dispatchers.Main) {
                        onProgress(checked, total)
                    }

                    proxy.takeIf { latencyMs != null }?.copy(latencyMs = latencyMs)
                }
            }
        }.awaitAll()
            .filterNotNull()
            .sortedWith(
                compareBy<ProxyItem>({ it.group.sortOrder }, { it.latencyMs ?: Long.MAX_VALUE })
            )

        saveProxies(mode, working)
        rememberLastMode(mode)
        working
    }

    suspend fun clearCache(mode: ProxyMode) = withContext(Dispatchers.IO) {
        cacheFile(mode).delete()
        if (readLastMode() == mode) {
            prefs.edit().remove(PREF_LAST_MODE).apply()
        }
    }

    suspend fun clearAllCaches() = withContext(Dispatchers.IO) {
        ProxyMode.entries.forEach { mode ->
            cacheFile(mode).delete()
        }
        prefs.edit().remove(PREF_LAST_MODE).apply()
    }

    suspend fun persistProxies(mode: ProxyMode, proxies: List<ProxyItem>) = withContext(Dispatchers.IO) {
        saveProxies(mode, proxies)
        rememberLastMode(mode)
    }

    private fun readCachedProxies(mode: ProxyMode): List<ProxyItem> {
        val file = cacheFile(mode)
        if (!file.exists()) {
            return emptyList()
        }

        return parseCachedProxies(file.readText())
            .filter { it.mode == mode && it.group in ENABLED_PROXY_GROUPS }
    }

    private fun saveProxies(mode: ProxyMode, proxies: List<ProxyItem>) {
        cacheFile(mode).writeText(
            proxies.joinToString(separator = "\n") { proxy ->
                listOf(
                    proxy.group.name,
                    proxy.latencyMs?.toString().orEmpty(),
                    proxy.url
                ).joinToString(separator = "\t")
            }
        )
    }

    private fun cacheFile(mode: ProxyMode): File {
        return File(
            appContext.filesDir,
            if (mode == ProxyMode.TELEGRAM) CACHE_FILE_TELEGRAM else CACHE_FILE_OTHER
        )
    }

    private fun rememberLastMode(mode: ProxyMode) {
        prefs.edit().putString(PREF_LAST_MODE, mode.name).apply()
    }

    private fun readLastMode(): ProxyMode? {
        val value = prefs.getString(PREF_LAST_MODE, null) ?: return null
        return ProxyMode.entries.firstOrNull { it.name == value }
    }

    private fun downloadText(sourceUrl: String): String {
        val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
            readTimeout = DOWNLOAD_READ_TIMEOUT_MS
        }

        connection.connect()
        val statusCode = connection.responseCode
        if (statusCode !in 200..299) {
            connection.disconnect()
            error("Не удалось загрузить файл. GitHub вернул код $statusCode.")
        }

        return connection.inputStream.bufferedReader().use { it.readText() }.also {
            connection.disconnect()
        }
    }

    private fun measureConnectLatency(server: String, port: Int, timeoutMs: Int): Long? {
        return runCatching {
            val startedAt = System.nanoTime()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(server, port), timeoutMs)
            }
            ((System.nanoTime() - startedAt) / 1_000_000).coerceAtLeast(1)
        }.getOrNull()
    }

    private fun parseProxyItems(rawText: String, group: ProxyGroup): List<ProxyItem> {
        val urls = when (group.mode) {
            ProxyMode.TELEGRAM -> parseTelegramUrls(rawText)
            ProxyMode.OTHER -> parseOtherUrls(rawText, group)
        }

        return urls
            .distinct()
            .map { url ->
                ProxyItem(
                    url = url,
                    group = group
                )
            }
    }

    private fun parseTelegramUrls(rawText: String): List<String> {
        val matches = TELEGRAM_URL_REGEX.findAll(rawText)
            .map { it.value.trim().trimEnd(',', ';') }
            .toList()

        return if (matches.isNotEmpty()) {
            matches
        } else {
            rawText.lineSequence()
                .map(String::trim)
                .filter { line ->
                    line.startsWith("tg://proxy", ignoreCase = true) ||
                        line.startsWith("https://t.me/proxy", ignoreCase = true)
                }
                .toList()
        }
    }

    private fun parseOtherUrls(rawText: String, group: ProxyGroup): List<String> {
        return rawText.lineSequence()
            .map(String::trim)
            .filter { line ->
                line.isNotBlank() &&
                    !line.startsWith("#") &&
                    group.schemePrefix != null &&
                    line.startsWith(group.schemePrefix, ignoreCase = true)
            }
            .toList()
    }

    private fun parseCachedProxies(rawText: String): List<ProxyItem> {
        return rawText.lineSequence()
            .mapNotNull(::parseCachedLine)
            .toList()
    }

    private fun parseCachedLine(rawLine: String): ProxyItem? {
        val line = rawLine.trim()
        if (line.isBlank()) {
            return null
        }

        val parts = line.split('\t', limit = 3)
        return when (parts.size) {
            1 -> inferGroupFromUrl(parts[0])?.let { group ->
                ProxyItem(
                    url = parts[0],
                    group = group
                )
            }

            3 -> {
                val group = ProxyGroup.entries.firstOrNull { it.name == parts[0] }
                    ?: inferGroupFromUrl(parts[2])
                    ?: return null

                ProxyItem(
                    url = parts[2],
                    group = group,
                    latencyMs = parts[1].toLongOrNull()
                )
            }

            else -> null
        }
    }
}

data class CachedSelection(
    val mode: ProxyMode,
    val proxies: List<ProxyItem>,
)

private data class ProxySource(
    val group: ProxyGroup,
    val url: String,
)

enum class ProxyMode {
    TELEGRAM,
    OTHER,
}

enum class ProxyGroup(
    val mode: ProxyMode,
    val sectionTitle: String,
    val schemePrefix: String?,
    val sortOrder: Int,
) {
    TG_RU(mode = ProxyMode.TELEGRAM, sectionTitle = "🇷🇺 TG RU", schemePrefix = null, sortOrder = 0),
    TG_EU(mode = ProxyMode.TELEGRAM, sectionTitle = "🇪🇺 TG EU", schemePrefix = null, sortOrder = 1),
    TG_WORLD(mode = ProxyMode.TELEGRAM, sectionTitle = "🌍 TG World", schemePrefix = null, sortOrder = 2),
    VMESS(mode = ProxyMode.OTHER, sectionTitle = "🔷 VMess", schemePrefix = "vmess://", sortOrder = 10),
    VLESS(mode = ProxyMode.OTHER, sectionTitle = "✨ VLESS", schemePrefix = "vless://", sortOrder = 11),
    TROJAN(mode = ProxyMode.OTHER, sectionTitle = "🛡 Trojan", schemePrefix = "trojan://", sortOrder = 12),
    SHADOWSOCKS(mode = ProxyMode.OTHER, sectionTitle = "🧦 ShadowSocks", schemePrefix = "ss://", sortOrder = 13),
    SHADOWSOCKS_R(mode = ProxyMode.OTHER, sectionTitle = "🌪 ShadowSocksR", schemePrefix = "ssr://", sortOrder = 14),
}

data class ProxyItem(
    val url: String,
    val group: ProxyGroup,
    val latencyMs: Long? = null,
) {
    private val parsed = parseProxyDetails(url, group)

    val mode: ProxyMode
        get() = group.mode
    val server: String? = parsed.host
    val port: Int? = parsed.port
    private val displayName: String? = parsed.displayName
    val uniqueKey: String
        get() = "${group.name}:$url"

    val endpointLabel: String
        get() = if (!server.isNullOrBlank() && port != null) {
            "$server:$port"
        } else {
            url.take(40).let { prefix ->
                if (prefix.length == url.length) prefix else "$prefix..."
            }
        }

    val shortLabel: String
        get() {
            val title = displayName
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { name ->
                    if (name.length <= 24) name else "${name.take(21)}..."
                }

            return listOfNotNull(endpointLabel, title).joinToString("  ")
        }

    val openUrl: String
        get() = when (mode) {
            ProxyMode.TELEGRAM -> buildTelegramWebUrl(url, server, port, parsed.secret)
            ProxyMode.OTHER -> url
        }
}

private data class ParsedProxyDetails(
    val host: String? = null,
    val port: Int? = null,
    val displayName: String? = null,
    val secret: String? = null,
)

private fun parseProxyDetails(url: String, group: ProxyGroup): ParsedProxyDetails {
    return when (group.mode) {
        ProxyMode.TELEGRAM -> parseTelegramDetails(url)
        ProxyMode.OTHER -> when (group) {
            ProxyGroup.VMESS -> parseVmessDetails(url)
            ProxyGroup.VLESS -> parseGenericUriDetails(url)
            ProxyGroup.TROJAN -> parseGenericUriDetails(url)
            ProxyGroup.SHADOWSOCKS -> parseShadowsocksDetails(url)
            ProxyGroup.SHADOWSOCKS_R -> parseShadowsocksRDetails(url)
            else -> ParsedProxyDetails()
        }
    }
}

private fun parseTelegramDetails(url: String): ParsedProxyDetails {
    val uri = Uri.parse(url)
    val server = uri.getQueryParameter("server")
    val port = uri.getQueryParameter("port")?.toIntOrNull()
    val secret = uri.getQueryParameter("secret")

    return ParsedProxyDetails(
        host = server,
        port = port,
        displayName = null,
        secret = secret
    )
}

private fun parseVmessDetails(url: String): ParsedProxyDetails {
    val payload = url.removePrefix("vmess://").trim()
    val decodedJson = decodeBase64Relaxed(payload) ?: return ParsedProxyDetails()
    val json = runCatching { JSONObject(decodedJson) }.getOrNull() ?: return ParsedProxyDetails()

    val host = json.optString("add").takeIf { it.isNotBlank() }
    val port = json.optString("port").toIntOrNull() ?: json.optInt("port").takeIf { it > 0 }
    val name = json.optString("ps").takeIf { it.isNotBlank() }

    return ParsedProxyDetails(host = host, port = port, displayName = name)
}

private fun parseGenericUriDetails(url: String): ParsedProxyDetails {
    val uri = Uri.parse(url)
    val fallback = parseHostAndPort(url.substringBefore('?').substringBefore('#').substringAfter("://"))
    val displayName = uri.fragment?.let(Uri::decode)?.takeIf { it.isNotBlank() }

    return ParsedProxyDetails(
        host = uri.host?.trim('[', ']') ?: fallback.first,
        port = uri.port.takeIf { it > 0 } ?: fallback.second,
        displayName = displayName
    )
}

private fun parseShadowsocksDetails(url: String): ParsedProxyDetails {
    val body = url.removePrefix("ss://")
        .substringBefore('#')
        .substringBefore('?')
        .trim()

    val hostPortText = when {
        '@' in body -> body.substringAfterLast('@')
        else -> decodeBase64Relaxed(body)?.substringAfterLast('@')
    }

    val (host, port) = parseHostAndPort(hostPortText)
    val displayName = url.substringAfter('#', "")
        .takeIf { it.isNotBlank() && it != url }
        ?.let(Uri::decode)

    return ParsedProxyDetails(host = host, port = port, displayName = displayName)
}

private fun parseShadowsocksRDetails(url: String): ParsedProxyDetails {
    val decoded = decodeBase64Relaxed(url.removePrefix("ssr://").trim()) ?: return ParsedProxyDetails()

    val server = decoded.substringBefore(':').takeIf { it.isNotBlank() }
    val port = decoded.substringAfter(':', "").substringBefore(':').toIntOrNull()
    val query = decoded.substringAfter("/?", "")
    val remarks = query.substringAfter("remarks=", "")
        .substringBefore('&')
        .takeIf { it.isNotBlank() }
        ?.let(Uri::decode)
        ?.let(::decodeBase64Relaxed)

    return ParsedProxyDetails(host = server, port = port, displayName = remarks)
}

private fun parseHostAndPort(rawValue: String?): Pair<String?, Int?> {
    val value = rawValue?.trim().orEmpty().trimEnd('/')
    if (value.isBlank()) {
        return null to null
    }

    val match = HOST_PORT_REGEX.find(value) ?: return null to null
    val host = match.groupValues[1].trim('[', ']')
    val port = match.groupValues[2].toIntOrNull()
    return host.takeIf { it.isNotBlank() } to port
}

private fun inferGroupFromUrl(url: String): ProxyGroup? {
    return when {
        url.startsWith("tg://proxy", ignoreCase = true) ||
            url.startsWith("https://t.me/proxy", ignoreCase = true) -> ProxyGroup.TG_WORLD

        else -> ProxyGroup.entries.firstOrNull { group ->
            group.schemePrefix != null && url.startsWith(group.schemePrefix, ignoreCase = true)
        }
    }
}

private fun decodeBase64Relaxed(rawValue: String): String? {
    val sanitized = rawValue
        .trim()
        .substringBefore('#')
        .replace('-', '+')
        .replace('_', '/')
        .let { value ->
            value + "=".repeat((4 - value.length % 4) % 4)
        }

    return runCatching {
        String(Base64.decode(sanitized, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()
}

private fun buildTelegramWebUrl(
    originalUrl: String,
    server: String?,
    port: Int?,
    secret: String?,
): String {
    if (originalUrl.startsWith("https://", ignoreCase = true)) {
        return originalUrl
    }

    val host = server ?: return originalUrl
    val portValue = port ?: return originalUrl
    val secretValue = secret ?: return originalUrl
    return "https://t.me/proxy?server=$host&port=$portValue&secret=$secretValue"
}

private val HOST_PORT_REGEX = Regex("""(?:[^@]+@)?(\[[^\]]+]|[^:/?#]+):(\d+)""")
private val TELEGRAM_URL_REGEX =
    Regex("""(?:tg://proxy\?[^\s]+|https://t\.me/proxy\?[^\s]+)""", RegexOption.IGNORE_CASE)
