package com.jrpetty.mcassistant.entity;

import net.minecraft.util.RandomSource;

/**
 * [till] What the shopkeeper says at the till: after you pay (dry, never gushing), when it buys off you, to a folk it
 * served, and the one line about its manner the checkout shows under its name.
 */
final class TillVoice {

    private TillVoice() {}

    private static final String[] PAID = {
        "That's you sorted.",
        "Lovely. Next.",
        "Mind the step on the way out.",
        "Exact change would have been nice. Never mind.",
        "A pleasure. Probably.",
        "Don't eat it all at once.",
        "Receipt's in your head. Keep it safe.",
        "Come back when you've run out.",
        "Bag? We don't do bags.",
        "That's the till happy, anyway.",
        "Spend it all in one place, why not.",
        "Right. Off you go, then."
    };

    private static final String[] GRUMPY = {
        "Fine.", "Don't loiter.", "Yes, yes. Paid. Go on.", "Next. There isn't a next. Go anyway."
    };

    private static final String[] CHEERFUL = {
        "Ooh, good choice. Mostly.", "There we are! Lovely doing business.", "That's made my morning. Low bar."
    };

    private static final String[] SELF = {
        "The till rings. Nobody claps.",
        "Paid. The honesty is noted, by nobody.",
        "Ding. You've served yourself admirably.",
        "The coin drops in. The till seems satisfied."
    };

    private static final String[] KIOSK = {
        "The kiosk clicks approvingly.",
        "Thank you. The kiosk means it, as far as a kiosk can.",
        "Transaction complete. Nobody saw you hesitate.",
        "The kiosk dings. It has no further comment."
    };

    private static final String[] BOUGHT = {
        "I'll take that off your hands.", "Fair enough. It'll sell.", "Done. Don't tell me where you found it.", "Right, that's ours now."
    };

    static String paid(RandomSource r, VillageFolkEntity k) {
        Social.Life life = k.life();
        if (life.has(Social.Trait.GRUMPY) && r.nextInt(2) == 0) return GRUMPY[r.nextInt(GRUMPY.length)];
        if (life.has(Social.Trait.CHEERFUL) && r.nextInt(2) == 0) return CHEERFUL[r.nextInt(CHEERFUL.length)];
        return PAID[r.nextInt(PAID.length)];
    }

    static String selfPaid(RandomSource r, boolean kiosk) {
        String[] from = kiosk ? KIOSK : SELF;
        return from[r.nextInt(from.length)];
    }

    static String bought(RandomSource r) {
        return BOUGHT[r.nextInt(BOUGHT.length)];
    }

    static String folkPaid(RandomSource r, VillageFolkEntity f) {
        String n = f.displayNameCap();
        String[] lines = { "There you are, " + n + ".", "That's you, " + n + ". Mind how you go.", n + ". Again. Lovely.", "Ta, " + n + "." };
        return lines[r.nextInt(lines.length)];
    }

    /** A line about the keeper's manner at the till, by its nature. */
    static String trait(VillageFolkEntity k) {
        Social.Life life = k.life();
        String n = k.displayNameCap();
        if (life.has(Social.Trait.GENEROUS)) return n + " rounds down for regulars, in spirit.";
        if (life.has(Social.Trait.GRUMPY)) return n + " doesn't do small talk. Or change.";
        if (life.has(Social.Trait.CHEERFUL)) return n + " hums while counting your coins.";
        if (life.has(Social.Trait.SHY)) return n + " nods at the total rather than saying it.";
        if (life.has(Social.Trait.HARDWORKING)) return n + " has restocked twice already today.";
        if (life.has(Social.Trait.EASYGOING)) return n + " isn't in a hurry. Neither should you be.";
        if (life.has(Social.Trait.SOCIABLE)) return n + " will want to know how your day was.";
        if (life.has(Social.Trait.CURIOUS)) return n + " will ask what you're making with it.";
        return "";
    }
}
