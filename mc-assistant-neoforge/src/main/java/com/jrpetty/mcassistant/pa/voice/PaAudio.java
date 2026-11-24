package com.jrpetty.mcassistant.pa.voice;

/**
 * [pa35] The natural voice's sound, finished like a broadcast: silence trimmed from the ends, loudness measured the
 * broadcast way (ITU-R BS.1770: K-weighted, gated) and set to about -16 LUFS so every line sits at the same level, a soft
 * limiter so nothing ever clips (peaks held under -1 dBFS), a short fade in and out so no line starts or ends with a
 * click, and a gentle pitch lift or drop by resampling (the mood: gloomy a touch lower, the joker a touch brighter).
 * Full 24 kHz throughout. Pure Java on float samples (-1..1), unit-tested on synthetic audio.
 */
public final class PaAudio {

    private PaAudio() {}

    public static final double TARGET_LUFS = -16.0, CEILING_DB = -1.0;

    // ------------------------------------------------------------------ loudness (BS.1770-4)

    /** A two-pole filter section, run over a signal. */
    record Biquad(double b0, double b1, double b2, double a1, double a2) {
        double[] run(double[] x) {
            double[] y = new double[x.length];
            double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
            for (int i = 0; i < x.length; i++) {
                double v = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
                x2 = x1; x1 = x[i]; y2 = y1; y1 = v;
                y[i] = v;
            }
            return y;
        }
    }

    /** The K-weighting's first stage: a high shelf of +4 dB from about 1.7 kHz (the head's effect), designed for this sample rate (as libebur128). */
    static Biquad shelf(double fs) {
        double f0 = 1681.974450955533, g = 3.999843853973347, q = 0.7071752369554196;
        double k = Math.tan(Math.PI * f0 / fs), vh = Math.pow(10, g / 20), vb = Math.pow(vh, 0.4996667741545416), a0 = 1 + k / q + k * k;
        return new Biquad((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0);
    }

    /** The K-weighting's second stage: a high-pass at about 38 Hz. */
    static Biquad highPass(double fs) {
        double f0 = 38.13547087602444, q = 0.5003270373238773;
        double k = Math.tan(Math.PI * f0 / fs), a0 = 1 + k / q + k * k;
        return new Biquad(1, -2, 1, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0);
    }

    /** Integrated loudness in LUFS (mono): -70 for silence. 400 ms blocks, 75% overlap, absolute and relative gates. */
    public static double lufs(float[] audio, int rate) {
        if (audio.length == 0) return -70;
        double[] x = new double[audio.length];
        for (int i = 0; i < x.length; i++) x[i] = audio[i];
        double[] k = highPass(rate).run(shelf(rate).run(x));
        int block = Math.max(1, (int) Math.round(0.4 * rate)), step = Math.max(1, block / 4);
        java.util.List<Double> z = new java.util.ArrayList<>();
        if (k.length < block) z.add(meanSquare(k, 0, k.length));
        else for (int s = 0; s + block <= k.length; s += step) z.add(meanSquare(k, s, block));
        double absSum = 0;
        int absN = 0;
        for (double v : z) if (loud(v) > -70) { absSum += v; absN++; }
        if (absN == 0) return -70;
        double rel = loud(absSum / absN) - 10;
        double sum = 0;
        int n = 0;
        for (double v : z) if (loud(v) > -70 && loud(v) > rel) { sum += v; n++; }
        return n == 0 ? -70 : loud(sum / n);
    }

    private static double meanSquare(double[] k, int from, int len) {
        double s = 0;
        for (int i = from; i < from + len; i++) s += k[i] * k[i];
        return s / Math.max(1, len);
    }

    private static double loud(double meanSquare) {
        return meanSquare <= 0 ? -1000 : -0.691 + 10 * Math.log10(meanSquare);
    }

    public static float peak(float[] a) {
        float p = 0;
        for (float v : a) p = Math.max(p, Math.abs(v));
        return p;
    }

    // ------------------------------------------------------------------ finishing a line

    /** Loudness to the target (the gain kept within 1/20 .. 32 so near-silence isn't turned into a roar), then the limiter. */
    public static float[] normalise(float[] a, int rate, double targetLufs) {
        double l = lufs(a, rate);
        if (l <= -69) return a.clone();
        double gain = Math.pow(10, (targetLufs - l) / 20);
        gain = Math.max(0.05, Math.min(32.0, gain));
        float[] out = new float[a.length];
        for (int i = 0; i < a.length; i++) out[i] = (float) (a[i] * gain);
        return limit(out, CEILING_DB);
    }

    /** A soft limiter: untouched under the knee (3 dB below the ceiling), bent smoothly towards the ceiling above it: never over. */
    public static float[] limit(float[] a, double ceilingDb) {
        double c = Math.pow(10, ceilingDb / 20), knee = c * Math.pow(10, -3.0 / 20);
        float[] out = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            double v = a[i], m = Math.abs(v);
            if (m > knee) m = knee + (c - knee) * Math.tanh((m - knee) / (c - knee));
            out[i] = (float) Math.copySign(Math.min(m, c * 0.9999), v);
        }
        return out;
    }

    /**
     * [voice37] The true peak in dBFS (ITU-R BS.1770-4 style: four times oversampled, a windowed-sinc between samples), so a
     * peak that falls between two samples is found too; -inf for silence.
     */
    public static double truePeakDb(float[] a) {
        double peak = 0;
        for (int i = 0; i < a.length; i++) {
            peak = Math.max(peak, Math.abs(a[i]));
            for (int k = 0; k < TP_OVER - 1; k++) {
                double v = 0;
                double[] c = TP_KERNEL[k];
                for (int j = 0; j < c.length; j++) {
                    int n = i + j - TP_TAPS + 1;
                    if (n >= 0 && n < a.length) v += a[n] * c[j];
                }
                peak = Math.max(peak, Math.abs(v));
            }
        }
        return peak <= 0 ? Double.NEGATIVE_INFINITY : 20 * Math.log10(peak);
    }

