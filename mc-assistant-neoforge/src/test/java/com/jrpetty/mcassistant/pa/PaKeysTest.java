package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [pa33c-2b] The talk modes, and every assistant key named in its own Controls category. */
class PaKeysTest {

    @Test
    void holdToTalk() {
        PaTalkMode t = new PaTalkMode();
        assertEquals(PaTalkMode.Act.NONE, t.key(PaTalkMode.Mode.HOLD, false));
        assertEquals(PaTalkMode.Act.START, t.key(PaTalkMode.Mode.HOLD, true));
        assertEquals(PaTalkMode.Act.NONE, t.key(PaTalkMode.Mode.HOLD, true));
        assertEquals(PaTalkMode.Act.STOP, t.key(PaTalkMode.Mode.HOLD, false));
    }

    @Test
    void toggleStartsAndSends() {
        PaTalkMode t = new PaTalkMode();
        assertEquals(PaTalkMode.Act.START, t.key(PaTalkMode.Mode.TOGGLE, true));
        assertEquals(PaTalkMode.Act.NONE, t.key(PaTalkMode.Mode.TOGGLE, false), "letting go keeps it listening");
        assertTrue(t.on());
        assertEquals(PaTalkMode.Act.NONE, t.key(PaTalkMode.Mode.TOGGLE, false));
        assertEquals(PaTalkMode.Act.STOP, t.key(PaTalkMode.Mode.TOGGLE, true), "the next press sends");
        assertEquals(PaTalkMode.Act.NONE, t.key(PaTalkMode.Mode.TOGGLE, false));
        assertEquals(PaTalkMode.Act.START, t.key(PaTalkMode.Mode.TOGGLE, true));
    }

    @Test
    void voiceActivationSendsAfterAPause() {
        PaTalkMode t = new PaTalkMode();
        assertEquals(PaTalkMode.Act.START, t.voice(0, 0.5F, 0), "always listening");
        assertEquals(PaTalkMode.Act.NONE, t.voice(0.01F, 0.5F, 500), "quiet: nothing yet");
        assertEquals(PaTalkMode.Act.NONE, t.voice(0.5F, 0.5F, 1000), "speech");
        assertEquals(PaTalkMode.Act.NONE, t.voice(0.01F, 0.5F, 1800));
        assertEquals(PaTalkMode.Act.STOP, t.voice(0.01F, 0.5F, 2300), "1.2 s quiet after speech: sent");
        assertEquals(PaTalkMode.Act.START, t.voice(0.01F, 0.5F, 2400), "and listening again");
        assertEquals(PaTalkMode.Mode.TOGGLE, PaTalkMode.of("toggle"));
        assertEquals(PaTalkMode.Mode.HOLD, PaTalkMode.of("anything"));
    }

    @Test
    void everyKeyIsNamedInItsCategory() throws Exception {
        String lang;
        try (InputStream in = PaKeysTest.class.getResourceAsStream("/assets/mc_assistant/lang/en_us.json")) {
            lang = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(lang.contains("\"key.categories.mc_assistant_pa\": \"Player AI Assistant\""));
        for (String k : new String[]{"pa_talk", "pa_panel", "pa_ping", "pa_wheel", "pa_quiet", "pa_come", "pa_unping"})
            assertTrue(lang.contains("\"key.mc_assistant." + k + "\""), k);
    }
}
