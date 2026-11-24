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
        String rest = text;
        for (int i = text.indexOf(". "); anim == null && i > 0 && i + 2 < text.length(); i = text.indexOf(". ", i + 2)) {
            rest = text.substring(i + 2);                            // [pa33e] a line after an opener ("Right, here's one. ..."): its own gesture
            anim = PaAnimWords.tagged(PaLines.tags(rest));
            if (anim == null && PaLines.bad(rest)) anim = "shrug";
        }
        if (anim == null) anim = PaAnimWords.guess(text, PaLines.bad(text));
        if (anim != null) a.playAnim(anim);
    }
}