    private static final int TP_OVER = 4, TP_TAPS = 8;
    /** The interpolation weights for each in-between point (a Hann-windowed sinc), worked out once. */
    private static final double[][] TP_KERNEL = new double[TP_OVER - 1][2 * TP_TAPS];
    static {
        for (int k = 1; k < TP_OVER; k++)
            for (int j = -TP_TAPS + 1; j <= TP_TAPS; j++) {
                double x = k / (double) TP_OVER - j;
                double sinc = Math.abs(x) < 1e-9 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
                TP_KERNEL[k - 1][j + TP_TAPS - 1] = sinc * (0.5 + 0.5 * Math.cos(Math.PI * x / TP_TAPS));
            }
    }

    /** [voice37] Turned down just enough that the true peak is at or under the ceiling (a whole-clip gain: no distortion). */
    public static float[] truePeakCeiling(float[] a, double ceilingDb) {
        double tp = truePeakDb(a);
        if (!(tp > ceilingDb - 0.05)) return a;
        double g = Math.pow(10, (ceilingDb - 0.1 - tp) / 20);
        float[] out = new float[a.length];
        for (int i = 0; i < a.length; i++) out[i] = (float) (a[i] * g);
        return out;
    }

    /** Raised-cosine fades in and out (milliseconds): no click at either end. */
    public static float[] fade(float[] a, int rate, double inMs, double outMs) {
        float[] out = a.clone();
        int fi = Math.min(out.length / 2, (int) Math.round(inMs / 1000 * rate)), fo = Math.min(out.length / 2, (int) Math.round(outMs / 1000 * rate));
        for (int i = 0; i < fi; i++) out[i] *= (float) (0.5 - 0.5 * Math.cos(Math.PI * i / fi));
        for (int i = 0; i < fo; i++) out[out.length - 1 - i] *= (float) (0.5 - 0.5 * Math.cos(Math.PI * i / fo));
        return out;
    }

    /** Silence off both ends (under -50 dBFS), keeping a little air (padMs) either side. */
    public static float[] trim(float[] a, int rate, double padMs) {
        double t = Math.pow(10, -50.0 / 20);
        int s = 0, e = a.length - 1;
        while (s < a.length && Math.abs(a[s]) < t) s++;
        while (e > s && Math.abs(a[e]) < t) e--;
        if (s >= a.length) return new float[0];
        int pad = (int) Math.round(padMs / 1000 * rate);
        s = Math.max(0, s - pad);
        e = Math.min(a.length - 1, e + pad);
        float[] out = new float[e - s + 1];
        System.arraycopy(a, s, out, 0, out.length);
        return out;
    }

    /**
     * Pitch up (factor > 1) or down by resampling: played that much quicker or slower. Its caller asks the voice for
     * speed / factor first, so the words keep their pace and only the pitch moves. Cubic interpolation (no zipper).
     */
    public static float[] resample(float[] a, double factor) {
        if (Math.abs(factor - 1) < 1e-4 || a.length < 4) return a.clone();
        int n = (int) Math.floor((a.length - 1) / factor);
        float[] out = new float[Math.max(0, n)];
        for (int i = 0; i < out.length; i++) {
            double pos = i * factor;
            int k = (int) pos;
            double t = pos - k;
            float y0 = a[Math.max(0, k - 1)], y1 = a[k], y2 = a[Math.min(a.length - 1, k + 1)], y3 = a[Math.min(a.length - 1, k + 2)];
            double c0 = y1, c1 = 0.5 * (y2 - y0), c2 = y0 - 2.5 * y1 + 2 * y2 - 0.5 * y3, c3 = 0.5 * (y3 - y0) + 1.5 * (y1 - y2);
            out[i] = (float) (((c3 * t + c2) * t + c1) * t + c0);
        }
        return out;
    }

    /** The mood's pitch: the Gloomy weight a touch lower (to -4%), the Funny weight a touch brighter (to +3%), the player's own -5..5 on top. */
    public static double pitchFactor(double gloom, double fun, int userPitch) {
        return Math.max(0.85, Math.min(1.15, (1 - 0.04 * gloom + 0.03 * fun) * (1 + 0.02 * userPitch)));
    }

    /** All of it, for one sentence from the voice: trimmed, pitched, levelled, limited, faded. */
    public static float[] finish(float[] raw, int rate, double pitch) {
        float[] a = trim(raw, rate, 25);
        a = resample(a, pitch);
        a = normalise(a, rate, TARGET_LUFS);
        a = truePeakCeiling(a, CEILING_DB);                                 // [voice37] between the samples too, not only on them
        return fade(a, rate, 6, 18);
    }

    /** Samples to 16-bit little-endian PCM at the player's volume (0..100); never over full scale. */
    public static byte[] pcm16(float[] audio, int volume) {
        double g = Math.max(0, Math.min(100, volume)) / 100.0;
        byte[] b = new byte[audio.length * 2];
        for (int i = 0; i < audio.length; i++) {
            int s = (int) Math.round(Math.max(-1.0, Math.min(1.0, audio[i] * g)) * 32767);
            b[2 * i] = (byte) s;
            b[2 * i + 1] = (byte) (s >> 8);
        }
        return b;
    }

    /** A pause of so many milliseconds (between sentences). */
    public static float[] silence(int rate, int ms) {
        return new float[Math.max(0, rate * ms / 1000)];
    }
}
