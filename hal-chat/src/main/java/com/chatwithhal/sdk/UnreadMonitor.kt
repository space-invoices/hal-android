package com.chatwithhal.sdk

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** Hears the unread count change. Runs on the main thread. */
fun interface HalUnreadCountListener {
    fun onUnreadCountChanged(count: Int)
}

/**
 * Keeps [HalChat.unreadCount] current.
 *
 * Only asks the server while someone is listening (a launcher on screen, or a
 * [HalUnreadCountListener]), an activity of the app is started and the chat is
 * closed. The chat marks replies read itself, and an app that never shows a
 * badge costs no requests. Main thread only.
 */
internal object UnreadMonitor {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<HalUnreadCountListener>()
    private var chatsOnScreen = 0
    private var lifecycleRegistered = false

    /** Bumped when the visitor changes, so a count fetched for the old one is dropped. */
    private var generation = 0

    var count = 0
        private set

    val isChatOnScreen: Boolean get() = chatsOnScreen > 0

    /**
     * Asked of the system each time rather than counted from lifecycle
     * callbacks: the SDK may be configured after an activity already started,
     * and a count that missed one start never returns to 0.
     */
    private val isForeground: Boolean
        get() {
            val process = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(process)
            return process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        }

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            schedulePoll()
        }
    }

    fun register(application: Application) {
        if (lifecycleRegistered) return
        lifecycleRegistered = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            // Back in front: check now, and restart polling. A stop needs
            // nothing: the next tick sees the app in the background and ends.
            override fun onActivityStarted(activity: Activity) {
                if (!polling) {
                    refresh()
                    schedulePoll()
                }
            }

            override fun onActivityStopped(activity: Activity) {}

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun addListener(listener: HalUnreadCountListener) {
        if (!listeners.add(listener)) return
        listener.onUnreadCountChanged(count)
        if (listeners.size == 1) refresh()
        schedulePoll()
    }

    fun removeListener(listener: HalUnreadCountListener) {
        listeners.remove(listener)
        schedulePoll()
    }

    fun chatStarted() {
        chatsOnScreen += 1
        generation += 1
        schedulePoll()
        notifyListeners()
    }

    fun chatStopped() {
        chatsOnScreen = maxOf(0, chatsOnScreen - 1)
        schedulePoll()
        notifyListeners()
        // The widget's read receipt for what the visitor just saw may still be
        // in flight; give it a moment so the badge doesn't flash the old count.
        main.postDelayed({ refresh() }, 1_000)
    }

    /** Identify, reset or a new configuration: the old count is someone else's. */
    fun visitorChanged() {
        generation += 1
        set(0)
        refresh()
    }

    fun refresh() {
        if (listeners.isEmpty() || chatsOnScreen > 0 || !isForeground) return
        val (configuration, client) = HalChat.clientState() ?: return
        val requested = generation
        client.unreadCount(configuration) { result ->
            main.post {
                if (result != null && requested == generation && chatsOnScreen == 0) set(result)
            }
        }
    }

    private fun set(value: Int) {
        if (value == count) return
        count = value
        notifyListeners()
    }

    /**
     * Launchers also hear about the chat opening and closing (they hide while
     * it is up), so listeners are told even when the count is unchanged. The
     * public listener API filters those repeats out.
     */
    private fun notifyListeners() {
        listeners.toList().forEach { it.onUnreadCountChanged(count) }
    }

    private var polling = false

    private fun schedulePoll() {
        main.removeCallbacks(poll)
        val seconds = HalChat.configuration?.unreadRefreshSeconds ?: 0
        polling = listeners.isNotEmpty() && chatsOnScreen == 0 && seconds > 0 && isForeground
        if (polling) main.postDelayed(poll, seconds * 1_000)
    }
}
