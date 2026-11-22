package com.lalilu.lplayer.audio;

import java.nio.ByteBuffer;

/** Quantizes interleaved float PCM to signed, little-endian, three-byte samples. */
final class Pcm24Packer {
    private Pcm24Packer() {}

    static void pack(ByteBuffer input, ByteBuffer output) {
        while (input.remaining() >= Float.BYTES && output.remaining() >= 3) {
            float sample = input.getFloat();
            int value = (int) Math.max(-8388608, Math.min(8388607, Math.round(sample * 8388608.0)));
            output.put((byte) value);
            output.put((byte) (value >> 8));
            output.put((byte) (value >> 16));
        }
    }
}
