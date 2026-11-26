package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * [featfolk] What folk have seen a player do, and the only things they will say of one.
 *
 * <p>The rule is the player's own: a folk talks about a player only from what it saw (in its line of sight, within
 * {@link #SIGHT} blocks, awake) or did with them itself (a trade, a talk, a gift, a hand given or a blow taken, a day's
 * hire). Each folk keeps its own memory of it ({@link FolkLines.Deed}). A folk that never set eyes on a player says
 * nothing of them in its own words, ever. What it heard from a witness it may pass on to the player, but only as that
 * witness's word ("Bramble says she saw you..."), at half the weight, in the witness's words and no more; and it never
 * passes hearsay on to another folk: only witnesses spread word of a player ({@link #passOn}).
 *
 * <p>Everything said of a player by a folk, aloud to another folk or to the player's face, goes through {@link #say},
 * which keeps the last few hundred lines with who said them and on whose word, so the tests can check the rule.
 */
public final class Witness {

    private Witness() {}

    /** How far a folk can see a deed from. */
    public static final int SIGHT = 16;
    /** How many deeds a folk keeps in mind. */
    public static final int MEMORY = 16;
    /** How many days a deed stays fresh enough to talk about. */
    public static final int FRESH_DAYS = 12;

    /**
     * What a player can be seen to do. Each has its weight in a folk's opinion, and two ways to say it: the bare verb
     * ("I saw you <b>defend the square from a raid</b>") and the past ("you <b>defended the square from a raid</b>").
     * {d} is the detail (a monster, a load of wheat, a poppy), {v} the folk it was done to (or for).
     */
    public enum Kind {
        DEFENDED(8, "defend the square from a raid", "defended the square from a raid"),
        SLEW(3, "put down {d} near the houses", "put down {d} near the houses"),
        RESCUED(10, "pull {d} off {v}", "pulled {d} off {v}"),
        HIT(-10, "hit {v}", "hit {v}"),
        STRUCK_DOWN(-30, "strike {v} down", "struck {v} down"),
        TRADED(2, "trade fair with {v} for {d}", "traded fair with {v} for {d}"),
        SHORTED(-6, "try to short {v} on {d}", "tried to short {v} on {d}"),
        GIFT(4, "give {v} {d}", "gave {v} {d}"),
        SNUB(-4, "hand {v} {d}, of all things", "handed {v} {d}, of all things"),
        HELPED(5, "bring {v} {d}", "brought {v} {d}"),
        CLEARED(5, "clear the monsters off for {v}", "cleared the monsters off for {v}"),
        HIRED(3, "hire {v} for {d}", "hired {v} for {d}"),
        PRAISED(2, "tell {v} they were doing a fine job", "told {v} they were doing a fine job"),
        /** A word passed: contact, and nothing worth repeating. */
        TALKED(0, "stop for a word with {v}", "stopped for a word with {v}");

        public final int weight;
        final String base, past;

        Kind(int weight, String base, String past) {
            this.weight = weight;
            this.base = base;
            this.past = past;
        }

        /** Worth talking about (a word passed is not). */
        public boolean notable() { return this != TALKED; }
    }

    // ------------------------------------------------------------------ what is said, for the tests

    /** One thing said of a player: by whom, about whom, on whose word (null: its own), and the words. */
    public record Said(UUID speaker, String speakerName, UUID player, @Nullable UUID source, Kind kind, String line) {}

    private static final Deque<Said> SAID = new ConcurrentLinkedDeque<>();

    public static List<Said> saidForTests() { return new ArrayList<>(SAID); }

    public static void resetForTests() { SAID.clear(); }

    private static void say(VillageFolkEntity f, FolkLines.Deed d, String line) {
        SAID.addLast(new Said(f.getUUID(), f.displayNameCap(), d.player, d.source, d.kind, line));
        while (SAID.size() > 400) SAID.pollFirst();
    }

    // ------------------------------------------------------------------ seeing and dealing

    /** Can this folk see the player, now: awake, within sight, nothing in the way? */
    public static boolean sees(VillageFolkEntity f, Player p) {
        return f.isAlive() && !f.isSleeping() && f.persona().rolled() && !p.isSpectator() && !p.isInvisible()
            && f.distanceToSqr(p) <= (double) SIGHT * SIGHT && f.hasLineOfSight(p);
    }

    /**
     * The player did this with this folk itself (a trade, a gift, a blow): its own memory of it. {@code victim} is the
     * folk itself; what it is about is {@code detail}.
     */
    public static void dealt(VillageFolkEntity f, Player p, Kind k, String detail) {
        if (!f.persona().rolled() || f.level().isClientSide) return;
        remember(f, p.getUUID(), p.getName().getString(), k, clean(detail), f.displayNameCap(), null, "", k.weight);
    }

    /**
     * The player did this where folk could see: every folk that sees the player now (sight and range: {@link #sees})
     * remembers it as its own, and thinks the better or worse of them by {@code feel} (0: as it already does). The one
     * it was done to, if any, is left to the caller (it remembers it as done to it: {@link #dealt}). Returns the
     * witnesses.
     */
    public static List<VillageFolkEntity> seen(ServerLevel level, Player p, Kind k, String detail,
                                               @Nullable VillageFolkEntity victim, int feel) {
        List<VillageFolkEntity> out = new ArrayList<>();
        String who = p.getName().getString();
        String v = victim == null ? "" : victim.displayNameCap();
        for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class, p.getBoundingBox().inflate(SIGHT),
                f -> f != victim && sees(f, p))) {
            remember(f, p.getUUID(), who, k, clean(detail), v, null, "", k.weight);
            if (feel != 0) f.persona().feelFor(p.getUUID(), who, feel);
            out.add(f);
        }
        return out;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('|', '/').trim();
    }

    /** Into its memory: the same deed again the same day is the one memory, held the stronger. */
    static void remember(VillageFolkEntity f, UUID player, String name, Kind k, String detail, String victim,
                         @Nullable UUID source, String sourceName, int weight) {
        List<FolkLines.Deed> deeds = f.lines().deeds;
        long day = f.level().getDayTime() / 24000L;
        for (FolkLines.Deed d : deeds) {
            if (!d.player.equals(player) || !d.sameAs(k, detail, victim)) continue;
            if (d.firstHand() && source != null) return;                // it saw it itself: hearsay adds nothing
            if (!d.firstHand() && source == null) {                       // it heard of it, and now has seen it too
                deeds.remove(d);
                break;
            }
            if (d.firstHand() == (source == null) && (source == null || source.equals(d.source))) {
                d.day = day;
                d.playerName = name;
                return;
            }
        }
        deeds.add(new FolkLines.Deed(player, name, k, detail, victim, day, weight, source, sourceName));
        while (deeds.size() > MEMORY) {
            // The least of it goes first: a word passed, then hearsay, then the oldest.
            FolkLines.Deed least = deeds.get(0);
            for (FolkLines.Deed d : deeds) if (rankOf(d) < rankOf(least)) least = d;
            deeds.remove(least);
        }
    }

    private static int rankOf(FolkLines.Deed d) {
        return (d.kind.notable() ? 1000 : 0) + (d.firstHand() ? 500 : 0) + Math.abs(d.weight) * 10 + (int) Math.min(9, d.day % 10);
    }

    // ------------------------------------------------------------------ contact

    /**
     * Has it any dealings of its own with this player: seen them do something, or dealt with them (talked, traded,
     * been given a thing)? Only a folk with contact speaks of a player in its own words, or carries weight in what its
     * town thinks of them.
     */
    public static boolean contact(VillageFolkEntity f, @Nullable UUID player) {
        if (player == null) return false;
        for (FolkLines.Deed d : f.lines().deeds) if (d.firstHand() && d.player.equals(player)) return true;
        Persona.Opinion o = f.persona().players.get(player);
        return o != null && (o.lastTalkDay >= 0 || o.lastGiftDay >= 0 || o.lastPraiseDay >= 0);
    }

    /** Does it know of this player at all, its own or on a witness's word? */
    public static boolean heardOf(VillageFolkEntity f, UUID player) {
        for (FolkLines.Deed d : f.lines().deeds) if (d.player.equals(player) && d.kind.notable()) return true;
        return false;
    }

    /** The folk of the town by this name has contact with the player (for a line that names it). */
    public static boolean contactByName(@Nullable UUID village, String name, UUID player) {
        if (village == null || name.isEmpty()) return false;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.displayNameCap().equals(name)) return contact(f, player);
        }
        return false;
    }

    /** Its most telling deed of this player: first-hand before hearsay, then the weightiest, then the latest. */
    @Nullable
    public static FolkLines.Deed telling(VillageFolkEntity f, UUID player, boolean ownOnly) {
        long day = f.level().getDayTime() / 24000L;
        FolkLines.Deed best = null;
        int most = Integer.MIN_VALUE;
        for (FolkLines.Deed d : f.lines().deeds) {
            if (!d.player.equals(player) || !d.kind.notable() || ownOnly && !d.firstHand()) continue;
            if (day - d.day > FRESH_DAYS * 3L) continue;
            int s = (d.firstHand() ? 1000 : 0) + Math.abs(d.weight) * 10 - (int) Math.min(9, day - d.day);
            if (s > most) { most = s; best = d; }
        }
        return best;
    }

    // ------------------------------------------------------------------ words

    /** The deed in a few words, about "you" or a name, from where the speaker stands ("me" if it was done to it). */
    static String clause(FolkLines.Deed d, boolean past, String speaker, String stand) {
        String v = d.victim.isEmpty() ? "someone" : d.victim.equals(speaker) ? stand : d.victim;
        return (past ? d.kind.past : d.kind.base).replace("{d}", d.detail.isEmpty() ? "something" : d.detail).replace("{v}", v);
    }

    private static String fill(String template, String clauseBase, String clausePast, String who, String witness) {
        return template.replace("{base}", clauseBase).replace("{past}", clausePast).replace("{p}", who).replace("{w}", witness);
    }

    private static final String[] SEEN_GOOD = {
        "I saw you {base}. I don't forget a thing like that.",
        "You {past}. I was there.",
        "I was there when you {past}. Credit where it's due.",
        "Saw you {base} with my own eyes. Not bad.",
        "You {past}. Some of us noticed.",
        "I watched you {base}. That counts for something here.",
        "I saw you {base}. I've told one or two people, I'll admit." };
    private static final String[] SEEN_BAD = {
        "I saw you {base}. I know what I saw.",
        "You {past}. I was standing right there.",
        "I watched you {base}. Not the kind of thing I forget.",
        "Saw you {base}. Make of that what you like; I have.",
        "You {past}, and I saw it. Keep your distance.",
        "I was there when you {past}. So, no, I'm not glad to see you." };
    private static final String[] DEALT_GOOD = {
        "You {past}. I've not forgotten.",
        "You {past}. That was decent of you.",
        "I remember you {past}. Most don't bother.",
        "You {past}. I tell people that, when they ask.",
        "Last time, you {past}. Good of you.",
        "You {past}. I keep a tally, and you're on the right side of it." };
    private static final String[] DEALT_BAD = {
        "You {past}. I remember.",
        "You {past}. Don't think I've let it go.",
        "Last time you {past}. I count my change now.",
        "You {past}. People here know, because I told them.",
        "You {past}. I'd rather you didn't do it again.",
        "I haven't forgotten that you {past}." };
    private static final String[] HEARD_SEEN = {
        "{w} says they saw you {base}. I wasn't there myself.",
        "According to {w}, you {past}. I only have it from them.",
        "{w} told me you {past}. Their words, not mine.",
        "I hear from {w} that you {past}. I'll take their word for it, for now.",
        "{w} saw you {base}, or so they say. I didn't.",
        "Word from {w} is that you {past}. That's all I know about it." };
    private static final String[] HEARD_DEALT = {
        "{w} says you {past}. I wasn't there.",
        "According to {w}, you {past}. I only have it from them.",
        "{w} told me you {past}. Their words.",
        "I hear from {w} that you {past}. That's their story.",
        "{w} mentioned you {past}. That's all I know.",
        "Word from {w} is that you {past}. I've nothing to add." };
    private static final String[] TELL_SEEN_GOOD = {
        "Saw {p} {base} the other day.",
        "{p}? I watched them {base}. Fair play.",
        "You know {p}? I saw them {base}.",
        "I was there when {p} {past}.",
        "Credit where it's due: {p} {past}. I saw it.",
        "If {p} comes by: I saw them {base}. Decent sort." };
    private static final String[] TELL_SEEN_BAD = {
        "Saw {p} {base}. Thought you should know.",
        "{p}? I watched them {base}. Keep an eye on them.",
        "I was there when {p} {past}. Not impressed.",
        "Word to the wise: I saw {p} {base}.",
        "{p} {past}. I saw it myself.",
        "If {p} comes round, remember I saw them {base}." };
    private static final String[] TELL_DEALT_GOOD = {
        "{p} {past}. Can't say fairer.",
        "Had dealings with {p}. They {past}.",
        "{p}? They {past}. No complaints from me.",
        "For the record, {p} {past}.",
        "{p} {past}. Worth knowing.",
        "Met {p}. They {past}. That's more than most do." };
    private static final String[] TELL_DEALT_BAD = {
        "{p} {past}. Count your change if they come by.",
        "Had dealings with {p}. They {past}.",
        "{p}? They {past}. I'm not having it twice.",
        "For the record, {p} {past}.",
        "{p} {past}. Thought you should hear it from me.",
        "Watch {p}. They {past}." };
    private static final String[] BACK_GOOD = {
        "Noted.", "Huh. Good to know.", "I'll bear it in mind.", "That's more than I'd heard.", "Fair enough.",
        "I'll take your word for it." };
    private static final String[] BACK_BAD = {
        "Noted.", "Hm. I'll watch them.", "Is that right.", "I'll bear it in mind.", "Can't say I'm surprised.",
        "I'll take your word for it." };
    private static final String[] BACK_SAW_TOO = {
        "I saw that too.", "I was there as well.", "Same as I saw.", "Yes. I know.", "That's how I saw it, too.",
        "Mm. I was there." };

    static String one(RandomSource r, String[] options) {
        return options[r.nextInt(options.length)];
    }

    /**
     * What this folk says to the player about them: its own memory in its own words, else (only if it has nothing of
     * its own) a witness's word, put as theirs. Null when it knows nothing of the player: it makes nothing up.
     */
    @Nullable
    public static String aboutYou(VillageFolkEntity f, Player p) {
        FolkLines.Deed d = telling(f, p.getUUID(), false);
        if (d == null) return null;
        RandomSource r = f.getRandom();
        String me = f.displayNameCap();
        String line;
        if (d.firstHand()) {
            boolean toMe = d.victim.equals(me);
            String[] set = toMe ? (d.weight >= 0 ? DEALT_GOOD : DEALT_BAD) : (d.weight >= 0 ? SEEN_GOOD : SEEN_BAD);
            line = fill(one(r, set), clause(d, false, me, "me"), clause(d, true, me, "me"), p.getName().getString(), "");
        } else {
            boolean theirs = d.victim.equals(d.sourceName);
            line = fill(one(r, theirs ? HEARD_DEALT : HEARD_SEEN), clause(d, false, d.sourceName, "them"),
                clause(d, true, d.sourceName, "them"), p.getName().getString(), d.sourceName);
        }
        line = capital(line);
        say(f, d, line);
        return line;
    }

    /**
     * Asked how the town sees the player (FolkTalk.repute) and with nothing of its own: the weightiest thing a witness
     * in the town saw or had done to it, put as that witness's word. Null if nobody in the town has seen the player do
     * anything worth the telling.
     */
    @Nullable
    public static String townWord(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null) return null;
        VillageFolkEntity by = null;
        FolkLines.Deed best = null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity w) || w == f) continue;
            FolkLines.Deed d = telling(w, p.getUUID(), true);
            if (d != null && (best == null || Math.abs(d.weight) > Math.abs(best.weight))) { best = d; by = w; }
        }
        if (best == null) return null;
        // As the witness's word, never as its own: the source is the witness.
        FolkLines.Deed told = new FolkLines.Deed(best.player, best.playerName, best.kind, best.detail, best.victim, best.day,
            best.weight / 2, by.getUUID(), by.displayNameCap());
        boolean theirs = told.victim.equals(told.sourceName);
        String line = capital(fill(one(f.getRandom(), theirs ? HEARD_DEALT : HEARD_SEEN), clause(told, false, told.sourceName, "them"),
            clause(told, true, told.sourceName, "them"), p.getName().getString(), told.sourceName));
        say(f, told, line);
        return line;
    }

    /**
     * Two folk together, and the teller has something of its own to tell of a player: it tells it (aloud, if a player
     * is near enough to hear and it has not spoken lately), and the listener takes it as the teller's word. Hearsay is
     * never passed on: a folk with nothing of its own to tell says nothing. Returns whether anything was told.
     */
    public static boolean passOn(VillageFolkEntity teller, VillageFolkEntity listener, boolean trusted, boolean aloud) {
        if (teller.isBaby() || listener.isBaby() || !teller.persona().rolled() || !listener.persona().rolled()) return false;
        long day = teller.level().getDayTime() / 24000L;
        // Its own freshest, weightiest deed this listener has not heard from it already.
        FolkLines.Deed tell = null;
        int most = 1;
        for (FolkLines.Deed d : teller.lines().deeds) {
            if (!d.firstHand() || !d.kind.notable() || day - d.day > FRESH_DAYS) continue;
            if (heardFrom(listener, teller, d)) continue;
            if (Math.abs(d.weight) > most) { most = Math.abs(d.weight); tell = d; }
        }
        if (tell == null) return false;
        boolean sawToo = false;
        for (FolkLines.Deed o : listener.lines().deeds) {
            if (o.firstHand() && o.player.equals(tell.player) && o.sameAs(tell.kind, tell.detail, tell.victim)) { sawToo = true; break; }
        }
        if (!sawToo) {
            remember(listener, tell.player, tell.playerName, tell.kind, tell.detail, tell.victim, teller.getUUID(),
                teller.displayNameCap(), tell.weight / 2);
        }
        // What it thinks of the player is nudged toward the teller's (Persona.hearsay), at the witness's word.
        int theirs = teller.persona().affinity(tell.player);
        listener.persona().hearsay(tell.player, tell.playerName, theirs, teller.displayNameCap(), trusted);
        UUID village = teller.ownerId();
        if (village != null) Standing.stir(village, tell.player);
        if (aloud) {
            RandomSource r = teller.getRandom();
            String me = teller.displayNameCap();
            boolean mine = tell.victim.equals(me);
            String[] set = mine ? (tell.weight >= 0 ? TELL_DEALT_GOOD : TELL_DEALT_BAD) : (tell.weight >= 0 ? TELL_SEEN_GOOD : TELL_SEEN_BAD);
            String line = capital(fill(one(r, set), clause(tell, false, me, "me"), clause(tell, true, me, "me"), tell.playerName, ""));
            say(teller, tell, line);
            teller.getLookControl().setLookAt(listener, 30.0F, 30.0F);
            listener.getLookControl().setLookAt(teller, 30.0F, 30.0F);
            FolkAction.play(teller, FolkAction.Act.TALK, 60);
            FolkAction.play(listener, FolkAction.Act.LISTEN, 60);
            FolkAction.mark(listener, tell.weight >= 0 ? FolkAction.Mark.EXCLAIM : FolkAction.Mark.QUESTION);
            FolkTalk.speak(teller, line);
            listener.sayLater(one(r, sawToo ? BACK_SAW_TOO : tell.weight >= 0 ? BACK_GOOD : BACK_BAD), 40);
        }
        return true;
    }

    /** Has the listener this deed already, on this teller's word? */
    private static boolean heardFrom(VillageFolkEntity listener, VillageFolkEntity teller, FolkLines.Deed d) {
        for (FolkLines.Deed o : listener.lines().deeds) {
            if (!o.player.equals(d.player) || !o.sameAs(d.kind, d.detail, d.victim)) continue;
            if (o.firstHand() || teller.getUUID().equals(o.source)) return true;
        }
        return false;
    }

    /**
     * A list of things a folk might pass on to the player (FolkTalk.gossipFor), with anything naming the player that the
     * folk has no contact to back taken out: it does not repeat what it never saw of them.
     */
    public static List<String> screen(VillageFolkEntity f, Player p, List<String> said) {
        if (contact(f, p.getUUID())) return said;
        String name = p.getName().getString();
        List<String> out = new ArrayList<>(said.size());
        for (String s : said) if (!s.contains(name)) out.add(s);
        return out;
    }

    /** For its profile (FolkProfile): what it has seen of this player, and what it was told of them and by whom. */
    public static List<String> profileLines(VillageFolkEntity f, UUID player) {
        List<String> out = new ArrayList<>();
        String me = f.displayNameCap();
        for (int i = f.lines().deeds.size() - 1; i >= 0 && out.size() < 6; i--) {
            FolkLines.Deed d = f.lines().deeds.get(i);
            if (!d.player.equals(player) || !d.kind.notable()) continue;
            if (d.firstHand()) {
                out.add((d.victim.equals(me) ? "You " + clause(d, true, me, "me") : "Saw you " + clause(d, false, me, "me"))
                    + " (day " + (d.day + 1) + ")");
            } else {
                out.add("Heard from " + d.sourceName + " that you " + clause(d, true, d.sourceName, "them") + " (day " + (d.day + 1) + ")");
            }
        }
        return out;
    }

    /** A reason in the town's book (TownOpinion): "Saw you defend the square from a raid", "Says you tried to short them on wheat". */
    static String reason(FolkLines.Deed d, String teller) {
        return d.victim.equals(teller) ? "Says you " + clause(d, true, teller, "them") : "Saw you " + clause(d, false, teller, "them");
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** "a zombie", "an enderman": the monster by its kind. */
    public static String monster(net.minecraft.world.entity.Entity e) {
        return FolkTalk.article(e.getType().getDescription().getString().toLowerCase(Locale.ROOT));
    }
}
