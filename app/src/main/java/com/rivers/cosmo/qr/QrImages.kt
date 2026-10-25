package com.rivers.cosmo.qr

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri

/** Reads a QR code from a photo or screenshot. */
object QrImages {
    /** Photos are scaled down to about this size first: plenty for a QR code, and quick to scan. */
    private const val MAX_SIDE = 1600

    /** Call off the main thread. Returns null if there's no readable QR code in the image. */
    fun decode(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()
        return QrReader.decode(pixels, width, height)
    }
}
