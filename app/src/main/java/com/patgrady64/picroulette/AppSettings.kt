package com.patgrady64.picroulette

import android.content.Context

private const val PIC_ROULETTE_PREFS = "PicRoulettePrefs"
private const val HAPTIC_FEEDBACK_KEY = "haptic_feedback_enabled"

fun isHapticFeedbackEnabled(context: Context): Boolean {
    return context
        .getSharedPreferences(PIC_ROULETTE_PREFS, Context.MODE_PRIVATE)
        .getBoolean(HAPTIC_FEEDBACK_KEY, true)
}

fun setHapticFeedbackEnabled(
    context: Context,
    enabled: Boolean
) {
    context
        .getSharedPreferences(PIC_ROULETTE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(HAPTIC_FEEDBACK_KEY, enabled)
        .apply()
}
