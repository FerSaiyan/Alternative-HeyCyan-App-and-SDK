package com.fersaiyan.cyanbridge.shared.ble

import com.fersaiyan.cyanbridge.shared.devices.memomind.GmCodec
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindRecordingLink
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindTransport
import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.ExternalAccessory.EAAccessory
import platform.ExternalAccessory.EAAccessoryManager
import platform.ExternalAccessory.EASession
import platform.Foundation.NSBundle
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSInputStream
import platform.Foundation.NSOutputStream
import platform.Foundation.NSRunLoop
import platform.Foundation.NSStream
import platform.Foundation.NSStreamDelegateProtocol
import platform.Foundation.NSStreamEvent
import platform.Foundation.NSStreamEventEndEncountered
import platform.Foundation.NSStreamEventErrorOccurred
import platform.Foundation.NSStreamEventHasBytesAvailable
import platform.Foundation.NSStreamEventHasSpaceAvailable
import platform.darwin.NSObject

/**
 * MemoMind over iAP2 (ExternalAccessory), the link the official iOS App uses: GM commands on
 * [COMMAND_PROTOCOL] and the Opus recording stream on [RECORDING_PROTOCOL], both plain byte
 * streams like SPP. iOS opens an accessory session only for protocols listed under
 * `UISupportedExternalAccessoryProtocols` in the app's Info.plist, and App Store builds also
 * need the accessory maker's MFi approval, so [findAccessory] is the feature check: it returns
 * the glasses only when this build declares the protocol and the connected accessory offers it.
 * All callbacks run on the main run loop.
 */
