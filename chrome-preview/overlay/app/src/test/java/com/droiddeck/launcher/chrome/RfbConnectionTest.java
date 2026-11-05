package com.droiddeck.launcher.chrome;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class RfbConnectionTest {
    private static final class Frames implements RfbConnection.Listener {
        int width;
        int frames;
        int[] pixels;
        public void resized(int width, int height) { this.width = width; }
        public void rectangle(int x, int y, int width, int height, int[] pixels) { this.pixels = pixels; }
        public void frame() { frames++; }
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static DataOutputStream handshake(ByteArrayOutputStream bytes, int width, int height) throws IOException {
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeBytes("RFB 003.008\n");
        out.write(new byte[] {1, 2});
        out.write(new byte[16]);
        out.writeInt(0);
        out.writeShort(width);
        out.writeShort(height);
        out.write(new byte[16]);
        text(out, "Local Linux display");
        return out;
    }

    private static RfbConnection connection(byte[] bytes, Frames frames) {
        return new RfbConnection(new ByteArrayInputStream(bytes), new ByteArrayOutputStream(), () -> { }, frames);
    }

    @Test public void rejectsUnauthenticatedServerAndClearsPassword() throws Exception {
        byte[] bytes = "RFB 003.008\n\001\001".getBytes(StandardCharsets.US_ASCII);
        char[] password = "test-only".toCharArray();
        IOException error = assertThrows(IOException.class, () -> connection(bytes, new Frames()).run(password));
        assertTrue(error.getMessage().contains("password authentication"));
        assertArrayEquals(new char[password.length], password);
    }

    @Test public void rejectsOversizedFramebufferBeforeAllocation() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        handshake(bytes, 65535, 65535);
        Frames frames = new Frames();
        IOException error = assertThrows(IOException.class,
                () -> connection(bytes.toByteArray(), frames).run("test".toCharArray()));
        assertTrue(error.getMessage().contains("dimensions"));
        assertEquals(0, frames.width);
    }

    @Test public void decodesNegotiatedBgrPixels() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = handshake(bytes, 2, 1);
        out.write(new byte[] {0, 0, 0, 1});
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(2);
        out.writeShort(1);
        out.writeInt(0);
        out.write(new byte[] {0x33, 0x22, 0x11, 0, (byte) 0xcc, (byte) 0xbb, (byte) 0xaa, 0});
        Frames frames = new Frames();
        assertThrows(IOException.class, () -> connection(bytes.toByteArray(), frames).run("test".toCharArray()));
        assertArrayEquals(new int[] {0xff112233, 0xffaabbcc}, frames.pixels);
    }

    @Test public void rejectsRectangleOutsideFramebuffer() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = handshake(bytes, 2, 1);
        out.write(new byte[] {0, 0, 0, 1});
        out.writeShort(1);
        out.writeShort(0);
        out.writeShort(2);
        out.writeShort(1);
        out.writeInt(0);
        Frames frames = new Frames();
        IOException error = assertThrows(IOException.class,
                () -> connection(bytes.toByteArray(), frames).run("test".toCharArray()));
        assertTrue(error.getMessage().contains("rectangle"));
        assertNull(frames.pixels);
    }

    @Test public void acceptsLastRectWithUnknownRectangleCount() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = handshake(bytes, 2, 1);
        out.write(new byte[] {0, 0, (byte) 255, (byte) 255});
        out.write(new byte[8]);
        out.writeInt(-224);
        Frames frames = new Frames();
        assertThrows(IOException.class, () -> connection(bytes.toByteArray(), frames).run("test".toCharArray()));
        assertEquals(1, frames.frames);
    }

    @Test public void rejectsOversizedServerText() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeBytes("RFB 003.008\n");
        out.writeByte(0);
        out.writeInt(Integer.MAX_VALUE);
        IOException error = assertThrows(IOException.class,
                () -> connection(bytes.toByteArray(), new Frames()).run("test".toCharArray()));
        assertTrue(error.getMessage().contains("text exceeds"));
    }
}
