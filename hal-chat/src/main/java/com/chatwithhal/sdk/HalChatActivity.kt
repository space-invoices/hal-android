package com.chatwithhal.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject

/**
 * The chat screen: HAL's widget, full screen, in a web view.
 *
 * Start it with [HalChat.open]. The widget's close button and the back
 * button both finish it.
 */
class HalChatActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var spinner: ProgressBar
    private lateinit var errorView: LinearLayout
    private var chatUrl: String? = null
    private var isReady = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** The widget never said ready or error, e.g. its bundle failed to load. */
    private val loadTimeout = Runnable {
        if (!isReady) fail(IllegalStateException("HAL chat timed out loading"))
    }
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        webView = createWebView()
        spinner = ProgressBar(this)
        errorView = createErrorView()
        root.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(spinner, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        root.addView(errorView, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        applyInsets(root)
        applyBarColors(root)
        setContentView(root)
        load()
        // Not again when the system recreates the screen (rotation is handled, other changes are not).
        if (savedInstanceState == null) HalChat.listener?.onChatOpened()
    }

    override fun onStart() {
        super.onStart()
        UnreadMonitor.chatStarted()
    }

    override fun onStop() {
        UnreadMonitor.chatStopped()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(loadTimeout)
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        pendingPermission?.deny()
        pendingPermission = null
        webView.destroy()
        if (isFinishing) HalChat.listener?.onChatClosed()
        super.onDestroy()
    }

    /*
     * Android 15 draws apps edge to edge, under the status and navigation
     * bars. The page cannot rely on `env(safe-area-inset-*)` in every WebView
     * version, so the bars and the keyboard are kept out of its way here.
     */
    private fun applyInsets(root: View) {
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }
    }

    /** Whether the chat draws dark: the configured theme, else the device's. */
    private fun isDark(): Boolean = when (HalChat.configuration?.theme) {
        HalTheme.DARK -> true
        HalTheme.LIGHT -> false
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    /** Readable status and navigation bar icons over the chat's own background. */
    private fun applyBarColors(root: View) {
        val dark = isDark()
        root.setBackgroundColor(if (dark) Color.rgb(22, 24, 28) else Color.WHITE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
        } else {
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
            flags = if (dark) flags and light.inv() else flags or light
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }

    // Loading

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // An agent's call rings with audio the visitor did not tap to start.
        settings.mediaPlaybackRequiresUserGesture = false
        // `target="_blank"` links then go through shouldOverrideUrlLoading.
        settings.setSupportMultipleWindows(false)
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        setBackgroundColor(Color.TRANSPARENT)
        alpha = 0f
        addJavascriptInterface(Bridge(), "HalChatAndroid")
        webViewClient = ChatClient()
        webChromeClient = ChromeClient()
    }

    private fun load() {
        val url = HalChat.chatUrl()
        if (url == null) {
            fail(IllegalStateException("HalChat is not configured. Call HalChat.configure() first."))
            return
        }
        chatUrl = url
        isReady = false
        errorView.visibility = View.GONE
        spinner.visibility = View.VISIBLE
        webView.alpha = 0f
        handler.removeCallbacks(loadTimeout)
        handler.postDelayed(loadTimeout, LOAD_TIMEOUT_MS)
        webView.loadUrl(url)
    }

    private fun becomeReady() {
        if (isReady) return
        isReady = true
        handler.removeCallbacks(loadTimeout)
        spinner.visibility = View.GONE
        webView.animate().alpha(1f).setDuration(150).start()

        val article = intent.getStringExtra(EXTRA_ARTICLE)
        val message = intent.getStringExtra(EXTRA_MESSAGE)
        when {
            article != null -> evaluate("window.HalWidget.openArticle(${jsString(article)})")
            message != null -> evaluate("window.HalWidget.showNewMessage(${jsString(message)})")
        }
    }

    private fun evaluate(script: String) {
        webView.evaluateJavascript("$script; undefined", null)
    }

    private fun fail(error: Throwable) {
        handler.removeCallbacks(loadTimeout)
        spinner.visibility = View.GONE
        webView.alpha = 0f
        errorView.visibility = View.VISIBLE
        HalChat.listener?.onChatError(error)
    }

    private fun createErrorView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        addView(TextView(context).apply {
            setText(R.string.hal_chat_load_failed)
            gravity = Gravity.CENTER
        })
        addView(Button(context).apply {
            setText(R.string.hal_chat_retry)
            setOnClickListener { load() }
        })
        addView(Button(context).apply {
            setText(R.string.hal_chat_close)
            setOnClickListener { finish() }
        })
    }

    // Links

    private fun isChatPage(uri: Uri): Boolean {
        val chat = chatUrl?.let(Uri::parse) ?: return false
        return uri.host == chat.host && uri.port == chat.port && uri.path == chat.path
    }

    /** Links in messages and articles open outside the chat. */
    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            // Nothing on the device handles it.
        }
    }

    private inner class ChatClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame || isChatPage(request.url)) return false
            openExternally(request.url)
            return true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && !isReady) {
                fail(HalChatException("Chat could not be loaded: ${error.description}"))
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            // The page's process died; start over rather than take the app down.
            recreate()
            return true
        }
    }

    // Files and the microphone

    private inner class ChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                @Suppress("DEPRECATION")
                startActivityForResult(params.createIntent(), REQUEST_FILE)
                true
            } catch (_: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }

        /** Calls from an agent. The host app declares RECORD_AUDIO to allow them. */
        override fun onPermissionRequest(request: PermissionRequest) {
            val wantsAudio = PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources
            val chat = chatUrl?.let(Uri::parse)
            val fromChat = chat != null && request.origin.host == chat.host && request.origin.port == chat.port
            if (!(wantsAudio && fromChat)) {
                request.deny()
                return
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                return
            }
            pendingPermission?.deny()
            pendingPermission = request
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        }
    }

    @Deprecated("Activity result API; kept to avoid an AndroidX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_AUDIO) {
            val request = pendingPermission ?: return
            pendingPermission = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                request.deny()
            }
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // Bridge

    /** Receives `HalChatAndroid.postMessage(json)` from the widget, on a WebView thread. */
    internal inner class Bridge {
        @JavascriptInterface
        fun postMessage(json: String) {
            val event = BridgeEvent.parse(json) ?: return
            runOnUiThread {
                // Only HAL's page may drive the screen.
                val current = webView.url?.let(Uri::parse)
                if (current == null || !isChatPage(current)) return@runOnUiThread
                when (event) {
                    BridgeEvent.Ready -> becomeReady()
                    BridgeEvent.Close -> finish()
                    is BridgeEvent.Error -> fail(HalChatException(event.message))
                }
            }
        }
    }

    internal companion object {
        const val EXTRA_MESSAGE = "com.chatwithhal.sdk.MESSAGE"
        const val EXTRA_ARTICLE = "com.chatwithhal.sdk.ARTICLE"
        private const val REQUEST_FILE = 0x4841
        private const val REQUEST_AUDIO = 0x4842
        private const val LOAD_TIMEOUT_MS = 20_000L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** A JavaScript string literal. */
        fun jsString(value: String): String = JSONObject.quote(value)
    }
}
