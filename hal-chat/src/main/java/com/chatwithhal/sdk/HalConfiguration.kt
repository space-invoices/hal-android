package com.chatwithhal.sdk

import java.net.URLEncoder

/** The chat's color scheme. [SYSTEM] follows the device. */
enum class HalTheme(internal val value: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system"),
}

/**
 * What [HalChat.configure] was given.
 *
 * @property projectId The project id from HAL's dashboard (Settings › Install).
 * @property serverUrl Your HAL server. Only change this for self-hosted or local development.
 * @property locale Force a language (`"de"`, `"sl"`…). `null` uses the project's
 *   language settings and the device language.
 * @property theme `null` uses the theme set in the dashboard.
 * @property unreadRefreshSeconds How often the unread count is checked while
 *   something shows it (a launcher, or an unread-count listener) and the app is
 *   in the foreground. It is also checked when the app comes to the foreground
 *   and when the chat closes. `0` checks only then.
 */
data class HalConfiguration @JvmOverloads constructor(
    val projectId: String,
    val serverUrl: String = DEFAULT_SERVER_URL,
    val locale: String? = null,
    val theme: HalTheme? = null,
    val unreadRefreshSeconds: Long = 60,
) {
    internal val baseUrl: String get() = serverUrl.trimEnd('/')

    /** The page the chat screen loads. */
    internal fun chatUrl(visitorId: String): String {
        val query = buildList {
            add("project_id" to projectId)
            add("visitor_id" to visitorId)
            locale?.let { add("locale" to it) }
            theme?.let { add("theme" to it.value) }
        }.joinToString("&") { (name, value) -> "$name=${encode(value)}" }
        return "$baseUrl/mobile-sdk/chat?$query"
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://api.chatwithhal.com"

        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
