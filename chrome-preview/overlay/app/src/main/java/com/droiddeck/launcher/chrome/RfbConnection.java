package com.droiddeck.launcher.chrome;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public final class RfbConnection implements Closeable {
    public interface Listener {
        default void connected() throws IOException { }
        void resized(int width, int height);
        void rectangle(int x, int y, int width, int height, int[] pixels);
        void frame();
    }

    private static final long MAX_PIXELS = 16L * 1024 * 1024;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final Closeable transport;
    private final Listener listener;
    private final ArrayBlockingQueue<byte[]> writes = new ArrayBlockingQueue<>(256);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile int width;
    private volatile int height;
    private volatile boolean ready;
    private Thread writer;

    public static RfbConnection loopback(int port, Listener listener) throws IOException {
        if (port < 1 || port > 65535) throw new IOException("Invalid local display port");
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 5000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(10000);
            return new RfbConnection(socket.getInputStream(), socket.getOutputStream(), socket, listener);
        } catch (IOException error) {
            socket.close();
            throw error;
        }
    }

    RfbConnection(InputStream input, OutputStream output, Closeable transport, Listener listener) {
        this.input = new DataInputStream(input);
        this.output = new DataOutputStream(output);
        this.transport = transport;
        this.listener = listener;
    }

    public void run(char[] password) throws IOException {
        try {
            try {
                handshake(password);
            } finally {
                Arrays.fill(password, '\0');
            }
            if (transport instanceof Socket) ((Socket) transport).setSoTimeout(0);
            listener.connected();
            writer = new Thread(() -> {
                try {
                    while (!closed.get()) {
                        byte[] message = writes.take();
                        output.write(message);
                        output.flush();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException error) {
                    closeQuietly();
                }
            }, "chrome-display-input");
            writer.setDaemon(true);
            writer.start();
            ready = true;
            requestFrame(false);
            while (!closed.get()) {
                int type = input.readUnsignedByte();
                if (type == 0) readFrame();
                else if (type == 2) { /* RFB Bell has no payload. */ }
                else if (type == 3) {
                    input.readFully(new byte[3]);
                    readText(64 * 1024);
                } else throw new IOException("Unsupported display message: " + type);
            }
        } finally {
            close();
        }
    }

    private void handshake(char[] password) throws IOException {
        byte[] version = new byte[12];
        input.readFully(version);
        if (!"RFB 003.008\n".equals(new String(version, StandardCharsets.US_ASCII))) {
            throw new IOException("The local display must support RFB 3.8");
        }
        output.write(version);
        output.flush();
        int count = input.readUnsignedByte();
        if (count == 0) throw new IOException("Display refused connection: " + readText(4096));
        boolean passwordAuth = false;
        for (int i = 0; i < count; i++) passwordAuth |= input.readUnsignedByte() == 2;
        if (!passwordAuth) throw new IOException("The local display did not offer password authentication");
        output.writeByte(2);
        output.flush();
        byte[] challenge = new byte[16];
        byte[] key = new byte[8];
        byte[] response = null;
        try {
            input.readFully(challenge);
            for (int i = 0; i < Math.min(password.length, key.length); i++) {
                key[i] = (byte) (Integer.reverse(password[i] & 255) >>> 24);
            }
            // RFB VncAuth specifies DES with the password bits reversed.
            Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
            response = cipher.doFinal(challenge);
            output.write(response);
            output.flush();
        } catch (GeneralSecurityException error) {
            throw new IOException("Local display authentication is unavailable", error);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(challenge, (byte) 0);
            if (response != null) Arrays.fill(response, (byte) 0);
        }
        if (input.readInt() != 0) throw new IOException("Display authentication failed: " + readText(4096));
        output.writeByte(1);
        output.flush();
        int initialWidth = input.readUnsignedShort();
        int initialHeight = input.readUnsignedShort();
        input.readFully(new byte[16]);
        readText(4096);
        resize(initialWidth, initialHeight);
        output.write(new byte[] {0, 0, 0, 0, 32, 24, 0, 1,
                0, (byte) 255, 0, (byte) 255, 0, (byte) 255, 16, 8, 0, 0, 0, 0});
        output.writeByte(2);
        output.writeByte(0);
        output.writeShort(3);
        output.writeInt(0);
        output.writeInt(-223);
        output.writeInt(-224);
        output.flush();
    }

    private String readText(int limit) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > limit) throw new IOException("Display text exceeds its size limit");
        byte[] bytes = new byte[size];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void resize(int newWidth, int newHeight) throws IOException {
        if (newWidth < 1 || newHeight < 1 || (long) newWidth * newHeight > MAX_PIXELS) {
            throw new IOException("Display dimensions exceed their size limit");
        }
        width = newWidth;
        height = newHeight;
        listener.resized(width, height);
    }

    private void readFrame() throws IOException {
        input.readUnsignedByte();
        int rectangles = input.readUnsignedShort();
        if (rectangles > 4096 && rectangles != 65535) throw new IOException("Too many display rectangles");
        boolean resized = false;
        for (int i = 0; i < rectangles; i++) {
            if (i >= 4096) throw new IOException("Display update exceeded the rectangle limit");
            int x = input.readUnsignedShort();
            int y = input.readUnsignedShort();
            int w = input.readUnsignedShort();
            int h = input.readUnsignedShort();
            int encoding = input.readInt();
            if (encoding == -224) break;
            if (encoding == -223) {
                resize(w, h);
                resized = true;
                continue;
            }
            if (encoding != 0) throw new IOException("Unsupported display encoding: " + encoding);
            if ((long) x + w > width || (long) y + h > height) {
                throw new IOException("Display rectangle exceeds the framebuffer");
            }
            int[] pixels = new int[w * h];
            byte[] row = new byte[w * 4];
            for (int line = 0; line < h; line++) {
                input.readFully(row);
                for (int column = 0; column < w; column++) {
                    int offset = column * 4;
                    pixels[line * w + column] = 0xff000000 | (row[offset] & 255)
                            | ((row[offset + 1] & 255) << 8) | ((row[offset + 2] & 255) << 16);
                }
            }
            listener.rectangle(x, y, w, h, pixels);
        }
        listener.frame();
        try {
            Thread.sleep(100);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Display connection interrupted", error);
        }
        requestFrame(!resized);
    }

    private void requestFrame(boolean incremental) throws IOException {
        enqueue(new byte[] {3, (byte) (incremental ? 1 : 0), 0, 0, 0, 0,
                (byte) (width >>> 8), (byte) width, (byte) (height >>> 8), (byte) height});
    }

    public boolean isReady() { return ready && !closed.get(); }

    public void pointer(int x, int y, int buttons) throws IOException {
        if (!isReady()) return;
        x = Math.max(0, Math.min(width - 1, x));
        y = Math.max(0, Math.min(height - 1, y));
        enqueue(new byte[] {5, (byte) buttons, (byte) (x >>> 8), (byte) x, (byte) (y >>> 8), (byte) y});
    }

    public void key(int keysym, boolean down) throws IOException {
        if (!isReady()) return;
        enqueue(new byte[] {4, (byte) (down ? 1 : 0), 0, 0, (byte) (keysym >>> 24),
                (byte) (keysym >>> 16), (byte) (keysym >>> 8), (byte) keysym});
    }

    private void enqueue(byte[] message) throws IOException {
        if (closed.get()) throw new IOException("Local display is disconnected");
        if (!writes.offer(message)) {
            close();
            throw new IOException("Local display input queue is full");
        }
    }

    private void closeQuietly() {
        try { close(); } catch (IOException ignored) { }
    }

    @Override public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            ready = false;
            if (writer != null) writer.interrupt();
            transport.close();
        }
    }
}
