# The widget calls into this class from JavaScript by name.
-keepclassmembers class com.chatwithhal.sdk.HalChatActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
