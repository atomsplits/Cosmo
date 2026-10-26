package com.rivers.cosmo.util

import android.util.Log
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

private const val TAG = "Cosmo"

/**
 * A scope for a controller's background work. A failure in one task is logged (Logcat tag
 * "Cosmo") instead of crashing the app: background work like polling or reconnecting often
 * finishes after the user has moved on, for example right after logging out.
 */
fun backgroundScope(name: String, context: CoroutineContext): CoroutineScope =
    CoroutineScope(SupervisorJob() + context + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Background work in $name failed", e) })

/** Runs a shutdown step, logging rather than throwing if it fails, so the other steps still run. */
inline fun closeQuietly(name: String, close: () -> Unit) {
    try {
        close()
    } catch (e: Exception) {
        Log.e("Cosmo", "Closing $name failed", e)
    }
}
