package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [perks] The leader's perks: what a leader gives its town while it is in office, what it learns there, and what
 * its reign leaves behind it when it goes.
 *
 * <h2>In office</h2>
 * A leader gives the town a perk from what it cares about most (Values), for as long as it leads:
 * <ul>
 * <li>a <b>Provider</b>: the larder lasts a tenth longer (meals a tenth further apart);</li>
 * <li>a <b>Homemaker</b>: every building goes up a tenth quicker;</li>
 * <li>a <b>Visionary</b>: the research comes fifteen in the hundred faster;</li>
 * <li>a <b>Guardian</b>: the town keeps one more guard;</li>
 * <li>a <b>Merchant</b>: the market's takings are five in the hundred higher;</li>
 * <li>a <b>Free Spirit</b>: everybody is 2 the happier;</li>
 * <li>a <b>Traditionalist</b>: the old remedies, so a cold passes a quarter sooner, and the town is a point more content.</li>
 * </ul>
 * A player who leads gives the perk of the plan it set on its Leader's page (food first a Provider's, growth a
 * Visionary's, defence a Guardian's, trade a Merchant's, steady a Traditionalist's), or of its steward's heart when
 * it leaves the plan to the steward.
 *
 * <h2>The leader's skills</h2>
 * A leader gains experience with every morning in office (ten), every civic finished under it (fifteen), every
 * building raised (five), a wonder's plans (twenty) and a wonder raised (sixty), and being elected again (twenty).
 * It levels at 30, 80, 150, 240, 350 and 500, a skill point a level, and spends each on a leadership skill, along
 * four lines of three (each needs the one before it in its line):
 * <ul>
 * <li>the <b>voice</b>: Orator (what it puts to the town's vote goes its way: twelve more for it on every folk's
 *     scales, and six on every voter's at its own election), Diplomat (its envoys are heard the warmer: fifteen on
 *     every answer they are given, and every neighbour thinks a point better of the town every other day),
 *     Statesman (the town is 2 more content, and a tenth less given to crime);</li>
 * <li>the <b>purse</b>: Steward (a twentieth of the wages comes back to the treasury), Quartermaster (a caravan
 *     carries two lots more), Treasurer (the takings four in the hundred higher);</li>
 * <li>the <b>watch</b>: Warden (crime a third rarer), Marshal (every guard a point more armour and hits a point
 *     harder), Protector (while the bell rings, every folk a point more armour);</li>
 * <li>the <b>heart</b>: Patron (the arts: the town 3 more content, and a busker's hat fills the quicker),
 *     Builder-King (every building goes up fifteen in the hundred quicker), Sage (two more research points a
 *     morning).</li>
 * </ul>
 * A leader of the town's own chooses by its heart and its nature (a Merchant the purse, a Guardian the watch, a
 * sociable folk the voice, a Visionary the Sage), on the morning it has a point; a player who leads chooses on its
 * Leader's page. Its skills are its own, kept if it leads again.
 *
 * <h2>Legacies</h2>
 * Every reign is counted as it goes: the civics finished, the buildings raised, the wonders, the great works, the
 * days of plenty, the raids weathered, the treasury grown, the town content, the neighbours warmed. When the leader
 * leaves office (beaten at an election, dead, or gone), a reign of three days or more leaves one lasting legacy,
 * from what it did most (a tie goes to what its heart was in), named for it:
 * <ul>
 * <li>"Bramble's Halls" (buildings): every building goes up 3% quicker;</li>
 * <li>"Bramble's Roads" (great works): everybody walks 3% faster;</li>
 * <li>"Bramble's Learning" (civics): a research point more a morning;</li>
 * <li>"Bramble's Granaries" (plenty): the larder goes 4% further;</li>
 * <li>"Bramble's Peace" (the neighbours warmed): every neighbour thinks a point better of the town every other day;</li>
 * <li>"Bramble's Watch" (raids weathered): every guard a point more armour;</li>
 * <li>"Bramble's Coffers" (the treasury grown): the takings 2% higher;</li>
 * <li>"Bramble's Merriment" (a content town): the town a point more content;</li>
 * <li>"Bramble's Glory" (wonders): five more renown.</li>
 * </ul>
 * Legacies stack over the town's history, three of a kind at most. The chronicle remembers each, and the hall:
 * a plaque for each leader before the leader's hall (or the meeting hall), with its name, its years and its legacy.
 *
 * <p>Kept in the village's Ledger notes: {@code reign.now} (the leader, its name, the day it took office), {@code
 * reign.deeds} (its reign's counts), {@code reign.seen} (the last morning's treasury and relations), {@code
 * leader.<id>} (each leader's experience and skills), {@code legacies} (the town's legacies, a row each). Shown on
 * the leader's card, the Leader's page, the Perks page of the books, the board and /village perks leader.
 */
public final class Reigns {

    private Reigns() {}

    // ------------------------------------------------------------------ in office

