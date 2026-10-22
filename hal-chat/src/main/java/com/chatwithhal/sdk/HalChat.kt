package com.chatwithhal.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Things the chat screen tells your app. Every method is optional; all run on the main thread. */
interface HalChatListener {
    fun onChatOpened() {}
    fun onChatClosed() {}
    fun onChatError(error: Throwable) {}
}

/**
 * HAL chat for Android.
 *
 * ```kotlin
 * HalChat.configure(context, "your-project-id")
 * HalChat.identify(HalUser(userId = user.id, email = user.email, name = user.name))
 * HalChat.open(context)
 * ```
 */
object HalChat {
    private const val TAG = "HalChat"
    private const val PREFERENCES = "com.chatwithhal.sdk"

    @Volatile
    private var state: State? = null

    private class State(
        val configuration: HalConfiguration,
        val visitors: VisitorStore,
        val client: HalApiClient,
    )

    private val main = Handler(Looper.getMainLooper())

    /** Receives open, close and error events from the chat screen. */
    @JvmStatic
    var listener: HalChatListener? = null

    /** The current configuration, or `null` before [configure]. */
    @JvmStatic
    val configuration: HalConfiguration? get() = state?.configuration

    /** Call once, from `Application.onCreate`. */
    @JvmStatic
    fun configure(context: Context, configuration: HalConfiguration) {
        val existing = state
        val visitors = existing?.visitors
            ?: VisitorStore(context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE))
        state = State(configuration, visitors, existing?.client ?: HalApiClient(visitors))
        main.post {
            (context.applicationContext as? Application)?.let(UnreadMonitor::register)
            if (existing?.configuration != configuration) {
                LauncherStyleStore.reset()
                UnreadMonitor.visitorChanged()
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun configure(
        context: Context,
        projectId: String,
        serverUrl: String = HalConfiguration.DEFAULT_SERVER_URL,
        locale: String? = null,
        theme: HalTheme? = null,
    ) = configure(context, HalConfiguration(projectId, serverUrl, locale, theme))

    /** The id this device chats as. Stable until [reset]. */
    @JvmStatic
    val visitorId: String? get() = state?.let { it.visitors.visitorId(it.configuration.projectId) }

    private fun requireState(): State =
        state ?: throw IllegalStateException("HalChat is not configured. Call HalChat.configure() first.")

    internal fun clientState(): Pair<HalConfiguration, HalApiClient>? = state?.let { it.configuration to it.client }

    internal fun chatUrl(): String? = state?.let { it.configuration.chatUrl(it.visitors.visitorId(it.configuration.projectId)) }

    // Identity and data. Callbacks run on the main thread with `null` on
    // success; without one, failures are logged.

    /** Link this device's conversations to a logged-in user. Call after login, and on launch when signed in. */
    @JvmStatic
    @JvmOverloads
    fun identify(user: HalUser, callback: ((Throwable?) -> Unit)? = null) {
        val current = requireState()
        val delivered = deliver(callback)
        current.client.identify(user, current.configuration) { error ->
            // Identify may move the device to the visitor the user already was.
            if (error == null) main.post { UnreadMonitor.refresh() }
            delivered(error)
        }
    }

    /** Set custom properties on the visitor and their contact. A `null` value deletes the key. */
    @JvmStatic
    @JvmOverloads
    fun set(properties: Map<String, Any?>, callback: ((Throwable?) -> Unit)? = null) {
        val current = requireState()
        current.client.set(properties, current.configuration, deliver(callback))
    }

    /** Record a product milestone, e.g. `"trial-started"`. */
    @JvmStatic
    @JvmOverloads
    fun track(name: String, value: Any? = null, callback: ((Throwable?) -> Unit)? = null) {
        val current = requireState()
        current.client.track(name, value, current.configuration, deliver(callback))
    }

    /**
     * Forget the user: call on logout. The next chat starts as a new, anonymous
     * visitor and the previous user's conversations are not shown.
     */
    @JvmStatic
    @JvmOverloads
    fun reset(callback: (() -> Unit)? = null) {
        val current = state ?: return
        current.client.afterPending {
            current.visitors.reset(current.configuration.projectId)
            main.post {
                UnreadMonitor.visitorChanged()
                callback?.invoke()
            }
        }
    }

    private fun deliver(callback: ((Throwable?) -> Unit)?): (Throwable?) -> Unit = { error ->
        if (callback == null) {
            if (error != null) Log.w(TAG, error.message ?: "HAL request failed", error)
        } else {
            main.post { callback(error) }
        }
    }

    // Chat screen

    /**
     * Show the chat full screen.
     *
     * @param message Opens a new conversation with this text in the composer.
     * @param article Opens a help article by slug.
     */
    @JvmStatic
    @JvmOverloads
    fun open(context: Context, message: String? = null, article: String? = null) {
        context.startActivity(intent(context, message, article))
    }

    /** The chat screen's intent, for apps that start activities themselves. */
    @JvmStatic
    @JvmOverloads
    fun intent(context: Context, message: String? = null, article: String? = null): Intent {
        requireState()
        return Intent(context, HalChatActivity::class.java).apply {
            message?.let { putExtra(HalChatActivity.EXTRA_MESSAGE, it) }
            article?.let { putExtra(HalChatActivity.EXTRA_ARTICLE, it) }
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // Unread count and launcher. Main thread only.

    private val publicListeners = mutableMapOf<HalUnreadCountListener, HalUnreadCountListener>()

    /**
     * Agent replies the user hasn't seen yet, across all their conversations.
     * Only kept current while something listens — see [addUnreadCountListener].
     */
    @JvmStatic
    val unreadCount: Int get() = UnreadMonitor.count

    /**
     * Calls [listener] now and whenever the unread count changes, for a badge
     * on your own chat button. The count is checked while a listener is added
     * and the app is in the foreground; remove it when the badge goes away.
     *
     * ```kotlin
     * HalChat.addUnreadCountListener { count -> badge.isVisible = count > 0 }
     * ```
     */
    @JvmStatic
    fun addUnreadCountListener(listener: HalUnreadCountListener) {
        if (listener in publicListeners) return
        var last: Int? = null
        val distinct = HalUnreadCountListener { count ->
            if (count != last) {
                last = count
                listener.onUnreadCountChanged(count)
            }
        }
        publicListeners[listener] = distinct
        UnreadMonitor.addListener(distinct)
    }

    @JvmStatic
    fun removeUnreadCountListener(listener: HalUnreadCountListener) {
        publicListeners.remove(listener)?.let(UnreadMonitor::removeListener)
    }

    /** Check the unread count now, e.g. after a push notification arrives. */
    @JvmStatic
    fun refreshUnreadCount() = UnreadMonitor.refresh()

    /**
     * Float a chat button over the bottom corner of [activity], on the side
     * and with the look set for the website widget. It shows the unread count
     * and opens the chat. Call it from `onCreate` of each activity that should
     * have one.
     *
     * @param bottomInsetDp Extra space below the button, e.g. for a bottom navigation bar.
     */
    @JvmStatic
    @JvmOverloads
    fun showLauncher(activity: Activity, bottomInsetDp: Int = 0) {
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        val existing = content.findViewWithTag<HalChatLauncherButton>(LAUNCHER_TAG)
        val launcher = existing ?: HalChatLauncherButton(activity).apply { tag = LAUNCHER_TAG }
        launcher.floating(bottomInsetDp)
        if (existing == null) content.addView(launcher) else launcher.bringToFront()
    }

    /** Remove the button [showLauncher] added. */
    @JvmStatic
    fun hideLauncher(activity: Activity) {
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        content.findViewWithTag<HalChatLauncherButton>(LAUNCHER_TAG)?.let(content::removeView)
    }

    private const val LAUNCHER_TAG = "com.chatwithhal.sdk.launcher"
}
