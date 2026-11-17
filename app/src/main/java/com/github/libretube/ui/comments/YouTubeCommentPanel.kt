package com.github.libretube.ui.comments

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/** Opens an authenticated YouTube page using the browser's own session and cookie jar. */
object YouTubeCommentPanel {
    /** Launch via ActivityResultLauncher to enable partial Custom Tabs where supported. */
    fun createIntent(context: Context, url: String): Intent? {
        val uri = url.toUri()
        if (uri.scheme != "https" || uri.host != "www.youtube.com" || uri.path != "/watch") {
            return null
        }

        // Resolve a real browser so the YouTube Android app cannot intercept the watch link.
        val browserPackage = CustomTabsClient.getPackageName(context, emptyList())
            ?: CustomTabsClient.getPackageName(
                context,
                listOf("com.android.chrome", "com.sec.android.app.sbrowser", "com.yandex.browser"),
                true
            )
            ?: return null

        val initialHeight = (context.resources.displayMetrics.heightPixels * 0.82f).toInt()
        val tab = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setInitialActivityHeightPx(initialHeight)
            .build()
        return tab.intent.apply {
            setPackage(browserPackage)
            data = uri
        }
    }
}
