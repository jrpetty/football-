package com.jrpetty.mcassistant.pa;

/** [pa38] The assistant's tick for its throws (what became of them), its mace's pillar, and its slips. */
final class PaFieldTick {

    private PaFieldTick() {}

    static void tick(PlayerAssistantEntity a) {
        PaThrows.tick(a);
        PaMace.tidy(a);
        PaSlips.fix(a);
        PaSlips.walk(a);
    }
}
