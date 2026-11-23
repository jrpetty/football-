package com.jrpetty.mcassistant.pa.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** [pa33] Raw 16-bit mono sound as a WAV file (for Windows' recogniser), and back. Plain Java. */
public final class PaWav {

    private PaWav() {}

    public static byte[] wav(byte[] pcm, int rate) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2)
            .putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(pcm.length).put(pcm);
        return b.array();
    }

    /** The samples of a 16-bit mono WAV (the header skipped; the data chunk found). */
    public static byte[] pcm(byte[] wav) {
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int at = 12;
        while (at + 8 <= wav.length) {
            String id = new String(wav, at, 4);
            int len = b.getInt(at + 4);
            if (id.equals("data")) {
                int n = Math.min(len, wav.length - at - 8);
                byte[] out = new byte[n];
                System.arraycopy(wav, at + 8, out, 0, n);
                return out;
            }
            at += 8 + len + (len & 1);
        }
        return new byte[0];
    }
}
