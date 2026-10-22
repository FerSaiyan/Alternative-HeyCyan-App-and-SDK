package com.fersaiyan.cyanbridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class MjpegServerTest {
    @Test
    public void servesPublishedFramesToLoopbackClients() throws Exception {
        byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, 0x01, 0x02, (byte) 0xff, (byte) 0xd9};
        try (MjpegServer server = new MjpegServer(0);
             Socket client = new Socket(InetAddress.getByName("127.0.0.1"), server.port())) {
            client.setSoTimeout(5000);
            OutputStream output = client.getOutputStream();
            InputStream input = client.getInputStream();
            output.write("GET /mjpeg HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();

            String responseHeaders = readHeaderBlock(input);
            assertTrue(responseHeaders.startsWith("HTTP/1.1 200 OK"));
            assertTrue(responseHeaders.contains("multipart/x-mixed-replace"));

            assertTrue(server.publish(jpeg));
            String frameHeaders = readHeaderBlock(input);
            assertTrue(frameHeaders.contains("Content-Type: image/jpeg"));
            assertTrue(frameHeaders.contains("Content-Length: " + jpeg.length));
            assertArrayEquals(jpeg, readExactly(input, jpeg.length));
        }
    }

    private static String readHeaderBlock(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int tail = 0;
        while (bytes.size() < 8192) {
            int value = input.read();
            if (value < 0) throw new AssertionError("Connection ended before headers were complete");
            bytes.write(value);
            tail = (tail << 8) | value;
            if (tail == 0x0d0a0d0a) {
                return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
            }
        }
        throw new AssertionError("Headers exceeded the test limit");
    }

    private static byte[] readExactly(InputStream input, int length) throws Exception {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) throw new AssertionError("Frame payload was truncated");
            offset += count;
        }
        return bytes;
    }
}
