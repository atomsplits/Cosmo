package com.rivers.cosmo.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Finds and reads a QR code in a picture, given as ARGB pixels. */
object QrReader {
    fun decode(pixels: IntArray, width: Int, height: Int): String? {
        val source = RGBLuminanceSource(width, height, pixels)
        // Also try light-on-dark, e.g. a QR code screenshotted from an app in dark mode.
        return read(source) ?: read(source.invert())
    }

    private fun read(source: LuminanceSource): String? = try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
    } catch (e: ReaderException) {
        null
    }
}
