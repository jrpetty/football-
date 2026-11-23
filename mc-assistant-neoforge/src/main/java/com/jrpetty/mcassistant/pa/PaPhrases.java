package com.jrpetty.mcassistant.pa;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [pa33] What the Player AI Assistant says, in its own voice: a phrase bank per personality (cheerful, dry, calm),
 * written to sound like a companion and not a machine. Nothing here is the village folk's speech bank; the folk
 * talk among themselves, this one talks to its player. Lines carry {name} (its own name), {owner} (its player's),
 * {n}, {item}, {job} and {what}, filled by {@link #line}. Pure Java.
 */
public final class PaPhrases {

    private PaPhrases() {}

    public enum Personality {
        CHEERFUL("Cheerful", "Warm and upbeat."), DRY("Dry", "Deadpan, a little wry."), CALM("Calm", "Quiet and steady.");

        public final String label, blurb;

        Personality(String label, String blurb) {
            this.label = label;
            this.blurb = blurb;
        }

        public static Personality of(int i) {
            return values()[Math.floorMod(i, values().length)];
        }
    }

    /** Something that picks a number under n (the game's random, or java.util.Random in tests). */
    public interface Dice {
        int nextInt(int n);
    }

    private static final Map<String, String[][]> BANK = new HashMap<>();

    /** One key, three personalities' lines: cheerful, dry, calm. */
    private static void put(String key, String[] cheerful, String[] dry, String[] calm) {
        BANK.put(key, new String[][]{cheerful, dry, calm});
    }

    static {
        put("intro",
            new String[]{"Hi {owner}! I'm {name}, your assistant. Talk to me, give me orders, or just let me help out. Want a quick tour?"},
            new String[]{"Hello, {owner}. I'm {name}. I fetch, build, dig and fight, and I complain very little. Tour?"},
            new String[]{"Hello {owner}. I'm {name}, and I'm here to help. Shall I show you around?"});
        put("greet",
            new String[]{"Welcome back, {owner}! I missed you.", "There you are, {owner}! Ready when you are.", "Good to see you, {owner}. What are we doing today?"},
            new String[]{"Oh, it's you. Good. I was running out of rocks to look at.", "Back again, {owner}. The world didn't end while you were out.", "{owner}. Hello. I kept your spot warm."},
            new String[]{"Welcome back, {owner}.", "Hello again, {owner}. All quiet here.", "Good to have you back, {owner}."});
        put("hello",
            new String[]{"Hello, {owner}!", "Hi! What do you need?", "Hey there!"},
            new String[]{"Hello. Yes, I'm still here.", "Hi. What now?", "Greetings, {owner}."},
            new String[]{"Hello, {owner}.", "Hi. I'm listening.", "Yes?"});
        put("thanks",
            new String[]{"Any time, {owner}!", "Happy to help!", "That's what I'm here for!"},
            new String[]{"I know. I'm very good.", "Put it on my tab.", "Don't mention it. Really, don't."},
            new String[]{"You're welcome.", "Glad to help.", "Of course."});
        put("bye",
            new String[]{"See you soon, {owner}!", "Bye! I'll be right here."},
            new String[]{"Off you go, then.", "Farewell. I'll try not to miss you."},
            new String[]{"Take care, {owner}.", "Goodbye for now."});
        put("ok",
            new String[]{"On it!", "You got it!", "Sure thing!", "Right away!"},
            new String[]{"Fine.", "If I must.", "Consider it done. Eventually.", "Right."},
            new String[]{"Okay.", "Understood.", "Will do.", "Alright."});
        put("follow",
            new String[]{"Right behind you!", "Lead the way!"},
            new String[]{"Following. Try not to walk into lava.", "After you."},
            new String[]{"I'll follow.", "With you."});
        put("stay",
            new String[]{"I'll wait right here!", "Staying put!"},
            new String[]{"I'll stand here and think about rocks.", "Staying. Riveting."},
            new String[]{"I'll wait here.", "Holding here."});
        put("come",
            new String[]{"Coming!", "On my way, {owner}!"},
            new String[]{"Coming. No need to shout.", "Yes, yes, coming."},
            new String[]{"Coming to you.", "On my way."});
        put("guard",
            new String[]{"I've got your back, {owner}!", "Nothing gets past me!"},
            new String[]{"Guarding. Monsters, form an orderly queue.", "I'll hit anything that hits you."},
            new String[]{"I'll keep watch.", "You're safe with me."});
        put("stop",
            new String[]{"Stopping! What next?", "Okay, stopped."},
            new String[]{"Stopped. Dramatically.", "Fine, I'll stand here."},
            new String[]{"Stopped.", "Alright, I've stopped."});
        put("undo",
            new String[]{"Undone! Back to what I was doing."},
            new String[]{"Undone. Let's pretend that never happened."},
            new String[]{"I've undone that."});
        put("pause",
            new String[]{"Pausing! Say resume when you're ready."},
            new String[]{"Paused. I'll just stand here, then."},
            new String[]{"Paused. Tell me to resume when you like."});
        put("resume",
            new String[]{"Back to it!"},
            new String[]{"Back to work, then."},
            new String[]{"Resuming."});
        put("done",
            new String[]{"{did}! All done. What's next?", "Done! {did}.", "{did}, done and dusted!"},
            new String[]{"{did}. Applause optional.", "{did}. You're welcome.", "{did}. That's that."},
            new String[]{"{did}. All done.", "Done. {did}.", "{did}. That's everything."});
        // a step of a longer plan finished, more to come
        put("step",
            new String[]{"{did}! On to the next bit.", "{did}. Next!", "{did}, onwards!"},
            new String[]{"{did}. Next.", "{did}. One down.", "{did}. Moving on, as one does."},
            new String[]{"{did}. Moving on.", "{did}. Next step.", "{did}."});
        put("queue_empty",
            new String[]{"That's everything on my list, {owner}!"},
            new String[]{"List's empty. I'll find something to stare at."},
            new String[]{"That's all my jobs done."});
        put("cant",
            new String[]{"I can't do that yet: {what}", "Hmm, I'm stuck: {what}"},
            new String[]{"Can't. {what}", "Problem: {what}"},
            new String[]{"I can't manage that: {what}", "Something's in the way: {what}"});
        put("night",
            new String[]{"It's getting dark, {owner}. Want me to light things up or head inside?", "Night's coming! Stay close."},
            new String[]{"Sun's going. Monsters coming. Just saying.", "Night again. My favourite. Not really."},
            new String[]{"Night is falling. Let's be careful.", "It's getting dark."});
        put("owner_hurt",
            new String[]{"You're hurt, {owner}! Eat something, and let me handle the fighting!", "Careful, {owner}, your health's low!"},
            new String[]{"You look terrible. Eat something.", "You're low on health. I'd rather you weren't dead."},
            new String[]{"Your health is low. Let's be careful.", "You're hurt. Eat something, {owner}."});
        put("me_hurt",
            new String[]{"Ouch! I need to patch up.", "I'm hurt, I'll back off a bit."},
            new String[]{"I've been better. Retreating heroically.", "That hurt. I'm leaving."},
            new String[]{"I'm hurt. I'll fall back.", "I need a moment to heal."});
        put("diamonds",
            new String[]{"DIAMONDS! {owner}, look!", "Ooh, shiny! I found diamonds!"},
            new String[]{"Diamonds. Try to contain yourself.", "Found diamonds. Yes, really."},
            new String[]{"I've found diamonds.", "Diamonds, here."});
        put("found_ore",
            new String[]{"Found some {item}!"},
            new String[]{"Oh look, {item}."},
            new String[]{"There's {item} here."});
        put("pack_full",
            new String[]{"My pack's full! Heading to put things away."},
            new String[]{"Pack's full. Even I have limits."},
            new String[]{"My pack is full; I'll empty it."});
        put("respawn",
            new String[]{"I'm back! Sorry about that, {owner}.", "Back on my feet!"},
            new String[]{"I have returned. Death was boring.", "Back. Let's not do that again."},
            new String[]{"I'm back.", "I've returned, {owner}."});
        put("died",
            new String[]{"Oh no, I've been knocked out! I'll be back soon, {owner}."},
            new String[]{"Well. That went badly. Back shortly."},
            new String[]{"I've fallen. I'll return soon."});
        put("tip",
            new String[]{"Tip: hold the talk key and just say what you want!", "Tip: the action wheel lets me act on whatever you're looking at.",
                "Tip: give me a chest to use in my settings and I can craft from your stock."},
            new String[]{"Tip: I take orders by voice. Wild, I know.", "Tip: look at something and use the wheel. I'll handle it.",
                "Tip: I can sort your chests. They need it."},
            new String[]{"A tip: you can talk to me with your voice.", "A tip: the action wheel works on what you're looking at.",
                "A tip: I can keep the furnace fuelled if you like."});
        put("autohelp",
            new String[]{"I'll {what}, since nothing else is going on.", "Spotted something useful: I'll {what}."},
            new String[]{"Bored, so I'll {what}.", "Nothing to do, so I'll {what}."},
            new String[]{"I'll {what} while it's quiet.", "While I wait, I'll {what}."});
        put("not_owner",
            new String[]{"Sorry, I only take orders from {owner}!"},
            new String[]{"Nice try. I work for {owner}."},
            new String[]{"I only answer to {owner}."});
        put("confirm",
            new String[]{"{what}. Sound good?"},
            new String[]{"{what}. Yes?"},
            new String[]{"{what}. Shall I?"});
        put("cancelled",
            new String[]{"No problem, I won't."},
            new String[]{"Fine, cancelled."},
            new String[]{"Alright, I won't."});
        put("stuck",
            new String[]{"I got stuck, so I've come back to you, {owner}."},
            new String[]{"I was stuck. Teleported. Don't ask."},
            new String[]{"I was stuck, so I came back to you."});
    }

    /** A line for a key in this personality, the blanks filled. A key it does not know comes back as {what}. */
    public static String line(String key, Personality p, Dice random, Map<String, String> fill) {
        String[][] all = BANK.get(key);
        String raw;
        if (all == null) raw = fill.getOrDefault("what", key);
        else {
            String[] mine = all[p.ordinal()];
            raw = mine[random.nextInt(mine.length)];
        }
        for (Map.Entry<String, String> e : fill.entrySet()) raw = raw.replace("{" + e.getKey() + "}", e.getValue());
        return raw.replace("{name}", "").replace("{owner}", "you").replace("{n}", "").replace("{item}", "it")
            .replace("{job}", "the job").replace("{did}", "Done").replace("{what}", "").replaceAll("\\s+", " ").trim();
    }

    public static String line(String key, Personality p, Dice random, String... kv) {
        Map<String, String> fill = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) fill.put(kv[i], kv[i + 1]);
        return line(key, p, random, fill);
    }

    private static final Map<String, String> PAST = Map.ofEntries(
        Map.entry("make", "made"), Map.entry("gather", "gathered"), Map.entry("get", "got"), Map.entry("build", "built"),
        Map.entry("craft", "crafted"), Map.entry("smelt", "smelted"), Map.entry("mine", "mined"), Map.entry("dig", "dug"),
        Map.entry("chop", "chopped"), Map.entry("farm", "farmed"), Map.entry("plant", "planted"), Map.entry("place", "placed"),
        Map.entry("put", "put"), Map.entry("fuel", "fuelled"), Map.entry("buy", "bought"), Map.entry("sell", "sold"),
        Map.entry("bring", "brought"), Map.entry("fetch", "fetched"), Map.entry("store", "stored"), Map.entry("light", "lit"),
        Map.entry("clear", "cleared"), Map.entry("fish", "caught fish"), Map.entry("hunt", "hunted"), Map.entry("cook", "cooked"),
        Map.entry("sort", "sorted"), Map.entry("shear", "sheared"), Map.entry("breed", "bred"), Map.entry("explore", "explored"),
        Map.entry("give", "gave"), Map.entry("take", "took"), Map.entry("go", "went"), Map.entry("deposit", "stored"),
        Map.entry("harvest", "harvested"), Map.entry("till", "tilled"), Map.entry("bridge", "bridged"), Map.entry("collect", "collected"));

    /** A step's label ("make a stone pickaxe first") as a finished thing said ("Made a stone pickaxe"). */
    public static String did(String label) {
        String l = label == null ? "" : label.trim().replaceAll("\\s+first$", "");
        if (l.isEmpty()) return "Done";
        int sp = l.indexOf(' ');
        String verb = (sp < 0 ? l : l.substring(0, sp)).toLowerCase(java.util.Locale.ROOT);
        String rest = sp < 0 ? "" : l.substring(sp);
        String past = PAST.get(verb);
        String out = past == null ? "Finished: " + l : past + rest;
        return Character.toUpperCase(out.charAt(0)) + out.substring(1);
    }

    /** Every key in the bank (the tests read every line of every personality). */
    public static List<String> keys() {
        return List.copyOf(BANK.keySet());
    }

    /** Every line under a key for one personality, unfilled. */
    public static List<String> raw(String key, Personality p) {
        String[][] all = BANK.get(key);
        return all == null ? List.of() : List.of(all[p.ordinal()]);
    }
}
