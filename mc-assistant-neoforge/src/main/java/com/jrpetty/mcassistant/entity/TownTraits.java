package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [identity] A town's history made into character: badges it earns from what really happened to it (the chronicle's
 * events, Identity.Ev), each with a line of character, a story ("Flood-hardy: the floods of days 12, 31 and 44") and a
 * small perk that a game can see. Most fade if what earned them is long past; the founding ones (the land, a colony's
 * beginnings) never do.
 *
 * <ul>
 * <li><b>Flood-hardy</b> (three floods weathered): the levee raised twice as fast, and nobody panics when the river
 *     comes up.</li>
 * <li><b>Iron-willed</b> (three raids held off): a guard more on the watch; spirits a little steadier.</li>
 * <li><b>Raid-scarred</b> (folk lost to raiders twice): the gates shut earlier, strangers trusted slowly, a few more on
 *     the watch.</li>
 * <li><b>Golden Fields</b> (two record harvests): farm work quicker; proud of its fields.</li>
 * <li><b>Deep Delvers</b> (its first diamond in its first weeks, or three finds out of the mine): mine work quicker.</li>
 * <li><b>Hospitable</b> (many visitors, guests and heroes honoured, newcomers taken in): players trusted faster, tourists
 *     sooner, newcomers welcomed.</li>
 * <li><b>Mourning Town</b> (three lost in five days): sombre for a fortnight: spirits down, fewer evenings at the
 *     tavern. It fades.</li>
 * <li><b>Lucky</b> (a whole year without a death): spirits up, till the next death.</li>
 * <li><b>Fire-born</b> (rebuilt after a fire): rebuilds twice as fast.</li>
 * <li><b>Well-wed</b> (four weddings): children a little more often.</li>
 * <li><b>Warlike</b> (two wars won) and <b>Peacemakers</b> (two peaces made): quicker to war and more on the watch, or
 *     more envoys and warmer with every neighbour.</li>
 * <li><b>Seafarers</b> (three great catches): fishing quicker.</li>
 * <li><b>Bookish</b> (four books written): a research point more a day.</li>
 * <li><b>Merchant Princes</b> (three trade deals): traders pay a little more.</li>
 * <li><b>Merrymakers</b> (six festivals kept): spirits up.</li>
 * <li><b>Builders' Town</b> (two great works): the houses made over faster.</li>
 * <li><b>Hero-honoured</b> (two heroes named): renown, and tourists come to see their statues.</li>
 * <li><b>Hardy</b> (founded on hard land: snow, desert, badlands, mountain) and <b>Colonists</b> (a colony): founding
 *     traits that never fade; steadier spirits, warmer with the mother town.</li>
 * </ul>
 */
public final class TownTraits {

    private TownTraits() {}

    public enum Trait {
        FLOOD_HARDY("Flood-hardy", "flood-hardy soul", null, "the levee raised twice as fast, and no panic when the river rises", 112, false),
        IRON_WILLED("Iron-willed", "iron-willed guard", null, "a guard more on the watch", 112, false),
        RAID_SCARRED("Raid-scarred", "raid-scarred soul", null, "the gates shut earlier, strangers trusted slowly, a few more on the watch", 84, false),
        GOLDEN_FIELDS("Golden Fields", "Golden Fields farmer", null, "farm work five in a hundred quicker", 84, false),
        DEEP_DELVERS("Deep Delvers", "Deep Delver", "delving", "mine work five in a hundred quicker", 112, false),
        HOSPITABLE("Hospitable", "host", null, "players trusted a quarter faster, tourists sooner, newcomers welcomed", 84, false),
        MOURNING("Mourning Town", "mourner", null, "sombre: spirits down and the tavern quiet, for a fortnight", 14, false),
        LUCKY("Lucky", "lucky soul", null, "spirits up, till the next death", 0, false),
        FIRE_BORN("Fire-born", "fire-born soul", null, "rebuilds after a fire twice as fast", 140, false),
        WELL_WED("Well-wed", "sweetheart", null, "children a little more often", 84, false),
        WARLIKE("Warlike", "warrior", "warlike", "quicker to war, and more on the watch", 140, false),
        PEACEMAKERS("Peacemakers", "peacemaker", null, "more envoys, and warmer with every neighbour", 140, false),
        SEAFARERS("Seafarers", "Seafarer", "seafaring", "fishing five in a hundred quicker", 84, false),
        BOOKISH("Bookish", "scholar", "bookish", "a research point more a day", 140, false),
        MERCHANT_PRINCES("Merchant Princes", "merchant prince", "trading", "traders pay a twentieth more", 112, false),
        MERRYMAKERS("Merrymakers", "merrymaker", null, "spirits up", 84, false),
        BUILDERS("Builders' Town", "builder", null, "the houses made over faster", 0, false),
        HEROES("Hero-honoured", "hero's neighbour", null, "renown, and visitors come to see the statues", 0, false),
        HARDY("Hardy", "hardy soul", null, "steadier spirits: we've known worse", 0, true),
        COLONISTS("Colonists", "colonist", null, "warmer with its mother town every day", 0, true);

