package io.nekohasekai.sfa.bg

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
private object Log {
    fun d(tag: String, msg: String) {
        try {
            android.util.Log.d(tag, msg)
        } catch (e: RuntimeException) {
            println("D/$tag: $msg")
        }
    }
    fun i(tag: String, msg: String) {
        try {
            android.util.Log.i(tag, msg)
        } catch (e: RuntimeException) {
            println("I/$tag: $msg")
        }
    }
    fun w(tag: String, msg: String) {
        try {
            android.util.Log.w(tag, msg)
        } catch (e: RuntimeException) {
            println("W/$tag: $msg")
        }
    }
    fun w(tag: String, msg: String, tr: Throwable?) {
        try {
            android.util.Log.w(tag, msg, tr)
        } catch (e: RuntimeException) {
            println("W/$tag: $msg")
            tr?.printStackTrace()
        }
    }
    fun e(tag: String, msg: String) {
        try {
            android.util.Log.e(tag, msg)
        } catch (e: RuntimeException) {
            println("E/$tag: $msg")
        }
    }
    fun e(tag: String, msg: String, tr: Throwable?) {
        try {
            android.util.Log.e(tag, msg, tr)
        } catch (e: RuntimeException) {
            println("E/$tag: $msg")
            tr?.printStackTrace()
        }
    }
}

/**
 * ConfigOrchestrator - Hiddify 风格的动态配置处理
 *
 * 配置处理流程：
 * 1. 输入检测（URI列表、Base64、Clash JSON、sing-box JSON）
 * 2. 协议解析（VMess、VLESS、Trojan、Shadowsocks、Hysteria2、TUIC、WireGuard）
 * 3. 配置转换（Clash → sing-box）
 * 4. 版本适配（1.10.x ↔ 1.12+）
 * 5. 配置验证和缓存
 */
object ConfigOrchestrator {
    private const val TAG = "ConfigOrchestrator"

    // 版本检测和验证回调
    var configValidator: ((String) -> Unit)? = null
    var singBoxVersion: String? = null

    // 配置缓存
    private val configCache = ConcurrentHashMap<String, String>()
    private const val CACHE_MAX_SIZE = 100

    // 支持的协议类型
    enum class ConfigFormat {
        URI_LIST,
        BASE64_ENCODED,
        CLASH_JSON,
        SINGBOX_JSON,
        UNKNOWN
    }

    // ========== 版本检测 ==========

