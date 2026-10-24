package com.fersaiyan.cyanbridge;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Serves the newest decoded frame to local MJPEG consumers. */
public final class MjpegServer implements Closeable {
    private static final String BOUNDARY = "cyanbridge-frame";
    private static final int MAX_CLIENTS = 4;
    private static final int MAX_HEADER_BYTES = 8192;

    private final ServerSocket serverSocket;
    private final Object frameLock = new Object();
    private final Set<Socket> clients = new HashSet<>();
    private final ExecutorService clientWorkers = Executors.newFixedThreadPool(MAX_CLIENTS);
    private volatile boolean closed;
    private Frame latestFrame;
    private long nextSequence;

    private static final class Frame {
        final long sequence;
        final byte[] jpeg;

        Frame(long sequence, byte[] jpeg) {
            this.sequence = sequence;
            this.jpeg = jpeg;
        }
    }

    public MjpegServer(int port) throws IOException {
        serverSocket = new ServerSocket();
        try {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(
                    InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port), MAX_CLIENTS);
        } catch (IOException error) {
            serverSocket.close();
            clientWorkers.shutdownNow();
            throw error;
        }

        Thread acceptThread = new Thread(this::acceptClients, "cyanbridge-mjpeg-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public boolean publish(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) {
            throw new IllegalArgumentException("A complete JPEG frame is required");
        }
        synchronized (frameLock) {
            if (closed) return false;
            latestFrame = new Frame(++nextSequence, jpeg.clone());
            frameLock.notifyAll();
            return true;
        }
    }

    private void acceptClients() {
        while (!closed) {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
                socket.setSoTimeout(5000);
                socket.setTcpNoDelay(true);
                synchronized (clients) {
                    if (closed || clients.size() >= MAX_CLIENTS) {
                        socket.close();
                        continue;
                    }
                    clients.add(socket);
                }
                Socket client = socket;
                clientWorkers.execute(() -> serve(client));
            } catch (IOException | RejectedExecutionException error) {
                if (socket != null) {
                    synchronized (clients) {
                        clients.remove(socket);
                    }
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // The socket is already being discarded.
                    }
                }
            }
        }
    }

    private static String readRequestLine(InputStream input) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int terminator = 0;
        while (header.size() < MAX_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) throw new EOFException("Client closed before sending HTTP headers");
            header.write(value);
            terminator = (terminator << 8) | value;
            if (terminator == 0x0d0a0d0a) {
                String text = new String(header.toByteArray(), StandardCharsets.US_ASCII);
                return text.split("\r\n", 2)[0];
            }
        }
        throw new IOException("HTTP request headers exceed the configured limit");
    }

    private void serve(Socket socket) {
        try (Socket client = socket) {
            String[] request = readRequestLine(client.getInputStream()).split(" ");
            OutputStream output = client.getOutputStream();
            if (request.length != 3 || !"GET".equals(request[0]) ||
                    !"/mjpeg".equals(request[1]) || !request[2].startsWith("HTTP/1.")) {
                output.write(("HTTP/1.1 404 Not Found\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                output.flush();
                return;
            }

            output.write(("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            long deliveredSequence = 0;
            while (!closed) {
                Frame frame;
                synchronized (frameLock) {
                    while (!closed && (latestFrame == null || latestFrame.sequence == deliveredSequence)) {
                        frameLock.wait();
                    }
                    if (closed) return;
                    frame = latestFrame;
                }

                output.write(("--" + BOUNDARY + "\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: " + frame.jpeg.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                output.write(frame.jpeg);
                output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                deliveredSequence = frame.sequence;
            }
        } catch (IOException ignored) {
            // A consumer disconnecting does not stop camera capture or other clients.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (clients) {
                clients.remove(socket);
            }
        }
    }

    @Override
    public void close() {
        synchronized (frameLock) {
            if (closed) return;
            closed = true;
            latestFrame = null;
            frameLock.notifyAll();
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Closing the server is best-effort during teardown.
        }
        synchronized (clients) {
            for (Socket client : clients) {
                try {
                    client.close();
                } catch (IOException ignored) {
                    // The client is already being discarded.
                }
            }
            clients.clear();
        }
        clientWorkers.shutdownNow();
    }
}
