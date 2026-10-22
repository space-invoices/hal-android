package com.chatwithhal.sdk

import android.content.SharedPreferences
import java.util.UUID

/**
 * The visitor id this device chats as, per project.
 *
 * Owned natively rather than by the web view, so identify, track and set work
 * while the chat is closed. The chat page is handed the same id.
 */
internal class VisitorStore(private val preferences: SharedPreferences) {
    private fun key(projectId: String) = "hal_visitor_$projectId"

    @Synchronized
    fun visitorId(projectId: String): String {
        preferences.getString(key(projectId), null)?.takeIf { it.isNotEmpty() }?.let { return it }
        return newId().also { replace(it, projectId) }
    }

    /** Identify may resolve to a visitor the user already had elsewhere. */
    @Synchronized
    fun replace(visitorId: String, projectId: String) {
        preferences.edit().putString(key(projectId), visitorId).apply()
    }

    /** A fresh anonymous visitor, for logout. */
    @Synchronized
    fun reset(projectId: String): String = newId().also { replace(it, projectId) }

    private fun newId() = UUID.randomUUID().toString()
}
