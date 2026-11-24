package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** [pa36b] Names are taken as said: never typo-fixed ("basil" was renamed "Basic", "wren" became "when"). */
class PaNamesTest {

    private static String selfRename(String said) {
        PaMemoryParse.Heard h = PaMemoryParse.read(said, "Pip");
        if (h.op() == PaMemoryParse.Op.RENAME) return h.subject();
        PaUnderstand.Reading r = PaUnderstand.read(said, "Pip");
        for (PaIntent i : r.steps()) if (i.kind() == PaIntent.Kind.RENAME) return i.item();
        return "(no rename)";
    }

    @Test
    void itsOwnNameAsSaid() {
        assertEquals("basil", selfRename("your name is now basil").toLowerCase());
        assertEquals("wren", selfRename("i'll call you wren").toLowerCase());
        assertEquals("Wren", selfRename("From now on your name is Wren!"));
        assertEquals("bea", selfRename("your name is bea").toLowerCase());
    }

    @Test
    void aChestsNameAsSaid() {
        assertEquals("NAME > POINT [basil]", PaLogiParse.brief("call this chest basil"));
        assertEquals("NAME > POINT[barrel] [Wren]", PaLogiParse.brief("name that barrel Wren"));
        assertEquals("NAME > POINT [stoen]", PaLogiParse.brief("call this chest stoen"));
        assertEquals("NAME > POINT [Kitchen Stuff]", PaLogiParse.brief("call this chest the Kitchen Stuff chest"));
    }

    @Test
    void cappedAndTrimmed() {
        String n = PaLogiParse.nameAsSaid("call this chest the extraordinarily long name for a chest of stuff", "x");
        assertEquals(true, n.length() <= 24, n);
        assertEquals("Basil", PaNames.asSaid("your name is  Basil!! ", "your name is basic", 13, 18, 16, "basic"));
        // another clause at the end is not the name
        assertEquals("bob", PaNames.asSaid("your name is bob and follow me", "your name is bob", 13, 16, 16, "bob"));
    }
}