    private data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            return patch.compareTo(other.patch)
        }
    }

    private fun parseVersion(versionStr: String?): Version {
        if (versionStr.isNullOrEmpty()) return Version(0, 0, 0)
        val parts = versionStr.split(".")
        try {
            val major = if (parts.isNotEmpty()) parts[0].toIntOrNull() ?: 0 else 0
            val minor = if (parts.size > 1) parts[1].replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0 else 0
            val patch = if (parts.size > 2) parts[2].replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0 else 0
            return Version(major, minor, patch)
        } catch (e: Exception) {
            Log.w(TAG, "[Version Parse] Failed to parse version: $versionStr")
            return Version(0, 0, 0)
        }
    }

    private fun ensureSingBoxVersion() {
        if (!singBoxVersion.isNullOrEmpty()) {
            Log.d(TAG, "[Version] singBoxVersion already set: $singBoxVersion")
            return
        }
        val detectedVersion = runCatching { Libbox.version() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        
        if (detectedVersion != null) {
            singBoxVersion = detectedVersion
            Log.i(TAG, "[Version] Successfully detected from Libbox.version(): '$detectedVersion'")
        } else {
            singBoxVersion = "1.12.0"
            Log.w(TAG, "[Version] Libbox.version() returned null/empty, defaulting to '1.12.0'")
        }
        Log.d(TAG, "[Version] Final singBoxVersion: '$singBoxVersion'")
    }

    private fun isVersionAtLeast(minVersion: String): Boolean {
        ensureSingBoxVersion()
        if (singBoxVersion.isNullOrEmpty()) {
            Log.w(TAG, "[Version Check] singBoxVersion unavailable, defaulting to new format (1.12+)")
            return true
        }
        val currentVersion = parseVersion(singBoxVersion)
        val minRequired = parseVersion(minVersion)
        val result = currentVersion >= minRequired
        Log.d(TAG, "[Version Check] Current: $singBoxVersion (parsed: $currentVersion), Min required: $minVersion (parsed: $minRequired), Result: $result")
        return result
    }

    // ========== 主入口 ==========

    /**
     * 处理配置字符串
     * @param configStr 原始配置输入（URI列表、Base64、Clash JSON、sing-box JSON）
     * @return 处理后的 sing-box 配置
     */
    fun orchestrate(configStr: String): String {
        Log.i(TAG, "================== ORCHESTRATE START ==================")
        Log.i(TAG, "[Orchestrate] Input config length: ${configStr.length} chars")
        
        ensureSingBoxVersion()
        Log.i(TAG, "[Orchestrate] singBoxVersion: '$singBoxVersion'")
        
        if (configStr.isBlank()) {
            Log.w(TAG, "[Orchestrate] Empty config, returning default")
            Log.i(TAG, "================== ORCHESTRATE END (Empty) ==================")
            return getDefaultConfig()
        }

        val trimmed = configStr.trim()
        val cacheKey = computeCacheKey(trimmed)

        // 检查缓存
        configCache[cacheKey]?.let {
            Log.d(TAG, "[Orchestrate] Returning cached config")
            Log.i(TAG, "================== ORCHESTRATE END (Cached) ==================")
            return it
        }

        return try {
            // 1. 检测格式
            val format = detectFormat(trimmed)
            Log.i(TAG, "[Orchestrate] Detected format: $format")

            // 2. 根据格式处理
            val result = when (format) {
                ConfigFormat.URI_LIST -> processUriList(trimmed)
                ConfigFormat.BASE64_ENCODED -> processBase64Config(trimmed)
                ConfigFormat.CLASH_JSON -> convertClashToSingBox(trimmed)
                ConfigFormat.SINGBOX_JSON -> enhanceConfig(trimmed)
                ConfigFormat.UNKNOWN -> processUriList(trimmed) // 尝试作为 URI 列表处理
            }

            // 3. 记录配置摘要（验证前）
            logConfigSummary(result)
            
            // 4. 验证并缓存
            validateAndCache(cacheKey, result)
            
            Log.i(TAG, "================== ORCHESTRATE END (Success) ==================")
            result

        } catch (e: Exception) {
            Log.e(TAG, "Orchestration failed", e)
            getDefaultConfig()
        }
    }

    // ========== 格式检测 ==========

    private fun detectFormat(input: String): ConfigFormat {
        val trimmed = input.trim()
        Log.i(TAG, "[Format Detect] Start detection, input length: ${trimmed.length}")
        Log.d(TAG, "[Format Detect] First 100 chars: ${trimmed.take(100)}...")

        // URI 列表检测
        if (trimmed.contains("://")) {
            Log.i(TAG, "[Format Detect] ✅ Condition met: contains '://' -> URI_LIST")
            return ConfigFormat.URI_LIST
        }
        Log.d(TAG, "[Format Detect] ❌ Does not contain '://'")

        // Base64 检测
        if (isValidBase64(trimmed)) {
            Log.d(TAG, "[Format Detect] ✅ Is valid Base64")
            val decoded = decodeBase64(trimmed)
            if (decoded.startsWith("{")) {
                Log.i(TAG, "[Format Detect] ✅ Base64 decoded to JSON -> CLASH_JSON")
                return ConfigFormat.CLASH_JSON
            }
            if (decoded.contains("://")) {
                Log.i(TAG, "[Format Detect] ✅ Base64 decoded to URI list -> BASE64_ENCODED")
                return ConfigFormat.BASE64_ENCODED
            }
            Log.i(TAG, "[Format Detect] ⚠️ Base64 decoded but not JSON/URI -> URI_LIST")
            return ConfigFormat.URI_LIST
        }
        Log.d(TAG, "[Format Detect] ❌ Not valid Base64")

        // JSON 检测
        if (trimmed.startsWith("{")) {
            Log.d(TAG, "[Format Detect] ✅ Starts with '{' -> JSON format")
            return when {
                isClashConfig(trimmed) -> {
                    Log.i(TAG, "[Format Detect] ✅ Detected as CLASH_JSON")
                    ConfigFormat.CLASH_JSON
                }
                isSingBoxConfig(trimmed) -> {
                    Log.i(TAG, "[Format Detect] ✅ Detected as SINGBOX_JSON")
                    ConfigFormat.SINGBOX_JSON
                }
                else -> {
                    Log.w(TAG, "[Format Detect] ⚠️ JSON but not Clash/SingBox -> UNKNOWN")
                    ConfigFormat.UNKNOWN
                }
            }
        }
        Log.d(TAG, "[Format Detect] ❌ Does not start with '{'")

        Log.w(TAG, "[Format Detect] ❌ All detection failed -> UNKNOWN")
        return ConfigFormat.UNKNOWN
    }

    private fun isClashConfig(content: String): Boolean {
        return try {
            val json = JSONObject(content)
            json.has("proxies") && !json.has("outbounds")
        } catch (e: Exception) {
            false
        }
    }

    private fun isSingBoxConfig(content: String): Boolean {
        return try {
            val json = JSONObject(content)
            json.has("outbounds")
        } catch (e: Exception) {
            false
        }
    }

    private fun isValidBase64(input: String): Boolean {
        if (input.length < 4) return false
        val cleaned = input.trim().replace("\\s".toRegex(), "")
        return Regex("^[A-Za-z0-9+/=]+$").matches(cleaned)
    }

    // ========== URI 列表处理 ==========

    private fun processUriList(uriStr: String): String {
        val uris = uriStr.split("\\r?\\n".toRegex())
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        if (uris.isEmpty()) {
            Log.w(TAG, "No URIs found")
            return getDefaultConfig()
        }

        val nodes = mutableListOf<JSONObject>()
        for (uri in uris) {
            parseUri(uri)?.let { nodes.add(it) }
        }

        if (nodes.isEmpty()) {
            Log.w(TAG, "No valid nodes parsed")
            return getDefaultConfig()
        }

        Log.d(TAG, "Parsed ${nodes.size} nodes from URI list")
        return buildConfigWithNodes(nodes)
    }

    private fun processBase64Config(base64Str: String): String {
        val decoded = decodeBase64(base64Str)
        if (decoded.isBlank()) {
            Log.w(TAG, "Empty decoded base64")
            return getDefaultConfig()
        }

        return if (decoded.trim().startsWith("{")) {
            when {
                isClashConfig(decoded) -> convertClashToSingBox(decoded)
                isSingBoxConfig(decoded) -> enhanceConfig(decoded)
                else -> processUriList(decoded)
            }
        } else {
            processUriList(decoded)
        }
    }

    // ========== URI 解析 ==========

    private fun parseUri(uri: String): JSONObject? {
        return try {
            when {
                uri.startsWith("vmess://", ignoreCase = true) -> parseVmess(uri)
                uri.startsWith("vless://", ignoreCase = true) -> parseVless(uri)
                uri.startsWith("trojan://", ignoreCase = true) -> parseTrojan(uri)
                uri.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(uri)
                uri.startsWith("hysteria2://", ignoreCase = true) -> parseHysteria2(uri)
                uri.startsWith("hy2://", ignoreCase = true) -> parseHysteria2(uri)
                uri.startsWith("tuic://", ignoreCase = true) -> parseTuic(uri)
                uri.startsWith("wireguard://", ignoreCase = true) -> parseWireguard(uri)
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse URI: ${uri.take(50)}...", e)
            null
        }
    }

    // VMess 解析
    private fun parseVmess(uri: String): JSONObject? {
        return try {
            val base64Part = uri.substring("vmess://".length)
            val decodedJson = decodeBase64(base64Part)
            val vmess = JSONObject(decodedJson)

            JSONObject().apply {
                put("type", "vmess")
                put("tag", vmess.optString("ps", "VMess"))
                put("server", vmess.optString("add"))
                put("server_port", vmess.optInt("port", 443))
                put("uuid", vmess.optString("id"))
                put("alter_id", vmess.optInt("aid", 0))
                put("security", vmess.optString("scy", "auto"))

                // TLS 配置
                if (vmess.optString("tls").equals("tls", ignoreCase = true)) {
                    put("tls", JSONObject().apply {
                        put("enabled", true)
                        val sni = vmess.optString("sni")
                        put("server_name", if (sni.isNotEmpty()) sni else vmess.optString("host"))
                        put("insecure", vmess.optString("allowInsecure").equals("1", ignoreCase = true))
                    })
                }

                // 传输协议
                val net = vmess.optString("net").lowercase(Locale.ROOT)
                if (net.isNotEmpty() && net != "tcp") {
                    put("transport", buildTransport(net, vmess))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse VMess URI", e)
            null
        }
    }

    // VLESS 解析
    private fun parseVless(uri: String): JSONObject? {
        return try {
            val parsedUri = URI(uri)
            val userInfo = parsedUri.userInfo ?: ""

            JSONObject().apply {
                put("type", "vless")
                put("tag", parsedUri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "VLESS")
                put("server", parsedUri.host)
                put("server_port", if (parsedUri.port != -1) parsedUri.port else 443)
                put("uuid", userInfo)

                // 解析查询参数
                val params = parseQuery(parsedUri.query)
                params["flow"]?.let { put("flow", it) }

                // TLS 配置
                val security = params["security"] ?: "none"
                if (security != "none") {
                    put("tls", buildVlessTls(params, parsedUri.host ?: ""))
                }

                // 传输协议
                val net = params["type"] ?: "tcp"
                if (net != "tcp") {
                    put("transport", buildVlessTransport(net, params))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse VLESS URI", e)
            null
        }
    }

    // Trojan 解析
    private fun parseTrojan(uri: String): JSONObject? {
        return try {
            val parsedUri = URI(uri)

            JSONObject().apply {
                put("type", "trojan")
                put("tag", parsedUri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "Trojan")
                put("server", parsedUri.host)
                put("server_port", if (parsedUri.port != -1) parsedUri.port else 443)
                put("password", parsedUri.userInfo)

                val params = parseQuery(parsedUri.query)
                if (params.isNotEmpty()) {
                    put("tls", JSONObject().apply {
                        put("enabled", true)
                        put("server_name", params["sni"] ?: params["peer"] ?: parsedUri.host)
                        put("insecure", params["insecure"] == "1")
                    })
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Trojan URI", e)
            null
        }
    }

    // Shadowsocks 解析
    private fun parseShadowsocks(uri: String): JSONObject? {
        return try {
            val parsedUri = URI(uri)
            val (method, password) = decodeSSCredentials(parsedUri)

            JSONObject().apply {
                put("type", "shadowsocks")
                put("tag", parsedUri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "SS")
                put("server", parsedUri.host)
                put("server_port", if (parsedUri.port != -1) parsedUri.port else 8388)
                put("method", method)
                put("password", password)

                // 插件
                val params = parseQuery(parsedUri.query)
                params["plugin"]?.let { pluginType ->
                    put("plugin", JSONObject().apply {
                        put("type", pluginType)
                        params["plugin_opts"]?.let {
                            put("options", JSONObject(parseQuery(it)))
                        }
                    })
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Shadowsocks URI", e)
            null
        }
    }

    // Hysteria2 解析
    private fun parseHysteria2(uri: String): JSONObject? {
        return try {
            val parsedUri = URI(uri)

            JSONObject().apply {
                put("type", "hysteria2")
                put("tag", parsedUri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "Hysteria2")
                put("server", parsedUri.host)
                put("server_port", if (parsedUri.port != -1) parsedUri.port else 443)
                put("password", URLDecoder.decode(parsedUri.userInfo ?: "", "UTF-8"))

                val params = parseQuery(parsedUri.query)

                // TLS 配置
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    params["sni"]?.let { put("server_name", it) }
                    put("insecure", params["insecure"] == "1")
                })

                // 混淆
                params["obfs"]?.takeIf { it != "none" }?.let { obfsType ->
                    put("obfs", JSONObject().apply {
                        put("type", obfsType)
                        params["obfs-password"]?.let { put("password", it) }
                    })
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Hysteria2 URI", e)
            null
        }
    }

    // TUIC 解析
    private fun parseTuic(uri: String): JSONObject? {
        return try {
            val parsedUri = URI(uri)

            JSONObject().apply {
                put("type", "tuic")
                put("tag", parsedUri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "TUIC")
                put("server", parsedUri.host)
                put("server_port", if (parsedUri.port != -1) parsedUri.port else 443)
                put("password", parsedUri.userInfo)

                val params = parseQuery(parsedUri.query)

                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", params["sni"] ?: parsedUri.host)
                    put("insecure", params["insecure"] == "1")
                })

                params["alpn"]?.let { put("alpn", JSONArray(it.split(","))) }
                params["congestion_control"]?.let { put("congestion_control", it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse TUIC URI", e)
            null
        }
    }

    // WireGuard 解析
    private fun parseWireguard(uri: String): JSONObject? {
        return try {
            val parsedUri = URI(uri)

            JSONObject().apply {
                put("type", "wireguard")
                put("tag", parsedUri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "WireGuard")
                put("server", parsedUri.host)
                put("server_port", if (parsedUri.port != -1) parsedUri.port else 51820)

                val params = parseQuery(parsedUri.query)
                params["public_key"]?.let { put("public_key", it) }
                params["preshared_key"]?.let { put("preshared_key", it) }
                params["local_address"]?.let { put("local_address", JSONArray(it.split(","))) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse WireGuard URI", e)
            null
        }
    }

    // ========== 辅助方法 ==========

    private fun buildTransport(net: String, config: JSONObject): JSONObject {
        return JSONObject().apply {
            put("type", if (net == "h2") "http" else net)
            when (net) {
                "ws" -> {
                    if (config.optString("path").isNotEmpty()) put("path", config.optString("path"))
                    if (config.optString("host").isNotEmpty()) {
                        put("headers", JSONObject(mapOf("Host" to config.optString("host"))))
                    }
                }
                "grpc" -> {
                    if (config.optString("path").isNotEmpty()) put("service_name", config.optString("path"))
                }
                "h2" -> {
                    if (config.optString("path").isNotEmpty()) put("path", config.optString("path"))
                    if (config.optString("host").isNotEmpty()) {
                        put("host", JSONArray(listOf(config.optString("host"))))
                    }
                }
            }
        }
    }

    private fun buildVlessTls(params: Map<String, String>, defaultServer: String): JSONObject {
        return JSONObject().apply {
            put("enabled", true)
            put("server_name", params["sni"] ?: params["peer"] ?: defaultServer)

            // Reality
            if (params["security"] == "reality") {
                put("reality", JSONObject().apply {
                    put("enabled", true)
                    put("public_key", params["pbk"] ?: "")
                    put("short_id", params["sid"] ?: "")
                })
            }

            // UTLS
            params["fp"]?.let {
                put("utls", JSONObject(mapOf("enabled" to true, "fingerprint" to it)))
            }

            put("insecure", params["insecure"] == "1")
        }
    }

    private fun buildVlessTransport(net: String, params: Map<String, String>): JSONObject {
        return JSONObject().apply {
            put("type", net)
            when (net) {
                "ws" -> {
                    put("path", params["path"] ?: "/")
                    params["host"]?.let { put("headers", JSONObject(mapOf("Host" to it))) }
                }
                "grpc" -> {
                    put("service_name", params["serviceName"] ?: "")
                }
                "http" -> {
                    put("path", params["path"] ?: "/")
                    params["host"]?.let { put("host", JSONArray(listOf(it))) }
                }
            }
        }
    }

    private fun decodeSSCredentials(uri: URI): Pair<String, String> {
        if (!uri.userInfo.isNullOrEmpty()) {
            val decoded = decodeBase64(uri.userInfo)
            val parts = decoded.split(":", limit = 2)
            return Pair(parts[0], parts.getOrElse(1) { "" })
        }

        // 尝试从 host 解析
        var dataStr = uri.host
        while (dataStr.length % 4 != 0) dataStr += "="
        val decoded = decodeBase64(dataStr)
        val atParts = decoded.split("@")
        if (atParts.size >= 2) {
            val userInfo = atParts[0].split(":", limit = 2)
            return Pair(userInfo[0], userInfo.getOrElse(1) { "" })
        }
        return Pair("", "")
    }

    private fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()
        return query.split("&").associate { pair ->
            val idx = pair.indexOf("=")
            if (idx > 0) {
                URLDecoder.decode(pair.substring(0, idx), "UTF-8") to URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            } else {
                pair to ""
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64(input: String): String {
        val sanitized = input.trim().replace("\\s+".toRegex(), "")
        if (sanitized.isEmpty()) return ""
        val padded = when (sanitized.length % 4) {
            2 -> "$sanitized=="
            3 -> "$sanitized="
            else -> sanitized
        }
        return try {
            String(Base64.Default.decode(padded), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            try {
                String(Base64.UrlSafe.decode(padded), StandardCharsets.UTF_8)
            } catch (ex: Exception) {
                try {
                    val cleaned = padded.replace(Regex("[^A-Za-z0-9+/=_-]"), "")
                    if (cleaned.contains("-") || cleaned.contains("_")) {
                        String(Base64.UrlSafe.decode(cleaned), StandardCharsets.UTF_8)
                    } else {
                        String(Base64.Default.decode(cleaned), StandardCharsets.UTF_8)
                    }
                } catch (e3: Exception) {
                    ""
                }
            }
        }
    }

    // ========== Clash 转换 ==========

    private fun convertClashToSingBox(clashContent: String): String {
        val clashConfig = JSONObject(clashContent)
        val singBoxConfig = JSONObject()

        // 日志配置
        singBoxConfig.put("log", JSONObject().apply {
            put("level", "info")
            put("timestamp", true)
        })

        // DNS 配置
        singBoxConfig.put("dns", buildDnsConfig())

        // 入站配置
        singBoxConfig.put("inbounds", buildInboundsConfig())

        // 出站配置
        val outbounds = JSONArray().apply {
            put(JSONObject(mapOf("type" to "direct", "tag" to "direct")))
            put(JSONObject(mapOf("type" to "block", "tag" to "block")))
            put(JSONObject(mapOf("type" to "dns", "tag" to "dns-out")))
        }

        val proxyTags = JSONArray()
        clashConfig.optJSONArray("proxies")?.let { proxies ->
            for (i in 0 until proxies.length()) {
                val proxy = proxies.optJSONObject(i) ?: continue
                convertClashProxy(proxy)?.let { singBoxNode ->
                    outbounds.put(singBoxNode)
                    proxyTags.put(singBoxNode.optString("tag"))
                }
            }
        }

        // Selector 出站
        outbounds.put(JSONObject().apply {
            put("type", "selector")
            put("tag", "Proxy")
            put("default", if (proxyTags.length() > 0) proxyTags.getString(0) else "direct")
            put("outbounds", proxyTags)
        })

        singBoxConfig.put("outbounds", outbounds)

        // 路由配置
        singBoxConfig.put("route", buildRouteConfig())

        // 版本适配
        migrateToVersion(singBoxConfig)

        return singBoxConfig.toString(2)
    }

    private fun convertClashProxy(clashProxy: JSONObject): JSONObject? {
        return try {
            val type = clashProxy.optString("type").lowercase(Locale.ROOT)
            val tag = clashProxy.optString("name", "Unknown")

            when (type) {
                "vmess" -> JSONObject().apply {
                    put("type", "vmess")
                    put("tag", tag)
                    put("server", clashProxy.optString("server"))
                    put("server_port", clashProxy.optInt("port"))
                    put("uuid", clashProxy.optString("uuid"))
                    put("alter_id", clashProxy.optInt("alterId", 0))
                    put("security", clashProxy.optString("cipher", "auto"))

                    if (clashProxy.optBoolean("tls")) {
                        put("tls", JSONObject().apply {
                            put("enabled", true)
                            put("server_name", clashProxy.optString("servername"))
                            put("insecure", clashProxy.optBoolean("skip-cert-verify"))
                        })
                    }

                    val net = clashProxy.optString("network", "tcp")
                    if (net != "tcp") {
                        put("transport", JSONObject().apply {
                            put("type", if (net == "h2") "http" else net)
                            when (net) {
                                "ws" -> {
                                    put("path", clashProxy.optString("ws-path", "/"))
                                    clashProxy.optString("ws-headers")?.let {
                                        put("headers", JSONObject(it))
                                    }
                                }
                                "grpc" -> {
                                    put("service_name", clashProxy.optString("grpc-service-name", ""))
                                }
                                "h2" -> {
                                    put("path", clashProxy.optString("h2-path", "/"))
                                }
                            }
                        })
                    }
                }
                "vless" -> JSONObject().apply {
                    put("type", "vless")
                    put("tag", tag)
                    put("server", clashProxy.optString("server"))
                    put("server_port", clashProxy.optInt("port"))
                    put("uuid", clashProxy.optString("uuid"))

                    if (clashProxy.optBoolean("tls")) {
                        put("tls", JSONObject().apply {
                            put("enabled", true)
                            put("server_name", clashProxy.optString("servername"))
                            put("insecure", clashProxy.optBoolean("skip-cert-verify"))
                        })
                    }

                    val net = clashProxy.optString("network", "tcp")
                    if (net != "tcp") {
                        put("transport", JSONObject().apply {
                            put("type", net)
                            when (net) {
                                "ws" -> put("path", clashProxy.optString("ws-path", "/"))
                                "grpc" -> put("service_name", clashProxy.optString("grpc-service-name", ""))
                            }
                        })
                    }
                }
                "trojan" -> JSONObject().apply {
                    put("type", "trojan")
                    put("tag", tag)
                    put("server", clashProxy.optString("server"))
                    put("server_port", clashProxy.optInt("port"))
                    put("password", clashProxy.optString("password"))

                    if (clashProxy.optBoolean("tls")) {
                        put("tls", JSONObject().apply {
                            put("enabled", true)
                            put("server_name", clashProxy.optString("servername"))
                            put("insecure", clashProxy.optBoolean("skip-cert-verify"))
                        })
                    }
                }
                "ss" -> JSONObject().apply {
                    put("type", "shadowsocks")
                    put("tag", tag)
                    put("server", clashProxy.optString("server"))
                    put("server_port", clashProxy.optInt("port"))
                    put("method", clashProxy.optString("cipher"))
                    put("password", clashProxy.optString("password"))
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to convert Clash proxy", e)
            null
        }
    }

    // ========== 配置构建 ==========

    private fun loadTemplateConfig(): String {
        return try {
            Application.application.assets.open("Spider-template.json").use { input ->
                InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                    reader.readText()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load Spider-template.json from assets, falling back to hardcoded template", e)
            getDefaultConfig()
        }
    }

    private fun buildConfigWithNodes(nodes: List<JSONObject>): String {
        if (nodes.isEmpty()) {
            return getDefaultConfig()
        }

        val config = try {
            JSONObject(loadTemplateConfig())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse template JSON, falling back to empty config", e)
            JSONObject(getDefaultConfig())
        }

        val templateOutbounds = config.optJSONArray("outbounds")
        val outbounds = JSONArray()
        if (templateOutbounds != null) {
            for (i in 0 until templateOutbounds.length()) {
                val outbound = templateOutbounds.optJSONObject(i) ?: continue
                val type = outbound.optString("type")
                val tag = outbound.optString("tag")
                if (type == "selector" && tag == "Proxy") continue
                if (type == "urltest" && tag == "auto") continue
                outbounds.put(outbound)
            }
        } else {
            outbounds.put(JSONObject(mapOf("type" to "direct", "tag" to "direct")))
            outbounds.put(JSONObject(mapOf("type" to "block", "tag" to "block")))
            outbounds.put(JSONObject(mapOf("type" to "dns", "tag" to "dns-out")))
        }

        val proxyTags = JSONArray()
        for (node in nodes) {
            val tag = node.optString("tag")
            if (tag.isNotEmpty()) {
                proxyTags.put(tag)
            }
            outbounds.put(node)
        }

        // URLTest + Selector
        if (proxyTags.length() > 1) {
            outbounds.put(JSONObject().apply {
                put("type", "urltest")
                put("tag", "auto")
                put("url", "https://www.gstatic.com/generate_204")
                put("interval", "3m")
                put("tolerance", 50)
                put("outbounds", proxyTags)
            })
        }

        outbounds.put(JSONObject().apply {
            put("type", "selector")
            put("tag", "Proxy")
            put("default", if (proxyTags.length() > 0) "auto" else "direct")
            val selectorOutbounds = JSONArray()
            if (proxyTags.length() > 1) {
                selectorOutbounds.put("auto")
            }
            for (i in 0 until proxyTags.length()) {
                selectorOutbounds.put(proxyTags.getString(i))
            }
            put("outbounds", selectorOutbounds)
        })

        config.put("outbounds", outbounds)

        // 版本适配
        migrateToVersion(config)

        // 替换路径占位符
        var result = config.toString(2)
        result = result.replace("\"GEOIP_PATH\"", "\"${getGeoIpPath().replace("\\", "\\\\")}\"")
        result = result.replace("\"GEOSITE_PATH\"", "\"${getGeoSitePath().replace("\\", "\\\\")}\"")

        return result
    }

    private fun enhanceConfig(configStr: String): String {
        return try {
            val config = JSONObject(configStr)

            val hasInbounds = config.has("inbounds")
            val hasRoute = config.has("route")

            if (!hasInbounds || !hasRoute) {
                val outbounds = config.optJSONArray("outbounds")
                val nodes = mutableListOf<JSONObject>()
                if (outbounds != null) {
                    val proxyTypes = setOf("vmess", "vless", "trojan", "shadowsocks", "hysteria2", "hy2", "tuic", "wireguard")
                    for (i in 0 until outbounds.length()) {
                        val outbound = outbounds.optJSONObject(i) ?: continue
                        val type = outbound.optString("type").lowercase(Locale.ROOT)
                        if (proxyTypes.contains(type)) {
                            nodes.add(outbound)
                        }
                    }
                }
                if (nodes.isNotEmpty()) {
                    return buildConfigWithNodes(nodes)
                }
            }

            if (!config.has("log")) {
                config.put("log", JSONObject().apply {
                    put("level", "info")
                    put("timestamp", true)
                })
            }

            migrateToVersion(config)
            config.toString(2)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enhance config", e)
            configStr
        }
    }

    private fun logConfigSummary(configStr: String) {
        try {
            val config = JSONObject(configStr)
            
            // 检查DNS配置
            val dns = config.optJSONObject("dns")
            if (dns != null) {
                val servers = dns.optJSONArray("servers")
                if (servers != null) {
                    Log.i(TAG, "[Config Summary] DNS servers count: ${servers.length()}")
                    for (i in 0 until servers.length()) {
                        val server = servers.optJSONObject(i)
                        if (server != null) {
                            val tag = server.optString("tag", "unknown")
                            val type = server.optString("type", "unknown")
                            val hasServer = server.has("server")
                            val hasAddress = server.has("address")
                            val address = server.optString("address", server.optString("server", "N/A"))
                            Log.i(TAG, "[Config Summary] DNS server $i: tag=$tag, type=$type, hasServer=$hasServer, hasAddress=$hasAddress, address=$address")
                            
                            if (hasServer && !hasAddress) {
                                Log.w(TAG, "[Config Summary] ⚠️ DNS server $i is using LEGACY format (server/server_port) instead of NEW format (address/port)")
                            } else if (hasAddress) {
                                Log.d(TAG, "[Config Summary] ✅ DNS server $i is using NEW format (address/port)")
                            }
                        }
                    }
                }
            }
            
            // 检查出站配置
            val outbounds = config.optJSONArray("outbounds")
            if (outbounds != null) {
                Log.i(TAG, "[Config Summary] Outbounds count: ${outbounds.length()}")
                for (i in 0 until outbounds.length()) {
                    val outbound = outbounds.optJSONObject(i)
                    if (outbound != null) {
                        val type = outbound.optString("type", "unknown")
                        val tag = outbound.optString("tag", "unknown")
                        val hasServer = outbound.has("server")
                        val hasAddress = outbound.has("address")
                        Log.d(TAG, "[Config Summary] Outbound $i: type=$type, tag=$tag, hasServer=$hasServer, hasAddress=$hasAddress")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "[Config Summary] Failed to parse config for logging", e)
        }
    }

    // ========== 版本适配 ==========

    private fun migrateToVersion(config: JSONObject) {
        migrateDnsFormat(config)
        if (isVersionAtLeast("1.12.0")) {
            migrateRouteRules(config)
            migrateDnsRules(config)
        }
        migrateOutbounds(config)
        removeUnsupportedFeatures(config)
    }

    private fun migrateDnsFormat(config: JSONObject) {
        Log.i(TAG, "[DNS Migrate] Starting DNS format migration")
        val dns = config.optJSONObject("dns")
        if (dns == null) {
            Log.w(TAG, "[DNS Migrate] ❌ Condition: dns object is null -> SKIP migration")
            return
        }
        Log.d(TAG, "[DNS Migrate] ✅ Condition: dns object exists")
        
        val servers = dns.optJSONArray("servers")
        if (servers == null) {
            Log.w(TAG, "[DNS Migrate] ❌ Condition: servers array is null -> SKIP migration")
            return
        }
        Log.d(TAG, "[DNS Migrate] ✅ Condition: servers array exists, count: ${servers.length()}")

        val useNewFormat = isVersionAtLeast("1.12.0")

        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            val tag = server.optString("tag", "unknown")
            Log.d(TAG, "[DNS Migrate] Processing server $i (tag: $tag)")

            if (useNewFormat) {
                // Migrate to new format (address as URL, no type/port/path/server fields)
                val type = server.optString("type", "").lowercase(Locale.ROOT)
                val host = if (server.has("address")) server.optString("address") else server.optString("server", "")
                
                if (host.isNotEmpty() && !host.contains("://")) {
                    val path = server.optString("path", "")
                    val port = if (server.has("port")) {
                        server.optInt("port")
                    } else if (server.has("server_port")) {
                        server.optInt("server_port")
                    } else {
                        when (type) {
                            "https" -> 443
                            "tls", "quic" -> 853
                            else -> 53
                        }
                    }

                    val newAddress = when (type) {
                        "https" -> {
                            val cleanPath = if (path.isEmpty()) "/dns-query" else path
                            if (port == 443) "https://$host$cleanPath" else "https://$host:$port$cleanPath"
                        }
                        "tls" -> {
                            if (port == 853) "tls://$host" else "tls://$host:$port"
                        }
                        "quic" -> {
                            if (port == 853) "quic://$host" else "quic://$host:$port"
                        }
                        "tcp" -> {
                            if (port == 53) "tcp://$host" else "tcp://$host:$port"
                        }
                        else -> {
                            // default is udp
                            if (port == 53) host else "udp://$host:$port"
                        }
                    }

                    server.put("address", newAddress)
                    
                    // Remove old fields
                    server.remove("type")
                    server.remove("port")
                    server.remove("server_port")
                    server.remove("server")
                    server.remove("path")
                    Log.i(TAG, "[DNS Migrate] ✅ Migrated DNS server to new format: '$newAddress'")
                }
            } else {
                // Migrate to old format (type, server, server_port, path)
                val addressValue = server.optString("address", server.optString("server", ""))
                if (addressValue.isNotEmpty()) {
                    if (addressValue.startsWith("http://") || addressValue.startsWith("https://") ||
                        addressValue.startsWith("tls://") || addressValue.startsWith("quic://") ||
                        addressValue.startsWith("udp://") || addressValue.startsWith("tcp://")) {
                        
                        parseUrlAddress(server, addressValue)
                    } else {
                        // Plain IP or host
                        if (!server.has("server")) {
                            server.put("server", addressValue)
                            server.remove("address")
                        }
                        if (!server.has("type")) {
                            server.put("type", "udp")
                        }
                        if (!server.has("server_port")) {
                            server.put("server_port", server.optInt("port", 53))
                            server.remove("port")
                        }
                    }
                }
            }
        }
        Log.i(TAG, "[DNS Migrate] DNS format migration completed")
    }

    private fun parseUrlAddress(server: JSONObject, urlString: String) {
        try {
            val uri = java.net.URI(urlString)
            val scheme = uri.scheme ?: "udp"
            val host = uri.host ?: urlString.substringAfter("://").substringBefore("/").substringBefore(":")
            val rawPort = uri.port
            val port = if (rawPort == -1) {
                when (scheme) {
                    "https" -> 443
                    "tls", "quic" -> 853
                    else -> 53
                }
            } else {
                rawPort
            }
            val path = uri.path ?: ""
            
            Log.i(TAG, "[DNS Migrate] ✅ Parsed URI: scheme=$scheme, host=$host, port=$port, path=$path")
            
            // For old format
            server.put("server", host)
            server.put("server_port", port)
            server.put("type", scheme)
            if (scheme == "https" && path.isNotEmpty() && path != "/") {
                server.put("path", path)
            } else {
                server.remove("path")
            }
            server.remove("address")
            server.remove("port")
            
            Log.i(TAG, "[DNS Migrate] ✅ DNS server updated: tag=${server.optString("tag", "unknown")}, type=$scheme, server=$host, server_port=$port")
            
        } catch (e: Exception) {
            Log.e(TAG, "[DNS Migrate] ❌ Failed to parse URL: '$urlString'", e)
        }
    }

    private fun migrateRouteRules(config: JSONObject) {
        val route = config.optJSONObject("route") ?: return
        val rules = route.optJSONArray("rules") ?: return
        
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val hasGeosite = rule.has("geosite")
            val hasGeoip = rule.has("geoip")
            if (hasGeosite) {
                val value = rule.optString("geosite")
                val ruleSetTag = "geosite-$value"
                ensureRuleSetExists(route, ruleSetTag, value, "geosite")
                rule.put("rule_set", ruleSetTag)
                rule.remove("geosite")
            }
            if (hasGeoip) {
                val value = rule.optString("geoip")
                val ruleSetTag = "geoip-$value"
                ensureRuleSetExists(route, ruleSetTag, value, "geoip")
                rule.put("rule_set", ruleSetTag)
                rule.remove("geoip")
            }
        }
    }

    private fun migrateDnsRules(config: JSONObject) {
        val dns = config.optJSONObject("dns") ?: return
        val rules = dns.optJSONArray("rules") ?: return
        val route = config.optJSONObject("route") ?: JSONObject().also { config.put("route", it) }
        
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val hasGeosite = rule.has("geosite")
            val hasGeoip = rule.has("geoip")
            if (hasGeosite) {
                val value = rule.optString("geosite")
                val ruleSetTag = "geosite-$value"
                ensureRuleSetExists(route, ruleSetTag, value, "geosite")
                rule.put("rule_set", ruleSetTag)
                rule.remove("geosite")
            }
            if (hasGeoip) {
                val value = rule.optString("geoip")
                val ruleSetTag = "geoip-$value"
                ensureRuleSetExists(route, ruleSetTag, value, "geoip")
                rule.put("rule_set", ruleSetTag)
                rule.remove("geoip")
            }
        }
    }

    private fun ensureRuleSetExists(route: JSONObject, tag: String, value: String, type: String) {
        val ruleSets = route.optJSONArray("rule_set") ?: JSONArray().also { route.put("rule_set", it) }
        
        for (i in 0 until ruleSets.length()) {
            val ruleSet = ruleSets.optJSONObject(i) ?: continue
            if (ruleSet.optString("tag") == tag) {
                return
            }
        }
        
        val path = if (type == "geoip") "GEOIP_PATH" else "GEOSITE_PATH"
        ruleSets.put(JSONObject().apply {
            put("tag", tag)
            put("type", "local")
            put("format", "binary")
            put("path", path)
        })
    }

    private fun migrateOutbounds(config: JSONObject) {
        val outbounds = config.optJSONArray("outbounds") ?: return

        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            migrateOutboundTls(outbound)
        }
    }

    private fun migrateOutboundTls(outbound: JSONObject) {
        val tls = outbound.optJSONObject("tls") ?: return

        // 移除新版本不支持的字段
        if (!isVersionAtLeast("1.12.0")) {
            tls.remove("reality")
            tls.remove("utls")
        }

        // 确保 enabled 字段存在
        if (!tls.has("enabled")) {
            tls.put("enabled", true)
        }
    }

    private fun removeUnsupportedFeatures(config: JSONObject) {
        if (!isVersionAtLeast("1.10.0")) {
            config.optJSONObject("dns")?.remove("fake_ip")
        }

        if (!isVersionAtLeast("1.12.0")) {
            config.optJSONObject("experimental")?.remove("clash_api")
        } else {
            config.optJSONObject("dns")?.remove("independent_cache")
        }
    }

    // ========== 配置模板 ==========

    private fun buildDnsConfig(): JSONObject {
        val useNewFormat = isVersionAtLeast("1.12.0")
        Log.d(TAG, "[DNS Config] Building DNS config with format: ${if (useNewFormat) "NEW URL format" else "OLD (server/server_port)"}")
        Log.d(TAG, "[DNS Config] singBoxVersion: ${singBoxVersion ?: "NOT SET"}")

        return JSONObject().apply {
            put("servers", JSONArray().apply {
                // Google DNS
                put(JSONObject().apply {
                    put("tag", "google")
                    if (useNewFormat) {
                        put("address", "https://8.8.8.8/dns-query")
                        Log.d(TAG, "[DNS Config] Google DNS using NEW URL format: https://8.8.8.8/dns-query")
                    } else {
                        put("type", "https")
                        put("server", "8.8.8.8")
                        put("server_port", 443)
                        put("path", "/dns-query")
                        Log.d(TAG, "[DNS Config] Google DNS using OLD format: server=8.8.8.8, server_port=443")
                    }
                })

                // Cloudflare DNS
                put(JSONObject().apply {
                    put("tag", "cloudflare")
                    if (useNewFormat) {
                        put("address", "https://1.1.1.1/dns-query")
                        Log.d(TAG, "[DNS Config] Cloudflare DNS using NEW URL format: https://1.1.1.1/dns-query")
                    } else {
                        put("type", "https")
                        put("server", "1.1.1.1")
                        put("server_port", 443)
                        put("path", "/dns-query")
                        Log.d(TAG, "[DNS Config] Cloudflare DNS using OLD format: server=1.1.1.1, server_port=443")
                    }
                })

                // 国内 DNS
                put(JSONObject().apply {
                    put("tag", "cn")
                    if (useNewFormat) {
                        put("address", "223.5.5.5")
                        Log.d(TAG, "[DNS Config] CN DNS using NEW format: address=223.5.5.5")
                    } else {
                        put("type", "udp")
                        put("server", "223.5.5.5")
                        Log.d(TAG, "[DNS Config] CN DNS using OLD format: server=223.5.5.5")
                    }
                })

                // 广告拦截
                put(JSONObject(mapOf("tag" to "block", "type" to "block")))
            })

            put("rules", JSONArray().apply {
                put(JSONObject(mapOf("geosite" to "cn", "server" to "cn")))
                put(JSONObject(mapOf("geoip" to "cn", "server" to "cn")))
                put(JSONObject(mapOf("geosite" to "category-ads", "server" to "block")))
                put(JSONObject(mapOf("geosite" to "category-ai", "server" to "google")))
                put(JSONObject(mapOf("type" to "default", "server" to "google")))
            })

            put("final", "google")
            put("strategy", "prefer_ipv4")

            if (isVersionAtLeast("1.10.0")) {
                put("fake_ip", JSONObject().apply {
                    put("enabled", true)
                    put("inet4_range", "198.18.0.0/15")
                })
            }
        }
    }

    private fun buildInboundsConfig(): JSONArray {
        return JSONArray().apply {
            put(JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put("interface_name", "tun0")
                put("mtu", 9000)
                put("stack", "system")
                put("auto_route", true)
                put("strict_route", true)
                put("sniff", true)
                put("sniff_override_destination", true)

                if (isVersionAtLeast("1.12.0")) {
                    put("address", JSONArray(listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126")))
                } else {
                    put("address", JSONArray(listOf("172.19.0.1/30")))
                }
            })
        }
    }

    private fun buildRouteConfig(): JSONObject {
        val useRuleSet = isVersionAtLeast("1.12.0")

        return JSONObject().apply {
            if (useRuleSet) {
                put("rule_set", JSONArray().apply {
                    put(JSONObject().apply {
                        put("tag", "geoip-cn")
                        put("type", "local")
                        put("format", "binary")
                        put("path", "GEOIP_PATH")
                    })
                    put(JSONObject().apply {
                        put("tag", "geosite-cn")
                        put("type", "local")
                        put("format", "binary")
                        put("path", "GEOSITE_PATH")
                    })
                })
            }

            put("rules", JSONArray().apply {
                put(JSONObject(mapOf("protocol" to "dns", "outbound" to "dns-out")))
                put(JSONObject(mapOf("ip_is_private" to true, "outbound" to "direct")))

                if (useRuleSet) {
                    put(JSONObject(mapOf("rule_set" to "geosite-cn", "outbound" to "direct")))
                    put(JSONObject(mapOf("rule_set" to "geoip-cn", "outbound" to "direct")))
                } else {
                    put(JSONObject(mapOf("geosite" to "cn", "outbound" to "direct")))
                    put(JSONObject(mapOf("geoip" to "cn", "outbound" to "direct")))
                }

                put(JSONObject(mapOf("geosite" to "category-ai", "outbound" to "Proxy")))
                put(JSONObject(mapOf("geosite" to "category-ads", "outbound" to "block")))
            })

            put("final", "Proxy")
            put("auto_detect_interface", true)
        }
    }

    private fun getDefaultConfig(): String {
        return try {
            val config = JSONObject(getRawDefaultConfig())
            migrateToVersion(config)
            config.toString(2)
        } catch (e: Exception) {
            getRawDefaultConfig()
        }
    }

    private fun getRawDefaultConfig(): String {
        val useNewFormat = isVersionAtLeast("1.12.0")

        val googleServer = if (useNewFormat) {
            """{"tag": "google", "address": "https://8.8.8.8/dns-query"}"""
        } else {
            """{"tag": "google", "type": "https", "server": "8.8.8.8", "server_port": 443, "path": "/dns-query"}"""
        }
        val cloudflareServer = if (useNewFormat) {
            """{"tag": "cloudflare", "address": "https://1.1.1.1/dns-query"}"""
        } else {
            """{"tag": "cloudflare", "type": "https", "server": "1.1.1.1", "server_port": 443, "path": "/dns-query"}"""
        }
        val cnServer = if (useNewFormat) {
            """{"tag": "cn", "address": "223.5.5.5"}"""
        } else {
            """{"tag": "cn", "type": "udp", "server": "223.5.5.5"}"""
        }

        return """
{
  "log": { "level": "info", "timestamp": true },
  "dns": {
    "servers": [
      $googleServer,
      $cloudflareServer,
      $cnServer,
      {"tag": "block", "type": "block"}
    ],
    "rules": [
      {"geosite": "cn", "server": "cn"},
      {"geoip": "cn", "server": "cn"},
      {"geosite": "category-ads", "server": "block"},
      {"geosite": "category-ai", "server": "google"},
      {"type": "default", "server": "google"}
    ],
    "final": "google",
    "strategy": "prefer_ipv4",
    "fake_ip": {
      "enabled": true,
      "inet4_range": "198.18.0.0/15"
    }
  },
  "inbounds": [
    {
      "type": "tun",
      "tag": "tun-in",
      "interface_name": "tun0",
      "mtu": 9000,
      "stack": "system",
      "auto_route": true,
      "strict_route": true,
      "sniff": true,
      "sniff_override_destination": true,
      "address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"]
    }
  ],
  "outbounds": [
    {"type": "direct", "tag": "direct"},
    {"type": "block", "tag": "block"},
    {"type": "dns", "tag": "dns-out"},
    {"type": "selector", "tag": "Proxy", "default": "direct", "outbounds": ["direct"]}
  ],
  "route": {
    "rules": [
      {"protocol": "dns", "outbound": "dns-out"},
      {"ip_is_private": true, "outbound": "direct"},
      {"geosite": "cn", "outbound": "direct"},
      {"geoip": "cn", "outbound": "direct"},
      {"geosite": "category-ai", "outbound": "Proxy"}
    ],
    "final": "Proxy",
    "auto_detect_interface": true
  },
  "experimental": {
    "clash_api": { "external_controller": "127.0.0.1:9090", "external_ui": "yacd", "secret": "" },
    "cache_file": { "enabled": true }
  }
}
        """.trimIndent()
    }

    // ========== 规则文件 ==========

    private fun getGeoIpPath(): String {
        return getRuleFilePath("geoip-cn.srs", "https://github.com/SagerNet/sing-geoip/releases/download/20240501/geoip-cn.srs")
    }

    private fun getGeoSitePath(): String {
        return getRuleFilePath("geosite-cn.srs", "https://github.com/SagerNet/sing-geosite/releases/download/20240501/geosite-cn.srs")
    }

    private fun getRuleFilePath(assetName: String, remoteUrl: String? = null): String {
        return try {
            val filesDir = Application.application.filesDir.absolutePath
            val file = java.io.File(filesDir, assetName)

            if (!file.exists()) {
                Application.application.assets.open(assetName).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }

            if (remoteUrl != null && shouldUpdateRuleFile(file)) {
                try {
                    downloadRuleFile(remoteUrl, file)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to download rule: $remoteUrl", e)
                }
            }

            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get rule file path", e)
            assetName
        }
    }

    private fun shouldUpdateRuleFile(file: java.io.File): Boolean {
        val now = System.currentTimeMillis()
        val updateInterval = 7 * 24 * 60 * 60 * 1000L // 7 days
        return now - file.lastModified() > updateInterval
    }

    private fun downloadRuleFile(url: String, destFile: java.io.File) {
        val connection = java.net.URL(url).openConnection()
        connection.connectTimeout = 10000
        connection.readTimeout = 30000
        connection.getInputStream().use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }
        Log.d(TAG, "Downloaded rule file: $url")
    }

    // ========== 缓存 ==========

    private fun computeCacheKey(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val keyString = "${singBoxVersion ?: ""}|$input"
            val hash = digest.digest(keyString.toByteArray(StandardCharsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }

    private fun validateAndCache(key: String, config: String) {
        try {
            // 检查 DNS 服务器配置
            try {
                val json = JSONObject(config)
                val dns = json.optJSONObject("dns")
                if (dns != null) {
                    val servers = dns.optJSONArray("servers")
                    if (servers != null) {
                        for (i in 0 until servers.length()) {
                            val server = servers.optJSONObject(i)
                            if (server != null) {
                                Log.d(TAG, "[Validate] DNS Server $i: ${server.toString()}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[Validate] Failed to parse config JSON", e)
            }
            
            Log.d(TAG, "[Validate] Config preview (first 2000 chars): ${config.take(2000)}")
            Log.d(TAG, "[Validate] libbox version: ${runCatching { Libbox.version() }.getOrNull()}")
            
            // 尝试验证，如果失败则跳过验证（可能存在版本兼容性问题）
            try {
                configValidator?.invoke(config)
            } catch (e: Exception) {
                Log.w(TAG, "[Validate] Config validation failed but will proceed anyway: ${e.message}")
                // 不返回，继续使用配置
            }
        } catch (e: Exception) {
            Log.w(TAG, "Config validation failed", e)
            return
        }

        if (configCache.size >= CACHE_MAX_SIZE) {
            configCache.clear()
        }
        configCache[key] = config
    }

    // ========== 规则订阅 ==========

    data class RuleSubscription(
        val id: String,
        val name: String,
        val urls: List<String>,
        val type: String,
        val updateInterval: Long = 7 * 24 * 60 * 60 * 1000L
    )

    private val defaultRuleSubscriptions = listOf(
        RuleSubscription(
            id = "ai-rules",
            name = "AI 分流规则",
            urls = listOf(
                "https://ruleset.skk.moe/List/non_ip/ai.conf",
                "https://gcore.jsdelivr.net/gh/SukkaW/Surge@master/dist/non_ip/ai.conf"
            ),
            type = "clash"
        ),
        RuleSubscription(
            id = "ios-script-cn",
            name = "国内应用规则",
            urls = listOf(
                "https://raw.githubusercontent.com/blackmatrix7/ios_rule_script/master/rule/Clash/China/China.list",
                "https://cdn.jsdelivr.net/gh/blackmatrix7/ios_rule_script@master/rule/Clash/China/China.list"
            ),
            type = "clash"
        ),
        RuleSubscription(
            id = "geoip-cn",
            name = "中国IP库",
            urls = listOf(
                "https://github.com/SagerNet/sing-geoip/releases/download/20240501/geoip-cn.srs",
                "https://cdn.jsdelivr.net/gh/SagerNet/sing-geoip@release/geoip-cn.srs"
            ),
            type = "singbox"
        ),
        RuleSubscription(
            id = "geosite-cn",
            name = "中国域名库",
            urls = listOf(
                "https://github.com/SagerNet/sing-geosite/releases/download/20240501/geosite-cn.srs",
                "https://cdn.jsdelivr.net/gh/SagerNet/sing-geosite@release/geosite-cn.srs"
            ),
            type = "singbox"
        )
    )

    fun getAvailableRuleSubscriptions(): List<RuleSubscription> = defaultRuleSubscriptions

    fun updateRuleSubscription(subscription: RuleSubscription): Boolean {
        return try {
            val filesDir = Application.application.filesDir.absolutePath
            val fileName = "${subscription.id}.conf"
            val file = java.io.File(filesDir, fileName)

            downloadRuleFileWithFallback(subscription.urls, file)
            Log.d(TAG, "Updated rule: ${subscription.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update rule: ${subscription.name}", e)
            false
        }
    }

    private fun downloadRuleFileWithFallback(urls: List<String>, destFile: java.io.File) {
        var lastException: Exception? = null
        for (url in urls) {
            try {
                downloadRuleFile(url, destFile)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download from $url", e)
                lastException = e
            }
        }
        throw lastException ?: Exception("No available sources")
    }

    fun getRuleSubscriptionPath(subscriptionId: String): String? {
        return try {
            val filesDir = Application.application.filesDir.absolutePath
            val fileName = "$subscriptionId.conf"
            val file = java.io.File(filesDir, fileName)

            if (!file.exists()) {
                defaultRuleSubscriptions.find { it.id == subscriptionId }?.let {
                    updateRuleSubscription(it)
                }
            }

            if (file.exists()) file.absolutePath else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get rule path: $subscriptionId", e)
            null
        }
    }
}
