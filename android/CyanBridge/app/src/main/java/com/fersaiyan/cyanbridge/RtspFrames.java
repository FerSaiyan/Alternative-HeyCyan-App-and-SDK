package com.fersaiyan.cyanbridge;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Receives H.264 over RTSP/RTP interleaved TCP and emits decoded JPEG frames. */
public final class RtspFrames implements Closeable {
    public interface Listener {
        void onFrame(byte[] jpeg);

        void onError(String message);
    }

    private static final String TAG = "LivePreview";
    private static final int RTSP_PORT = 8554;
    private static final String STREAM_PATH = "/ch0";
    private static final byte[] ANNEX_B = new byte[]{0, 0, 0, 1};
    private static final int MAX_RTSP_HEADER_BYTES = 32 * 1024;
    private static final int MAX_RTSP_BODY_BYTES = 1024 * 1024;
    private static final int MAX_ACCESS_UNIT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_JPEG_BYTES = 1_000_000;
    private static final long MAX_DECODED_PIXELS = 4_000_000L;
    private static final int MAX_FRAME_DIMENSION = 960;

    private final String host;
    private final String streamUrl;
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicInteger cseq = new AtomicInteger();
    private final Object outputLock = new Object();
    private final ScheduledExecutorService keepAlive =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "cyanbridge-rtsp-keepalive");
                thread.setDaemon(true);
                return thread;
            });
    private final ByteArrayOutputStream accessUnit = new ByteArrayOutputStream();

    private volatile Socket socket;
    private volatile InputStream input;
    private volatile OutputStream output;
    private volatile String sessionId;
    private volatile long remoteSsrc;
    private volatile int highestSequence;

    private MediaCodec decoder;
    private byte[] sps;
    private byte[] pps;
    private boolean fragmentInProgress;
    private long currentTimestamp = -1L;
    private long lastPresentationTimeUs = -1L;
    private long lastFrameNanos;

    public RtspFrames(String host, Listener listener) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("A glasses IP address is required");
        }
        if (listener == null) throw new IllegalArgumentException("A frame listener is required");
        this.host = host;
        this.listener = listener;
        this.streamUrl = "rtsp://" + host + ":" + RTSP_PORT + STREAM_PATH;
    }

    public void start() {
        if (closed.get()) throw new IllegalStateException("RTSP frame receiver is already closed");
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("RTSP frame receiver can only be started once");
        }
        Thread receiver = new Thread(this::receive, "cyanbridge-rtsp-receiver");
        receiver.setDaemon(true);
        receiver.start();
    }

    private void receive() {
        try {
            connectAndStartStream();
            while (!closed.get()) {
                try {
                    int marker = input.read();
                    if (marker < 0) throw new EOFException("RTSP server closed the connection");
                    if (marker == '$') {
                        readInterleavedPacket();
                    } else {
                        discardRtspResponse(marker);
                    }
                } catch (SocketTimeoutException timeout) {
                    // The scheduled keepalive sends RTSP and RTCP traffic while this read idles.
                }
            }
        } catch (Exception error) {
            if (!closed.get()) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                Log.e(TAG, "RTSP receive failed: " + message, error);
                listener.onError(message);
            }
        } finally {
            releaseDecoder();
            close();
        }
    }

    private void connectAndStartStream() throws IOException {
        if (closed.get()) return;
        Socket nextSocket = new Socket();
        socket = nextSocket;
        nextSocket.setTcpNoDelay(true);
        nextSocket.setSoTimeout(10_000);
        nextSocket.connect(new InetSocketAddress(host, RTSP_PORT), 10_000);
        if (closed.get()) throw new IOException("RTSP session was stopped");
        input = nextSocket.getInputStream();
        output = nextSocket.getOutputStream();

        Response options = sendRequest("OPTIONS", streamUrl, "");
        requireSuccess("OPTIONS", options);

        Response description = sendRequest(
                "DESCRIBE", streamUrl, "Accept: application/sdp\r\n");
        requireSuccess("DESCRIBE", description);
        String trackUrl = findTrackUrl(description);

        Response setup = sendRequest(
                "SETUP",
                trackUrl,
                "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
        requireSuccess("SETUP", setup);
        String session = setup.header("session");
        if (session == null || session.isEmpty()) {
            throw new IOException("RTSP SETUP response did not include a session identifier");
        }
        int separator = session.indexOf(';');
        sessionId = separator >= 0 ? session.substring(0, separator).trim() : session.trim();

        Response play = sendRequest("PLAY", streamUrl, "Range: npt=0.000-\r\n");
        requireSuccess("PLAY", play);
        Log.i(TAG, "Receiving glasses stream at " + streamUrl);

        nextSocket.setSoTimeout(1000);
        keepAlive.scheduleAtFixedRate(this::sendKeepalive, 2, 2, TimeUnit.SECONDS);
    }

    private Response sendRequest(String method, String target, String extraHeaders) throws IOException {
        int requestNumber = cseq.incrementAndGet();
        StringBuilder request = new StringBuilder()
                .append(method).append(' ').append(target).append(" RTSP/1.0\r\n")
                .append("CSeq: ").append(requestNumber).append("\r\n")
                .append("User-Agent: CyanBridge/1.0\r\n");
        if (sessionId != null && !sessionId.isEmpty()) {
            request.append("Session: ").append(sessionId).append("\r\n");
        }
        request.append(extraHeaders).append("\r\n");
        synchronized (outputLock) {
            output.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }
        return readResponse();
    }

    private Response readResponse() throws IOException {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int terminator = 0;
        while (headerBytes.size() < MAX_RTSP_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) throw new EOFException("RTSP response ended before its headers");
            headerBytes.write(value);
            terminator = (terminator << 8) | value;
            if (terminator == 0x0d0a0d0a) break;
        }
        if (headerBytes.size() >= MAX_RTSP_HEADER_BYTES && terminator != 0x0d0a0d0a) {
            throw new IOException("RTSP response headers exceed the configured limit");
        }

        String[] lines = new String(headerBytes.toByteArray(), StandardCharsets.US_ASCII).split("\r\n");
        if (lines.length == 0 || !lines[0].startsWith("RTSP/1.")) {
            throw new IOException("Invalid RTSP response status line");
        }
        Map<String, String> headers = new HashMap<>();
        for (int index = 1; index < lines.length; index++) {
            int colon = lines[index].indexOf(':');
            if (colon > 0) {
                headers.put(
                        lines[index].substring(0, colon).trim().toLowerCase(Locale.US),
                        lines[index].substring(colon + 1).trim());
            }
        }

        int bodyLength = parseContentLength(headers.get("content-length"));
        byte[] body = readExact(input, bodyLength);
        return new Response(lines[0], headers, new String(body, StandardCharsets.US_ASCII));
    }

    private static byte[] readExact(InputStream source, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = source.read(data, offset, length - offset);
            if (count < 0) throw new EOFException("RTSP response body was truncated");
            offset += count;
        }
        return data;
    }

    private void requireSuccess(String method, Response response) throws IOException {
        String[] fields = response.statusLine.split(" ", 3);
        if (fields.length < 2 || !"200".equals(fields[1])) {
            throw new IOException(method + " failed: " + response.statusLine);
        }
    }

    private String findTrackUrl(Response description) throws IOException {
        String base = description.header("content-base");
        if (base == null || base.isEmpty()) base = streamUrl + "/";
        String activeMedia = "";
        for (String line : description.body.split("\r?\n")) {
            if (line.startsWith("m=")) {
                activeMedia = line.startsWith("m=video") ? "video" : "";
            } else if ("video".equals(activeMedia) && line.startsWith("a=control:")) {
                String control = line.substring("a=control:".length()).trim();
                if (control.isEmpty() || "*".equals(control)) return streamUrl;
                if (control.startsWith("rtsp://")) return control;
                if (control.startsWith("/")) {
                    int authorityStart = streamUrl.indexOf("://") + 3;
                    int pathStart = streamUrl.indexOf('/', authorityStart);
                    return (pathStart >= 0 ? streamUrl.substring(0, pathStart) : streamUrl) + control;
                }
                if (!base.endsWith("/")) base += "/";
                return java.net.URI.create(base).resolve(control).toString();
            }
        }
        return streamUrl;
    }

    private void readInterleavedPacket() throws IOException {
        int channel = input.read();
        int high = input.read();
        int low = input.read();
        if ((channel | high | low) < 0) throw new EOFException("Incomplete interleaved RTP header");
        int packetLength = (high << 8) | low;
        byte[] packet = readExact(input, packetLength);
        if (channel == 0) receiveRtp(packet);
    }

    private void discardRtspResponse(int firstByte) throws IOException {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        received.write(firstByte);
        int terminator = firstByte & 0xff;
        while (received.size() < MAX_RTSP_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) throw new EOFException("RTSP server closed while sending a response");
            received.write(value);
            terminator = (terminator << 8) | value;
            if (terminator == 0x0d0a0d0a) break;
        }
        String[] lines = new String(received.toByteArray(), StandardCharsets.US_ASCII).split("\r\n");
        for (String line : lines) {
            if (line.toLowerCase(Locale.US).startsWith("content-length:")) {
                int bodyLength = parseContentLength(line.substring(line.indexOf(':') + 1).trim());
                readExact(input, bodyLength);
                break;
            }
        }
    }

    private void receiveRtp(byte[] packet) throws IOException {
        if (packet.length < 12 || ((packet[0] >> 6) & 0x03) != 2) return;
        int headerBytes = 12 + (packet[0] & 0x0f) * 4;
        if (headerBytes > packet.length) return;
        if ((packet[0] & 0x10) != 0) {
            if (headerBytes + 4 > packet.length) return;
            int extensionLength = ((packet[headerBytes + 2] & 0xff) << 8) |
                    (packet[headerBytes + 3] & 0xff);
            headerBytes += 4 + extensionLength * 4;
        }
        if (headerBytes >= packet.length) return;
        int packetEnd = packet.length;
        if ((packet[0] & 0x20) != 0) {
            int padding = packet[packet.length - 1] & 0xff;
            if (padding == 0 || padding > packetEnd - headerBytes) return;
            packetEnd -= padding;
        }
        if (headerBytes >= packetEnd) return;

        boolean marker = (packet[1] & 0x80) != 0;
        int sequence = ((packet[2] & 0xff) << 8) | (packet[3] & 0xff);
        long timestamp = ((packet[4] & 0xffL) << 24) |
                ((packet[5] & 0xffL) << 16) |
                ((packet[6] & 0xffL) << 8) |
                (packet[7] & 0xffL);
        remoteSsrc = ((packet[8] & 0xffL) << 24) |
                ((packet[9] & 0xffL) << 16) |
                ((packet[10] & 0xffL) << 8) |
                (packet[11] & 0xffL);
        highestSequence = sequence;

        if (currentTimestamp != -1L && currentTimestamp != timestamp && accessUnit.size() > 0) {
            if (fragmentInProgress) {
                accessUnit.reset();
                fragmentInProgress = false;
            } else {
                decodeAccessUnit(currentTimestamp);
            }
        }
        currentTimestamp = timestamp;
        appendH264Payload(packet, headerBytes, packetEnd - headerBytes);
        if (marker) decodeAccessUnit(timestamp);
    }

    private void appendH264Payload(byte[] packet, int offset, int length) throws IOException {
        if (length <= 0) return;
        int packetType = packet[offset] & 0x1f;
        if (packetType >= 1 && packetType <= 23) {
            appendNal(packet, offset, length);
        } else if (packetType == 24) {
            int cursor = offset + 1;
            int limit = offset + length;
            while (cursor + 2 <= limit) {
                int nalLength = ((packet[cursor] & 0xff) << 8) | (packet[cursor + 1] & 0xff);
                cursor += 2;
                if (nalLength == 0 || cursor + nalLength > limit) {
                    throw new IOException("Malformed H.264 aggregation packet");
                }
                appendNal(packet, cursor, nalLength);
                cursor += nalLength;
            }
            if (cursor != limit) throw new IOException("Malformed H.264 aggregation packet");
        } else if (packetType == 28) {
            if (length < 2) return;
            int fragmentHeader = packet[offset + 1] & 0xff;
            boolean first = (fragmentHeader & 0x80) != 0;
            boolean last = (fragmentHeader & 0x40) != 0;
            if (first) {
                accessUnit.reset();
                fragmentInProgress = true;
                writeAnnexB();
                accessUnit.write((packet[offset] & 0xe0) | (fragmentHeader & 0x1f));
            } else if (!fragmentInProgress) {
                return;
            }
            accessUnit.write(packet, offset + 2, length - 2);
            if (last) fragmentInProgress = false;
            ensureAccessUnitSize();
        }
    }

    private void appendNal(byte[] bytes, int offset, int length) throws IOException {
        if (length == 0) return;
        int type = bytes[offset] & 0x1f;
        if (type == 7) sps = copyOfRange(bytes, offset, length);
        if (type == 8) pps = copyOfRange(bytes, offset, length);
        writeAnnexB();
        accessUnit.write(bytes, offset, length);
        ensureAccessUnitSize();
    }

    private void writeAnnexB() throws IOException {
        accessUnit.write(ANNEX_B);
        ensureAccessUnitSize();
    }

    private void ensureAccessUnitSize() throws IOException {
        if (accessUnit.size() > MAX_ACCESS_UNIT_BYTES) {
            accessUnit.reset();
            fragmentInProgress = false;
            throw new IOException("H.264 access unit exceeded the size limit");
        }
    }

    private void decodeAccessUnit(long timestamp) throws IOException {
        if (accessUnit.size() == 0) return;
        byte[] encoded = accessUnit.toByteArray();
        accessUnit.reset();
        fragmentInProgress = false;
        if (sps == null || pps == null) return;

        if (decoder == null) configureDecoder();
        int inputIndex = decoder.dequeueInputBuffer(10_000);
        if (inputIndex < 0) return;
        ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
        if (inputBuffer == null || inputBuffer.capacity() < encoded.length) {
            throw new IOException("H.264 decoder input buffer is too small");
        }
        inputBuffer.clear();
        inputBuffer.put(encoded);
        long presentationTimeUs = timestamp * 1_000_000L / 90_000L;
        if (presentationTimeUs <= lastPresentationTimeUs) presentationTimeUs = lastPresentationTimeUs + 1;
        lastPresentationTimeUs = presentationTimeUs;
        decoder.queueInputBuffer(inputIndex, 0, encoded.length, presentationTimeUs, 0);
        drainDecodedFrames();
    }

    private void configureDecoder() throws IOException {
        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 1920, 1080);
            format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps)));
            MediaCodec newDecoder = MediaCodec.createDecoderByType("video/avc");
            newDecoder.configure(format, null, null, 0);
            newDecoder.start();
            decoder = newDecoder;
        } catch (Exception error) {
            throw new IOException("Could not initialize the H.264 decoder", error);
        }
    }

    private void drainDecodedFrames() throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int outputIndex;
        while ((outputIndex = decoder.dequeueOutputBuffer(info, 0)) >= 0) {
            Image image = null;
            try {
                image = decoder.getOutputImage(outputIndex);
                if (image != null) {
                    long now = System.nanoTime();
                    if (now - lastFrameNanos >= TimeUnit.SECONDS.toNanos(1)) {
                        byte[] jpeg = compressImage(image);
                        if (jpeg != null) {
                            lastFrameNanos = now;
                            listener.onFrame(jpeg);
                        }
                    }
                }
            } catch (RuntimeException error) {
                throw new IOException("Could not convert decoded video to JPEG", error);
            } finally {
                if (image != null) image.close();
                decoder.releaseOutputBuffer(outputIndex, false);
            }
        }
    }

    private static byte[] compressImage(Image image) throws IOException {
        if (image.getFormat() != ImageFormat.YUV_420_888) return null;
        Rect crop = image.getCropRect();
        int width = crop.width();
        int height = crop.height();
        if (width <= 0 || height <= 0) return null;
        if ((long) width * height > MAX_DECODED_PIXELS) {
            throw new IOException("Decoded video frame exceeds the pixel limit");
        }

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yPlane = planes[0].getBuffer();
        ByteBuffer uPlane = planes[1].getBuffer();
        ByteBuffer vPlane = planes[2].getBuffer();
        double scale = Math.min(1.0, MAX_FRAME_DIMENSION / (double) Math.max(width, height));
        int outputWidth = Math.max(1, (int) Math.round(width * scale));
        int outputHeight = Math.max(1, (int) Math.round(height * scale));
        int[] rgb = new int[outputWidth * outputHeight];
        for (int row = 0; row < outputHeight; row++) {
            int sourceRow = Math.min(height - 1, (int) ((long) row * height / outputHeight));
            int sourceY = (sourceRow + crop.top) * planes[0].getRowStride() +
                    crop.left * planes[0].getPixelStride();
            int sourceU = ((sourceRow + crop.top) / 2) * planes[1].getRowStride() +
                    (crop.left / 2) * planes[1].getPixelStride();
            int sourceV = ((sourceRow + crop.top) / 2) * planes[2].getRowStride() +
                    (crop.left / 2) * planes[2].getPixelStride();
            for (int column = 0; column < outputWidth; column++) {
            int sourceColumn = Math.min(width - 1, (int) ((long) column * width / outputWidth));
            int y = yPlane.get(sourceY + sourceColumn * planes[0].getPixelStride()) & 0xff;
            int u = uPlane.get(sourceU + (sourceColumn / 2) * planes[1].getPixelStride()) & 0xff;
            int v = vPlane.get(sourceV + (sourceColumn / 2) * planes[2].getPixelStride()) & 0xff;
                int luminance = Math.max(0, y - 16);
                int red = clamp((298 * luminance + 409 * (v - 128) + 128) >> 8);
                int green = clamp((298 * luminance - 100 * (u - 128) - 208 * (v - 128) + 128) >> 8);
                int blue = clamp((298 * luminance + 516 * (u - 128) + 128) >> 8);
                rgb[row * outputWidth + column] = 0xff000000 | (red << 16) | (green << 8) | blue;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(rgb, outputWidth, outputHeight, Bitmap.Config.ARGB_8888);

        try {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            while (true) {
                for (int quality = 75; quality >= 45; quality -= 10) {
                    compressed.reset();
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, compressed)) {
                        throw new IOException("JPEG encoder rejected a decoded frame");
                    }
                    if (compressed.size() <= MAX_JPEG_BYTES) return compressed.toByteArray();
                }
                if (bitmap.getWidth() <= 320 && bitmap.getHeight() <= 240) {
                    throw new IOException("JPEG frame remains over the one-megabyte limit at minimum size");
                }
                Bitmap resized = Bitmap.createScaledBitmap(
                        bitmap, Math.max(1, Math.round(bitmap.getWidth() * 0.75f)),
                        Math.max(1, Math.round(bitmap.getHeight() * 0.75f)), true);
                bitmap.recycle();
                bitmap = resized;
            }
        } finally {
            bitmap.recycle();
        }
    }

    private void sendKeepalive() {
        if (closed.get() || output == null) return;
        try {
            int requestNumber = cseq.incrementAndGet();
            StringBuilder request = new StringBuilder()
                    .append("OPTIONS ").append(streamUrl).append(" RTSP/1.0\r\n")
                    .append("CSeq: ").append(requestNumber).append("\r\n")
                    .append("User-Agent: CyanBridge/1.0\r\n");
            if (sessionId != null) request.append("Session: ").append(sessionId).append("\r\n");
            request.append("\r\n");
            synchronized (outputLock) {
                output.write(request.toString().getBytes(StandardCharsets.US_ASCII));
                output.write(interleavedRtcpReport());
                output.flush();
            }
        } catch (IOException error) {
            if (!closed.get()) Log.w(TAG, "RTSP keepalive failed: " + error.getMessage());
        }
    }

    private byte[] interleavedRtcpReport() {
        byte[] cname = "cyanbridge".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer report = ByteBuffer.allocate(4 + 32 + 24);
        report.put((byte) '$').put((byte) 1).putShort((short) 56);
        report.put((byte) 0x81).put((byte) 201).putShort((short) 7);
        report.putInt(0x43594252);
        report.putInt((int) remoteSsrc);
        report.put((byte) 0);
        report.put((byte) 0).put((byte) 0).put((byte) 0);
        report.putInt(highestSequence & 0xffff);
        report.putInt(0).putInt(0).putInt(0);
        report.put((byte) 0x81).put((byte) 202).putShort((short) 5);
        report.putInt(0x43594252);
        report.put((byte) 1).put((byte) cname.length).put(cname);
        report.put((byte) 0).put((byte) 0).put((byte) 0);
        return report.array();
    }

    @Override
    public void close() {
        boolean closingNow = closed.compareAndSet(false, true);
        if (closingNow) keepAlive.shutdownNow();
        Socket activeSocket = socket;
        OutputStream activeOutput = output;
        if (closingNow && activeSocket != null && activeSocket.isConnected() &&
                activeOutput != null && sessionId != null) {
            try {
                int requestNumber = cseq.incrementAndGet();
                String teardown = "TEARDOWN " + streamUrl + " RTSP/1.0\r\n" +
                        "CSeq: " + requestNumber + "\r\n" +
                        "Session: " + sessionId + "\r\n\r\n";
                synchronized (outputLock) {
                    activeOutput.write(teardown.getBytes(StandardCharsets.US_ASCII));
                    activeOutput.flush();
                }
            } catch (IOException ignored) {
                // The peer may have already closed its RTSP session.
            }
        }
        if (activeSocket != null) {
            try {
                activeSocket.close();
            } catch (IOException ignored) {
                // Closing the socket is best-effort during teardown.
            }
        }
    }

    private void releaseDecoder() {
        MediaCodec activeDecoder = decoder;
        decoder = null;
        if (activeDecoder == null) return;
        try {
            activeDecoder.stop();
        } catch (IllegalStateException ignored) {
            // A failed decoder may already be stopped.
        }
        activeDecoder.release();
    }

    private static byte[] withStartCode(byte[] nal) {
        byte[] result = new byte[ANNEX_B.length + nal.length];
        System.arraycopy(ANNEX_B, 0, result, 0, ANNEX_B.length);
        System.arraycopy(nal, 0, result, ANNEX_B.length, nal.length);
        return result;
    }

    private static byte[] copyOfRange(byte[] data, int offset, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);
        return copy;
    }

    private static int parseContentLength(String value) throws IOException {
        if (value == null) return 0;
        try {
            int length = Integer.parseInt(value);
            if (length < 0 || length > MAX_RTSP_BODY_BYTES) {
                throw new IOException("RTSP response body exceeds the configured size limit");
            }
            return length;
        } catch (NumberFormatException error) {
            throw new IOException("RTSP response has an invalid Content-Length", error);
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static final class Response {
        final String statusLine;
        final Map<String, String> headers;
        final String body;

        Response(String statusLine, Map<String, String> headers, String body) {
            this.statusLine = statusLine;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }
    }
}
