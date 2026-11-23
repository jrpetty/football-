package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.pa.PaClientConfig;
import com.jrpetty.mcassistant.pa.voice.PaMic;
import com.jrpetty.mcassistant.pa.voice.PaStt;
import com.jrpetty.mcassistant.pa.voice.PaTts;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * [pa33] The assistant's voice and ears on the player's computer: replies spoken (PaTts) and push-to-talk (PaMic and
 * PaStt), all off the game thread. Listening is off until the player turns it on (About tab); where the offline model
 * is needed they are asked first, and it is downloaded once into the config folder. Only the recognised words go to
 * the server, as the same "talk" a typed order is.
 */
public final class PaVoice {

    private PaVoice() {}

    private static PaTts tts;
    private static PaStt stt;
    private static final PaMic MIC = new PaMic();
    static volatile boolean listening, hearing, installing;
    static volatile String transcript = "", status = "";
    static long transcriptAt, statusAt;

    static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve("mc_assistant").resolve("voice");
    }

    public static PaTts tts() {
        if (tts == null) tts = PaTts.create();
        return tts;
    }

    public static PaStt stt() {
        if (stt == null) stt = new PaStt(dir(), PaClientConfig.get(PaClientConfig.ENGINE, "auto"), System.getProperty("os.name"), PaTts::onPath);
        return stt;
    }

    public static void speak(String text) {
        if (!PaClientConfig.get(PaClientConfig.SPEAK, true)) return;
        tts().say(text, PaClientConfig.get(PaClientConfig.VOICE, 0), PaClientConfig.get(PaClientConfig.RATE, 0),
            PaClientConfig.get(PaClientConfig.VOLUME, 80));
    }

    static void say(String s) {
        status = s;
        statusAt = System.currentTimeMillis();
    }

    public static void startListening() {
        if (listening || hearing) return;
        if (!PaClientConfig.get(PaClientConfig.LISTEN, false)) {
            say("Listening is off: turn it on in your assistant's About tab (Y).");
            return;
        }
        if (!stt().ready()) {
            say(stt().status());
            return;
        }
        tts().hush();
        stt().begin();
        if (!MIC.start(chunk -> stt().feed(chunk))) {
            say(MIC.error() + " Typing still works.");
            return;
        }
        listening = true;
        transcript = "";
        say("");
    }

    public static void stopListening() {
        if (!listening) return;
        listening = false;
        byte[] pcm = MIC.stop();
        hearing = true;
        stt().end(pcm).thenAccept(h -> Minecraft.getInstance().execute(() -> {
            hearing = false;
            if (h.text().isEmpty()) {
                say(h.error().isEmpty() ? "I didn't catch that. Try again?" : h.error());
                return;
            }
            transcript = h.text();
            transcriptAt = System.currentTimeMillis();
            PaClient.talk(h.text(), true);
        }));
    }

    static void tick() {
        if (listening) {
            String p = stt().partial();
            if (!p.isEmpty()) transcript = p;
        }
    }

    public static float level() {
        return MIC.level();
    }

    /** The player agreed: download the offline model, off the game thread. */
    static void install() {
        if (installing) return;
        installing = true;
        Thread t = new Thread(() -> {
            boolean ok = stt().vosk().install(PaVoice::say);
            installing = false;
            say(ok ? "The speech model is ready. Hold Z and speak." : stt().vosk().status() + " Typing still works.");
        }, "mca-assistant-voice-install");
        t.setDaemon(true);
        t.start();
    }
}