    /** The perk of a leader of this heart, in words. */
    public static String officeWords(Values.Value v) {
        return switch (v) {
            case FOOD -> "the larder lasts a tenth longer";
            case HOMES -> "buildings go up a tenth quicker";
            case PROGRESS -> "research comes 15% faster";
            case SAFETY -> "the town keeps a guard more";
            case WEALTH -> "the market's takings are 5% higher";
            case LEISURE -> "everybody is 2 the happier";
            case TRADITION -> "a cold passes a quarter sooner, and the town is a point more content";
        };
    }

    private record Office(long at, @Nullable Values.Value heart, String who) {}

    private static final Map<UUID, Office> OFFICE = new ConcurrentHashMap<>();

    /**
     * The heart of whoever leads the town, for its perk: a folk's own (its top care), a player's plan, or its
     * steward's; null with nobody leading, or in the tests' quiet. Looked up afresh every ten seconds.
     */
    @Nullable
    public static Values.Value heart(@Nullable UUID village) {
        if (village == null || !Perks.live()) return null;
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        long now = server == null ? 0L : server.overworld().getGameTime();
        Office o = OFFICE.get(village);
        if (o != null && now - o.at() < 200 && now >= o.at()) return o.heart();
        Values.Value heart = null;
        String who = "";
        VillageFolkEntity elder = Orders.elderOf(village);
        if (elder != null && !elder.isBaby()) {
            heart = Values.top(elder);
            who = elder.displayNameCap();
        } else if (PlayerLeader.leaderId(village) != null) {
            String plan = Ledger.note(village, "civic.plan");
            heart = plan == null ? null : switch (plan) {
                case "food" -> Values.Value.FOOD;
                case "growth" -> Values.Value.PROGRESS;
                case "defence" -> Values.Value.SAFETY;
                case "trade" -> Values.Value.WEALTH;
                case "steady" -> Values.Value.TRADITION;
                default -> null;
            };
            if (heart == null) {
                VillageFolkEntity s = PlayerLeader.steward(village);
                if (s != null) heart = Values.top(s);
            }
            who = PlayerLeader.leaderName(village);
        }
        OFFICE.put(village, new Office(now, heart, who));
        return heart;
    }

    private static boolean office(@Nullable UUID village, Values.Value v) {
        return heart(village) == v;
    }

    // ------------------------------------------------------------------ the skills

    public enum Line {
        VOICE("the voice"), PURSE("the purse"), WATCH("the watch"), HEART("the heart");

        public final String words;

        Line(String words) { this.words = words; }
    }

    public enum Skill {
        ORATOR(Line.VOICE, 1, "Orator", "what it puts to the vote goes its way; +6 at its own election"),
        DIPLOMAT(Line.VOICE, 2, "Diplomat", "its envoys are heard warmer (+15); the neighbours warm to the town"),
        STATESMAN(Line.VOICE, 3, "Statesman", "the town 2 more content, a tenth less crime"),
        STEWARD(Line.PURSE, 1, "Steward", "a twentieth of the wages back to the treasury"),
        QUARTERMASTER(Line.PURSE, 2, "Quartermaster", "a caravan carries two lots more"),
        TREASURER(Line.PURSE, 3, "Treasurer", "the takings 4% higher"),
        WARDEN(Line.WATCH, 1, "Warden", "crime a third rarer"),
        MARSHAL(Line.WATCH, 2, "Marshal", "guards +1 armour and +1 attack"),
        PROTECTOR(Line.WATCH, 3, "Protector", "under the bell, everyone +1 armour"),
        PATRON(Line.HEART, 1, "Patron", "the arts flourish: +3 contentment, buskers tipped more"),
        BUILDER_KING(Line.HEART, 2, "Builder-King", "every building goes up 15% quicker"),
        SAGE(Line.HEART, 3, "Sage", "two more research points a morning");

        public final Line line;
        public final int tier;
        public final String title, effect;

        Skill(Line line, int tier, String title, String effect) {
            this.line = line;
            this.tier = tier;
            this.title = title;
            this.effect = effect;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The one before it in its line, or null for the first. */
        @Nullable
        public Skill before() {
            for (Skill s : values()) if (s.line == line && s.tier == tier - 1) return s;
            return null;
        }

        @Nullable
        public static Skill byKey(@Nullable String k) {
            if (k == null) return null;
            String n = k.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            for (Skill s : values()) if (s.name().equals(n) || s.title.toUpperCase(Locale.ROOT).replace('-', '_').equals(n)) return s;
            return null;
        }
    }

    /** The experience at which a leader reaches each level. */
    static final int[] LEVELS = { 30, 80, 150, 240, 350, 500 };

    /** A leader's own book: its experience and its skills, by the town ({@code leader.<id>}: "xp|SKILL,SKILL"). */
    public record Book(int xp, EnumSet<Skill> skills) {
        public int level() {
            int l = 0;
            for (int t : LEVELS) if (xp >= t) l++;
            return l;
        }

        public int free() {
            return Math.max(0, level() - skills.size());
        }