        public final String name, person, perk;
        @Nullable public final String adjective;
        /** Days after its last cause it fades (0: never). */
        public final int fade;
        public final boolean founding;

        Trait(String name, String person, @Nullable String adjective, String perk, int fade, boolean founding) {
            this.name = name;
            this.person = person;
            this.adjective = adjective;
            this.perk = perk;
            this.fade = fade;
            this.founding = founding;
        }

        @Nullable
        public static Trait named(String s) {
            try { return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
        }
    }

    /** Whether a town has a trait (none for a test's plain town). */
    public static boolean has(@Nullable UUID village, Trait t) {
        if (Identity.neutral()) return false;
        Identity.Rec r = Identity.known(village);
        return r != null && r.has(t);
    }

    // ------------------------------------------------------------------ earning them

    /** The traits a town has from its founding: hard land, or a colony's beginnings. */
    static List<Trait> founding(UUID village) {
        List<Trait> out = new ArrayList<>();
        switch (Homeland.of(village)) {
            case SNOW, DESERT, BADLANDS, MOUNTAIN -> out.add(Trait.HARDY);
            default -> { }
        }
        if (Ledger.links().containsKey(village)) out.add(Trait.COLONISTS);
        return out;
    }

    static String foundingStory(UUID village, Trait t) {
        return t == Trait.HARDY ? "founded " + Homeland.of(village).where + ", where nothing comes easy"
            : "founded from " + Villages.name(Ledger.links().get(village));
    }

    /**
     * The morning's look at its history: every trait whose cause is met and not yet earned is earned (into the
     * chronicle, with its story); every one whose cause is long past fades (but the founding ones).
     */
    static void review(ServerLevel level, Villages.Village v, Identity.Rec r, long day) {
        UUID id = v.id();
        for (Trait t : Trait.values()) {
            if (t.founding) continue;
            String story = earned(id, r, t, day);
            long cause = lastCause(r, t, day);
            if (r.has(t)) {
                boolean gone = t == Trait.LUCKY ? fades(id, r, t, day) : t == Trait.MOURNING ? story == null
                    : t.fade > 0 && day - cause > t.fade;
                if (gone) lose(v, r, t, day);
                else if (story != null) {
                    r.traits.get(t)[1] = cause;
                    r.stories.put(t, story);
                }
            } else if (story != null && cause > r.faded.getOrDefault(t, Long.MIN_VALUE)) {
                award(level, v, r, t, day, story, true);
                r.traits.get(t)[1] = cause;
            }
        }
    }

    /** The events that earn a trait. */
    static Identity.Ev[] causes(Trait t) {
        return switch (t) {
            case FLOOD_HARDY -> new Identity.Ev[]{ Identity.Ev.FLOOD };
            case IRON_WILLED -> new Identity.Ev[]{ Identity.Ev.RAID_HELD };
            case RAID_SCARRED -> new Identity.Ev[]{ Identity.Ev.RAID_LOST };
            case GOLDEN_FIELDS -> new Identity.Ev[]{ Identity.Ev.HARVEST };
            case DEEP_DELVERS -> new Identity.Ev[]{ Identity.Ev.DIAMOND, Identity.Ev.MINE_FIND };
            case HOSPITABLE -> new Identity.Ev[]{ Identity.Ev.VISITOR, Identity.Ev.HERO, Identity.Ev.GUEST, Identity.Ev.TAKEN_IN };
            case MOURNING -> new Identity.Ev[]{ Identity.Ev.DEATH };
            case FIRE_BORN -> new Identity.Ev[]{ Identity.Ev.REBUILT };
            case WELL_WED -> new Identity.Ev[]{ Identity.Ev.WEDDING };
            case WARLIKE -> new Identity.Ev[]{ Identity.Ev.WAR_WON };
            case PEACEMAKERS -> new Identity.Ev[]{ Identity.Ev.PEACE };
            case SEAFARERS -> new Identity.Ev[]{ Identity.Ev.CATCH };
            case BOOKISH -> new Identity.Ev[]{ Identity.Ev.BOOK };
            case MERCHANT_PRINCES -> new Identity.Ev[]{ Identity.Ev.DEAL };
            case MERRYMAKERS -> new Identity.Ev[]{ Identity.Ev.FESTIVAL };
            case BUILDERS -> new Identity.Ev[]{ Identity.Ev.BIG_WORK };
            case HEROES -> new Identity.Ev[]{ Identity.Ev.HERO };
            default -> new Identity.Ev[0];
        };
    }

