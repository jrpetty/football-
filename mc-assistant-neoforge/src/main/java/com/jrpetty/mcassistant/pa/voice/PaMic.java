package com.jrpetty.mcassistant.pa.voice;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

/**
 * [pa33] The microphone, while the talk key is held: 16 kHz, 16-bit, mono (what both recognisers want), read on its
 * own thread. Gives the level for the listening indicator and each chunk for a live transcript. With no microphone
 * (or none the game may use) it says so and nothing else breaks. Plain Java (javax.sound).
 */
public final class PaMic {

    public static final AudioFormat FORMAT = new AudioFormat(16000F, 16, 1, true, false);

    private TargetDataLine line;
    private Thread reader;
    private final ByteArrayOutputStream heard = new ByteArrayOutputStream();
    private volatile boolean on;
    private volatile float level;
    private volatile String error = "";

    /** Start listening. False (with {@link #error()}) when there is no microphone to be had. */
    public boolean start(Consumer<byte[]> chunks) {
        stop();
        heard.reset();
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            if (!AudioSystem.isLineSupported(info)) {
                error = "No microphone found (or the game isn't allowed to use it).";
                return false;
            }
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(FORMAT, 3200 * 4);
            line.start();
        } catch (Throwable e) {
            error = "The microphone couldn't be opened: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            line = null;
            return false;
        }
        on = true;
        error = "";
        reader = new Thread(() -> {
            byte[] buf = new byte[3200];                 // a tenth of a second
            while (on && line != null) {
                int n = line.read(buf, 0, buf.length);
                if (n <= 0) continue;
                synchronized (heard) {
                    heard.write(buf, 0, n);
                }
                level = rms(buf, n);
                if (chunks != null) {
                    byte[] c = new byte[n];
                    System.arraycopy(buf, 0, c, 0, n);
                    try { chunks.accept(c); } catch (Throwable ignored) { }
                }
                if (heard.size() > 16000 * 2 * 20) on = false;   // twenty seconds at most
            }
        }, "mca-assistant-mic");
        reader.setDaemon(true);
        reader.start();
        return true;
    }

    /** Stop and give back everything heard (raw 16 kHz 16-bit mono). */
    public byte[] stop() {
        on = false;
        if (line != null) {
            try {
                line.stop();
                line.close();
            } catch (Throwable ignored) {
                // already gone
            }
            line = null;
        }
        if (reader != null) {
            try { reader.join(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            reader = null;
        }
        synchronized (heard) {
            return heard.toByteArray();
        }
    }

    public boolean listening() { return on; }

    /** 0..1: how loud, for the indicator. */
    public float level() { return level; }

    public String error() { return error; }

    static float rms(byte[] b, int n) {
        long sum = 0;
        int count = n / 2;
        for (int i = 0; i + 1 < n; i += 2) {
            int s = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
            sum += (long) s * s;
        }
        if (count == 0) return 0;
        double r = Math.sqrt(sum / (double) count) / 32768.0;
        return (float) Math.min(1.0, r * 6.0);
    }
}
