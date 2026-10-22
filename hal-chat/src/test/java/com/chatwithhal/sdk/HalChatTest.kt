package com.chatwithhal.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/** Runs work on the calling thread, so tests see results immediately. */
internal class DirectExecutor : AbstractExecutorService() {
    override fun execute(command: Runnable) = command.run()
    override fun shutdown() {}
    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
    override fun isShutdown() = false
    override fun isTerminated() = false
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}

class HalChatTest {
    @Test
    fun chatUrlCarriesProjectVisitorLocaleAndTheme() {
        val configuration = HalConfiguration("proj 1", "http://10.0.2.2:8001/", "de", HalTheme.DARK)
        assertEquals(
            "http://10.0.2.2:8001/mobile-sdk/chat?project_id=proj%201&visitor_id=v-1&locale=de&theme=dark",
            configuration.chatUrl("v-1"),
        )
    }

    @Test
    fun chatUrlOmitsUnsetOptions() {
        assertEquals(
            "https://api.chatwithhal.com/mobile-sdk/chat?project_id=p&visitor_id=v",
            HalConfiguration("p").chatUrl("v"),
        )
    }

    @Test
    fun bridgeEventsParse() {
        assertEquals(BridgeEvent.Ready, BridgeEvent.parse("""{"type":"ready"}"""))
        assertEquals(BridgeEvent.Close, BridgeEvent.parse("""{"type":"close"}"""))
        assertEquals(BridgeEvent.Error("nope"), BridgeEvent.parse("""{"type":"error","message":"nope"}"""))
        assertNull(BridgeEvent.parse("""{"type":"other"}"""))
        assertNull(BridgeEvent.parse("not json"))
    }

    @Test
    fun userSerialisesWithServerFieldNames() {
        val json = HalUser(
            userId = "u1",
            userHash = "hash",
            company = HalCompany(id = "c1", metadata = mapOf("seats" to 5)),
            metadata = mapOf("plan" to "pro", "gone" to null),
            stageUpdateMode = HalStageUpdateMode.EXACT,
        ).toJson()
        assertEquals("u1", json.getString("user_id"))
        assertEquals("hash", json.getString("user_hash"))
        assertEquals("exact", json.getString("stage_update_mode"))
        assertFalse("unset fields are left out", json.has("email"))
        assertEquals(JSONObject.NULL, json.getJSONObject("metadata").get("gone"))
        assertEquals(5, json.getJSONObject("company").getJSONObject("metadata").getInt("seats"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonScalarValuesAreRefused() {
        scalarMap(mapOf("nested" to listOf(1, 2)))
    }

    @Test
    fun identifyAdoptsTheVisitorTheServerResolvesTo() {
        val visitors = VisitorStore(FakePreferences())
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val client = HalApiClient(visitors, DirectExecutor()) { url, body ->
            calls += url to body
            200 to """{"visitor":{"external_id":"known-visitor"}}"""
        }
        val configuration = HalConfiguration("p", "http://localhost:8001")
        val anonymous = visitors.visitorId("p")

        var result: Throwable? = Exception("not called")
        client.identify(HalUser(email = "a@example.com"), configuration) { result = it }
        client.track("trial-started", 3, configuration) {}

        assertNull(result)
        assertEquals("http://localhost:8001/api/widget/identify", calls[0].first)
        assertEquals(anonymous, calls[0].second.getString("visitor_id"))
        assertEquals("known-visitor", visitors.visitorId("p"))
        // Later writes go to the visitor identify resolved to.
        assertEquals("known-visitor", calls[1].second.getString("visitor_id"))
        assertEquals(3, calls[1].second.getInt("value"))
    }

    @Test
    fun serverErrorsReachTheCallback() {
        val client = HalApiClient(VisitorStore(FakePreferences()), DirectExecutor()) { _, _ ->
            400 to """{"error":"Invalid project_id"}"""
        }
        var result: Throwable? = null
        client.set(mapOf("plan" to "pro"), HalConfiguration("p")) { result = it }
        assertNotNull(result)
        assertEquals(400, (result as HalChatException).status)
        assertTrue(result!!.message!!.contains("Invalid project_id"))
    }

    @Test
    fun identifyWithoutUserIdOrEmailFailsBeforeAnyRequest() {
        val client = HalApiClient(VisitorStore(FakePreferences()), DirectExecutor()) { _, _ ->
            throw AssertionError("no request expected")
        }
        var result: Throwable? = null
        client.identify(HalUser(name = "Nobody"), HalConfiguration("p")) { result = it }
        assertTrue(result is IllegalArgumentException)
    }

    @Test
    fun visitorIdIsStableUntilReset() {
        val store = VisitorStore(FakePreferences())
        val first = store.visitorId("p")
        assertEquals(first, store.visitorId("p"))
        val fresh = store.reset("p")
        assertTrue(fresh != first)
        assertEquals(fresh, store.visitorId("p"))
    }
}
