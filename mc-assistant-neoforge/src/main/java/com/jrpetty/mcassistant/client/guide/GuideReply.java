package com.jrpetty.mcassistant.client.guide;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * [guide31] The Guide's words around an article, worked out from what it knows of the player (PlayerFacts) and nothing
 * else: the town's name where an answer says "{town}", a first line for a player with no town of their own yet ("you
 * haven't founded a town yet: here's how"), where they stand in the town they are in for the articles about standing and
 * belonging, a note on a shop or business they already have, and which misspelt words were read as what. Plain Java.
 */
public final class GuideReply {

    private GuideReply() {}

    /** The article's text with the player's town put in ("the town" when it is not known). */
    public static String fill(String text, PlayerFacts f) {
        if (text == null) return "";
        return text.replace("{town}", f.inTown() ? f.town() : "the town");
    }

    /** Articles where the player's standing in the town they stand in is worth saying first. */
    private static final List<String> STANDING = List.of("folk_make_friends", "folk_standing", "civic_citizen", "civic_stand",
        "start_join_town", "trouble_cant_citizen", "trouble_unfriendly", "biz_start", "trouble_cant_charter");

    /** The line said before the answer, for this player; "" for none. */
    public static String lead(GuideEntry e, PlayerFacts f) {
        if (!f.known()) return "";
        if (!f.ownTown() && !e.notown().isEmpty()) return fill(e.notown(), f);
        if (!f.inTown()) {
            if (e.id().equals("found_how") && f.ownTown()) return "You have a town of your own already; you may found another if the server allows.";
            return STANDING.contains(e.id()) ? "You're out in the wild just now: walk into a town and this applies there." : "";
        }
        if (e.id().equals("biz_open_shop") && f.shop()) return "You keep a shop in " + f.town() + " already (its screen opens from its counter, or U → Your shop). Want another? Set a new counter in another room of yours: each shop has its own counter, licence and keeper.";   // [biz30]
        if (e.id().equals("biz_start") && f.firms() > 0) {
            return "You run " + (f.firms() == 1 ? "a business" : f.firms() + " businesses") + " in " + f.town() + " already (U → Your businesses). You may start as many more as you like: each its own charter fee, premises and hands.";   // [biz30]
        }
        if (e.id().equals("civic_leader") && f.leader()) return "You lead " + f.town() + ": the Leader's page is yours.";
        if (STANDING.contains(e.id()) && !f.title().isEmpty()) {
            return "In " + f.town() + " you're " + f.title() + (f.citizen() ? ", and a citizen." : ".");
        }
        return "";
    }

    /** "I read buisness as business." for the corrections made, or "". */
    public static String corrected(Map<String, String> fixed) {
        if (fixed == null || fixed.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : fixed.entrySet()) parts.add("“" + e.getKey() + "” as “" + e.getValue() + "”");
        return "(I read " + String.join(", ", parts) + ".)";
    }

    /** The answer's first sentence, for a list row under an article's title. */
    public static String firstSentence(String text) {
        if (text == null) return "";
        int dot = -1;
        for (int i = 0; i + 1 < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && text.charAt(i + 1) == ' ') { dot = i; break; }
        }
        return dot < 0 ? text : text.substring(0, dot + 1);
    }
}
