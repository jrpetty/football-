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

    /** [pa33b] What a recogniser hears from a British speaker: the slips on British vowels, and British phrasings. */
    static final String[][] BRITISH_HEARD = {
        {"get me sixteen lugs", "GATHER:logs:16"}, {"chop thirty two lags", "GATHER:logs:32"}, {"fallow me", "FOLLOW"},
        {"fellow me mate", "FOLLOW"}, {"gather sixteen stun", "GATHER:stone:16"}, {"mine ion until you have ten", "MINE_ORE:iron:10"},
        {"mind iron", "MINE_ORE:iron"}, {"god me", "GUARD_ME"}, {"gourd me", "GUARD_ME"}, {"fam this field", "FARM"},
        {"build a fam here", "FARM"}, {"put everything in the chess", "DEPOSIT"}, {"get some would", "GATHER:logs"},
        {"stay hair", "STAY"}, {"come hair", "COME"}, {"fetch us some lugs", "GATHER:logs"}, {"bung it in the chess", "DEPOSIT"},
        {"nip over here", "COME"}, {"cheers mate", "THANKS"}, {"ta", "THANKS"}, {"hang on a sec", "PAUSE"}, {"crack on", "RESUME"},
        {"right fallow me", "FOLLOW"}, {"have a go at mining ion", "MINE_ORE:iron"}, {"go and get sixteen lugs", "GATHER:logs:16"},
        {"pop it in the chest", "DEPOSIT"}, {"what are you up to", "DOING"}, {"you alright", "STATUS"}, {"pack it in", "STOP"},
        {"grab us thirty two stun", "GATHER:stone:32"},
    };

    @Test
    void britishSpeechBecomesOrders() {
        int ok = 0;
        StringBuilder miss = new StringBuilder();
        for (String[] h : BRITISH_HEARD) {
            String text = com.jrpetty.mcassistant.pa.PaWords.voiceFix(PaStt.choose(h[0], h[0]));
            PaUnderstand.Reading r = PaUnderstand.read(text, "pip");
            if (r.understood() && r.brief().startsWith(h[1])) ok++;
            else miss.append(h[0]).append(" -> ").append(r.brief()).append("; ");
        }
        System.out.printf("[pa33b] British speech to order: %d/%d%n", ok, BRITISH_HEARD.length);
        assertTrue(ok >= BRITISH_HEARD.length - 1, "British speech to order: " + ok + "/" + BRITISH_HEARD.length + " " + miss);
    }

    @Test
    void misHearsOnlyWhereSafe() {
        // typed words are left alone; a real word in another sense is not turned into an order
        assertEquals("thanks fam", com.jrpetty.mcassistant.pa.PaWords.voiceFix("thanks fam"));
        assertEquals("i would like that", com.jrpetty.mcassistant.pa.PaWords.voiceFix("i would like that"));
        assertEquals("oh my god", com.jrpetty.mcassistant.pa.PaWords.voiceFix("oh my god"));
        assertEquals("mind the lava", com.jrpetty.mcassistant.pa.PaWords.voiceFix("mind the lava"));
    }

    @Test
    void accentFollowsTheSettingTheGameAndTheComputer() {
        assertEquals(PaAccent.UK, PaAccent.resolve("auto", "en_gb", java.util.Locale.US));
        assertEquals(PaAccent.UK, PaAccent.resolve("auto", "en_us", java.util.Locale.UK));
        assertEquals(PaAccent.US, PaAccent.resolve("auto", "en_us", java.util.Locale.US));
        assertEquals(PaAccent.UK, PaAccent.resolve("uk", "en_us", java.util.Locale.US));
        assertEquals(PaAccent.US, PaAccent.resolve("us", "en_gb", java.util.Locale.UK));
        assertEquals("uk", PaAccent.next("auto"));
        assertEquals("auto", PaAccent.next("us"));
    }

    @Test
    void aBritishVoiceWhenThereIsOne() {
        assertEquals("Microsoft Hazel Desktop", PaAccent.preferredVoice(PaAccent.UK, List.of("Microsoft David Desktop", "Microsoft Hazel Desktop", "Microsoft Zira Desktop")));
        assertEquals("Daniel", PaAccent.preferredVoice(PaAccent.UK, List.of("Default", "Alex", "Daniel", "Samantha")));
        assertEquals(null, PaAccent.preferredVoice(PaAccent.UK, List.of("Default", "Microsoft David Desktop")));
        assertEquals("en-gb", PaAccent.UK.espeakVoice());
    }

    @Test
    void windowsSaysWhichRecogniserAndHowToAddTheUkOne() {
        String withUk = PaAccent.windowsAdvice(PaAccent.UK, "en-GB", List.of("en-GB", "en-US"));
        assertTrue(withUk.contains("United Kingdom") && !withUk.contains("add"), withUk);
        String noUk = PaAccent.windowsAdvice(PaAccent.UK, "en-US", List.of("en-US"));
        assertTrue(noUk.contains("United States") && noUk.contains("Time & language > Speech") && noUk.contains("English (United Kingdom)"), noUk);
        String none = PaAccent.windowsAdvice(PaAccent.UK, "", List.of());
        assertTrue(none.contains("no speech recogniser"), none);
        // the script asks for en-GB first, then en-US, then any English
        String ps = PaStt.recognizerFor(PaAccent.UK);
        assertTrue(ps.indexOf("'en-GB'") < ps.indexOf("'en-US'") && ps.contains("InstalledRecognizers()"), ps);
    }

    @Test
    void theGrammarHasTheBritishSayings() {
        assertTrue(PaUnderstand.grammar().contains("bung it in the chest") && PaStt.words().contains("cheers") && PaStt.words().contains("us"));
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

    // ------------------------------------------------------------------ [pa33b] natural voices

    @Test
    void linesAreShapedToBeSpoken() {
        String s = PaSpeech.speakable("Your things are at 140 64 -30. They'll vanish in about five minutes.", 100, 0);
        assertTrue(s.contains("about fifty blocks north-east") && !s.contains("140"), s);
        assertTrue(PaSpeech.speakable("Heading there.  \u00D72", Double.NaN, Double.NaN).equals("Heading there."));
        assertEquals("Watch the nether-ite and the ender man", PaSpeech.speakable("Watch the netherite and the Enderman", 0, 0).replace("Watch the nether-ite and the ender man", "Watch the nether-ite and the ender man"));
        assertTrue(PaSpeech.speakable("Hello Dev_123", 0, 0).contains("Dev 123"));
        assertEquals(List.of("One.", "Two?", "Three."), PaSpeech.sentences("One. Two? Three."));
    }

    @Test
    void ssmlCarriesTheMood() {
        String gloomy = PaSpeech.ssml("Oh good. More logs.", "en-GB", 1.0, 0.0, 0, 0);
        assertTrue(gloomy.contains("xml:lang=\"en-GB\"") && gloomy.contains("rate=\"-22%\"") && gloomy.contains("pitch=\"-14%\""), gloomy);
        String joke = PaSpeech.ssml("Why did the creeper cross the road. To get to the other ssssside.", "en-GB", 0, 1, 0, 0);
        assertTrue(joke.contains("<break time=\"600ms\"/>To get"), joke);
        assertTrue(PaSpeech.ssml("Fish & chips <now>", "en-US", 0, 0, 0, 0).contains("Fish &amp; chips &lt;now&gt;"));
        assertTrue(PaSpeech.ssml("Well... fine.", "en-GB", 0, 0, 0, 0).contains("<break time=\"500ms\"/>"));
    }

    @Test
    void voiceListsAreRead() {
        List<PaTts.VoiceInfo> w = PaTts.parseWindows("winrt", "Microsoft Hazel~en-GB~Female|Microsoft George~en-GB~Male|Microsoft David~en-US~Male");
        assertEquals(3, w.size());
        assertEquals("Microsoft Hazel  \u00B7  female, British", w.get(0).label());
        assertEquals("Windows (modern)", w.get(0).group());
        List<PaTts.VoiceInfo> m = PaTts.parseMac(List.of("Daniel (Enhanced)  en_GB    # Hello! My name is Daniel.", "Alex                en_US    # Most people recognize me by my voice.",
            "Thomas              fr_FR    # Bonjour", "Kate (Premium)      en_GB    # Hello"));
        assertEquals(3, m.size());
        assertEquals("Enhanced", m.get(0).quality());
        List<PaTts.VoiceInfo> e = PaTts.parseEspeak(List.of("Pty Language       Age/Gender VoiceName          File                 Other Languages",
            " 2  en-gb           --/M      English_(Great_Britain) gmw/en               (en 2)"));
        assertEquals("en-gb", e.get(0).name());
        assertTrue(PaTts.parseWindows("sapi", "").isEmpty());
    }

    @Test
    void piperIsDesignedButNeverRequired(@TempDir Path dir) {
        assertEquals("piper_windows_amd64.zip", PaPiper.archiveFor("Windows 11", "amd64"));
        assertEquals("piper_macos_aarch64.tar.gz", PaPiper.archiveFor("Mac OS X", "aarch64"));
        assertEquals("piper_linux_x86_64.tar.gz", PaPiper.archiveFor("Linux", "amd64"));
        PaPiper p = new PaPiper(dir);
        PaPiper.Voice alan = PaPiper.byId("en_GB-alan-medium");
        assertFalse(p.installed("Linux", alan), "nothing installed: nothing claimed");
        assertTrue(p.installedVoices("Linux").isEmpty());
        assertTrue(alan.modelUrl().endsWith("/en/en_GB/alan/medium/en_GB-alan-medium.onnx"), alan.modelUrl());
        List<String> cmd = p.command("Linux", alan, dir.resolve("o.wav").toFile(), PaPiper.lengthScale(1.0, 0));
        assertTrue(cmd.contains("--model") && cmd.contains("1.25"), cmd.toString());
        long british = PaPiper.CATALOGUE.stream().filter(v -> v.id().startsWith("en_GB") && v.offered()).count();
        assertTrue(british >= 4, "British voices first: " + british);
    }

    @Test
    void everyEngineFailsGracefully() {
        PaTts none = PaTts.none("nothing here");
        assertDoesNotThrow(() -> none.speak("Hello there", new PaTts.Opts("winrt:Nobody", 0, 0, 80, 0.5, 0.5, PaAccent.UK, 0, 0)));
        assertDoesNotThrow(() -> none.speak("", new PaTts.Opts("", 0, 0, 80, 0, 0, PaAccent.US, Double.NaN, Double.NaN)));
        assertTrue(none.catalogue().isEmpty());
        assertEquals("Cloud voice: off (the default).", PaTts.cloudStatus(false, "abc"));
        assertTrue(PaTts.cloudStatus(true, "").contains("nothing is sent"));
        assertTrue(PaTts.cloudStatus(true, "key").contains("nothing is sent"));
    }
}
