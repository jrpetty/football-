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
        if (tts == null) {
            tts = PaTts.create();
            tts.accent(PaVoice::accent);                        // [pa33b] a British voice for UK
            tts.piper(dir().resolve("piper-voices"));
            tts.kokoro(dir().resolve("kokoro"));                // [pa33b] the natural voices, once downloaded
        }
        return tts;
    }

    public static PaStt stt() {
        if (stt == null) stt = new PaStt(dir(), PaClientConfig.get(PaClientConfig.ENGINE, "auto"), System.getProperty("os.name"), PaTts::onPath,
            PaVoice::accent);                                   // [pa33b] en-GB recogniser for UK
        return stt;
    }

    /** [pa33b] The English it listens for and speaks: the About tab's Accent (Auto follows the game's language, then the computer's). */
    public static com.jrpetty.mcassistant.pa.voice.PaAccent accent() {
        String lang = "";
        try {
            lang = Minecraft.getInstance().options.languageCode;
        } catch (Throwable ignored) {
            // too early: the computer's own then
        }
        return com.jrpetty.mcassistant.pa.voice.PaAccent.resolve(PaClientConfig.get(PaClientConfig.ACCENT, "auto"), lang, java.util.Locale.getDefault());
    }

    public static void speak(String text) {
        speak(text, 0, 0);
    }

    /** [pa33b] Spoken in the picked voice (or the most natural one for the accent), with the mood in its pace and pitch. */
    public static void speak(String text, double gloom, double fun) {
        if (!PaClientConfig.get(PaClientConfig.SPEAK, true)) return;
        var p = Minecraft.getInstance().player;
        tts().speak(text, new PaTts.Opts(PaClientConfig.get(PaClientConfig.VOICEKEY, ""), PaClientConfig.get(PaClientConfig.RATE, 0),
            PaClientConfig.get(PaClientConfig.PITCH, 0), PaClientConfig.get(PaClientConfig.VOLUME, 80), gloom, fun, accent(),
            p == null ? Double.NaN : p.getX(), p == null ? Double.NaN : p.getZ()));
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
        PaPingClient.speechStarted(Minecraft.getInstance());      // [pa33c-2b] "that" is what you look at as you start
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

    static volatile boolean installingVoices;

    /** [pa33b] The player agreed: download the natural (Kokoro) voices, off the game thread. Nothing is fetched before. */
    static void installVoices() {
        if (installingVoices || tts().kokoro() == null) return;
        installingVoices = true;
        Thread t = new Thread(() -> {
            boolean ok = tts().kokoro().install(PaVoice::say);
            installingVoices = false;
            if (!ok) say(tts().kokoro().why());
            else if (!tts().kokoroReady()) say(com.jrpetty.mcassistant.pa.voice.PaKokoro.espeakAdvice(System.getProperty("os.name")));
            else say("The natural voices are ready: pick one under Voice.");
        }, "mca-assistant-voice-download");
        t.setDaemon(true);
        t.start();
    }

    /** [pa33b] A voice that suits the look, of those on this computer (a hint only; the look never forces a voice). */
    static String suggestFor(int look, java.util.List<PaTts.VoiceInfo> cat) {
        if (tts().kokoroReady()) {                           // the natural voices: George for the knight, Fable for the butler...
            boolean female = PaClientConfig.get(PaClientConfig.VOICEKEY, "").startsWith("kokoro:")
                && "female".equals(java.util.Optional.ofNullable(com.jrpetty.mcassistant.pa.voice.PaKokoro.byLabel(PaClientConfig.get(PaClientConfig.VOICEKEY, "").substring(7)))
                    .map(com.jrpetty.mcassistant.pa.voice.PaKokoro.Voice::gender).orElse(""));
            return com.jrpetty.mcassistant.pa.voice.PaKokoro.forLook(look, accent(), female).label();
        }
        String[] want = switch (look) {
            case 4 -> new String[]{"george", "ryan", "alan", "northern", "daniel", "guy", "david"};          // Knight: deep
            case 8 -> new String[]{"george", "alan", "daniel", "semaine", "oliver"};                         // Butler: posh
            case 9 -> new String[]{"alan", "george", "daniel", "arthur"};                                   // Wizard
            case 7 -> new String[]{"northern", "ryan", "george", "guy"};                                     // Pirate
            case 14, 15 -> new String[]{"northern", "alan", "george", "david"};                              // Skeleton, Zombie
            case 10, 5 -> new String[]{"jenny", "susan", "hazel", "kate", "serena"};                         // Chef, Farmer
            case 2 -> new String[]{"alba", "hazel", "kate", "susan"};                                        // Ranger
            default -> new String[]{"hazel", "george", "jenny", "alan", "daniel", "kate"};
        };
        for (String w : want) for (PaTts.VoiceInfo v : cat) if (v.name().toLowerCase(java.util.Locale.ROOT).contains(w)) return v.name();
        return null;
    }

    /** [pa33b] A preview line in its current personality. */
    static String sample(com.jrpetty.mcassistant.pa.PaPersona.Tri t) {
        java.util.Random r = new java.util.Random();
        String s = com.jrpetty.mcassistant.pa.PaPersona.from(com.jrpetty.mcassistant.pa.PaPersona.linesFor("say.greet", t, r::nextInt), r::nextInt, null);
        return s == null ? "Hello. This is my voice." : com.jrpetty.mcassistant.pa.PaPersona.fill(s, java.util.Map.of("owner", "you", "name", "me"));
    }
}
