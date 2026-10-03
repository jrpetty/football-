package com.jrpetty.mcassistant.client.voice;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The offline speech-to-text engine behind push-to-talk. Vosk runs fully
 * locally — audio never leaves the machine. The acoustic model (~40 MB) is
 * fetched once from the official Vosk site into config/mc_assistant/ and
 * reused forever after; everything else is offline from then on.
 */
public final class VoiceEngine {

    public enum State { IDLE, PREPARING, READY, FAILED }

    private static final String MODEL_NAME = "vosk-model-small-en-us-0.15";
    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/" + MODEL_NAME + ".zip";
    public static final float SAMPLE_RATE = 16000f;

    private static volatile State state = State.IDLE;
    private static volatile Model model;
    private static volatile String failReason = "";

    private VoiceEngine() {}

    public static State state() {
        return state;
    }

    public static String failReason() {
        return failReason;
    }

    /** Start async download+load if it hasn't been kicked off yet. */
    public static synchronized void ensureReady() {
        if (state != State.IDLE) return;
        state = State.PREPARING;
        Thread t = new Thread(VoiceEngine::prepare, "mc-assistant-voice-setup");
        t.setDaemon(true);
        t.start();
    }

    public static Recognizer newRecognizer() throws IOException {
        return new Recognizer(model, SAMPLE_RATE);
    }

    private static void prepare() {
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("mc_assistant");
            Path modelDir = dir.resolve(MODEL_NAME);
            if (!Files.isDirectory(modelDir.resolve("conf"))) {
                chat("Downloading the offline voice model (~40 MB, one time only)...");
                Files.createDirectories(dir);
                Path zip = dir.resolve(MODEL_NAME + ".zip");
                try (InputStream in = URI.create(MODEL_URL).toURL().openStream()) {
                    Files.copy(in, zip, StandardCopyOption.REPLACE_EXISTING);
                }
                chat("Unpacking voice model...");
                unzip(zip, dir);
                Files.deleteIfExists(zip);
            }
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            model = new Model(modelDir.toString());
            state = State.READY;
            chat("Voice ready — hold the voice key (default V), speak, release to send.");
        } catch (Throwable t) {
            state = State.FAILED;
            failReason = t.getClass().getSimpleName()
                + (t.getMessage() != null ? ": " + t.getMessage() : "");
            chat("Voice engine couldn't start (" + failReason + "). "
                + "Typing and OS dictation into chat still work.");
        }
    }

    private static void unzip(Path zip, Path dir) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                Path out = dir.resolve(e.getName()).normalize();
                if (!out.startsWith(dir)) throw new IOException("Bad zip entry: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static void chat(String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("<Assistant/Voice> " + message), false);
            }
        });
    }

    static void overlay(String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(message), true);
            }
        });
    }
}
