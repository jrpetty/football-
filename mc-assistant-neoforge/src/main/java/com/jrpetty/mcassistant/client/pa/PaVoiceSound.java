package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.pa.PaClientConfig;
import com.jrpetty.mcassistant.pa.voice.PaTts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;

/**
 * [voice37] The assistant's voice as a sound in the world: streamed through the game's own sound system (OpenAL) as a
 * mono source at the assistant, so it is quieter with distance (heard to 32 blocks) and comes from the left or right,
 * follows it as it walks, sits under the game's Voice/Speech volume slider and shows the "Assistant speaks" subtitle.
 * "Radio" (Settings > Voice) plays it at full volume wherever it is. Lines with no speaker (Settings' previews) play
 * as radio too.
 *
 * <p>The sound is fed while it plays: each piece of a line is written as soon as it is made; while the next piece is
 * still being made the stream gives a moment of silence rather than run dry (an empty stream would end the sound). The
 * game's sound thread is never made to wait.
 */
public final class PaVoiceSound {

    private PaVoiceSound() {}

    static final SoundEvent EVENT = SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "pa_voice"));

    /** Sinks for PaTts: the game's sound when the game is up, else the computer's own audio. */
    static final PaTts.Sinks SINKS = (rate, speaker) -> {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getSoundManager() == null) return PaTts.JAVA_SOUND.open(rate, speaker);
        return new Sink(rate, speaker, PaClientConfig.get(PaClientConfig.VOICERADIO, false) || speaker < 0);
    };

    /** The bytes waiting to be played, read by the sound thread a buffer at a time (never blocking it). */
    static final class Stream implements AudioStream {
        private final AudioFormat format;
        private final int rate;
        private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
        private int head;
        private boolean finished, stopped;
        private volatile boolean ended, started;
        private long playEnd;

        Stream(int rate) {
            this.rate = rate;
            this.format = new AudioFormat(rate, 16, 1, true, false);
        }

        synchronized void add(byte[] pcm) {
            if (!stopped && pcm.length > 0) chunks.addLast(pcm);
        }

        /** Nothing more is coming: the stream ends once what's here has played. */
        synchronized void finish() { finished = true; }

        synchronized void stop() {
            stopped = true;
            chunks.clear();
        }

        boolean ended() { return ended; }

        boolean started() { return started; }

        synchronized long playEnd() { return playEnd; }

        synchronized boolean empty() { return chunks.isEmpty(); }

        @Override
        public AudioFormat getFormat() { return format; }

        @Override
        public synchronized ByteBuffer read(int size) {
            started = true;
            if (stopped || finished && chunks.isEmpty()) { ended = true; return null; }
            int want = Math.max(2, size & ~1);
            ByteBuffer out;
            if (chunks.isEmpty()) {                                       // still being made: a tenth of a second of quiet
                int n = Math.min(want, rate / 10 * 2);
                out = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());
                out.put(new byte[n]);
            } else {
                int avail = -head;
                for (byte[] c : chunks) avail += c.length;
                int n = Math.max(2, Math.min(want, avail) & ~1);
                out = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());
                boolean le = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
                while (out.hasRemaining() && !chunks.isEmpty()) {
                    byte[] c = chunks.peekFirst();
                    int take = Math.min(out.remaining(), c.length - head) & ~1;
                    if (take <= 0) { chunks.removeFirst(); head = 0; continue; }
                    if (le) out.put(c, head, take);
                    else for (int i = 0; i < take; i += 2) { out.put(c[head + i + 1]); out.put(c[head + i]); }
                    head += take;
                    if (head >= c.length - 1) { chunks.removeFirst(); head = 0; }
                }
                while (out.hasRemaining()) out.put((byte) 0);
            }
            out.flip();
            long now = System.currentTimeMillis();
            playEnd = Math.max(playEnd, now) + Math.round(out.remaining() * 1000.0 / (rate * 2));
            return out;
        }

        /** The sound system is done with it (the source was stopped or finished). */
        @Override
        public void close() {
            synchronized (this) { stopped = true; chunks.clear(); }
            ended = true;
        }
    }

    /** The sound in the world: at the assistant (followed as it moves), or at the listener for radio. */
    static final class Instance extends AbstractTickableSoundInstance {
        private final Stream stream;
        private final int speaker;
        private final boolean radio;

        Instance(Stream stream, int speaker, boolean radio) {
            super(EVENT, SoundSource.VOICE, SoundInstance.createUnseededRandom());
            this.stream = stream;
            this.speaker = speaker;
            this.radio = radio;
            this.looping = false;
            this.delay = 0;
            this.volume = 1.0f;
            this.pitch = 1.0f;
            this.relative = radio;
            this.attenuation = radio ? SoundInstance.Attenuation.NONE : SoundInstance.Attenuation.LINEAR;
            follow();
        }

        private void follow() {
            if (radio) { x = 0; y = 0; z = 0; return; }
            Minecraft mc = Minecraft.getInstance();
            Entity e = mc.level == null ? null : mc.level.getEntity(speaker);
            if (e != null) { x = e.getX(); y = e.getEyeY(); z = e.getZ(); }
            else if (mc.player != null && x == 0 && y == 0 && z == 0) { x = mc.player.getX(); y = mc.player.getEyeY(); z = mc.player.getZ(); }
        }

        @Override
        public void tick() {
            if (stream.ended()) { stop(); return; }
            follow();
        }

        @Override
        public boolean canStartSilent() { return true; }

        @Override
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
            return CompletableFuture.completedFuture(stream);
        }
    }

    /** One line's sound: started on the game thread at its first piece, fed from the voice's thread. */
    static final class Sink implements PaTts.Sink {
        private final Stream stream;
        private final int speaker;
        private final boolean radio;
        private Instance instance;
        private long written, startedAt;
        private volatile boolean stopped;

        Sink(int rate, int speaker, boolean radio) {
            this.stream = new Stream(rate);
            this.speaker = speaker;
            this.radio = radio;
        }

        @Override
        public void write(byte[] pcm) {
            if (stopped) return;
            stream.add(pcm);
            written += pcm.length;
            if (instance == null) {
                instance = new Instance(stream, speaker, radio);
                startedAt = System.currentTimeMillis();
                Instance i = instance;
                Minecraft.getInstance().execute(() -> { try { Minecraft.getInstance().getSoundManager().play(i); } catch (Throwable ignored) { } });
            }
        }

        /** Wait until it has been heard: the stream ran out and its last buffer's time has passed (or it was stopped, or never started). */
        @Override
        public void drain() throws InterruptedException {
            stream.finish();
            if (instance == null) return;
            long seconds = written / (2L * Math.max(1, (int) stream.getFormat().getSampleRate()));
            long deadline = System.currentTimeMillis() + seconds * 1000 + 4000;
            while (!stopped && System.currentTimeMillis() < deadline) {
                if (!stream.started() && System.currentTimeMillis() - startedAt > 2500) return;   // the game didn't play it (volume off): the words still show
                if (stream.ended() && System.currentTimeMillis() >= stream.playEnd() + 60) return;
                Thread.sleep(20);
            }
        }

        @Override
        public void stop() {
            stopped = true;
            stream.stop();
            Instance i = instance;
            if (i != null) Minecraft.getInstance().execute(() -> { try { Minecraft.getInstance().getSoundManager().stop(i); } catch (Throwable ignored) { } });
        }

        @Override
        public void close() {
            stream.finish();
        }
    }
}