@OptIn(ExperimentalForeignApi::class)
class IosMemoMindAccessoryTransport(
    private val onCommandBytes: (ByteArray) -> Unit,
    private val onRecordingBytes: (ByteArray) -> Unit,
    /** The command session ended on its own (glasses gone, stream error). */
    private val onClosed: () -> Unit,
) : MemoMindTransport {
    private var accessory: EAAccessory? = null
    private var command: AccessoryStream? = null
    private var recording: AccessoryStream? = null

    override val isConnected: Boolean get() = command?.isOpen == true
    override val maxFrameBytes: Int = GmCodec.MAX_FRAME_BYTES
    override val awaitsFragmentAcks: Boolean = false

    private val recordingStream = object : MemoMindRecordingLink {
        override suspend fun open() {
            val target = accessory ?: error("MemoMind iAP2 session is closed")
            recording?.close()
            recording = AccessoryStream(target, RECORDING_PROTOCOL, onRecordingBytes, onClosed = { recording = null })
                .also { it.open() }
        }

        override suspend fun write(bytes: ByteArray) {
            (recording ?: error("MemoMind recording session is closed")).write(bytes)
        }

        override suspend fun close() {
            recording?.close()
            recording = null
        }
    }

    /** The recording stream when the glasses and this build both offer it. */
    override val recordingLink: MemoMindRecordingLink?
        get() = recordingStream.takeIf { accessory?.offers(RECORDING_PROTOCOL) == true && isDeclared(RECORDING_PROTOCOL) }

    override suspend fun writeCommand(frame: ByteArray) {
        (command ?: error("MemoMind iAP2 session is closed")).write(frame)
    }

    /** Opens the command session on [target]; throws when iOS refuses it (for example another app holds it). */
    fun open(target: EAAccessory) {
        close()
        val stream = AccessoryStream(target, COMMAND_PROTOCOL, onCommandBytes, onClosed = {
            command = null
            closeRecording()
            accessory = null
            onClosed()
        })
        stream.open()
        accessory = target
        command = stream
        PlatformLogger.i(TAG, "iAP2 session open: ${target.name} fw=${target.firmwareRevision} protocols=${target.protocolStrings}")
    }

    fun close() {
        closeRecording()
        command?.close()
        command = null
        accessory = null
    }

    private fun closeRecording() {
        recording?.close()
        recording = null
    }

    /** One accessory protocol session: a byte stream each way, scheduled on the main run loop. */
    private class AccessoryStream(
        private val accessory: EAAccessory,
        private val protocol: String,
        private val onBytes: (ByteArray) -> Unit,
        private val onClosed: () -> Unit,
    ) {
        private var session: EASession? = null
        private val pending = ArrayDeque<ByteArray>()
        private var pendingOffset = 0

        // NSStream keeps its delegate weakly; this reference keeps it alive.
        private val delegate = StreamDelegate()

        val isOpen: Boolean get() = session != null

        fun open() {
            val refused = "iOS did not open the MemoMind $protocol session (is the official MemoMind app using it?)"
            val opened = runCatching { EASession(accessory = accessory, forProtocol = protocol) }.getOrNull() ?: error(refused)
            val input: NSStream = opened.inputStream ?: error(refused)
            val output: NSStream = opened.outputStream ?: error(refused)
            session = opened
            listOf<NSStream>(input, output).forEach { stream ->
                stream.delegate = delegate
                stream.scheduleInRunLoop(NSRunLoop.mainRunLoop, forMode = NSDefaultRunLoopMode)
                stream.open()
            }
        }

        fun write(bytes: ByteArray) {
            check(session != null) { "MemoMind $protocol session is closed" }
            if (bytes.isEmpty()) return
            pending.addLast(bytes)
            flush()
        }

        fun close() {
            val current = session ?: return
            session = null
            pending.clear()
            pendingOffset = 0
            listOfNotNull<NSStream>(current.inputStream, current.outputStream).forEach { stream ->
                stream.delegate = null
                stream.close()
                stream.removeFromRunLoop(NSRunLoop.mainRunLoop, forMode = NSDefaultRunLoopMode)
            }
        }

        private fun flush() {
            val output = session?.outputStream ?: return
            while (pending.isNotEmpty() && output.hasSpaceAvailable) {
                val head = pending.first()
                val written = head.usePinned { pinned ->
                    output.write(pinned.addressOf(pendingOffset).reinterpret<UByteVar>(), (head.size - pendingOffset).toULong())
                }
                if (written < 0L) {
                    fail("write failed")
                    return
                }
                if (written == 0L) return
                pendingOffset += written.toInt()
                if (pendingOffset >= head.size) {
                    pending.removeFirst()
                    pendingOffset = 0
                }
            }
        }

        private fun readAvailable(input: NSInputStream) {
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (session != null && input.hasBytesAvailable) {
                val count = buffer.usePinned { pinned ->
                    input.read(pinned.addressOf(0).reinterpret<UByteVar>(), buffer.size.toULong())
                }
                if (count <= 0L) break
                onBytes(buffer.copyOf(count.toInt()))
            }
        }

        private fun fail(reason: String) {
            if (session == null) return
            PlatformLogger.w(TAG, "iAP2 $protocol $reason")
            close()
            onClosed()
        }

        private inner class StreamDelegate : NSObject(), NSStreamDelegateProtocol {
            override fun stream(aStream: NSStream, handleEvent: NSStreamEvent) {
                when (handleEvent) {
                    NSStreamEventHasBytesAvailable -> (aStream as? NSInputStream)?.let(::readAvailable)
                    NSStreamEventHasSpaceAvailable -> if (aStream is NSOutputStream) flush()
                    NSStreamEventEndEncountered -> fail("ended")
                    NSStreamEventErrorOccurred -> fail("error: ${aStream.streamError?.localizedDescription}")
                    else -> Unit
                }
            }
        }
    }

    companion object {
        private const val TAG = "IosMemoMindIap2"
        const val COMMAND_PROTOCOL = "ql.iap2.protocol02"
        const val RECORDING_PROTOCOL = "ql.iap2.protocol01"
        private const val READ_BUFFER_BYTES = 4096

        /** True when this build lists [protocol] under `UISupportedExternalAccessoryProtocols`. */
        fun isDeclared(protocol: String): Boolean =
            (NSBundle.mainBundle.objectForInfoDictionaryKey("UISupportedExternalAccessoryProtocols") as? List<*>)
                .orEmpty()
                .any { it == protocol }

        /** The connected MemoMind accessory offering GM commands over iAP2, when this build may open it. */
        fun findAccessory(): EAAccessory? {
            if (!isDeclared(COMMAND_PROTOCOL)) return null
            return EAAccessoryManager.sharedAccessoryManager().connectedAccessories
                .filterIsInstance<EAAccessory>()
                .firstOrNull { it.offers(COMMAND_PROTOCOL) }
        }

        private fun EAAccessory.offers(protocol: String): Boolean = protocolStrings.any { it == protocol }
    }
}