        /** The experience at which it next levels, or 0 at the top. */
        public int next() {
            for (int t : LEVELS) if (xp < t) return t;
            return 0;
        }
    }

    static String bookKey(UUID leader) {
        return "leader." + leader;
    }

    public static Book book(@Nullable UUID village, @Nullable UUID leader) {
        if (village == null || leader == null) return new Book(0, EnumSet.noneOf(Skill.class));
        String s = Ledger.note(village, bookKey(leader));
        EnumSet<Skill> skills = EnumSet.noneOf(Skill.class);
        int xp = 0;
        if (s != null && !s.isEmpty()) {
            String[] p = s.split("\\|", 2);
            xp = CityTree.parse(p[0]);
            if (p.length > 1) for (String k : p[1].split(",")) {
                Skill sk = Skill.byKey(k);
                if (sk != null) skills.add(sk);
            }
        }
        return new Book(xp, skills);
    }

    private static void save(UUID village, UUID leader, Book b) {
        List<String> keys = new ArrayList<>();
        for (Skill s : b.skills()) keys.add(s.name());
        Ledger.note(village, bookKey(leader), b.xp() + "|" + String.join(",", keys));
    }

    /** The leader in office now: a folk's or a player's id. */
    @Nullable
    static UUID leader(@Nullable UUID village) {
        return village == null ? null : Villages.elder(village);
    }

    /** Does the leader in office have this skill (and the town is not in the tests' quiet)? */
    public static boolean skill(@Nullable UUID village, Skill s) {
        if (village == null || !Perks.live()) return false;
        UUID l = leader(village);
        return l != null && book(village, l).skills().contains(s);
    }

    /** Can this leader take this skill now: a point free, the one before it taken, and not taken already? */
    public static boolean canTake(UUID village, UUID leader, Skill s) {
        Book b = book(village, leader);
        if (b.free() <= 0 || b.skills().contains(s)) return false;
        Skill before = s.before();
        return before == null || b.skills().contains(before);
    }

    /** The leader takes a skill: on its book, in the chronicle. Returns what happened. */
    public static String take(UUID village, UUID leader, Skill s, long day, String who) {
        Book b = book(village, leader);
        if (b.skills().contains(s)) return who + " has " + s.title + " already.";
        if (b.free() <= 0) return who + " has no skill point to spend (level " + b.level() + ", next at " + b.next() + " experience).";
        Skill before = s.before();
        if (before != null && !b.skills().contains(before)) return s.title + " needs " + before.title + " first.";
        EnumSet<Skill> now = EnumSet.copyOf(b.skills().isEmpty() ? EnumSet.noneOf(Skill.class) : b.skills());
        now.add(s);
        save(village, leader, new Book(b.xp(), now));
        Villages.tell(village, day, who + " took up the leader's skill of " + s.title + ": " + s.effect);
        return who + " is now " + (s == Skill.BUILDER_KING ? "a Builder-King" : "a " + s.title) + ": " + s.effect + ".";
    }

    /** How much this leader wants a skill, by its heart and its nature. */
    static double want(VillageFolkEntity f, Skill s) {
        int[] w = Values.of(f);
        double score = switch (s.line) {
            case VOICE -> w[Values.Value.TRADITION.ordinal()] * 0.6 + w[Values.Value.LEISURE.ordinal()] * 0.3
                + (f.life().has(Social.Trait.SOCIABLE) ? 12 : 0) + (f.life().has(Social.Trait.CHEERFUL) ? 5 : 0);
            case PURSE -> w[Values.Value.WEALTH.ordinal()] + w[Values.Value.FOOD.ordinal()] * 0.3
                + (f.life().has(Social.Trait.SHY) ? 5 : 0);
            case WATCH -> w[Values.Value.SAFETY.ordinal()] + (f.life().has(Social.Trait.GRUMPY) ? 10 : 0);
            case HEART -> w[Values.Value.LEISURE.ordinal()] * 0.5 + w[Values.Value.HOMES.ordinal()] * 0.5
                + w[Values.Value.PROGRESS.ordinal()] * 0.5 + (f.life().has(Social.Trait.CURIOUS) ? 6 : 0);
        };
        if (s == Skill.SAGE) score += w[Values.Value.PROGRESS.ordinal()] * 0.4;
        if (s == Skill.BUILDER_KING) score += w[Values.Value.HOMES.ordinal()] * 0.4;
        if (s == Skill.PATRON) score += w[Values.Value.LEISURE.ordinal()] * 0.3 + (f.life().has(Social.Trait.CHEERFUL) ? 6 : 0);
        if (s == Skill.QUARTERMASTER) score += w[Values.Value.FOOD.ordinal()] * 0.3;
        return score + s.tier * 2;                               // deeper in a line it has started, the better
    }

