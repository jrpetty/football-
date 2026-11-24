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
            tts.onSound(PaVoice::soundStarted);                 // [pa35] the gestures go with the words
            // [pa35] an offline voice pack dropped in the config folder: unpacked and checked, off the game thread
            Thread t = new Thread(() -> {
                if (tts.kokoro() != null && tts.kokoro().importPack(FMLPaths.CONFIGDIR.get(), dir())) readyNow();
            }, "mca-assistant-voice-pack");
            t.setDaemon(true);
            t.start();
        }
        return tts;
    }

    // ------------------------------------------------------------------ [pa35] the natural voice, on by default

    /** Download state for the banner: running, the fraction done, the line, and when it finished (or failed). */
    static volatile double packFraction;
    static volatile String packLine = "";
    static volatile long packEndedAt;
    static volatile boolean packFailed;
    private static boolean autoTried;

    /**
     * The first time this player has an assistant (this session, on this computer): the natural voice is fetched in the
     * background unless they said not to. Client-side and per player: a server never starts or stops it.
     */
    static void maybeAutoDownload(boolean hasAssistant) {
        boolean go = com.jrpetty.mcassistant.pa.voice.PaVoiceDefaults.autoDownload(hasAssistant, autoTried, PaClientConfig.get(PaClientConfig.VOICEPACK, "auto"),
            tts().kokoroReady(), installingVoices);
        if (hasAssistant) autoTried = true;
        if (!go) return;
        installVoices();
        try {
            net.minecraft.client.gui.components.toasts.SystemToast.add(Minecraft.getInstance().getToasts(),
                net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                net.minecraft.network.chat.Component.literal("Fetching your assistant's natural voice"),
                net.minecraft.network.chat.Component.literal("About " + com.jrpetty.mcassistant.pa.voice.PaKokoro.totalMb() + " MB, once. Words show until it's ready."));
        } catch (Throwable ignored) {
            // no toasts this early: the banner still shows
        }
    }

    /** "Don't download": stops it now (what's down is kept for later) and never starts it again by itself. */
    static void declineVoices() {
        PaClientConfig.set(PaClientConfig.VOICEPACK, "declined");
        if (tts().kokoro() != null) tts().kokoro().cancel();
        installingVoices = false;
        packEndedAt = System.currentTimeMillis();
        packLine = "Natural voice: not downloading. Settings > Voice can fetch it any time.";
    }

    /** The natural voice became ready: it says so, once, in its own (new) voice. */
    static void readyNow() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (PaClientConfig.get(PaClientConfig.VOICEREADYSAID, false)) return;
            PaClientConfig.set(PaClientConfig.VOICEREADYSAID, true);
            var set = PaClient.state.getCompound("set");
            com.jrpetty.mcassistant.pa.PaPersona.Tri tri = com.jrpetty.mcassistant.pa.PaPersona.Tri.of(set.contains("funny") ? set.getFloat("funny") : 0.5,
                set.contains("gloomy") ? set.getFloat("gloomy") : 0.2);
            java.util.Random r = new java.util.Random();
            String line = com.jrpetty.mcassistant.pa.PaPersona.from(com.jrpetty.mcassistant.pa.PaPersona.linesFor("say.voice.ready", tri, r::nextInt), r::nextInt, null);
            if (line == null) line = "Ah, that's better. My real voice.";
            PaClient.subtitle = PaClient.name() + ": " + line;
            PaClient.subtitleUntil = System.currentTimeMillis() + 5000L;
            speak(line, tri.gloomy(), tri.funny(), PaClient.state.getInt("id"));
        });
    }

    // ------------------------------------------------------------------ [pa35] gestures with the words

    private static volatile int holdId = -1, soundId = -1;
    private static volatile long holdUntil, soundUntil;

    /** Its gesture waits for its voice (a moment's synthesis) so the two line up; at most two and a half seconds. */
    public static boolean holdingGesture(int id) {
        return id == holdId && System.currentTimeMillis() < holdUntil;
    }

    /** Its voice is sounding now (the talking motion runs with it). */
    public static boolean sounding(int id) {
        return id == soundId && System.currentTimeMillis() < soundUntil;
    }

    static void soundStarted(long ms) {
        soundId = holdId;
        soundUntil = System.currentTimeMillis() + ms;
        holdUntil = 0;
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
        speak(text, gloom, fun, -1);
    }

    /** [pa35] Spoken by this assistant (its gesture waits for the sound): natural voice first; the basic one only if picked; else words only. */
    public static void speak(String text, double gloom, double fun, int id) {
        if (!PaClientConfig.get(PaClientConfig.SPEAK, true)) return;
        var p = Minecraft.getInstance().player;
        boolean basic = PaClientConfig.get(PaClientConfig.BASICVOICE, false);
        if (id >= 0 && (tts().naturalReady() || basic)) { holdId = id; holdUntil = System.currentTimeMillis() + 2500L; }
        tts().speak(text, new PaTts.Opts(PaClientConfig.get(PaClientConfig.VOICEKEY, ""), PaClientConfig.get(PaClientConfig.RATE, 0),
            PaClientConfig.get(PaClientConfig.PITCH, 0), PaClientConfig.get(PaClientConfig.VOLUME, 80), gloom, fun, accent(),
            p == null ? Double.NaN : p.getX(), p == null ? Double.NaN : p.getZ(), basic, PaClientConfig.get(PaClientConfig.VOICEWOMAN, false)));
    }

    /** [pa35] With no voice ready, its words show as a subtitle whatever the subtitle setting (text only, never silent). */
    public static boolean textOnly() {
        return !PaClientConfig.get(PaClientConfig.SPEAK, true) || !(tts().naturalReady() || PaClientConfig.get(PaClientConfig.BASICVOICE, false) && tts().available());
    }

    static void say(String s) {
        status = s;
        statusAt = System.currentTimeMillis();
    }

    public static void startListening() {
        if (listening || hearing) return;
        if (!PaClientConfig.get(PaClientConfig.LISTEN, false)) {
            say("Listening is off: turn it on in your assistant's Settings, Voice (Y).");
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
        packFailed = false;
        packFraction = 0;
        packLine = "Natural voice: starting...";
        Thread t = new Thread(() -> {
            boolean ok = tts().kokoro().install(com.jrpetty.mcassistant.pa.voice.PaKokoro.HTTP, (f, l) -> { packFraction = f; packLine = l; });
            boolean wasCancelled = !installingVoices;
            installingVoices = false;
            packEndedAt = System.currentTimeMillis();
            if (wasCancelled) return;
            if (!ok) { packFailed = true; packLine = tts().kokoro().why(); say(tts().kokoro().why()); }
            else { packFraction = 1; packLine = "Natural voice ready."; say("The natural voice is ready."); readyNow(); }
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
