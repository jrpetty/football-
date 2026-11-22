package com.jrpetty.mcassistant.client.guide;

import java.util.List;

/**
 * [guide31] One article of the Guide (assets/mc_assistant/guide/*.json): what it is called and where it is filed, the
 * ways a player might ask for it, the words it answers to, and the answer itself, written as the Guide says it: a short
 * direct answer, the steps, the keys and buttons, the things involved, tips and warnings, and the articles next to it.
 *
 * <p>{@code covers} names what in the mod the article explains ("item:shop_counter", "key:life", "screen:LifeScreen",
 * "business:baker", "talk:GIFT"), so the coverage test can hold the Guide to the whole mod. {@code op}: an operator's
 * article (settings, the admin panel), said to be so. {@code answer} is the short answer, a sentence or two (what it is,
 * the quickest way in); {@code more} is the rest of the detail, shown under "More detail". {@code notown}: a line said first to a player who has no town of
 * their own yet. Plain Java: the unit tests read it exactly as the game does.
 */
public record GuideEntry(String id, String title, String category, String icon, String kind,
                         List<String> questions, List<String> keywords,
                         String answer, List<String> more, List<String> steps, List<String> controls, List<String> items,
                         List<String> tips, List<String> warnings, List<String> related, List<String> covers,
                         boolean op, String notown) {

    /** A how-to (numbered steps), a "what is", or a trouble ("why won't..."). */
    public static final String HOWTO = "howto", WHAT = "what", TROUBLE = "trouble";

    public boolean trouble() {
        return TROUBLE.equals(kind);
    }

    public boolean howto() {
        return HOWTO.equals(kind);
    }
}
