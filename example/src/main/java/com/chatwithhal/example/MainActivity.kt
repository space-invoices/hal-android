package com.chatwithhal.example

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.chatwithhal.sdk.HalChat
import com.chatwithhal.sdk.HalUnreadCountListener
import com.chatwithhal.sdk.HalUser

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var openChat: Button

    // Your own button, with the unread count in its label.
    private val unreadListener = HalUnreadCountListener { count ->
        openChat.text = if (count > 0) "Open chat ($count unread)" else "Open chat"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        status = TextView(this)

        openChat = button("Open chat") { HalChat.open(this) }
        layout.addView(openChat)
        layout.addView(button("Ask about upgrading") { HalChat.open(this, message = "I want to upgrade") })
        layout.addView(button("Log in as Ada") {
            HalChat.identify(HalUser(userId = "ada-1", email = "ada@example.com", name = "Ada Lovelace")) { error ->
                status.text = error?.let { "Identify failed: ${it.message}" } ?: "Identified as Ada"
            }
        })
        layout.addView(button("Track trial-started") {
            HalChat.track("trial-started") { error ->
                status.text = error?.let { "Track failed: ${it.message}" } ?: "Tracked"
            }
        })
        layout.addView(button("Log out") { HalChat.reset { status.text = "Logged out" } })
        layout.addView(status)
        // Android 15 draws edge to edge: keep the buttons below the app bar.
        layout.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(view.paddingLeft, insets.systemWindowInsetTop + view.paddingLeft, view.paddingRight, view.paddingBottom)
            insets
        }
        setContentView(layout)
        // Or the drop-in launcher: a floating button styled like the website widget's.
        HalChat.showLauncher(this)
    }

    override fun onStart() {
        super.onStart()
        HalChat.addUnreadCountListener(unreadListener)
    }

    override fun onStop() {
        HalChat.removeUnreadCountListener(unreadListener)
        super.onStop()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }
}