    /** The day of the last event that earned (or keeps earning) a trait; today, for one earned some other way. */
    static long lastCause(Identity.Rec r, Trait t, long day) {
        long last = Long.MIN_VALUE;
        for (Identity.Ev e : causes(t)) {
            List<Long> d = r.days(e);
            if (!d.isEmpty()) last = Math.max(last, d.get(d.size() - 1));
        }
        return last == Long.MIN_VALUE ? day : last;
    }

    /** The story of how it earned a trait, if it has earned it now; null if not. */
    @Nullable
    static String earned(UUID village, Identity.Rec r, Trait t, long day) {
        return switch (t) {
            case FLOOD_HARDY -> r.count(Identity.Ev.FLOOD) >= 3 ? "the floods of " + daysWords(r.days(Identity.Ev.FLOOD), 3) : null;
            case IRON_WILLED -> r.count(Identity.Ev.RAID_HELD) >= 3 ? "raiders held off at the gate on " + daysWords(r.days(Identity.Ev.RAID_HELD), 3) : null;
            case RAID_SCARRED -> r.count(Identity.Ev.RAID_LOST) >= 2 ? "folk lost to the raiders on " + daysWords(r.days(Identity.Ev.RAID_LOST), 3) : null;
            case GOLDEN_FIELDS -> r.count(Identity.Ev.HARVEST) >= 2 ? "record harvests on " + daysWords(r.days(Identity.Ev.HARVEST), 3) : null;
            case DEEP_DELVERS -> {
                List<Long> d = r.days(Identity.Ev.DIAMOND);
                if (!d.isEmpty() && d.get(0) - Math.max(0, r.seededOn) <= 25) yield "its first diamond on day " + (d.get(0) + 1) + ", in its first weeks";
                yield r.count(Identity.Ev.MINE_FIND) >= 3 ? "finds brought up out of the mine on " + daysWords(r.days(Identity.Ev.MINE_FIND), 3) : null;
            }
            case HOSPITABLE -> {
                int n = r.count(Identity.Ev.VISITOR) + 2 * (r.count(Identity.Ev.HERO) + r.count(Identity.Ev.GUEST) + r.count(Identity.Ev.TAKEN_IN));
                yield n >= 8 ? r.count(Identity.Ev.VISITOR) + " visitors made welcome, " + (r.count(Identity.Ev.HERO) + r.count(Identity.Ev.GUEST))
                    + " guests honoured, " + r.count(Identity.Ev.TAKEN_IN) + " parties of newcomers taken in" : null;
            }
            case MOURNING -> {
                List<Long> deaths = r.days(Identity.Ev.DEATH);
                for (int i = 0; i + 2 < deaths.size(); i++) {
                    long a = deaths.get(i), b = deaths.get(i + 2);
                    if (b - a <= 5 && day - b <= 14 && day >= b) yield "three lost between days " + (a + 1) + " and " + (b + 1);
                }
                yield null;
            }
            case LUCKY -> {
                if (r.seededOn < 0 || day - r.seededOn < 28) yield null;
                List<Long> deaths = r.days(Identity.Ev.DEATH);
                long last = deaths.isEmpty() ? r.seededOn : deaths.get(deaths.size() - 1);
                yield day - last >= 28 ? "nobody lost since day " + (last + 1) : null;
            }
            case FIRE_BORN -> r.count(Identity.Ev.REBUILT) >= 1 ? "rebuilt after the fire, on " + daysWords(r.days(Identity.Ev.REBUILT), 2) : null;
            case WELL_WED -> r.count(Identity.Ev.WEDDING) >= 4 ? r.count(Identity.Ev.WEDDING) + " weddings, the latest on " + daysWords(r.days(Identity.Ev.WEDDING), 2) : null;
            case WARLIKE -> r.count(Identity.Ev.WAR_WON) >= 2 ? "wars won on " + daysWords(r.days(Identity.Ev.WAR_WON), 3) : null;
            case PEACEMAKERS -> r.count(Identity.Ev.PEACE) >= 2 ? "peace made on " + daysWords(r.days(Identity.Ev.PEACE), 3) : null;
            case SEAFARERS -> r.count(Identity.Ev.CATCH) >= 3 ? "the great catches of " + daysWords(r.days(Identity.Ev.CATCH), 3) : null;
            case BOOKISH -> r.count(Identity.Ev.BOOK) >= 4 ? r.count(Identity.Ev.BOOK) + " books written, the latest on " + daysWords(r.days(Identity.Ev.BOOK), 2) : null;
            case MERCHANT_PRINCES -> r.count(Identity.Ev.DEAL) >= 3 ? r.count(Identity.Ev.DEAL) + " trade deals struck, the latest on " + daysWords(r.days(Identity.Ev.DEAL), 2) : null;
            case MERRYMAKERS -> r.count(Identity.Ev.FESTIVAL) >= 6 ? r.count(Identity.Ev.FESTIVAL) + " festivals kept" : null;
            case BUILDERS -> {
                int n = Villages.greatWorks(village) + r.count(Identity.Ev.BIG_WORK);
                yield n >= 2 ? n + " great works raised" : null;
            }
            case HEROES -> r.count(Identity.Ev.HERO) >= 2 ? "heroes honoured on " + daysWords(r.days(Identity.Ev.HERO), 3) : null;
            case HARDY, COLONISTS -> null;
        };
    }

