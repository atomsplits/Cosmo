package com.rivers.cosmo.splits

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.rivers.cosmo.wallet.StellarNetwork
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stellar.sdk.KeyPair

/** Invites are shown as QR codes (with the same settings as InviteDialog), so they must stay scannable. */
class InviteQrTest {
    @Test
    fun aFivePersonTripInviteSurvivesAQrRoundTrip() {
        val organizer = KeyPair.random()
        val split = BillSplit(
            id = BillSplit.newId(), title = "Trip fund", organizerCosmoId = "COSMO-AAAA-AAAA",
            organizerAddress = organizer.accountId, network = StellarNetwork.Testnet,
            assetCode = "USDC", assetIssuer = "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5",
            shares = listOf("Organizer", "Alexandra", "Sam", "Jo", "Kim").map { SplitShare(it, BigDecimal("500"), isOrganizer = it == "Organizer") },
            createdAt = 1_761_400_000_000L
        )
        val link = SplitInvite.link(SplitInvite.sign(split, organizer), shareIndex = 3)

        val hints = mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 0, 0, hints)
        // Stays well within what phone cameras read comfortably (version 40 would be 177 modules).
        assertTrue("QR is ${matrix.width} modules wide", matrix.width <= 120)

        val scale = 4
        val size = matrix.width * scale
        val pixels = IntArray(size * size) { i -> if (matrix.get((i % size) / scale, (i / size) / scale)) 0xFF000000.toInt() else -1 }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels)))).text
        assertEquals(link, decoded)
        assertEquals(split, SplitInvite.open(decoded).split)
    }
}
