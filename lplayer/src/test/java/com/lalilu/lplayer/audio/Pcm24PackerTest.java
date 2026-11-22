package com.lalilu.lplayer.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class Pcm24PackerTest {
    @Test
    public void clipsAndPacksSignedLittleEndianSamplesInOrder() {
        assertArrayEquals(new byte[] {
                0, 0, (byte) 0x80, 0, 0, (byte) 0x80, 0, 0, (byte) 0xc0,
                0, 0, 0, 0, 0, 0x40, -1, -1, 0x7f, -1, -1, 0x7f
        }, pack(-2f, -1f, -0.5f, 0f, 0.5f, 1f, 2f));
    }

    @Test
    public void roundsAndHandlesNonFiniteSamples() {
        float lsb = 1f / 8388608;
        assertArrayEquals(new byte[] {
                1, 0, 0, -1, -1, -1, 0, 0, 0,
                -1, -1, 0x7f, 0, 0, (byte) 0x80
        }, pack(0.75f * lsb, -0.75f * lsb, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY));
    }

    @Test
    public void leavesUnreadSamplesWhenOutputFills() {
        ByteBuffer input = input(0f, 1f);
        ByteBuffer output = ByteBuffer.allocate(4);
        Pcm24Packer.pack(input, output);
        assertEquals(Float.BYTES, input.position());
        assertEquals(3, output.position());
    }

    private static byte[] pack(float... samples) {
        ByteBuffer output = ByteBuffer.allocate(samples.length * 3);
        Pcm24Packer.pack(input(samples), output);
        return output.array();
    }

    private static ByteBuffer input(float... samples) {
        ByteBuffer input = ByteBuffer.allocate(samples.length * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        for (float sample : samples) {
            input.putFloat(sample);
        }
        input.flip();
        return input;
    }
}