    /** Has the cause of a trait been long enough past for it to fade? */
    static boolean fades(UUID village, Identity.Rec r, Trait t, long day) {
        if (t.founding) return false;
        if (t == Trait.LUCKY) {
            List<Long> deaths = r.days(Identity.Ev.DEATH);
            return !deaths.isEmpty() && deaths.get(deaths.size() - 1) >= r.traits.get(t)[0];
        }
        if (t.fade <= 0) return false;
        long last = r.traits.get(t)[1];
        return day - last > t.fade;
    }

    /** Earned: into its record, the chronicle and the gazette; and its character leans with it. */
    static void award(ServerLevel level, Villages.Village v, Identity.Rec r, Trait t, long day, String story, boolean tell) {
        r.traits.put(t, new long[]{ day, day });
        r.stories.put(t, story);
        switch (t) {
            case WARLIKE -> Ethos.nudge(r, Ethos.Axis.WAR, 10);
            case PEACEMAKERS -> Ethos.nudge(r, Ethos.Axis.WAR, -10);
            case BOOKISH -> Ethos.nudge(r, Ethos.Axis.LEARNING, 8);
            case MERCHANT_PRINCES -> Ethos.nudge(r, Ethos.Axis.TRADE, 8);
            case HOSPITABLE -> Ethos.nudge(r, Ethos.Axis.DOORS, 8);
            case RAID_SCARRED -> { Ethos.nudge(r, Ethos.Axis.DOORS, -8); Ethos.nudge(r, Ethos.Axis.WAR, 5); }
            case MOURNING -> Ethos.nudge(r, Ethos.Axis.FAITH, 5);
            default -> { }
        }
        String line = Villages.name(v.id()) + (t == Trait.MOURNING ? " is a town in mourning: " : " has earned a name: " + t.name + ", for ") + story
            + " (" + t.perk + ")";
        r.change(day, line);
        if (tell) Villages.tell(v.id(), day, line);
        Identity.dirty();
    }

    static void lose(Villages.Village v, Identity.Rec r, Trait t, long day) {
        r.traits.remove(t);
        r.faded.put(t, day);
        r.stories.remove(t);
        String line = t == Trait.MOURNING ? Villages.name(v.id()) + " has come out of its mourning"
            : t == Trait.LUCKY ? Villages.name(v.id()) + "'s luck has run out" : Villages.name(v.id()) + " is no longer called " + t.name + ": that is long past";
        r.change(day, line);
        Villages.tell(v.id(), day, line);
        Identity.dirty();
    }

