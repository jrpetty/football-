package com.jrpetty.mcassistant.pa;

/**
 * [pa33c-2b] When the microphone listens: Hold (while the talk key is down), Toggle (a press starts, the next press
 * sends), or Voice activation (always listening; a stretch of speech is sent after a short silence). Each call says
 * what to do now. Pure Java, so it is tested on its own.
 */
public final class PaTalkMode {

    public enum Mode { HOLD, TOGGLE, VOICE }

    public enum Act { NONE, START, STOP }

    private boolean keyWas, on, spoke;
    private long loudAt;

    public static Mode of(String s) {
        return "toggle".equalsIgnoreCase(s) ? Mode.TOGGLE : "voice".equalsIgnoreCase(s) ? Mode.VOICE : Mode.HOLD;
    }

    public static String label(Mode m) {
        return switch (m) { case HOLD -> "Hold to talk"; case TOGGLE -> "Press to start, again to send"; case VOICE -> "Voice activation"; };
    }

    /** The talk key's state this tick (Hold and Toggle). */
    public Act key(Mode m, boolean down) {
        Act a = Act.NONE;
        if (m == Mode.HOLD) {
            if (down && !keyWas) { on = true; a = Act.START; }
            else if (!down && keyWas && on) { on = false; a = Act.STOP; }
        } else if (m == Mode.TOGGLE) {
            if (down && !keyWas) { on = !on; a = on ? Act.START : Act.STOP; }
        }
        keyWas = down;
        return a;
    }

    /**
     * Voice activation: the microphone's loudness (0..1) now, the threshold from the sensitivity slider (0..1, higher is
     * more sensitive), and the time in ms. Starts listening if it isn't; sends after 1.2 s quiet following speech.
     */
    public Act voice(float level, float sensitivity, long nowMs) {
        float threshold = 0.25F - 0.2F * Math.max(0, Math.min(1, sensitivity));
        if (!on) { on = true; spoke = false; return Act.START; }
        if (level >= threshold) { spoke = true; loudAt = nowMs; return Act.NONE; }
        if (spoke && nowMs - loudAt > 1200) { on = false; spoke = false; return Act.STOP; }
        return Act.NONE;
    }

    public boolean on() { return on; }

    /** Its microphone was stopped elsewhere (the panel's button, an error). */
    public void reset() { on = false; spoke = false; }
}
