package com.chatwithhal.sdk

import org.json.JSONException
import org.json.JSONObject

/** A message from the widget, posted on `HalChatAndroid.postMessage` as JSON. */
internal sealed class BridgeEvent {
    object Ready : BridgeEvent()
    object Close : BridgeEvent()
    data class Error(val message: String) : BridgeEvent()

    companion object {
        fun parse(json: String): BridgeEvent? = try {
            val event = JSONObject(json)
            when (event.optString("type")) {
                "ready" -> Ready
                "close" -> Close
                "error" -> Error(event.optString("message", "Unknown error"))
                else -> null
            }
        } catch (_: JSONException) {
            null
        }
    }
}