    /** "days 12, 31 and 44": the last few of them. */
    static String daysWords(List<Long> days, int most) {
        List<String> out = new ArrayList<>();
        for (int i = Math.max(0, days.size() - most); i < days.size(); i++) out.add(Long.toString(days.get(i) + 1));
        if (out.isEmpty()) return "days gone by";
        if (out.size() == 1) return "day " + out.get(0);
        return "days " + String.join(", ", out.subList(0, out.size() - 1)) + " and " + out.get(out.size() - 1);
    }

    // ------------------------------------------------------------------ the words for it

    /** Its traits' names, the latest first (founding ones last), up to so many. */
    static List<String> names(Identity.Rec r, int most) {
        List<Trait> order = ordered(r);
        List<String> out = new ArrayList<>();
        for (Trait t : order) if (out.size() < most) out.add(t.name);
        return out;
    }

    static List<Trait> ordered(Identity.Rec r) {
        List<Trait> order = new ArrayList<>(r.traits.keySet());
        order.sort((a, b) -> a.founding != b.founding ? Boolean.compare(a.founding, b.founding)
            : Long.compare(r.traits.get(b)[0], r.traits.get(a)[0]));
        return order;
    }

    @Nullable
    static Trait top(Identity.Rec r) {
        List<Trait> o = ordered(r);
        return o.isEmpty() ? null : o.get(0);
    }

    static String story(Identity.Rec r, @Nullable Trait t) {
        if (t == null) return "";
        return t.name + ": " + r.stories.getOrDefault(t, t.perk);
    }

    /** A word for its character from its history ("seafaring", "warlike"), or null. */
    @Nullable
    static String adjective(@Nullable Identity.Rec r) {
        if (r == null) return null;
        for (Trait t : ordered(r)) if (t.adjective != null) return t.adjective;
        return null;
    }

    /** The trait its trade makes it a true one of ("a true Seafarer"), or null. */
    @Nullable
    static Trait tradeTrait(Identity.Rec r, VillageFolkEntity f) {
        StationTask t = f.stationTask();
        if (t == StationTask.FISH && r.has(Trait.SEAFARERS)) return Trait.SEAFARERS;
        if (t == StationTask.FARM && r.has(Trait.GOLDEN_FIELDS)) return Trait.GOLDEN_FIELDS;
        if ((t == StationTask.MINE || t == StationTask.CAVE) && r.has(Trait.DEEP_DELVERS)) return Trait.DEEP_DELVERS;
        if (t == StationTask.GUARD && r.has(Trait.WARLIKE)) return Trait.WARLIKE;
        if (t == StationTask.GUARD && r.has(Trait.IRON_WILLED)) return Trait.IRON_WILLED;
        if ((t == StationTask.SHOP || t == StationTask.STORE) && r.has(Trait.MERCHANT_PRINCES)) return Trait.MERCHANT_PRINCES;
        return null;
    }

    static ListTag report(Identity.Rec r) {
        ListTag out = new ListTag();
        for (Trait t : ordered(r)) {
            CompoundTag c = new CompoundTag();
            c.putString("name", t.name);
            c.putString("story", r.stories.getOrDefault(t, ""));
            c.putString("perk", t.perk);
            c.putLong("since", r.traits.get(t)[0]);
            c.putBoolean("founding", t.founding);
            c.putBoolean("sombre", t == Trait.MOURNING || t == Trait.RAID_SCARRED);
            out.add(c);
        }
        return out;
    }

    static String line(Identity.Rec r) {
        if (r.traits.isEmpty()) return "none earned yet";
        List<String> out = new ArrayList<>();
        for (Trait t : ordered(r)) out.add(t.name + " (" + r.stories.getOrDefault(t, "") + "; " + t.perk + ")");
        return String.join("; ", out);
    }

    // ------------------------------------------------------------------ what they do

    /** The share of a trade (Ethos.shareLean): the Raid-scarred and the Warlike a tenth more on the watch. */
    static double shareLean(@Nullable UUID village, StationTask t) {
        if (t != StationTask.GUARD) return 1.0;
        double x = 1.0;
        if (has(village, Trait.RAID_SCARRED)) x *= 1.1;
        if (has(village, Trait.WARLIKE)) x *= 1.1;
        return x;
    }

