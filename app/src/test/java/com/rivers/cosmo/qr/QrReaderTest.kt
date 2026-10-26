package com.rivers.cosmo.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrReaderTest {
    private val code = "COSMO-THEME:1:{\"m\":\"Stellar\",\"c\":[\"#101418\",\"#E8EAED\",\"#4FC3F7\"],\"f\":\"Inter\"}"

    /** A QR code drawn into a bigger "photo" at an offset, as ARGB pixels. */
    private fun picture(dark: Int, light: Int, size: Int = 500, offset: Int = 70): IntArray {
        val matrix = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 300, 300, mapOf(EncodeHintType.MARGIN to 4))
        return IntArray(size * size) { i ->
            val x = i % size - offset
            val y = i / size - offset
            if (x in 0 until matrix.width && y in 0 until matrix.height && matrix.get(x, y)) dark else light
        }
    }

    @Test
    fun readsAQrCodeInAPicture() {
        assertEquals(code, QrReader.decode(picture(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 500, 500))
    }

    @Test
    fun readsLightOnDarkCodes() {
        assertEquals(code, QrReader.decode(picture(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 500, 500))
    }

    @Test
    fun findsNothingInAPlainPicture() {
        assertNull(QrReader.decode(IntArray(200 * 200) { 0xFF808080.toInt() }, 200, 200))
    }
}
