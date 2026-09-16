package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.ui.topology.TopologyBox
import io.nekohasekai.sagernet.ui.topology.TopologyInbound
import io.nekohasekai.sagernet.ui.topology.TopologyLayout
import io.nekohasekai.sagernet.ui.topology.TopologyModelHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopologyTest {

    @Test
    fun testExtractMainDomain() {
        assertEquals("google", TopologyModelHelper.extractMainDomain("www.google.com"))
        assertEquals("google", TopologyModelHelper.extractMainDomain("sub.maps.google.com"))
        assertEquals("google", TopologyModelHelper.extractMainDomain("maps.google.co.jp"))
        assertEquals("google", TopologyModelHelper.extractMainDomain("google.com.cn"))
        assertEquals("github", TopologyModelHelper.extractMainDomain("api.github.com"))
        assertEquals("bilibili", TopologyModelHelper.extractMainDomain("bilibili.com"))
        assertEquals("192.168.1.1", TopologyModelHelper.extractMainDomain("192.168.1.1"))
        assertEquals("localhost", TopologyModelHelper.extractMainDomain("localhost"))
    }

    @Test
    fun testExtractRegion() {
        assertEquals("US", TopologyModelHelper.extractRegion("[US] Los Angeles 01"))
        assertEquals("HK", TopologyModelHelper.extractRegion("🇭🇰 香港 IPLC 01"))
        assertEquals("JP", TopologyModelHelper.extractRegion("JP-Tokyo-VIP"))
        assertEquals("SG", TopologyModelHelper.extractRegion("新加坡 02"))
        assertEquals("TW", TopologyModelHelper.extractRegion("🇹🇼 台湾 01"))
        assertEquals("直连", TopologyModelHelper.extractRegion("直连"))
    }

    @Test
    fun testInboundDisplayLabel() {
        val inbound = TopologyInbound(
            id = "in-test",
            label = "sub.google.com",
            proxyLabel = "US",
            endpoint = "sub.google.com:443",
            appPackage = "",
            upload = 100,
            download = 500,
            ruleCardId = null,
            ruleName = null,
            matchKind = io.nekohasekai.sagernet.ui.topology.RuleMatchKind.DOMAIN,
        )
        assertEquals("US·google", inbound.displayLabel)
    }

    @Test
    fun testPlaceScatteredZeroOverlap() {
        val unit = TopologyLayout.Unit(1f, 1f)
        val items = listOf(
            TopologyLayout.Item("1", w = 80f, h = 25f),
            TopologyLayout.Item("2", w = 60f, h = 18f),
            TopologyLayout.Item("3", w = 90f, h = 30f),
            TopologyLayout.Item("4", w = 70f, h = 22f),
            TopologyLayout.Item("5", w = 85f, h = 28f),
            TopologyLayout.Item("6", w = 65f, h = 20f),
            TopologyLayout.Item("7", w = 75f, h = 24f),
            TopologyLayout.Item("8", w = 95f, h = 32f),
        )
        val boxes = HashMap<String, TopologyBox>()
        TopologyLayout.placeScattered(
            items = items,
            x0 = 10f, x1 = 350f,
            y0 = 50f, y1 = 400f,
            h = 22f, gap = 8f,
            capLo = 2, capHi = 4,
            seed = 20260914L, layer = 1, unit = unit,
            out = boxes,
        )

        assertEquals(items.size, boxes.size)

        // Verify zero overlap between any two boxes
        val boxList = boxes.values.toList()
        for (i in 0 until boxList.size) {
            val a = boxList[i]
            val aLeft = a.cx - a.w / 2f
            val aRight = a.cx + a.w / 2f
            val aTop = a.cy - a.h / 2f
            val aBottom = a.cy + a.h / 2f

            for (j in i + 1 until boxList.size) {
                val b = boxList[j]
                val bLeft = b.cx - b.w / 2f
                val bRight = b.cx + b.w / 2f
                val bTop = b.cy - b.h / 2f
                val bBottom = b.cy + b.h / 2f

                val xOverlap = aLeft < bRight && aRight > bLeft
                val yOverlap = aTop < bBottom && aBottom > bTop
                val overlap = xOverlap && yOverlap
                assertTrue("Boxes ${a.id} and ${b.id} must not overlap!", !overlap)
            }
        }
    }
}
