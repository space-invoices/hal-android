package com.chatwithhal.sdk

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** HAL refused a request, or it never got there. */
class HalChatException(message: String, val status: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * The widget's REST endpoints, called with the native visitor id.
 *
 * One thread, so requests run in the order they were made: an identify that
 * changes the visitor id lands before a track sent right after it.
 */
internal class HalApiClient(
    private val visitors: VisitorStore,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HalChat").apply { isDaemon = true }
    },
    private val fetcher: (url: String) -> Pair<Int, String> = ::get,
    private val transport: (url: String, body: JSONObject) -> Pair<Int, String> = ::post,
) {
    fun identify(user: HalUser, configuration: HalConfiguration, callback: (Throwable?) -> Unit) {
        if (!user.hasIdentifier) {
            callback(IllegalArgumentException("HalChat.identify needs a userId or an email"))
            return
        }
        submit(callback) {
            val visitorId = visitors.visitorId(configuration.projectId)
            val body = user.toJson()
                .put("project_id", configuration.projectId)
                .put("visitor_id", visitorId)
            val response = request("/api/widget/identify", body, configuration)
            // The server may resolve this user to the visitor they already are.
            val externalId = response.optJSONObject("visitor")?.optString("external_id").orEmpty()
            if (externalId.isNotEmpty() && externalId != visitorId) {
                visitors.replace(externalId, configuration.projectId)
            }
        }
    }

    fun set(properties: Map<String, Any?>, configuration: HalConfiguration, callback: (Throwable?) -> Unit) {
        val json = try {
            scalarMap(properties)
        } catch (error: IllegalArgumentException) {
            callback(error)
            return
        }
        submit(callback) {
            val body = JSONObject()
                .put("project_id", configuration.projectId)
                .put("visitor_id", visitors.visitorId(configuration.projectId))
                .put("properties", json)
            request("/api/widget/set-properties", body, configuration)
        }
    }

    fun track(name: String, value: Any?, configuration: HalConfiguration, callback: (Throwable?) -> Unit) {
        val jsonValue = try {
            scalarValue(value)
        } catch (error: IllegalArgumentException) {
            callback(error)
            return
        }
        submit(callback) {
            val body = JSONObject()
                .put("project_id", configuration.projectId)
                .put("visitor_id", visitors.visitorId(configuration.projectId))
                .put("name", name)
                .put("value", jsonValue)
                .put("entity", "visitor")
            request("/api/widget/track", body, configuration)
        }
    }

    /**
     * Agent replies this visitor hasn't seen; `null` when the request failed.
     * Queued behind writes, so a count asked for right after identify is for
     * the identified visitor.
     */
    fun unreadCount(configuration: HalConfiguration, callback: (Int?) -> Unit) {
        executor.execute {
            val count = try {
                val visitorId = visitors.visitorId(configuration.projectId)
                fetch("/api/widget/unread", mapOf("project_id" to configuration.projectId, "visitor_id" to visitorId), configuration)
                    .optInt("unread_count", 0)
            } catch (error: Exception) {
                Log.w("HalChat", "Unread count failed: ${error.message}")
                null
            }
            callback(count)
        }
    }

    /** The project's widget settings (`primaryColor`, `position`, …); `null` when the request failed. */
    fun widgetConfig(configuration: HalConfiguration, callback: (JSONObject?) -> Unit) {
        executor.execute {
            val config = try {
                fetch("/api/widget/config", mapOf("project_id" to configuration.projectId), configuration)
                    .optJSONObject("config") ?: JSONObject()
            } catch (error: Exception) {
                Log.w("HalChat", "Widget config failed: ${error.message}")
                null
            }
            callback(config)
        }
    }

    /** After every write already queued, so a logout cannot reassign them. */
    fun afterPending(action: () -> Unit) {
        executor.execute(action)
    }

    private fun submit(callback: (Throwable?) -> Unit, work: () -> Unit) {
        executor.execute {
            val error = try {
                work()
                null
            } catch (error: Throwable) {
                error
            }
            callback(error)
        }
    }

    private fun request(path: String, body: JSONObject, configuration: HalConfiguration): JSONObject =
        parse(configuration.baseUrl + path) { transport(it, body) }

    private fun fetch(path: String, query: Map<String, String>, configuration: HalConfiguration): JSONObject {
        val queryString = query.entries.joinToString("&") { (name, value) ->
            "$name=${URLEncoder.encode(value, "UTF-8").replace("+", "%20")}"
        }
        return parse("${configuration.baseUrl}$path?$queryString", fetcher)
    }

    private fun parse(url: String, send: (String) -> Pair<Int, String>): JSONObject {
        val (status, text) = try {
            send(url)
        } catch (error: IOException) {
            throw HalChatException("HAL request failed: ${error.message}", cause = error)
        }
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject()
        }
        if (status !in 200..299) {
            val reason = json.optString("error").takeIf { it.isNotEmpty() }
            throw HalChatException("HAL request failed ($status)${reason?.let { ": $it" } ?: ""}", status)
        }
        return json
    }

    private companion object {
        fun get(url: String): Pair<Int, String> {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                return status to text
            } finally {
                connection.disconnect()
            }
        }

        fun post(url: String, body: JSONObject): Pair<Int, String> {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                return status to text
            } finally {
                connection.disconnect()
            }
        }
    }
}
