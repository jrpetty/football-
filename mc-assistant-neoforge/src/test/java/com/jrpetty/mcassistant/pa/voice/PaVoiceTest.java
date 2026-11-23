package com.jrpetty.mcassistant.pa.voice;

import com.jrpetty.mcassistant.pa.PaUnderstand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [pa33] The voice path: recognised words to orders, and every backend failing gracefully when it is not there. */
class PaVoiceTest {

    /** What recognisers hand back (lower case, no punctuation, numbers as words, a wake word sometimes), and the order. */
    static final String[][] HEARD = {
        {"follow me", "FOLLOW"}, {"hey pip follow me", "FOLLOW"}, {"pip come here", "COME"}, {"stay here", "STAY"},
        {"get me sixteen logs", "GATHER:logs:16"}, {"gather thirty two stone", "GATHER:stone:32"}, {"chop sixty four logs", "GATHER:logs:64"},
        {"mine iron until you have twenty", "MINE_ORE:iron:20"}, {"mine for diamonds", "MINE_ORE:diamond"},
        {"build me a small house here", "BUILD:house"}, {"build a shelter here", "BUILD:shelter"}, {"build a farm here", "FARM"},
        {"craft a stone pickaxe", "CRAFT:stone pickaxe"}, {"make sixteen torches", "CRAFT:torches:16"}, {"smelt eight iron", "SMELT:iron:8"},
        {"put everything in that chest", "DEPOSIT"}, {"bring me food", "BRING_FOOD"}, {"i am hungry", "BRING_FOOD"},
        {"guard me", "GUARD_ME"}, {"attack that", "FIGHT"}, {"kill the zombies", "FIGHT"}, {"farm this field", "FARM"},
        {"stop", "STOP"}, {"undo that", "UNDO"}, {"pause", "PAUSE"}, {"carry on", "RESUME"}, {"go home", "GO_HOME"},
        {"what are you doing", "DOING"}, {"how are you", "STATUS"}, {"light up the area", "LIGHT_AREA"},
        {"sort the chests", "SORT"}, {"catch five fish", "FISH:fish:5"}, {"hunt three cows", "HUNT:cows:3"},
        {"dig a mine down to level twelve", "MINE_DOWN"}, {"how do i start a business", "QUESTION"},
    };

    @Test
    void heardWordsBecomeOrders() {
        int ok = 0;
        StringBuilder miss = new StringBuilder();
        for (String[] h : HEARD) {
            // both readings agree here; choose() must keep an order that reads
            String text = PaStt.choose(h[0], h[0]);
            PaUnderstand.Reading r = PaUnderstand.read(text, "pip");
            if (r.understood() && r.brief().startsWith(h[1])) ok++;
            else miss.append(h[0]).append(" -> ").append(r.brief()).append("; ");
        }
        System.out.printf("[pa33] voice text to order: %d/%d%n", ok, HEARD.length);
        assertTrue(ok >= HEARD.length - 1, "voice text to order: " + ok + "/" + HEARD.length + " " + miss);
    }

    @Test
    void chooseKeepsTheReadingThatMakesAnOrder() {
        assertEquals("get me sixteen logs", PaStt.choose("get me sixteen logs", "get me sixty logs dog"));
        assertEquals("how do i start a business", PaStt.choose("how do i start a [unk]", "how do i start a business"));
        assertEquals("follow me", PaStt.choose("", "follow me"));
        assertEquals("", PaStt.choose("", ""));
    }

    @Test
    void ttsWithNothingInstalledIsSilentAndSaysSo() {
        assertEquals(PaTts.Kind.NONE, PaTts.pick("Linux", p -> false));
        assertEquals(PaTts.Kind.NONE, PaTts.pick("Windows 11", p -> false));
        assertEquals(PaTts.Kind.WINDOWS, PaTts.pick("Windows 11", p -> p.equals("powershell")));
        assertEquals(PaTts.Kind.MAC, PaTts.pick("Mac OS X", p -> p.equals("say")));
        assertEquals(PaTts.Kind.ESPEAK, PaTts.pick("Linux", p -> p.equals("espeak")));
        PaTts none = PaTts.none("nothing here");
        assertFalse(none.available());
        assertDoesNotThrow(() -> none.say("hello", 0, 0, 80));
        assertDoesNotThrow(() -> PaTts.create().status());
        assertEquals("Hello there", PaTts.clean("Hello 🎤 there"));
    }

    @Test
    void sttWithNoModelSaysSoAndNeverThrows(@TempDir Path dir) throws Exception {
        PaStt stt = new PaStt(dir, "auto", "Linux", p -> false);
        assertEquals(PaStt.Engine.NONE, stt.engine());
        assertFalse(stt.ready());
        assertTrue(stt.status().contains("model"), stt.status());
        assertDoesNotThrow(stt::begin);
        assertDoesNotThrow(() -> stt.feed(new byte[3200]));
        PaStt.Heard h = stt.end(new byte[32000]).get(10, TimeUnit.SECONDS);
        assertTrue(h.text().isEmpty() && !h.error().isEmpty(), "an empty hearing, with a reason: " + h);
        PaStt.Heard tooShort = stt.end(new byte[100]).get(10, TimeUnit.SECONDS);
        assertTrue(tooShort.error().contains("short"), tooShort.error());
        PaVosk v = new PaVosk(dir.resolve("vosk"));
        assertFalse(v.installed());
        assertFalse(v.load());
        assertTrue(v.status().contains("downloaded"), v.status());
        assertTrue(v.session(List.of("follow")) == null);
    }

    @Test
    void voskPieces() {
        String g = PaVosk.grammar(List.of("follow", "me", "Bad!", "get"));
        assertEquals("[\"follow\",\"me\",\"get\",\"[unk]\"]", g);
        assertEquals("follow me", PaVosk.field("{\n  \"text\" : \"follow me\"\n}", "text"));
        assertEquals("get me", PaVosk.field("{ \"partial\" : \"get me\" }", "partial"));
        assertTrue(PaStt.words().contains("pickaxe") && PaStt.words().contains("sixteen"));
    }

    @Test
    void wavRoundTrip() {
        byte[] pcm = new byte[640];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) i;
        byte[] wav = PaWav.wav(pcm, 16000);
        assertEquals(44 + 640, wav.length);
        assertEquals(640, PaWav.pcm(wav).length);
        assertEquals(pcm[100], PaWav.pcm(wav)[100]);
    }

    @Test
    void micWithoutAMicrophoneFailsGracefully() {
        PaMic mic = new PaMic();
        boolean started = assertDoesNotThrow(() -> mic.start(null));
        if (!started) assertFalse(mic.error().isEmpty());
        assertDoesNotThrow(mic::stop);
        assertTrue(PaMic.rms(new byte[]{0, 0, 0, 0}, 4) == 0F);
    }
}
