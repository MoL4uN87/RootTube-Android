package com.github.libretube.voiceover

import android.content.Context

/**
 * Persistent gains for translated playback. Values are fractions (0f..1f).
 * The normal RootTube volume is restored when the translation ends.
 */
internal object VoiceoverVolumeSettings {
    private const val PREF_NAME = "roottube_ai_voiceover"
    private const val ORIGINAL_KEY = "original_gain"
    private const val TRANSLATED_KEY = "translated_gain"

    const val DEFAULT_ORIGINAL = 0.25f
    const val DEFAULT_TRANSLATED = 1.0f

    fun original(context: Context): Float =
        clean(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getFloat(ORIGINAL_KEY, DEFAULT_ORIGINAL))

    fun translated(context: Context): Float =
        clean(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getFloat(TRANSLATED_KEY, DEFAULT_TRANSLATED))

    fun setOriginal(context: Context, gain: Float) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(ORIGINAL_KEY, clean(gain)).apply()
    }

    fun setTranslated(context: Context, gain: Float) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(TRANSLATED_KEY, clean(gain)).apply()
    }

    private fun clean(value: Float) =
        if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
}
