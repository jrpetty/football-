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

    /** The engine sorting itself out ("don't have the parts... getting them first"): not a failure. */
    private static final Pattern RECOVERING = Pattern.compile("(?i)(getting them first|first:|on it|i'll |i will |going to get|heading to|off to)");

    /** Ticks a bubble stays up: long enough to read on camera. */
    static int bubbleTicks(String text) {
        return Math.max(60, Math.min(240, 40 + text.length() * 2));
    }

    /** A reply to its player: always said. */
    public static void reply(PlayerAssistantEntity a, String text) {
        reply(a, text, null);
    }

    public static void reply(PlayerAssistantEntity a, String text, @Nullable List<PaUnderstand.Choice> choices) {
        reply(a, text, choices, false);
    }

    /** [pa35] A reply its player alone hears and reads (a memory: never a bubble anyone else can read). */
    public static void reply(PlayerAssistantEntity a, String text, @Nullable List<PaUnderstand.Choice> choices, boolean ownerOnly) {
        if (text == null || text.isBlank()) return;
        PaData.Rec r = a.rec();
        if (r != null) {
            r.say(1, text, a.level().getGameTime());
            PaData.touch();
        }
        a.mind.lastSaid = a.tickCount;
        PaAnim.forLine(a, text);                                     // [pa33c-2b] a gesture with the words ([anim:x] tags)
        PaNet.say(a, text, true, choices, bubbleTicks(text), false, ownerOnly);
    }

    /** [pa33b] A hello (on login, on coming back): one in the log however often its player reconnects. */
    public static void greet(PlayerAssistantEntity a, String text) {
        PaData.Rec r = a.rec();
        if (r != null) {
            r.say(1, text, a.level().getGameTime(), true);
            PaData.touch();
        }
        a.mind.lastSaid = a.tickCount;
        PaAnim.forLine(a, text);
        PaNet.say(a, text, true, null, bubbleTicks(text), true);
    }

    /** Something it says unasked (a comment, a tip, a job done), at this chattiness or above (0 quiet .. 3 chatty). */
    public static boolean comment(PlayerAssistantEntity a, int least, String text) {
        return comment(a, least, text, false);
    }

    /** As comment; an event (something just happened) may come a little sooner than idle chatter. */
    public static boolean comment(PlayerAssistantEntity a, int least, String text, boolean event) {
        return comment(a, least, text, event, false);
    }

    /** [pa35] As comment, for its player alone when it touches on a memory. */
    public static boolean comment(PlayerAssistantEntity a, int least, String text, boolean event, boolean ownerOnly) {
        PaData.Rec r = a.rec();
        int chatty = r == null ? 2 : r.chatty;
        if (chatty < least) return false;
        if (r != null && r.mute >= 2 && least > 0) return false;   // [pa33b] told to be quiet: nothing unasked
        // rate limit: [pa33b] at the usual chattiness one remark every few minutes (sooner around events); only "Won't shut up" keeps on
        int gap = PaQuips.gap(chatty, event);
        if (a.tickCount - a.mind.lastComment < gap && least > 0) return false;
        a.mind.lastComment = a.tickCount;
        reply(a, text, null, ownerOnly);
        return true;
    }

    /** [pa33b] Its line for a situation, at its place in the personality triangle, with its player's and its own names. */
    public static String line(PlayerAssistantEntity a, String key, String... kv) {
        String[] all = new String[kv.length + 4];
        all[0] = "owner";
        all[1] = PaMemoryAct.callName(a);                        // [pa35] what it was told to call them
        all[2] = "name";
        all[3] = a.displayNameCap();
        System.arraycopy(kv, 0, all, 4, kv.length);              // the caller's own blanks after, so they win
        return sentences(PaPhrases.line(key, a.tri(), a.getRandom()::nextInt, a.mind.recent, all));
    }

    /** [pa33c-2b] A blank that opens a sentence ("{what}, nine blocks west") starts it with a capital. */
    static String sentences(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder b = new StringBuilder(s);
        boolean start = true;
        for (int i = 0; i < b.length(); i++) {
            char c = b.charAt(i);
            if (start && Character.isLetter(c)) { b.setCharAt(i, Character.toUpperCase(c)); start = false; }
            else if (c == '.' || c == '!' || c == '?') start = i + 1 < b.length() && b.charAt(i + 1) == ' ' && !(i > 0 && b.charAt(i - 1) == '.');
            else if (c != ' ' && c != '"' && c != '(') start = false;
        }
        return b.toString();
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
        if (FAIL.matcher(message).find() && !RECOVERING.matcher(message).find()) {
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