    /** A folk leader with a point to spend chooses: the open skill it wants most. Returns it, or null. */
    @Nullable
    static Skill chooseSkill(UUID village, VillageFolkEntity f, long day) {
        UUID id = f.getUUID();
        Skill best = null;
        double bestScore = -1;
        for (Skill s : Skill.values()) {
            if (!canTake(village, id, s)) continue;
            double sc = want(f, s);
            if (sc > bestScore) { bestScore = sc; best = s; }
        }
        if (best == null) return null;
        take(village, id, best, day, Leader.leaderName(village, f));
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I've learnt a thing or two in office. " + best.title + ", they'll call me.",
            "A leader grows into the job: " + best.title + " — that's me now.", best.title + ". The town will feel the difference."));
        f.persona().remember(day, "I became " + best.title + " in office on day " + day, 5);
        return best;
    }

    /** Experience for the leader in office. */
    static void xp(UUID village, int n) {
        UUID l = leader(village);
        if (l == null || n <= 0) return;
        Book b = book(village, l);
        save(village, l, new Book(b.xp() + n, b.skills().isEmpty() ? EnumSet.noneOf(Skill.class) : EnumSet.copyOf(b.skills())));
    }

    // ------------------------------------------------------------------ the reign's deeds

    /** What a reign is counted by; each the seed of a legacy. */
    public enum Deed {
        CIVIC("Learning", 3, "a research point more a morning", Values.Value.PROGRESS),
        BUILD("Halls", 2, "every building goes up 3% quicker", Values.Value.HOMES),
        WORKS("Roads", 3, "everybody walks 3% faster", null),
        FOOD("Granaries", 1, "the larder goes 4% further", Values.Value.FOOD),
        PEACE("Peace", 1, "every neighbour warms to the town", Values.Value.TRADITION),
        WATCH("Watch", 2, "every guard a point more armour", Values.Value.SAFETY),
        COIN("Coffers", 1, "the takings 2% higher", Values.Value.WEALTH),
        MERRY("Merriment", 1, "the town a point more content", Values.Value.LEISURE),
        WONDER("Glory", 4, "five more renown", null);

        public final String word, effect;
        final int weight;
        @Nullable final Values.Value heart;

        Deed(String word, int weight, String effect, @Nullable Values.Value heart) {
            this.word = word;
            this.weight = weight;
            this.effect = effect;
            this.heart = heart;
        }
    }

    /** A legacy most of a kind, stacked. */
    static final int STACK = 3;

    /** Something done under the leader in office: counted toward its legacy, and to its experience. */
    public static void deed(@Nullable UUID village, Deed d, int n) {
        if (village == null || n <= 0 || !Perks.live() || leader(village) == null) return;
        Map<Deed, Integer> deeds = deeds(village);
        deeds.merge(d, n, Integer::sum);
        saveDeeds(village, deeds);
        int xp = switch (d) {
            case CIVIC -> 15 * n;
            case BUILD, WORKS -> 5 * n;
            case WONDER -> 20 * n;
            default -> 0;
        };
        xp(village, xp);
    }

    static Map<Deed, Integer> deeds(UUID village) {
        Map<Deed, Integer> out = new EnumMap<>(Deed.class);
        String s = Ledger.note(village, "reign.deeds");
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(",")) {
            String[] kv = part.split(":");
            try {
                out.put(Deed.valueOf(kv[0]), CityTree.parse(kv.length > 1 ? kv[1] : "0"));
            } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    private static void saveDeeds(UUID village, Map<Deed, Integer> deeds) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Deed, Integer> e : deeds.entrySet()) parts.add(e.getKey().name() + ":" + e.getValue());
        Ledger.note(village, "reign.deeds", String.join(",", parts));
    }

    /** The reign now: {leader id, its name, the day it took office}, or null. */
    public record Reign(UUID leader, String name, long since) {}

    @Nullable
    public static Reign reign(@Nullable UUID village) {
        if (village == null) return null;
        String s = Ledger.note(village, "reign.now");
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split("\\|");
        try {
            return new Reign(UUID.fromString(p[0]), p.length > 1 ? p[1] : "", p.length > 2 ? Long.parseLong(p[2]) : -1L);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The town's morning (Perks.morning): a new leader's reign begun (and the last one's ended, with its legacy);
     * the day in office counted, the day's deeds read off the town (plenty, a raid weathered, the treasury grown,
     * the town content, the neighbours warmed), a re-election credited; and a folk leader with a point to spend
     * chooses its next skill.
     */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        UUID now = leader(id);
        Reign r = reign(id);
        if (r != null && !r.leader().equals(now)) {
            end(level, v, r, day);
            r = null;
        }
        if (r == null && now != null) {
            String name = leaderNameOf(id, now);
            Ledger.note(id, "reign.now", now + "|" + name + "|" + day);
            Ledger.forget(id, "reign.deeds");
            Ledger.note(id, "reign.seen", seen(id));
            Values.Value h = heart(id);
            if (h != null) Villages.tell(id, day, name + " took office: " + h.type + " at heart, so " + officeWords(h));
            return;
        }
        if (r == null) return;
        // A day in office, and what the day shows of the reign.
        xp(id, 10);
        Map<Deed, Integer> d = deeds(id);
        if (Leader.plan(id) == Leader.Plan.PLENTY) d.merge(Deed.FOOD, 1, Integer::sum);
        long raided = Raids.raidedOn(id);
        if (raided >= 0 && raided == day - 1) d.merge(Deed.WATCH, 2, Integer::sum);
        if (Contentment.score(id) >= 65) d.merge(Deed.MERRY, 1, Integer::sum);
        String[] was = String.valueOf(Ledger.note(id, "reign.seen")).split("\\|");
        String[] is = seen(id).split("\\|");
        if (was.length >= 2 && is.length >= 2) {
            int coins = CityTree.parse(is[0]) - CityTree.parse(was[0]);
            int warmth = CityTree.parse(is[1]) - CityTree.parse(was[1]);
            if (coins >= 10) d.merge(Deed.COIN, 1, Integer::sum);
            if (warmth >= 5) d.merge(Deed.PEACE, warmth / 5, Integer::sum);
        }
        Ledger.note(id, "reign.seen", seen(id));
        saveDeeds(id, d);
        // Elected again yesterday: to its credit.
        String last = Ledger.note(id, "election.last");
        if (last != null && !last.isEmpty()) {
            String[] p = last.split("\\|");
            if (p.length > 1 && CityTree.parse(p[0]) == day - 1 && p[1].equals(r.name()) && r.since() < day - 1) xp(id, 20);
        }
        VillageFolkEntity elder = Orders.elderOf(id);
        if (elder != null && !elder.isBaby() && book(id, elder.getUUID()).free() > 0) chooseSkill(id, elder, day);
    }

    /** {treasury, the sum of its relations with every town it knows}, for the next morning's comparison. */
    private static String seen(UUID village) {
        int rel = 0;
        for (Villages.Village o : Villages.every()) if (!o.id().equals(village)) rel += Ledger.relation(village, o.id());
        return Ledger.coins(village) + "|" + rel;
    }

    /** "Bramble": the name the town knows its leader by (a folk's, or a player's). */
    static String leaderNameOf(UUID village, UUID leader) {
        for (AssistantEntity a : Villages.folkOf(village)) if (a.getUUID().equals(leader) && a instanceof VillageFolkEntity f) return f.displayNameCap();
        String p = PlayerLeader.leaderName(village);
        if (!p.isEmpty()) return p;
        String e = Villages.elderName(village);
        return e.isEmpty() ? "the old leader" : e;
    }

    /** A reign over: its legacy, if it lasted three days, from what it did most; told, and its plaque wanted. */
    static void end(ServerLevel level, Villages.Village v, Reign r, long day) {
        UUID id = v.id();
        Ledger.forget(id, "reign.now");
        long days = day - Math.max(0, r.since());
        Map<Deed, Integer> d = deeds(id);
        Ledger.forget(id, "reign.deeds");
        if (days < 3) return;
        Deed best = legacyOf(d, heartOf(id, r.leader()));
        String title = CityTree.capital(Homeland.leaderTitle(id));
        if (best == null) {
            Villages.tell(id, day, title + " " + r.name() + "'s reign ended after " + days + " days, and left nothing much behind it");
            return;
        }
        int already = 0;
        for (Legacy l : legacies(id)) if (l.deed() == best) already++;
        String name = r.name() + "'s " + best.word;
        if (already >= STACK) {
            Villages.tell(id, day, title + " " + r.name() + "'s reign ended after " + days + " days; it is remembered beside the town's "
                + best.word.toLowerCase(Locale.ROOT) + ", but the town has as much of that as it can hold");
            return;
        }
        List<Legacy> all = legacies(id);
        all.add(new Legacy(best, r.name(), title, Math.max(0, r.since()), day));
        saveLegacies(id, all);
        Villages.tell(id, day, title + " " + r.name() + "'s reign ended after " + days + " days, and leaves " + name + ": "
            + best.effect + ", for good");
        Market.assemblyNews(id, "We remember " + r.name() + "'s time as our " + Homeland.leaderTitle(id) + ": " + name + ", " + best.effect + ".");
        // A plaque before the hall, for the town to remember it by.
        BlockPos hall = Villages.builtAt(id, "townhall");
        if (hall == null) hall = Villages.builtAt(id, "hall");
        if (hall == null) hall = v.centre();
        Plaques.legacy(id, hall, new String[]{ title + " " + r.name(), "days " + (Math.max(0, r.since()) + 1) + "-" + (day + 1),
            name, best.effect.length() > 15 ? best.word.toLowerCase(Locale.ROOT) : best.effect }, day);
    }

    /** Its heart, for the tie: the folk's own if it is about, else nothing. */
    @Nullable
    private static Values.Value heartOf(UUID village, UUID leader) {
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.getUUID().equals(leader) && a instanceof VillageFolkEntity f) return Values.top(f);
        }
        return null;
    }

    /** What a reign did most, weighed: the biggest, a tie to its heart. Null for a reign of nothing. */
    @Nullable
    static Deed legacyOf(Map<Deed, Integer> d, @Nullable Values.Value heart) {
        Deed best = null;
        double bestScore = 0;
        for (Map.Entry<Deed, Integer> e : d.entrySet()) {
            double s = e.getValue() * e.getKey().weight + (heart != null && e.getKey().heart == heart ? 0.5 : 0);
            if (s > bestScore) { bestScore = s; best = e.getKey(); }
        }
        return best;
    }

    // ------------------------------------------------------------------ the legacies

    /** A legacy: what of, whose, its title, and its reign's first and last days. */
    public record Legacy(Deed deed, String leader, String title, long from, long to) {
        public String name() {
            return leader + "'s " + deed.word;
        }
    }

    public static List<Legacy> legacies(@Nullable UUID village) {
        List<Legacy> out = new ArrayList<>();
        if (village == null) return out;
        String s = Ledger.note(village, "legacies");
        if (s == null || s.isEmpty()) return out;
        for (String row : s.split("\n")) {
            String[] p = row.split("\t");
            if (p.length < 5) continue;
            try {
                out.add(new Legacy(Deed.valueOf(p[0]), p[1], p[2], Long.parseLong(p[3]), Long.parseLong(p[4])));
            } catch (RuntimeException ignored) { }
        }
        return out;
    }

    private static void saveLegacies(UUID village, List<Legacy> all) {
        StringBuilder sb = new StringBuilder();
        for (Legacy l : all) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l.deed().name()).append('\t').append(l.leader().replace("\t", " ")).append('\t').append(l.title())
                .append('\t').append(l.from()).append('\t').append(l.to());
        }
        Ledger.note(village, "legacies", sb.toString());
    }

    /** How many legacies of this kind the town has (three at most count). */
    public static int legacy(@Nullable UUID village, Deed d) {
        if (village == null) return 0;
        int n = 0;
        for (Legacy l : legacies(village)) if (l.deed() == d) n++;
        return Math.min(STACK, n);
    }

    // ------------------------------------------------------------------ what it all does (CityTree, Perks)

    /** The time between meals, in percent: a Provider in office 110, and 4 more for each legacy of granaries. */
    public static int mealPercent(@Nullable UUID village) {
        return (office(village, Values.Value.FOOD) ? 110 : 100) * (100 + 4 * legacy(village, Deed.FOOD)) / 100;
    }

    /** Twentieths of the wages back to the treasury: a Steward's one. */
    public static int countingTwentieths(@Nullable UUID village) {
        return skill(village, Skill.STEWARD) ? 1 : 0;
    }

    /** Research points more a morning, with the words: a Sage 2, a legacy of learning 1 each. */
    public static int researchPoints(@Nullable UUID village, List<String> why) {
        int n = 0;
        if (skill(village, Skill.SAGE)) { n += 2; why.add("a Sage in office 2"); }
        int l = legacy(village, Deed.CIVIC);
        if (l > 0) { n += l; why.add("the town's legacies of learning " + l); }
        return n;
    }

    /** Research, in percent more, with the words: a Visionary in office 15. */
    public static int researchPercent(@Nullable UUID village, List<String> why) {
        if (office(village, Values.Value.PROGRESS)) {
            why.add("a Visionary in office +15%");
            return 15;
        }
        return 0;
    }

    /** Building, in percent more: a Homemaker 10, a Builder-King 15, a legacy of halls 3 each. */
    static int buildPercent(@Nullable UUID village) {
        return (office(village, Values.Value.HOMES) ? 10 : 0) + (skill(village, Skill.BUILDER_KING) ? 15 : 0) + 3 * legacy(village, Deed.BUILD);
    }

    /** The takings, in percent: a Merchant 105, a Treasurer 104, a legacy of coffers 102 each. */
    static int takingsPercent(@Nullable UUID village) {
        int p = office(village, Values.Value.WEALTH) ? 105 : 100;
        if (skill(village, Skill.TREASURER)) p = p * 104 / 100;
        p = p * (100 + 2 * legacy(village, Deed.COIN)) / 100;
        return p;
    }

    /** Guards more on the town's books: a Guardian in office one. */
    static int guardsMore(@Nullable UUID village) {
        return office(village, Values.Value.SAFETY) ? 1 : 0;
    }

    /** Contentment, with the words: a Traditionalist 1, a Statesman 2, a Patron 3, a legacy of merriment 1 each. */
    static int contentment(@Nullable UUID village, List<String> good) {
        int c = 0;
        if (office(village, Values.Value.TRADITION)) { c += 1; good.add("a leader of the old ways"); }
        if (skill(village, Skill.STATESMAN)) { c += 2; good.add("a statesman in office"); }
        if (skill(village, Skill.PATRON)) { c += 3; good.add("a patron of the arts in office"); }
        int m = legacy(village, Deed.MERRY);
        if (m > 0) { c += m; good.add("the merriment of old reigns"); }
        return c;
    }

    /** Everybody's spirits: a Free Spirit in office 2. */
    static int mood(@Nullable UUID village) {
        return office(village, Values.Value.LEISURE) ? 2 : 0;
    }

    /** Crime, as a factor on its chance: a Warden two thirds, a Statesman nine tenths. */
    static double crimeFactor(@Nullable UUID village) {
        double x = 1.0;
        if (skill(village, Skill.WARDEN)) x *= 0.67;
        if (skill(village, Skill.STATESMAN)) x *= 0.9;
        return x;
    }

    /** Caravan lots more: a Quartermaster two. */
    static int caravanLots(@Nullable UUID village) {
        return skill(village, Skill.QUARTERMASTER) ? 2 : 0;
    }

    /** How the neighbours warm to the town today: a Diplomat and each legacy of peace, every other day. */
    static int warmth(@Nullable UUID village, long day) {
        if (Math.floorMod(day, 2L) != 0) return 0;
        return (skill(village, Skill.DIPLOMAT) ? 1 : 0) + legacy(village, Deed.PEACE);
    }

    /** A Diplomat's envoys: so much warmer every answer. */
    static int envoyWarmth(@Nullable UUID village) {
        return skill(village, Skill.DIPLOMAT) ? 15 : 0;
    }

    /** An Orator: what it puts to the vote, so much more for it on every folk's scales; the caller's id. */
    static int oratory(@Nullable UUID village, String callerId) {
        if (!skill(village, Skill.ORATOR)) return 0;
        UUID l = leader(village);
        return l != null && l.toString().equals(callerId) ? 12 : 0;
    }

    /** An Orator standing again: so much more on every voter's scales at its own election. */
    static int hustings(@Nullable UUID village, UUID candidate) {
        UUID l = leader(village);
        return l != null && l.equals(candidate) && skill(village, Skill.ORATOR) ? 6 : 0;
    }

    /** Armour for a guard (a Marshal 1, legacies of the watch 1 each) and for everybody under the bell (a Protector 1). */
    static double armour(VillageFolkEntity f, @Nullable UUID village) {
        double a = 0;
        boolean guard = f.stationTask() == AssistantEntity.StationTask.GUARD && !f.isBaby();
        if (guard) a += (skill(village, Skill.MARSHAL) ? 1 : 0) + legacy(village, Deed.WATCH);
        if (!f.isBaby() && Raids.underAlarm(village) && skill(village, Skill.PROTECTOR)) a += 1;
        return a;
    }

    /** A guard's blow: a Marshal 1. */
    static double hit(VillageFolkEntity f, @Nullable UUID village) {
        return f.stationTask() == AssistantEntity.StationTask.GUARD && !f.isBaby() && skill(village, Skill.MARSHAL) ? 1 : 0;
    }

    /** Walking: legacies of roads 3% each. */
    static double walk(@Nullable UUID village) {
        return 0.03 * legacy(village, Deed.WORKS);
    }

    /** A cold's length, in percent: a Traditionalist in office 75. */
    static int coldPercent(@Nullable UUID village) {
        return office(village, Values.Value.TRADITION) ? 75 : 100;
    }

    /** A busker's chance of a coin, more: a Patron's 0.1. */
    static double tips(@Nullable UUID village) {
        return skill(village, Skill.PATRON) ? 0.1 : 0.0;
    }

    /** Renown: five for each legacy of glory. */
    public static int renown(@Nullable UUID village) {
        return 5 * legacy(village, Deed.WONDER);
    }

    // ------------------------------------------------------------------ telling

    /** The leader's card line ("Leads"): its heart's perk, its level and skills, its reign's days. "" for anybody else. */
    public static String cardLine(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || !f.getUUID().equals(Villages.elder(village))) return "";
        Book b = book(village, f.getUUID());
        Values.Value h = Values.top(f);
        Reign r = reign(village);
        long day = f.level().getDayTime() / 24000L;
        List<String> skills = new ArrayList<>();
        for (Skill s : b.skills()) skills.add(s.title);
        return (r != null && r.leader().equals(f.getUUID()) ? "in office " + (day - Math.max(0, r.since())) + " days; " : "")
            + "a " + h.type + ", so " + officeWords(h) + "; level " + b.level()
            + (skills.isEmpty() ? "" : ", " + String.join(", ", skills)) + (b.free() > 0 ? " (" + b.free() + " to choose)" : "");
    }

    /** Lines for the books and the commands: the leader, its perk, its skills, the reign and the town's legacies. */
    public static List<String> lines(@Nullable UUID village, long day) {
        List<String> out = new ArrayList<>();
        if (village == null) return out;
        UUID l = leader(village);
        Reign r = reign(village);
        Values.Value h = heart(village);
        if (l == null) out.add("Nobody leads the town just now.");
        else {
            Book b = book(village, l);
            List<String> skills = new ArrayList<>();
            for (Skill s : b.skills()) skills.add(s.title + " (" + s.effect + ")");
            out.add(CityTree.capital(Homeland.leaderTitle(village)) + " " + leaderNameOf(village, l)
                + (r != null && r.leader().equals(l) ? ", " + (day - Math.max(0, r.since())) + " days in office" : "")
                + (h == null ? "" : "; " + h.type + " at heart: " + officeWords(h)) + ".");
            out.add("Level " + b.level() + " (" + b.xp() + " experience" + (b.next() > 0 ? ", next at " + b.next() : "") + "); skills: "
                + (skills.isEmpty() ? "none yet" : String.join("; ", skills)) + (b.free() > 0 ? "; " + b.free() + " to choose" : "") + ".");
            Map<Deed, Integer> d = deeds(village);
            if (!d.isEmpty()) {
                List<String> parts = new ArrayList<>();
                for (Map.Entry<Deed, Integer> e : d.entrySet()) parts.add(e.getKey().word.toLowerCase(Locale.ROOT) + " " + e.getValue());
                Deed lead = legacyOf(d, h);
                out.add("The reign so far: " + String.join(", ", parts) + (lead == null ? "" : "; it would leave its " + lead.word));
            }
        }
        List<Legacy> all = legacies(village);
        if (all.isEmpty()) out.add("Legacies: none yet.");
        else for (Legacy x : all) out.add("Legacy: " + x.name() + " (" + x.title() + ", days " + (x.from() + 1) + "-" + (x.to() + 1) + "): " + x.deed().effect + ".");
        return out;
    }

    /** "Bramble's Roads, Wren's Granaries": the legacies in a line, or "". */
    public static String legacyWords(@Nullable UUID village) {
        List<String> names = new ArrayList<>();
        for (Legacy l : legacies(village)) names.add(l.name());
        return String.join(", ", names);
    }

    // ------------------------------------------------------------------ the stage (PerksStage)

    /**
     * For the pictures (/village perks stage): the leader in office given a reign begun five days ago, experience
     * enough for three skills and the first three of the line its heart leans to; and before it, one past reign of
     * so many days whose deeds leave a legacy (its plaque wanted, as any reign's would). The words of it, a line each.
     */
    static List<String> stage(ServerLevel level, Villages.Village v, String pastName, Deed pastDeed, long day) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        // The past reign first: written as the reign now, ended, and the reign now put back as it was.
        String was = Ledger.note(id, "reign.now"), wasDeeds = Ledger.note(id, "reign.deeds");
        Ledger.note(id, "reign.deeds", pastDeed.name() + ":6");
        end(level, v, new Reign(UUID.nameUUIDFromBytes(("past" + id).getBytes()), pastName, Math.max(0, day - 12)), Math.max(3, day - 2));
        if (was != null && !was.isEmpty()) Ledger.note(id, "reign.now", was);
        if (wasDeeds != null && !wasDeeds.isEmpty()) Ledger.note(id, "reign.deeds", wasDeeds);
        out.add("LEGACY " + legacyWords(id));
        UUID l = leader(id);
        if (l == null) {
            out.add("LEADER none");
            return out;
        }
        Ledger.note(id, "reign.now", l + "|" + leaderNameOf(id, l) + "|" + Math.max(0, day - 5));
        Map<Deed, Integer> d = new EnumMap<>(Deed.class);
        d.put(Deed.BUILD, 3);
        d.put(Deed.CIVIC, 2);
        saveDeeds(id, d);
        Book b = book(id, l);
        if (b.xp() < LEVELS[2]) save(id, l, new Book(LEVELS[2], b.skills().isEmpty() ? EnumSet.noneOf(Skill.class) : EnumSet.copyOf(b.skills())));
        Values.Value h = heart(id);
        Line line = h == Values.Value.SAFETY ? Line.WATCH : h == Values.Value.WEALTH || h == Values.Value.FOOD ? Line.PURSE
            : h == Values.Value.TRADITION ? Line.VOICE : Line.HEART;
        String who = leaderNameOf(id, l);
        for (Skill s : Skill.values()) if (s.line == line && canTake(id, l, s)) take(id, l, s, day, who);
        for (Skill s : Skill.values()) if (canTake(id, l, s)) take(id, l, s, day, who);
        out.addAll(lines(id, day));
        return out;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the town's leaders' morning, now. */
    public static void morningForTests(ServerLevel level, Villages.Village v, long day) {
        morning(level, v, day);
    }

    /** Tests: so much experience to the leader in office. */
    public static void xpForTests(UUID village, int n) {
        xp(village, n);
    }

    /** Tests: the reign's counts set, as if done. */
    public static void deedsForTests(UUID village, Map<Deed, Integer> d) {
        saveDeeds(village, new LinkedHashMap<>(d));
    }

    /** Tests: the office's heart looked up afresh. */
    public static void forgetForTests() {
        OFFICE.clear();
    }

    static void reset() {
        OFFICE.clear();
    }
}
