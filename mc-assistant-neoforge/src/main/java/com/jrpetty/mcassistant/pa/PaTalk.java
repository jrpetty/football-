package com.jrpetty.mcassistant.pa;

import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.regex.Pattern;

/**
 * [pa33] How the Player AI Assistant speaks. A reply to its player always comes through (in the talk log, aloud on
 * its player's client, and as a bubble over its head that everyone near can read). Everything else it might say,
 * a comment, a tip, the engine's running narration, is held to its player's chattiness setting and a rate limit, so
 * it never buries the screen. Nothing here goes to the chat.
 */
public final class PaTalk {

    private PaTalk() {}

    /** The engine's words that mean a job could not be done (see PaBrain: the step's honest failure). */
    private static final Pattern FAIL = Pattern.compile(
        "(?i)(?:^i can'?t|can'?t (?:find|reach|make|get|see|build)|^i need |still need|^no (?:chest|furnace|room|tree|ore|water|crops|animals|food)|"
        + "nothing (?:to|left)|couldn'?t|out of |don'?t have|haven'?t got|not enough|missing|no room|is full|are full|no pickaxe|need a |"
        + "before i can|i can build:|i can smelt:|too far|stuck)");

    /** Ticks a bubble stays up: long enough to read on camera. */
    static int bubbleTicks(String text) {
        return Math.max(60, Math.min(240, 40 + text.length() * 2));
    }

    /** A reply to its player: always said. */
    public static void reply(PlayerAssistantEntity a, String text) {
        reply(a, text, null);
    }

    public static void reply(PlayerAssistantEntity a, String text, @Nullable List<PaUnderstand.Choice> choices) {
        if (text == null || text.isBlank()) return;
        PaData.Rec r = a.rec();
        if (r != null) {
            r.say(1, text, a.level().getGameTime());
            PaData.touch();
        }
        a.mind.lastSaid = a.tickCount;
        PaNet.say(a, text, true, choices, bubbleTicks(text));
    }

    /** Something it says unasked (a comment, a tip, a job done), at this chattiness or above (0 quiet .. 3 chatty). */
    public static boolean comment(PlayerAssistantEntity a, int least, String text) {
        PaData.Rec r = a.rec();
        int chatty = r == null ? 2 : r.chatty;
        if (chatty < least) return false;
        // rate limit: chattier means sooner; a quiet one waits a good while between remarks
        int gap = switch (chatty) { case 3 -> 100; case 2 -> 400; case 1 -> 1200; default -> 2400; };
        if (a.tickCount - a.mind.lastComment < gap && least > 0) return false;
        a.mind.lastComment = a.tickCount;
        reply(a, text);
        return true;
    }

    /** A line for a non-owner (only they see it). */
    public static void to(PlayerAssistantEntity a, ServerPlayer p, String text) {
        net.minecraft.nbt.CompoundTag t = new net.minecraft.nbt.CompoundTag();
        t.putInt("id", a.getId());
        t.putString("name", a.displayNameCap());
        t.putString("text", text);
        t.putInt("ticks", bubbleTicks(text));
        t.putBoolean("owner", false);
        PaNet.send(p, PaPayload.SAY, t);
    }

    /** A line the original engine said while working (a goal's narration). Failures mark the step; the rest is quiet. */
    public static void work(PlayerAssistantEntity a, String message) {
        if (message == null || message.isBlank()) return;
        PaPlan.Step s = a.plan.current;
        if (FAIL.matcher(message).find()) {
            if (s != null && s.fail.isEmpty()) s.fail = message;
            else if (s == null) comment(a, 1, message);
            return;
        }
        PaData.Rec r = a.rec();
        if (r != null && r.chatty >= 3) comment(a, 3, message);
    }

    /** Is this line one of the engine's "could not"? (Tests.) */
    public static boolean failure(String message) {
        return FAIL.matcher(message).find();
    }
}
