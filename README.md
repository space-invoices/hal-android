# HAL chat for Android

Put [HAL](https://chatwithhal.com)'s chat into your Android app: the same
conversations, help articles, AI answers, attachments and agent calls as the
website widget. The SDK also identifies logged-in users, records events, and
can show an unread badge or a ready-made chat button.

Android 7 (API 24)+. No third-party dependencies. You need a HAL project ID (Settings ›
Install in the HAL dashboard).

The chat screen is HAL's widget in a web view, so it stays in step with the
website widget and your dashboard settings without app updates.

## Install

Add JitPack to your repositories in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then the dependency:

```kotlin
dependencies {
    implementation("com.github.space-invoices:hal-android:0.1.0")
}
```

The library declares `INTERNET` and its own activity. Declare
`android.permission.RECORD_AUDIO` in your app if agents should be able to call
users. The SDK asks for it at runtime when a call starts.

## Use

```kotlin
import com.chatwithhal.sdk.*

// Application.onCreate
HalChat.configure(this, "YOUR_PROJECT_ID")

// After login (and at launch if already signed in)
HalChat.identify(HalUser(
    userId = user.id,
    email = user.email,
    name = user.name,
    userHash = user.halHash,            // if identity verification is on
    company = HalCompany(id = org.id, name = org.name),
    metadata = mapOf("plan" to "pro"),
))

HalChat.open(context)
HalChat.open(context, message = "I want to upgrade")
HalChat.open(context, article = "refund-policy")

HalChat.set(mapOf("seats" to 5, "trial" to true, "old_key" to null))
HalChat.track("trial-started")

// Logout
HalChat.reset()
```

## Chat button and unread badge

```kotlin
// Your own button: add in onStart, remove in onStop
private val unread = HalUnreadCountListener { count -> badge.isVisible = count > 0; badge.text = "$count" }
HalChat.addUnreadCountListener(unread)
HalChat.removeUnreadCountListener(unread)

// The launcher: a floating chat button that looks like the website widget's
HalChat.showLauncher(this)                        // in Activity.onCreate
HalChat.showLauncher(this, bottomInsetDp = 56)    // clear of a bottom navigation bar
HalChatLauncherButton(context)                    // a View, placed in your layout
```

The count is checked only while it is shown, the app is in the foreground and
the chat is closed. That happens every 60 seconds (`unreadRefreshSeconds` in
`HalConfiguration`), when the app comes to the foreground, and when the chat
closes. Call `HalChat.refreshUnreadCount()` to check it sooner, for example
when your own push notification arrives.

Callbacks run on the main thread with `null` on success. Without a callback,
failures are logged under the `HalChat` tag. `HalChat.listener` reports open,
close and load errors. Everything is `@JvmStatic`, so you can call it from Java.

## Identity verification

If the project requires verified identities, compute `userHash` **on your
server**: an HMAC-SHA256 of the user ID with the project's identity secret. This
is the same value the website widget uses. Never ship the secret in the app.

## Example app

`example/` is an app with every call wired to a button:

```bash
./gradlew :example:installDebug
adb shell setprop debug.hal.project <your project id>
```

## License

MIT
