package io.nekohasekai.sfa

import io.nekohasekai.sfa.bg.ConfigOrchestrator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigUnitTest {

    @Test
    fun testBase64SubscriptionOrchestration() {
        val base64Subscription = "aHlzdGVyaWEyOi8vMTRkNGM5NWQtMmExMy00ODZhLWFiMmQtOTdmNzcxNzMzNzAzQGNjZ2xhMy44OTU4OTYueHl6OjE5ODQyP3NuaT1jY2dsYTMuODk1ODk2Lnh5eiZzZWN1cml0eT10bHMmaW5zZWN1cmU9MCMlNUJIeTIlNUQlRTclQkUlOEUlRTUlOUIlQkQlNUIxLjV4JTVELSVFOSVBQiU5OCVFNyVBQiVBRkExDQpoeXN0ZXJpYTI6Ly8xNGQ0Yzk1ZC0yYTEzLTQ4NmEtYWIyZC05N2Y3NzE3MzM3MDNAY2NnbGE0Ljg5NTg5Ni54eXo6NDQzP3NuaT1jY2dsYTQuODk1ODk2Lnh5eiZzZWN1cml0eT10bHMmb2Jmcz1zYWxhbWFuZGVyJm9iZnMtcGFzc3dvcmQ9NlJ6NnFTT29vS2NlTXJzWCZpbnNlY3VyZT0wIyU1Qkh5MiU1RCVFNyVCRSU4RSVFNSU5QiVCRCU1QjEuNHglNUQtJUU5JUFCJTk4JUU3JUFCJUFGQTINCmh5c3RlcmlhMjovLzE0ZDRjOTVkLTJhMTMtNDg2YS1hYjJkLTk3Zjc3MTczMzcwM0BjY2dsYTEuODk1ODk2Lnh5ejoyMDc3MD9zbmk9Y2NnbGExLjg5NTg5Ni54eXomc2VjdXJpdHk9dGxzJmluc2VjdXJlPTAjJTVCSHkyJTVEJUU3JUJFJThFJUU1JTlCJUJEJTVCMS40eCU1RC0lRTklQUIlOTglRTclQUIlQUZBMw0KaHlzdGVyaWEyOi8vMTRkNGM5NWQtMmExMy00ODZhLWFiMmQtOTdmNzcxNzMzNzAzQGNjZ2xhNS44OTU4OTYueHl6OjQ0Mz9zbmk9Y2NnbGE1Ljg5NTg5Ni54eXomc2VjdXJpdHk9dGxzJmluc2VjdXJlPTEjJTVCSHkyJTVEJUU3JUJFJThFJUU1JTlCJUJEJTVCMS40eCU1RC0lRTklQUIlOTglRTclQUIlQUZBNC0lRTUlQUQlOTUlRTUlOUIlQkQlNUIxLjR4JTVE"

        println("=== Original Base64 Subscription ===")
        println(base64Subscription)

        val orchestrated = ConfigOrchestrator.orchestrate(base64Subscription)

        println("\n=== Orchestrated Config from Subscription ===")
        println(orchestrated)

        val json = JSONObject(orchestrated)

        val clashApi = json.getJSONObject("experimental").getJSONObject("clash_api")
        assertEquals("127.0.0.1:9090", clashApi.getString("external_controller"))

        val outbounds = json.getJSONArray("outbounds")
        var hasProxySelector = false
        var hasAutoOutbound = false
        var proxyOutbounds: org.json.JSONArray? = null
        var autoOutbounds: org.json.JSONArray? = null
        
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.getJSONObject(i)
            when (outbound.getString("type")) {
                "selector" -> {
                    if (outbound.getString("tag") == "Proxy") {
                        hasProxySelector = true
                        proxyOutbounds = outbound.getJSONArray("outbounds")
                    }
                }
                "urltest" -> {
                    if (outbound.getString("tag") == "auto") {
                        hasAutoOutbound = true
                        autoOutbounds = outbound.getJSONArray("outbounds")
                    }
                }
            }
        }
        
        assertTrue("Should contain a selector outbound tagged Proxy", hasProxySelector)
        assertTrue("Should contain urltest outbound tagged auto", hasAutoOutbound)
        assertNotNull("Proxy selector outbounds should not be null", proxyOutbounds)
        assertTrue("Proxy selector outbounds should not be empty", proxyOutbounds!!.length() > 0)
        assertNotNull("Auto outbounds should not be null", autoOutbounds)
        assertTrue("Auto outbounds should not be empty", autoOutbounds!!.length() > 0)

        val hysteriaNodes = mutableListOf<JSONObject>()
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.getJSONObject(i)
            if (outbound.getString("type") == "hysteria2") {
                hysteriaNodes.add(outbound)
            }
        }
        assertEquals("Should parse 4 Hysteria2 nodes", 4, hysteriaNodes.size)

        val node1 = hysteriaNodes[0]
        assertEquals("[Hy2]美国[1.5x]-高端A1", node1.getString("tag"))
        assertEquals("ccgla3.895896.xyz", node1.getString("server"))
        assertEquals(19842, node1.getInt("server_port"))
        assertEquals("14d4c95d-2a13-486a-ab2d-97f771733703", node1.getString("password"))
        assertTrue(node1.getJSONObject("tls").getBoolean("enabled"))
        assertEquals("ccgla3.895896.xyz", node1.getJSONObject("tls").getString("server_name"))
        assertFalse(node1.getJSONObject("tls").getBoolean("insecure"))

        val route = json.getJSONObject("route")
        assertEquals("Proxy", route.getString("final"))

        val dns = json.getJSONObject("dns")
        assertTrue("DNS should contain servers", dns.has("servers"))
        assertTrue("DNS should contain rules", dns.has("rules"))
    }

    @Test
    fun testJsonConfigurationOrchestration() {
        val messyJsonConfig = """
        {
          "log": { "level": "debug" },
          "dns": {
            "servers": [
              { "tag": "dns_proxy", "type": "https", "server": "dns.google", "client": "invalid-field" }
            ],
            "rules": [
              { "outbound": "any", "server": "dns_proxy", "client": "invalid-field-again" }
            ]
          },
          "outbounds": [
            { "type": "vmess", "tag": "VMessNode", "server": "1.2.3.4", "uuid": "uuid-value" },
            { "type": "direct", "tag": "direct" },
            { "type": "tuic", "tag": "TUICNode", "server": "2.3.4.5" }
          ]
        }
        """.trimIndent()

        var validatorCalledCount = 0
        ConfigOrchestrator.configValidator = { config ->
            validatorCalledCount++
            val json = JSONObject(config)
            assertTrue("Config should have dns section", json.has("dns"))
            assertTrue("Config should have route section", json.has("route"))
            assertTrue("Config should have inbounds section", json.has("inbounds"))
            assertTrue("Config should have outbounds section", json.has("outbounds"))
        }

        val orchestrated = ConfigOrchestrator.orchestrate(messyJsonConfig)
        
        ConfigOrchestrator.configValidator = null

        println("=== Orchestrated Json Config ===")
        println(orchestrated)

        assertEquals(1, validatorCalledCount)

        val json = JSONObject(orchestrated)

        val dns = json.getJSONObject("dns")
        val servers = dns.getJSONArray("servers")
        val serverKey = if (servers.getJSONObject(0).has("address")) "address" else "server"
        assertTrue(servers.getJSONObject(0).getString(serverKey).contains("8.8.8.8"))

        val outbounds = json.getJSONArray("outbounds")
        var foundVMess = false
        var foundTuic = false
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.getJSONObject(i)
            val type = outbound.optString("type")
            if (type == "vmess") {
                foundVMess = true
                assertEquals("VMessNode", outbound.getString("tag"))
                assertEquals("1.2.3.4", outbound.getString("server"))
            } else if (type == "tuic") {
                foundTuic = true
                assertEquals("TUICNode", outbound.getString("tag"))
            }
        }
        assertTrue("VMess node should be extracted and injected", foundVMess)
        assertTrue("TUIC node should be extracted and injected", foundTuic)

        var hasProxySelector = false
        var hasAutoOutbound = false
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.getJSONObject(i)
            if (outbound.getString("type") == "selector" && outbound.getString("tag") == "Proxy") {
                hasProxySelector = true
                val groupNodes = outbound.getJSONArray("outbounds")
                var containsNodes = false
                for (j in 0 until groupNodes.length()) {
                    if (groupNodes.getString(j) == "VMessNode" || groupNodes.getString(j) == "TUICNode") {
                        containsNodes = true
                    }
                }
                assertTrue("Proxy selector should contain extracted nodes", containsNodes)
            } else if (outbound.getString("type") == "urltest" && outbound.getString("tag") == "auto") {
                hasAutoOutbound = true
            }
        }
        assertTrue("Should contain Proxy selector", hasProxySelector)
        assertTrue("Should contain auto urltest", hasAutoOutbound)

        val route = json.getJSONObject("route")
        val rules = route.getJSONArray("rules")
        assertTrue("Route rules should not be empty", rules.length() > 0)
        
        var foundDirectRule = false
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.optString("outbound") == "direct") {
                foundDirectRule = true
                break
            }
        }
        assertTrue("Should have direct outbound rule", foundDirectRule)
    }

    @Test
    fun testEmptyConfigReturnsTemplate() {
        val result = ConfigOrchestrator.orchestrate("")
        assertTrue("Empty config should return non-empty template", result.isNotEmpty())
        
        val json = JSONObject(result)
        assertTrue("Result should have DNS section", json.has("dns"))
        assertTrue("Result should have route section", json.has("route"))
        assertTrue("Result should have inbounds section", json.has("inbounds"))
        assertTrue("Result should have outbounds section", json.has("outbounds"))
    }

    @Test
    fun testVlessParsing() {
        val vlessUri = "vless://uuid-value@example.com:443?security=tls&sni=example.com&type=ws&path=%2Fws#TestNode"
        
        val orchestrated = ConfigOrchestrator.orchestrate(vlessUri)
        val json = JSONObject(orchestrated)
        val outbounds = json.getJSONArray("outbounds")
        
        var foundVless = false
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.getJSONObject(i)
            if (outbound.getString("type") == "vless") {
                foundVless = true
                assertEquals("TestNode", outbound.getString("tag"))
                assertEquals("example.com", outbound.getString("server"))
                assertEquals(443, outbound.getInt("server_port"))
                assertEquals("uuid-value", outbound.getString("uuid"))
                assertTrue(outbound.has("tls"))
                assertTrue(outbound.getJSONObject("tls").getBoolean("enabled"))
                assertEquals("example.com", outbound.getJSONObject("tls").getString("server_name"))
                assertTrue(outbound.has("transport"))
                assertEquals("ws", outbound.getJSONObject("transport").getString("type"))
                assertEquals("/ws", outbound.getJSONObject("transport").getString("path"))
            }
        }
        assertTrue("VLESS node should be parsed", foundVless)
    }

    @Test
    fun testDnsRulesMigrationAndCacheRemoval() {
        ConfigOrchestrator.singBoxVersion = "1.12.0"
        val messyJsonConfig = """
        {
          "dns": {
            "servers": [
              { "tag": "google", "type": "https", "server": "8.8.8.8", "server_port": 443, "path": "/dns-query" }
            ],
            "rules": [
              { "geosite": "cn", "server": "cn" },
              { "geoip": "cn", "server": "local" }
            ],
            "independent_cache": true
          },
          "outbounds": [
            { "type": "direct", "tag": "direct" }
          ]
        }
        """.trimIndent()

        val orchestrated = ConfigOrchestrator.orchestrate(messyJsonConfig)
        val json = JSONObject(orchestrated)
        val dns = json.getJSONObject("dns")

        // 验证 independent_cache 已被移除
        assertFalse("1.12.0+ should remove independent_cache", dns.has("independent_cache"))

        // 验证 dns.rules 中的 geosite 和 geoip 已被转换为 rule_set
        val rules = dns.getJSONArray("rules")
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.optString("server") == "cn") {
                assertEquals("geosite-cn", rule.getString("rule_set"))
                assertFalse(rule.has("geosite"))
            } else if (rule.optString("server") == "local") {
                assertEquals("geoip-cn", rule.getString("rule_set"))
                assertFalse(rule.has("geoip"))
            }
        }
    }

    @Test
    fun testRouteRulesContainDirectAndProxy() {
        val base64Subscription = "aHlzdGVyaWEyOi8vMTRkNGM5NWQtMmExMy00ODZhLWFiMmQtOTdmNzcxNzMzNzAzQGNjZ2xhMy44OTU4OTYueHl6OjE5ODQyP3NuaT1jY2dsYTMuODk1ODk2Lnh5eiZzZWN1cml0eT10bHMmaW5zZWN1cmU9MCMlNUJIeTIlNUQlRTclQkUlOEUlRTUlOUIlQkQlNUIxLjV4JTVELSVFOSVBQiU5OCVFNyVBQiVBRkEx"

        val orchestrated = ConfigOrchestrator.orchestrate(base64Subscription)
        println("=== Orchestrated Route Config ===")
        println(orchestrated)
        val json = JSONObject(orchestrated)
        
        val route = json.getJSONObject("route")
        assertTrue("Route should have rules", route.has("rules"))
        
        val rules = route.getJSONArray("rules")
        var hasIpIsPrivateRule = false
        var hasGeositeRule = false
        var hasDnsRule = false
        
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.has("ip_is_private")) {
                hasIpIsPrivateRule = true
                assertEquals("direct", rule.getString("outbound"))
            }
            if (rule.has("rule_set")) {
                hasGeositeRule = true
            }
            if (rule.has("protocol") && rule.getString("protocol") == "dns") {
                hasDnsRule = true
                assertEquals("dns-out", rule.getString("outbound"))
            }
        }
        
        assertTrue("Should have ip_is_private rule", hasIpIsPrivateRule)
        assertTrue("Should have geosite/geoip rule", hasGeositeRule)
        assertTrue("Should have DNS hijacking rule", hasDnsRule)
        assertEquals("Proxy", route.getString("final"))
    }
}