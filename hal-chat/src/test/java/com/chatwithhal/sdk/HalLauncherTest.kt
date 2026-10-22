package com.chatwithhal.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HalLauncherTest {
    @Test
    fun styleFollowsTheWidgetSettings() {
        val style = LauncherStyle.from(
            JSONObject(
                """{"primaryColor":"#000000","position":"bottom-left","buttonSize":"small",
                   "buttonShape":"rounded","edgeOffset":32}""",
            ),
        )
        assertEquals(0xFF000000.toInt(), style.color)
        assertEquals(true, style.onLeft)
        assertEquals(48, style.sizeDp)
        assertEquals(12f, style.cornerRadiusDp)
        assertEquals(32, style.edgeOffsetDp)
        // Falls back to the horizontal offset, as the widget does.
        assertEquals(32, style.verticalEdgeOffsetDp)
        assertEquals(LauncherStyle.WHITE, style.foreground)
    }

    @Test
    fun missingOrMalformedSettingsKeepTheDefaults() {
        val style = LauncherStyle.from(JSONObject("""{"primaryColor":"teal","buttonShape":"hexagon","position":3}"""))
        assertEquals(LauncherStyle(), style)
        assertEquals(60, style.sizeDp)
        assertEquals(30f, style.cornerRadiusDp)
    }

    @Test
    fun hexColors() {
        assertEquals(0xFFFFFFFF.toInt(), LauncherStyle.parseHex("#fff"))
        assertEquals(0xFFFF0000.toInt(), LauncherStyle.parseHex("ff0000"))
        assertNull(LauncherStyle.parseHex("#12345"))
        assertNull(LauncherStyle.parseHex("#gggggg"))
    }

    @Test
    fun lightColorsGetDarkIcons() {
        assertEquals(LauncherStyle.BLACK, LauncherStyle(color = LauncherStyle.parseHex("#fde047")!!).foreground)
        assertEquals(LauncherStyle.WHITE, LauncherStyle().foreground)
    }

    @Test
    fun unreadCountAsksForThisVisitor() {
        val visitors = VisitorStore(FakePreferences())
        val urls = mutableListOf<String>()
        val client = HalApiClient(visitors, DirectExecutor(), { url ->
            urls += url
            200 to """{"unread_count":3}"""
        }) { _, _ -> throw AssertionError("no POST expected") }

        var count: Int? = null
        client.unreadCount(HalConfiguration("p 1", "http://localhost:8001/")) { count = it }

        assertEquals(3, count)
        assertEquals(
            "http://localhost:8001/api/widget/unread?project_id=p%201&visitor_id=${visitors.visitorId("p 1")}",
            urls.single(),
        )
    }

    @Test
    fun aFailedUnreadRequestIsNullNotZero() {
        val client = HalApiClient(VisitorStore(FakePreferences()), DirectExecutor(), { 500 to "" }) { _, _ ->
            throw AssertionError("no POST expected")
        }
        var count: Int? = 0
        client.unreadCount(HalConfiguration("p")) { count = it }
        assertNull(count)
    }
}
