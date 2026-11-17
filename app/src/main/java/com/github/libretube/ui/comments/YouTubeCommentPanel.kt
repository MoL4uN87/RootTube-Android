package com.github.libretube.ui.comments

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsSession
import androidx.core.net.toUri

/** Opens an authenticated YouTube page using the browser's own session and cookie jar. */
object YouTubeCommentPanel {
    /** A real browser session is required to prevent YouTube's Android App Link interception. */
    fun browserPackage(context: Context): String? = CustomTabsClient.getPackageName(
        context,
        listOf("com.android.chrome", "com.sec.android.app.sbrowser", "com.yandex.browser"),
        true
    )

    /** Launch via ActivityResultLauncher to enable partial Custom Tabs where supported. */
    fun createIntent(context: Context, url: String, session: CustomTabsSession): Intent? {
        val uri = url.toUri()
        if (uri.scheme != "https" || uri.host != "www.youtube.com" || uri.path != "/watch") {
            return null
        }

        val initialHeight = (context.resources.displayMetrics.heightPixels * 0.82f).toInt()
        val tab = CustomTabsIntent.Builder(session)
            .setShowTitle(true)
            .setInitialActivityHeightPx(initialHeight)
            .setSendToExternalDefaultHandlerEnabled(false)
            .build()
        return tab.intent.apply {
            data = uri
        }
    }
}
