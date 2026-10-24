package com.fersaiyan.cyanbridge.devices.tunebuds

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsFrame
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsFrameDecoder
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsFrameType
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsMediaCounts
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsProtocol
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsScreenConfig
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsStorageInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Pins the RFCOMM bytes TuneBudsManager sends and how it reads the glasses' frames. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TuneBudsManagerTest {
    private lateinit var glasses: FakeGlasses
    private lateinit var manager: TuneBudsManager

    /** The glasses' side of the RFCOMM socket: answers requests, pushes notifications. */
    private class FakeGlasses(context: Context) {
        val requests = CopyOnWriteArrayList<TuneBudsFrame>()
        val raw = ByteArrayOutputStream()
        val deviceInfo = linkedMapOf<Int, ByteArray>()
        val statuses = mutableMapOf<Int, Int>()
        var afterResponse: (TuneBudsFrame) -> Unit = {}
        private val feeder: java.io.PipedOutputStream
        private val sink: java.io.PipedInputStream
        private var sequence = 0
        private val reader: Thread

        init {
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            shadowOf(adapter).setEnabled(true)
            val device = adapter.getRemoteDevice(ADDRESS)
            shadowOf(device).setBondState(BluetoothDevice.BOND_BONDED)
            val socket = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"))
            feeder = shadowOf(socket).inputStreamFeeder
            sink = shadowOf(socket).outputStreamSink
            reader = thread(isDaemon = true, name = "fake-tunebuds") {
                val decoder = TuneBudsFrameDecoder()
                val buffer = ByteArray(1024)
                runCatching {
                    while (true) {
                        val count = sink.read(buffer)
                        if (count < 0) break
                        synchronized(raw) { raw.write(buffer, 0, count) }
                        decoder.append(buffer.copyOf(count)).forEach(::answer)
                    }
                }
            }
        }

        private fun answer(request: TuneBudsFrame) {
            requests += request
            val payload = if (request.command == TuneBudsProtocol.CMD_DEVICE_INFO) {
                val asked = request.payload.filterIndexed { index, _ -> index % 2 == 0 }.map { it.toInt() and 0xFF }
                asked.flatMap { type ->
                    deviceInfo[type]?.let { listOf(type.toByte(), it.size.toByte()) + it.toList() }.orEmpty()
                }.toByteArray()
            } else {
                byteArrayOf((statuses[request.command] ?: 0).toByte())
            }
            send(request.command, TuneBudsFrameType.RESPONSE, payload)
            afterResponse(request)
        }

        @Synchronized
        fun send(command: Int, type: TuneBudsFrameType, payload: ByteArray) {
            val encoded = TuneBudsProtocol.encode(command, payload, type, maxPacketSize = 260, initialSequence = sequence)
            sequence = encoded.nextSequence
            encoded.frames.forEach { feeder.write(it); feeder.flush() }
        }

        fun notify(command: Int, payload: ByteArray) = send(command, TuneBudsFrameType.NOTIFICATION, payload)

        fun rawBytes(): ByteArray = synchronized(raw) { raw.toByteArray() }

        fun commands(): List<Int> = requests.map { it.command }

        fun close() = reader.interrupt()
    }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        glasses = FakeGlasses(app)
        manager = TuneBudsManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(app)
    }

    @After
    fun tearDown() {
        manager.disconnect()
        glasses.close()
    }

    private fun awaitState(predicate: (TuneBudsState) -> Boolean): TuneBudsState =
        onWorker { manager.state.first(predicate) }

    /**
     * Runs [block] on a worker with a hard deadline. Robolectric's SystemClock only moves when
     * the test idles the main looper, and the photo drain loop waits on it.
     */
    private fun <T> onWorker(block: suspend () -> T): T {
        val result = CoroutineScope(Dispatchers.IO).async { withTimeout(20_000L) { block() } }
        val deadline = System.nanoTime() + 25_000_000_000L
        while (!result.isCompleted) {
            if (System.nanoTime() > deadline) {
                result.cancel()
                fail("Timed out waiting for the TuneBuds manager")
            }
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
        return runBlocking { result.await() }
    }

    private fun awaitRequests(count: Int) = onWorker {
        while (glasses.requests.size < count) delay(10)
    }

    private fun u32(vararg values: Long) = values.flatMap { value -> (0 until 4).map { (value shr (8 * it)).toByte() } }.toByteArray()

    private fun info(vararg types: Int) = TuneBudsProtocol.deviceInfoQuery(*types)

    /** Answers the vendor app's startup the way XK ONE PRO firmware does. */
    private fun connectToCapableGlasses() {
        glasses.deviceInfo.putAll(
            mapOf(
                TuneBudsProtocol.INFO_MAX_PACKET_SIZE to byteArrayOf(15),
                TuneBudsProtocol.INFO_AI_KIT_SUPPORT to byteArrayOf(2),
                TuneBudsProtocol.INFO_BATTERY to byteArrayOf((0x80 or 57).toByte()),
                TuneBudsProtocol.INFO_FIRMWARE_VERSION to byteArrayOf(4, 3, 2, 1),
                TuneBudsProtocol.INFO_MODEL to "XK ONE PRO".encodeToByteArray(),
                TuneBudsProtocol.INFO_SERIAL_NUMBER to "SN123".encodeToByteArray(),
                TuneBudsProtocol.INFO_VIDEO_LIMIT to byteArrayOf(0x2C, 0x01),
                TuneBudsProtocol.INFO_AUDIO_LIMIT to byteArrayOf(0x58, 0x02),
                TuneBudsProtocol.INFO_SUPPORT_DETECTION to byteArrayOf(1),
                TuneBudsProtocol.INFO_WIFI_SUPPORT to byteArrayOf(7),
                TuneBudsProtocol.INFO_DEVICE_ABILITY to byteArrayOf(0x34, 0x12),
                TuneBudsProtocol.INFO_SUPPORT_AUDIO to byteArrayOf(1),
                TuneBudsProtocol.INFO_RESOLUTION to byteArrayOf(2),
                TuneBudsProtocol.INFO_SCREEN_CONFIG to byteArrayOf(1, 0),
                TuneBudsProtocol.INFO_SUPPORT_VOLUME_CONTROL to byteArrayOf(1),
                TuneBudsProtocol.INFO_CURRENT_VOLUME to byteArrayOf(5, 7),
                TuneBudsProtocol.INFO_SUPPORT_OPUS to byteArrayOf(1),
                TuneBudsProtocol.INFO_AI_CHAT_SUPPORT to byteArrayOf(0),
                TuneBudsProtocol.INFO_SUPPORT_APP_LIST to byteArrayOf(1),
            ),
        )
        glasses.statuses[TuneBudsProtocol.CMD_CAMERA_COPROCESSOR_INFO] = 1
        glasses.statuses[TuneBudsProtocol.CMD_WORK_STATE] = 3
        glasses.afterResponse = { request ->
            when (request.command) {
                TuneBudsProtocol.CMD_COPROCESSOR_VERSION ->
                    glasses.notify(request.command, "V821-1.0".encodeToByteArray())
                TuneBudsProtocol.CMD_STORAGE -> glasses.notify(request.command, u32(100, 200))
                TuneBudsProtocol.CMD_MEDIA_COUNTS -> glasses.notify(request.command, u32(3, 2, 1))
            }
        }
        manager.connect(" $ADDRESS ", "TuneBuds X")
        awaitState { it.caps.workState == 3 }
    }

    @Test
    fun connectReadsCapabilitiesInTheVendorOrder() {
        connectToCapableGlasses()

        assertEquals(
            listOf(0x27, 0x27, 0x27, 0xE0, 0xE9, 0xEA, 0xEF, 0xF6),
            glasses.commands(),
        )
        val payloads = glasses.requests.map { it.payload }
        assertArrayEquals(info(0xFF, 0xA0), payloads[0])
        assertArrayEquals(info(0x01, 0x02, 0x86, 0x85, 0x82, 0x83, 0x96, 0xA3), payloads[1])
        assertArrayEquals(info(0xFE, 0x96, 0x98, 0x93, 0x9A, 0x8D, 0x8E, 0x82, 0x83, 0x9F, 0x8A, 0x9B), payloads[2])
        payloads.drop(3).forEach { assertEquals(0, it.size) }

        // The first query uses 20-byte packets; the reported 15-byte size splits the next one.
        val raw = glasses.rawBytes()
        assertArrayEquals(byteArrayOf(0, 0x27, 1, 0, 4, 0xFF.toByte(), 0, 0xA0.toByte(), 0), raw.copyOfRange(0, 9))
        assertArrayEquals(byteArrayOf(1, 0x27, 1, 0x10, 10), raw.copyOfRange(9, 14))
        assertArrayEquals(byteArrayOf(2, 0x27, 1, 0x11, 6), raw.copyOfRange(24, 29))

        val state = manager.state.value
        assertEquals("TuneBuds connected", state.connectionLabel)
        assertEquals("CONNECTED", state.protocolState)
        assertEquals(ADDRESS, state.deviceAddress)
        assertEquals("TuneBuds X", state.deviceName)
        assertEquals(57, state.batteryPercent)
        assertTrue(state.caps.isCharging)
        assertEquals("1.2.3.4", state.firmwareVersion)
        assertEquals("XK ONE PRO", state.model)
        assertEquals("SN123", state.caps.serialNumber)
        assertEquals("V821-1.0", state.coprocessorVersion)
        assertEquals(TuneBudsStorageInfo(100, 200), state.storage)
        assertEquals(TuneBudsMediaCounts(3, 2, 1), state.mediaCounts)
        assertEquals(15, state.caps.protocolPacketSize)
        assertEquals(2, state.caps.aiKitSupport)
        assertEquals(1, state.caps.cameraCoprocessorType)
        assertArrayEquals(byteArrayOf(0x34, 0x12), state.caps.deviceAbility)
        assertEquals(0x1234, state.caps.deviceCapabilityMask)
        assertEquals(1, state.caps.supportDetection)
        assertEquals(1, state.caps.supportAudio)
        assertEquals(300, state.caps.videoLimitSeconds)
        assertEquals(600, state.caps.audioLimitSeconds)
        assertEquals(1, state.caps.supportVolumeControl)
        assertEquals(listOf(5, 7), state.caps.currentVolume)
        assertEquals(TuneBudsScreenConfig(isSupported = true, isLandscape = true), state.caps.screenConfig)
        assertEquals(2, state.caps.resolution)
        assertEquals(1, state.caps.supportOpus)
        assertEquals(0, state.caps.supportAiChat)
        assertEquals(1, state.caps.supportAppList)
        assertEquals(7, state.wifiSupportMask)
        assertNull(state.lastError)
        assertTrue(manager.isConnected())
    }

    @Test
    fun notificationsUpdateTheState() {
        connectToCapableGlasses()

        glasses.notify(TuneBudsProtocol.INFO_BATTERY, byteArrayOf(40))
        awaitState { it.batteryPercent == 40 && !it.caps.isCharging }
        glasses.notify(TuneBudsProtocol.CMD_STORAGE, u32(300, 50))
        awaitState { it.storage == TuneBudsStorageInfo(300, 50) }
        glasses.notify(TuneBudsProtocol.CMD_MEDIA_COUNTS, u32(9, 8, 7))
        awaitState { it.mediaCounts == TuneBudsMediaCounts(9, 8, 7) }
        glasses.notify(TuneBudsProtocol.CMD_COPROCESSOR_VERSION, "V821-2.0".encodeToByteArray())
        awaitState { it.coprocessorVersion == "V821-2.0" }
        glasses.notify(TuneBudsProtocol.CMD_CAMERA_COPROCESSOR_INFO, byteArrayOf(2))
        awaitState { it.caps.cameraCoprocessorType == 2 }
        glasses.notify(TuneBudsProtocol.CMD_WIFI_STATE, byteArrayOf(2))
        awaitState { it.wifiState == 2 }
        glasses.notify(TuneBudsProtocol.CMD_WORK_STATE, byteArrayOf(5))
        awaitState { it.caps.workState == 5 }

        // The firmware stops a recording on its own (limit or the physical button).
        manager.toggleVideo()
        awaitState { it.isVideoRecording }
        glasses.notify(TuneBudsProtocol.CMD_CAMERA_CLOSE, byteArrayOf(0))
        awaitState { !it.isVideoRecording }

        glasses.notify(TuneBudsProtocol.CMD_AI_PICTURE, byteArrayOf(2))
        awaitState { it.lastError == "TuneBuds AI photo failed with status 2" }
    }

    @Test
    fun dashboardCommandsSendTheVendorRequests() {
        connectToCapableGlasses()
        val start = glasses.requests.size
        fun sent() = glasses.requests.drop(start)

        manager.requestBattery()
        awaitRequests(start + 1)
        assertArrayEquals(info(0x01), sent()[0].payload)

        manager.requestVersion()
        awaitRequests(start + 5)
        assertEquals(listOf(0x27, 0x27, 0xE9, 0x27, 0xE0), sent().map { it.command })
        assertArrayEquals(info(0x02, 0x86, 0x85), sent()[1].payload)

        manager.requestMediaCount()
        awaitRequests(start + 6)
        assertEquals(0xEF, sent()[5].command)

        val before = System.currentTimeMillis() / 1000
        manager.syncTime()
        awaitRequests(start + 7)
        val time = sent()[6]
        assertEquals(0xF0, time.command)
        assertEquals(6, time.payload.size)
        val seconds = time.payload.take(4).mapIndexed { index, byte -> (byte.toLong() and 0xFF) shl (8 * index) }.sum()
        assertTrue(seconds in before..before + 5)

        manager.toggleVideo()
        awaitState { it.isVideoRecording && !it.isAudioRecording }
        manager.toggleAudio()
        awaitState { it.isAudioRecording && !it.isVideoRecording }
        manager.toggleAudio()
        awaitState { !it.isAudioRecording }
        assertEquals(listOf(0xE4, 0xE5, 0xE2), sent().drop(7).map { it.command })

        manager.takePhoto()
        awaitRequests(start + 12)
        assertEquals(listOf(0xE1, 0xE2), sent().drop(10).map { it.command })
        assertArrayEquals(byteArrayOf(0), sent()[10].payload)

        manager.refreshStatus()
        awaitRequests(start + 18)
        assertEquals(listOf(0x27, 0x27, 0xE0, 0xEA, 0xEF, 0xE9), sent().drop(12).map { it.command })
        assertArrayEquals(info(0x01, 0x02, 0x86, 0x85, 0xA3), sent()[12].payload)
    }

    @Test
    fun aiPhotoChunksAreJoinedIntoTheJpeg() {
        connectToCapableGlasses()
        val jpeg = JPEG
        glasses.afterResponse = { request ->
            if (request.command == TuneBudsProtocol.CMD_CAMERA_ON) {
                val chunks = jpeg.toList().chunked(200)
                chunks.forEachIndexed { index, chunk ->
                    val status = if (index == chunks.lastIndex) 0 else 1
                    glasses.notify(TuneBudsProtocol.CMD_AI_PICTURE, byteArrayOf(status.toByte()) + chunk.toByteArray())
                }
            }
        }

        val photo = onWorker { manager.capturePhotoForAi(timeoutMs = 10_000L) }

        assertArrayEquals(jpeg, photo)
        val camera = glasses.requests.last { it.command == TuneBudsProtocol.CMD_CAMERA_ON }
        assertArrayEquals(byteArrayOf(1), camera.payload)
    }

    @Test
    fun failedCommandsAndDisconnectsReachTheState() {
        connectToCapableGlasses()
        glasses.statuses[TuneBudsProtocol.CMD_MEDIA_COUNTS] = 2

        manager.requestMediaCount()
        awaitState { it.lastError == "Could not read media count: TuneBuds command 0xef failed with status 2" }

        manager.toggleVideo()
        awaitState { it.isVideoRecording }
        manager.disconnect()
        val state = awaitState { it.protocolState == "DISCONNECTED" }
        assertEquals("TuneBuds disconnected", state.connectionLabel)
        assertFalse(state.isVideoRecording)
        assertFalse(manager.isConnected())

        manager.requestBattery()
        awaitState { it.lastError == "Connect TuneBuds glasses before trying to read battery" }
    }

    @Test
    fun fileManagerAndLivestreamStartOverTheHotspot() {
        connectToCapableGlasses()
        glasses.notify(TuneBudsProtocol.CMD_WIFI_STATE, byteArrayOf(0))
        awaitState { it.wifiState == 0 }
        glasses.afterResponse = { request ->
            when (request.command) {
                TuneBudsProtocol.CMD_FILE_MANAGER ->
                    glasses.notify(request.command, "http://10.0.0.2:80/files/".encodeToByteArray())
                TuneBudsProtocol.CMD_START_RTSP ->
                    glasses.notify(request.command, "rtsp://10.0.0.2/live".encodeToByteArray())
            }
        }
        val start = glasses.requests.size

        val endpoint = onWorker { manager.startFileManager("Phone", "secret", channel = 6) }

        assertEquals("http://10.0.0.2:80/files/", endpoint)
        assertNull(awaitState { it.wifiState == null }.wifiState)
        val sent = glasses.requests.drop(start)
        assertEquals(listOf(0xE6, 0xE7), sent.map { it.command })
        assertArrayEquals(TuneBudsProtocol.buildWifiPayload(0, "Phone", "secret", 6), sent[0].payload)

        val url = onWorker { manager.startLivestream(TuneBudsHotspotCredentials("Phone", "secret"), quality = 75) }

        assertEquals("rtsp://10.0.0.2/live", url)
        val live = glasses.requests.drop(start + 2)
        assertEquals(listOf(0xE6, 0xE8), live.map { it.command })
        assertArrayEquals(byteArrayOf(6, 5, 10), live[1].payload)
    }

    private companion object {
        const val ADDRESS = "00:11:22:33:44:55"

        /** An 8x8 JPEG, so BitmapFactory can decode it. */
        val JPEG: ByteArray = (
            "ffd8ffe000104a46494600010100000100010000ffdb004300100b0c0e0c0a100e0d0e1211101318281a181616183123" +
            "251d283a333d3c3933383740485c4e404457453738506d51575f626768673e4d71797064785c656763ffdb0043011112" +
            "121815182f1a1a2f63423842636363636363636363636363636363636363636363636363636363636363636363636363" +
            "6363636363636363636363636363ffc00011080008000803012200021101031101ffc4001f0000010501010101010100" +
            "000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d010203000411051221" +
            "31410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a" +
            "434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a9293949596979899" +
            "9aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1" +
            "f2f3f4f5f6f7f8f9faffc4001f0100030101010101010101010000000000000102030405060708090a0bffc400b51100" +
            "020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f015" +
            "6272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a63646566676869" +
            "6a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4" +
            "c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c03010002110311003f00cf" +
            "a28a2bdc3c23ffd9"
        ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

/** Capability fields as the manager exposes them. */
private val TuneBudsState.caps: TuneBudsState get() = this