    /** Whole hands more: the Iron-willed keep a guard more on the watch. */
    static double extraHands(@Nullable UUID village, StationTask t) {
        return t == StationTask.GUARD && has(village, Trait.IRON_WILLED) ? 1.0 : 0.0;
    }

    /** The pace of the trades its history is proud of. */
    static int workPercent(@Nullable UUID village, StationTask t) {
        int p = 0;
        if (t == StationTask.FARM && has(village, Trait.GOLDEN_FIELDS)) p += 5;
        if ((t == StationTask.MINE || t == StationTask.CAVE) && has(village, Trait.DEEP_DELVERS)) p += 5;
        if (t == StationTask.FISH && has(village, Trait.SEAFARERS)) p += 5;
        return p;
    }

    static int growBudget(@Nullable UUID village) {
        return has(village, Trait.BUILDERS) ? 8 : 0;
    }

    static int warLean(@Nullable UUID village) {
        return (has(village, Trait.WARLIKE) ? 1 : 0) - (has(village, Trait.PEACEMAKERS) ? 1 : 0);
    }

    static int relationLean(UUID a, UUID b) {
        int d = 0;
        if (has(a, Trait.PEACEMAKERS) || has(b, Trait.PEACEMAKERS)) d++;
        Map<UUID, UUID> links = Ledger.links();
        if (has(a, Trait.COLONISTS) && b.equals(links.get(a)) || has(b, Trait.COLONISTS) && a.equals(links.get(b))) d++;
        return d;
    }

    /** Blocks of the levee raised a step (Floods.tick): twice as many in a Flood-hardy town. */
    public static int leveePace(@Nullable UUID village, int usual) {
        return has(village, Trait.FLOOD_HARDY) ? usual * 2 : usual;
    }

    /** Burnt blocks put back a step (Rebuilding.tick): twice as many in a Fire-born town. */
    public static int rebuildPace(@Nullable UUID village, int usual) {
        return has(village, Trait.FIRE_BORN) ? usual * 2 : usual;
    }

    /** Ticks between a folk's looks at raising a child (VillageFolkEntity.raisedAChild): a sixth sooner in a Well-wed town. */
    public static int breedEvery(@Nullable UUID village, int usual) {
        return has(village, Trait.WELL_WED) ? usual * 5 / 6 : usual;
    }

    /** What a folk says as the river comes up (Floods): calm, in a Flood-hardy town; null for the usual alarm. */
    @Nullable
    public static String floodWords(@Nullable UUID village, net.minecraft.util.RandomSource r) {
        if (!has(village, Trait.FLOOD_HARDY)) return null;
        return FolkTalk.pick(r, "River's up again. Steady now — up the hill, same as last time.",
            "Here it comes. Children first, and no running.", "We've seen worse. Up to the high ground, everyone.");
    }

    /** What its traits do to the town's spirits (Identity.contentment). */
    static int contentment(UUID village, List<String> good, List<String> bad) {
        int n = 0;
        if (has(village, Trait.MOURNING)) { n -= 4; bad.add("the town is in mourning"); }
        if (has(village, Trait.LUCKY)) { n += 2; good.add("a lucky town"); }
        if (has(village, Trait.MERRYMAKERS)) { n += 2; good.add("a merry town"); }
        if (has(village, Trait.GOLDEN_FIELDS)) { n += 1; good.add("proud of its fields"); }
        if (has(village, Trait.HARDY)) { n += 2; good.add("we've known worse"); }
        if (has(village, Trait.IRON_WILLED)) { n += 1; good.add("the watch has never failed us"); }
        return n;
    }

    // ------------------------------------------------------------------ tests

    /** Tests (and /village identity trait): a trait earned now, its story the operator's say-so. */
    public static void awardForTests(ServerLevel level, Villages.Village v, Trait t) {
        Identity.Rec r = Identity.rec(v.id());
        if (!r.has(t)) award(level, v, r, t, level.getDayTime() / 24000L, "by order", true);
    }

    /** Tests: the morning's look at its traits, now. */
    public static void reviewForTests(ServerLevel level, Villages.Village v, long day) {
        review(level, v, Identity.rec(v.id()), day);
    }
}
