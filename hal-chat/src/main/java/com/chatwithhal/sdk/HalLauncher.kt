package com.chatwithhal.sdk

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject

/**
 * How the launcher looks: the website widget's button settings from the
 * dashboard (Settings › Widget), so the app and the site match.
 */
internal data class LauncherStyle(
    val color: Int = DEFAULT_COLOR,
    val onLeft: Boolean = false,
    val small: Boolean = false,
    val shape: String = "circle",
    val edgeOffsetDp: Int = 20,
    val verticalEdgeOffsetDp: Int = 20,
) {
    val sizeDp: Int get() = if (small) 48 else 60
    val iconDp: Int get() = if (small) 22 else 28
    val cornerRadiusDp: Float
        get() = when (shape) {
            "rounded" -> 12f
            "square" -> 0f
            else -> sizeDp / 2f
        }

    /** Black or white, whichever reads on [color] (the widget's rule). */
    val foreground: Int
        get() {
            val luminance = 0.299 * ((color shr 16) and 0xFF) + 0.587 * ((color shr 8) and 0xFF) + 0.114 * (color and 0xFF)
            return if (luminance / 255 > 0.6) BLACK else WHITE
        }

    companion object {
        const val DEFAULT_COLOR = 0xFF6366F1.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()

        /** From `/api/widget/config`'s `config`. Anything missing or malformed keeps the default. */
        fun from(config: JSONObject): LauncherStyle {
            val defaults = LauncherStyle()
            val edge = config.optNumber("edgeOffset")?.toInt() ?: defaults.edgeOffsetDp
            return LauncherStyle(
                color = parseHex(config.optString("primaryColor")) ?: defaults.color,
                onLeft = config.optString("position") == "bottom-left",
                small = config.optString("buttonSize") == "small",
                shape = config.optString("buttonShape").takeIf { it in setOf("circle", "rounded", "square") }
                    ?: defaults.shape,
                edgeOffsetDp = edge,
                verticalEdgeOffsetDp = config.optNumber("verticalEdgeOffset")?.toInt() ?: edge,
            )
        }

        private fun JSONObject.optNumber(name: String): Number? = opt(name) as? Number

        /** `#rgb` or `#rrggbb`, as an opaque ARGB int. */
        fun parseHex(hex: String): Int? {
            var digits = hex.trim().removePrefix("#")
            if (digits.length == 3) digits = digits.map { "$it$it" }.joinToString("")
            if (digits.length != 6) return null
            val value = digits.toIntOrNull(16) ?: return null
            return value or 0xFF000000.toInt()
        }
    }
}

/** Loads the project's launcher style once per configuration. Main thread only. */
internal object LauncherStyleStore {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<(LauncherStyle) -> Unit>()
    private var loadedFor: HalConfiguration? = null
    private var loading = false

    var style = LauncherStyle()
        private set

    fun addListener(listener: (LauncherStyle) -> Unit) {
        listeners.add(listener)
        listener(style)
        load()
    }

    fun removeListener(listener: (LauncherStyle) -> Unit) {
        listeners.remove(listener)
    }

    /** A new configuration may be a different project. */
    fun reset() {
        loadedFor = null
    }

    private fun load() {
        val (configuration, client) = HalChat.clientState() ?: return
        if (configuration == loadedFor || loading) return
        loading = true
        client.widgetConfig(configuration) { config ->
            main.post {
                loading = false
                // Failed: keep the default and try again next time a launcher appears.
                if (config == null) return@post
                loadedFor = configuration
                val loaded = LauncherStyle.from(config)
                if (loaded != style) {
                    style = loaded
                    listeners.toList().forEach { it(loaded) }
                }
            }
        }
    }
}

/**
 * A chat button with the unread count on it, styled like the website widget's
 * launcher. Tapping it opens the chat.
 *
 * Put it in your layout, or let [HalChat.showLauncher] float one over an
 * activity. It hides itself while the chat is open.
 */
class HalChatLauncherButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    /** Runs on tap instead of opening the chat, if set. */
    var onTap: (() -> Unit)? = null

    private val circle = FrameLayout(context)
    private val icon = ImageView(context)
    private val badge = TextView(context)
    private var style = LauncherStyleStore.style
    private var floatingBottomInsetDp: Int? = null
    private var systemBottom = 0
    private var systemLeft = 0
    private var systemRight = 0

    private val unreadListener = HalUnreadCountListener { update(it) }
    private val styleListener: (LauncherStyle) -> Unit = { apply(it) }

    init {
        clipChildren = false
        clipToPadding = false
        // Room for the shadow, and for the badge to sit over the corner, without being clipped.
        setPadding(dp(MARGIN), dp(MARGIN), dp(MARGIN), dp(MARGIN))

        circle.elevation = dp(5).toFloat()
        circle.isClickable = true
        circle.isFocusable = true
        circle.contentDescription = context.getString(R.string.hal_chat_launcher)
        circle.setOnClickListener { tapped() }
        icon.setImageResource(R.drawable.hal_ic_chat)
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        circle.addView(icon)
        addView(circle)

        badge.setTextColor(Color.WHITE)
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        badge.typeface = Typeface.DEFAULT_BOLD
        badge.gravity = Gravity.CENTER
        badge.minWidth = dp(20)
        badge.setPadding(dp(5), 0, dp(5), 0)
        badge.background = GradientDrawable().apply {
            setColor(BADGE_COLOR)
            cornerRadius = dp(10).toFloat()
        }
        badge.elevation = dp(6).toFloat()
        badge.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        badge.visibility = View.GONE
        addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, dp(20), Gravity.TOP or Gravity.END).apply {
            topMargin = -dp(BADGE_OVERHANG)
            rightMargin = -dp(BADGE_OVERHANG)
        })

        apply(style)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        UnreadMonitor.addListener(unreadListener)
        LauncherStyleStore.addListener(styleListener)
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        UnreadMonitor.removeListener(unreadListener)
        LauncherStyleStore.removeListener(styleListener)
        super.onDetachedFromWindow()
    }

    /** Pinned to the bottom corner of its FrameLayout parent, clear of the system bars. */
    internal fun floating(bottomInsetDp: Int) {
        floatingBottomInsetDp = bottomInsetDp
        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                systemLeft = bars.left
                systemRight = bars.right
                systemBottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                systemLeft = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                systemRight = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                systemBottom = insets.systemWindowInsetBottom
            }
            position()
            insets
        }
        position()
    }

    private fun position() {
        val bottomInset = floatingBottomInsetDp ?: return
        val params = (layoutParams as? LayoutParams)
            ?: LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        params.gravity = Gravity.BOTTOM or (if (style.onLeft) Gravity.START else Gravity.END)
        // The padding around the circle is part of the offset from the edge.
        val edge = maxOf(0, dp(style.edgeOffsetDp - MARGIN))
        params.leftMargin = if (style.onLeft) edge + systemLeft else 0
        params.rightMargin = if (style.onLeft) 0 else edge + systemRight
        params.bottomMargin = maxOf(0, dp(style.verticalEdgeOffsetDp + bottomInset - MARGIN)) + systemBottom
        layoutParams = params
    }

    private fun apply(style: LauncherStyle) {
        this.style = style
        val size = dp(style.sizeDp)
        circle.layoutParams = LayoutParams(size, size, Gravity.CENTER)
        circle.background = GradientDrawable().apply {
            setColor(style.color)
            cornerRadius = resources.displayMetrics.density * style.cornerRadiusDp
        }
        icon.layoutParams = LayoutParams(dp(style.iconDp), dp(style.iconDp), Gravity.CENTER)
        icon.setColorFilter(style.foreground)
        position()
    }

    private fun update(count: Int) {
        badge.text = if (count > 99) "99+" else count.toString()
        badge.visibility = if (count > 0) View.VISIBLE else View.GONE
        val label = context.getString(R.string.hal_chat_launcher)
        circle.contentDescription = if (count > 0) {
            "$label, " + resources.getQuantityString(R.plurals.hal_chat_unread, count, count)
        } else {
            label
        }
        visibility = if (UnreadMonitor.isChatOnScreen) View.INVISIBLE else View.VISIBLE
    }

    private fun tapped() {
        val onTap = onTap
        if (onTap != null) onTap() else HalChat.open(activity() ?: context)
    }

    private fun activity(): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val BADGE_OVERHANG = 4

        /** Around the circle: holds its shadow and the badge's overhang. */
        const val MARGIN = 12
        val BADGE_COLOR = 0xFFEF4444.toInt()
    }
}
