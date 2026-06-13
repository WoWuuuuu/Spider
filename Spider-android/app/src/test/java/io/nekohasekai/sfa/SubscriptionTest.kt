package io.nekohasekai.sfa

import io.nekohasekai.sfa.bg.ConfigOrchestrator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class SubscriptionTest {

    private val TEST_SUBSCRIPTION_URL = "https://ddyun.org/dd/b359563fc34cae7ea089f44a3a61f1aa"

    @Test
    fun testSubscriptionUrlImport() {
        println("=== Testing Subscription URL: $TEST_SUBSCRIPTION_URL ===")
        
        // 下载订阅内容
        val subscriptionContent = downloadSubscription(TEST_SUBSCRIPTION_URL)
        println("Downloaded ${subscriptionContent.length} characters")
        
        assertNotNull("Subscription content should not be null", subscriptionContent)
        assertTrue("Subscription content should not be empty", subscriptionContent.isNotEmpty())

        // 解析订阅
        val orchestrated = ConfigOrchestrator.orchestrate(subscriptionContent)
        
        println("\n=== Orchestrated Config ===")
        println(orchestrated)

        val json = JSONObject(orchestrated)

        // 验证基本结构
        assertTrue("Config should have dns", json.has("dns"))
        assertTrue("Config should have inbounds", json.has("inbounds"))
        assertTrue("Config should have outbounds", json.has("outbounds"))
        assertTrue("Config should have route", json.has("route"))

        // 验证出站配置
        val outbounds = json.getJSONArray("outbounds")
        var proxyCount = 0
        var hasProxySelector = false
        var hasDirect = false
        var hasBlock = false
        
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.getJSONObject(i)
            val type = outbound.optString("type")
            val tag = outbound.optString("tag")
            
            when (type) {
                "hysteria2", "vmess", "vless", "trojan", "shadowsocks" -> {
                    proxyCount++
                    println("Found proxy node: $tag (type: $type)")
                }
                "selector" -> {
                    if (tag == "Proxy") hasProxySelector = true
                }
                "direct" -> hasDirect = true
                "block" -> hasBlock = true
            }
        }

        println("\n=== Analysis ===")
        println("Total proxy nodes: $proxyCount")
        println("Has Proxy selector: $hasProxySelector")
        println("Has direct outbound: $hasDirect")
        println("Has block outbound: $hasBlock")

        assertTrue("Should have at least 1 proxy node", proxyCount > 0)
        assertTrue("Should have Proxy selector", hasProxySelector)
        assertTrue("Should have direct outbound", hasDirect)
        assertTrue("Should have block outbound", hasBlock)

        // 验证路由规则
        val route = json.getJSONObject("route")
        assertEquals("Proxy", route.getString("final"))
        
        val rules = route.getJSONArray("rules")
        assertTrue("Should have route rules", rules.length() > 0)

        // 验证DNS配置
        val dns = json.getJSONObject("dns")
        val dnsServers = dns.getJSONArray("servers")
        assertTrue("Should have DNS servers", dnsServers.length() > 0)
    }

    @Test
    fun testRuleSubscriptions() {
        println("=== Testing Rule Subscriptions ===")
        
        val subscriptions = ConfigOrchestrator.getAvailableRuleSubscriptions()
        println("Available subscriptions: ${subscriptions.size}")
        
        subscriptions.forEach { 
            println("  - ${it.name} (${it.id})") 
        }

        // 测试AI规则订阅
        val aiSubscription = subscriptions.find { it.id == "ai-rules" }
        assertNotNull("AI rules subscription should exist", aiSubscription)
        
        if (aiSubscription != null) {
            println("\nAI rules URLs:")
            aiSubscription.urls.forEach { println("  - $it") }
        }

        // 测试国内应用规则
        val cnSubscription = subscriptions.find { it.id == "ios-script-cn" }
        assertNotNull("China rules subscription should exist", cnSubscription)
    }

    @Test
    fun testVersionCompatibility() {
        println("=== Testing Version Compatibility ===")
        
        // 测试1.12+格式
        ConfigOrchestrator.singBoxVersion = "1.12.0"
        val config12 = ConfigOrchestrator.orchestrate("hysteria2://test@example.com:443")
        val json12 = JSONObject(config12)
        
        val dns12 = json12.getJSONObject("dns")
        val server12 = dns12.getJSONArray("servers").getJSONObject(0)
        assertTrue("1.12+ should use address field", server12.has("address"))
        assertFalse("1.12+ should not use server field", server12.has("server"))
        
        println("1.12+ format test: PASSED")

        // 测试1.10格式
        ConfigOrchestrator.singBoxVersion = "1.10.0"
        val config10 = ConfigOrchestrator.orchestrate("hysteria2://test@example.com:443")
        val json10 = JSONObject(config10)
        
        val dns10 = json10.getJSONObject("dns")
        val server10 = dns10.getJSONArray("servers").getJSONObject(0)
        assertTrue("1.10 should use server field", server10.has("server"))
        
        println("1.10 format test: PASSED")
    }

    private fun downloadSubscription(url: String): String {
        return try {
            val connection = URL(url).openConnection()
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.getInputStream().bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            println("Failed to download subscription: ${e.message}")
            ""
        }
    }
}
