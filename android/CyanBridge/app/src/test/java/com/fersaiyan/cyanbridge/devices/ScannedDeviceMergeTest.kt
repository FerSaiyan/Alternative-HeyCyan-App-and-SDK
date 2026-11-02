package com.fersaiyan.cyanbridge.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/** Device picker rows for a dual-mode TuneBuds unit: one row whatever order its addresses show up in. */
class ScannedDeviceMergeTest {

    private val rows = mutableListOf<ScannedDevice>()

    /** What DeviceBindDestination.upsertDevice does with the row list. */
    private fun upsert(address: String, rssi: Int, connectionAddress: String? = null): ScannedDevice =
        rows.mergeRowFor(address, connectionAddress)?.also { it.rssi = rssi }
            ?: ScannedDevice(address, NAME, rssi).also { row ->
                connectionAddress?.let { row.connectionAddress = it }
                rows += row
            }

    private fun bonded() = upsert(CLASSIC, rssi = 0, connectionAddress = CLASSIC)
    private fun onLeScan(rssi: Int) = upsert(LE, rssi)
    private fun onParsedData(rssi: Int) = upsert(LE, rssi, connectionAddress = CLASSIC)

    @Test
    fun bondedFirstThenLeAdvertsKeepOneRow() {
        val row = bonded()
        onLeScan(-60)
        onParsedData(-60)
        // The scanner calls onLeScan (LE address only) before onParsedData for every advert.
        repeat(3) { i ->
            assertSame(row, onLeScan(-50 - i))
            assertSame(row, rows.single())
            onParsedData(-50 - i)
        }
        assertSame(row, rows.single())
        assertEquals(CLASSIC, row.connectionAddress)
        assertEquals(setOf(CLASSIC, LE), row.addresses())
        assertEquals(-52, row.rssi)
    }

    @Test
    fun leFirstThenBondedAndInquiryKeepOneRow() {
        val row = onLeScan(-60)
        onParsedData(-60)
        bonded()
        upsert(CLASSIC, rssi = -70, connectionAddress = CLASSIC) // Classic inquiry ACTION_FOUND
        onLeScan(-55)
        assertSame(row, rows.single())
        assertEquals(CLASSIC, row.connectionAddress)
        assertEquals(setOf(CLASSIC, LE), row.addresses())
    }

    @Test
    fun leRowSeenBeforeItsClassicAddressFoldsIntoTheBondedRow() {
        bonded()
        val advertRow = onLeScan(-60) // no Classic address yet: its own row for now
        assertEquals(2, rows.size)
        val merged = onParsedData(-58)
        assertSame(merged, rows.single())
        assertFalse(merged === advertRow)
        assertEquals(setOf(CLASSIC, LE), merged.addresses())
        // Another unit still gets its own row.
        upsert("11:22:33:44:55:66", -40)
        assertEquals(2, rows.size)
    }

    private companion object {
        const val NAME = "E2E-TuneBuds-5606"
        const val CLASSIC = "DA:4C:10:DE:00:01"
        const val LE = "F0:F1:F2:F3:F4:03"
    }
}
