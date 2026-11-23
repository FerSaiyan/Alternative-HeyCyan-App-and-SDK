package com.fersaiyan.cyanbridge.glasses.offlineauth

import android.os.Looper
import com.oudmon.ble.base.bluetooth.LargeDataParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * add.242 rig: an RCSP reply wedged the vendor SDK's BigData reassembler, so the 0x73/0x08 Wi-Fi IP notify after the
 * unlock never reached the app and media sync timed out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class HeyCyanVendorParserResetTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val uuid = LargeDataParser.uuid_notify
    private val ipNotify = hex("bc730500ac7108c0a83101")

    private fun parserBuffer(): ByteArray = LargeDataParser.getInstance().tempData ?: ByteArray(0)

    @Test
    fun rcspReplyWedgesTheVendorParserAndTheResetUnwedgesIt() {
        val parser = LargeDataParser.getInstance()
        HeyCyanAuthRawChannel.resetVendorParser()
        shadowOf(Looper.getMainLooper()).idle()

        // The vendor parser glues the RCSP reply and every later 0xBC frame into one buffer.
        parser.parseBigLargeData(uuid, hex("fedcba008a336fef516741686b4ba347b247faa2"))
        parser.parseBigLargeData(uuid, ipNotify)
        assertEquals(31, parserBuffer().size)

        // After the reset a 0xBC frame is parsed on its own again (complete frames leave the buffer empty).
        HeyCyanAuthRawChannel.resetVendorParser()
        shadowOf(Looper.getMainLooper()).idle()
        parser.parseBigLargeData(uuid, ipNotify)
        assertArrayEquals(ByteArray(0), parserBuffer())
    }

    @Test
    fun anRcspNotifySeenByTheRawChannelResetsTheVendorParser() {
        val parser = LargeDataParser.getInstance()
        HeyCyanAuthRawChannel.resetVendorParser()
        shadowOf(Looper.getMainLooper()).idle()
        parser.parseBigLargeData(uuid, hex("fedcba0200"))
        HeyCyanAuthRawChannel.onNotify(hex("fedcba0200"))
        shadowOf(Looper.getMainLooper()).idle()
        parser.parseBigLargeData(uuid, ipNotify)
        assertArrayEquals(ByteArray(0), parserBuffer())
    }
}
