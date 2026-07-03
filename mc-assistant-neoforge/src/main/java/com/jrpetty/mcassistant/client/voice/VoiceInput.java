package com.jrpetty.mcassistant.client.voice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;
import org.vosk.Recognizer;

import javax.annotation.Nullable;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.TargetDataLine;

/**
 * Push-to-talk: hold the voice key (default V, rebindable in Controls),
 * speak an order — "gather 128 logs and deposit it into the chest" — and
 * release. The recognized sentence is sent as a normal chat message, so it
 * flows through the exact same natural-language parser as typed chat: every
 * capability the assistant has (and every future one) is automatically
 * voice-controllable.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class VoiceInput {

    public static final KeyMapping TALK = new KeyMapping(
        "key.mc_assistant.voice", GLFW.GLFW_KEY_V, "key.categories.mc_assistant");

    private static boolean wasDown;
    @Nullable private static Capture capture;

    private VoiceInput() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (capture != null) {
                capture.cancel();
                capture = null;
            }
            wasDown = false;
            return;
        }
        boolean down = mc.screen == null && TALK.isDown();
        if (down && !wasDown) {
            press();
        } else if (!down && wasDown) {
            release();
        }
        wasDown = down;
    }

    private static void press() {
        switch (VoiceEngine.state()) {
            case READY -> {
                capture = new Capture();
                capture.start();
                VoiceEngine.overlay("Listening... release to send");
            }
            case IDLE -> {
                VoiceEngine.ensureReady();
                VoiceEngine.overlay("Preparing voice (first time only) — try again in a moment");
            }
            case PREPARING -> VoiceEngine.overlay("Voice model still preparing...");
            case FAILED -> VoiceEngine.overlay("Voice unavailable: " + VoiceEngine.failReason());
        }
    }

    private static void release() {
        Capture c = capture;
        capture = null;
        if (c != null) c.finish();
    }

    /** One mic->recognizer session on its own daemon thread. */
    private static final class Capture extends Thread {
        private volatile boolean running = true;
        private volatile boolean send;

        Capture() {
            super("mc-assistant-voice");
            setDaemon(true);
        }

        void finish() { send = true; running = false; }

        void cancel() { running = false; }

        @Override
        public void run() {
            AudioFormat fmt = new AudioFormat(VoiceEngine.SAMPLE_RATE, 16, 1, true, false);
            try (Recognizer rec = VoiceEngine.newRecognizer()) {
                TargetDataLine line = AudioSystem.getTargetDataLine(fmt);
                try {
                    line.open(fmt);
                    line.start();
                    byte[] buf = new byte[3200]; // 100ms of 16kHz 16-bit mono
                    long deadline = System.currentTimeMillis() + 15_000; // hard cap per press
                    while (running && System.currentTimeMillis() < deadline) {
                        int n = line.read(buf, 0, buf.length);
                        if (n > 0) rec.acceptWaveForm(buf, n);
                    }
                } finally {
                    line.close();
                }
                if (!send) return;

                JsonObject result = JsonParser.parseString(rec.getFinalResult()).getAsJsonObject();
                String heard = result.has("text") ? result.get("text").getAsString().trim() : "";
                if (heard.isEmpty()) {
                    VoiceEngine.overlay("Didn't catch anything — hold the key while you speak.");
                    return;
                }
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> {
                    if (mc.getConnection() != null && mc.player != null) {
                        mc.player.displayClientMessage(Component.literal("🎤 " + heard), true);
                        mc.getConnection().sendChat(heard);
                    }
                });
            } catch (Throwable t) {
                VoiceEngine.overlay("Mic error: "
                    + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
            }
        }
    }
}
