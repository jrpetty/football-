package com.jrpetty.mcassistant.pa;

/**
 * [pa33c-2b] A gesture with what it says: a written line's own "[anim:wave]" tag first, else a guess from the words
 * (a hello waves, an owned bad joke shrugs, thanks bows, a job done cheers). Played with the folk's own clips.
 */
public final class PaAnim {

    private PaAnim() {}

    public static void forLine(PlayerAssistantEntity a, String text) {
        if (a.mute() >= 2) return;                                   // quiet: no flourishes either
        String anim = PaAnimWords.tagged(PaLines.tags(text));
        if (anim == null) anim = PaAnimWords.guess(text, PaLines.bad(text));
        if (anim != null) a.playAnim(anim);
    }
}
