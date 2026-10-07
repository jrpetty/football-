package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Going to war, and making peace: why a feud boils over into a war, who decides it and how, what it is
 * fought for, the herald and the ultimatum, the day war is declared, the allies called and their guards
 * on the walls, the white flag, and the treaty.
 *
 * <p><b>Why a town goes to war.</b> Not on a whim. Two neighbours must be in a feud (Diplomacy), and the
 * one must hold something real against the other (a quarrel over the land, a stolen sheep, a broken deal:
 * every grievance is written in its books, harvested from what it remembers of them, Bonds). Then it is
 * down to whoever leads it: a prickly or shrewd elder who cares most for safe streets is a hawk; a warm or
 * wary one who cares for trade is a dove, and a dove does not go to war. A hawk weighs the odds, its
 * strength against theirs as it sees it: from its scouts' last report (Intel) when it has one, else from
 * rumour, a guess coloured by its temper (a prickly elder thinks little of them, a wary one fears the
 * worst). It never picks a fight with a town much stronger than itself unless its allies would stand with
 * it. And it is rare: a few towns in a long game, if any.
 *
 * <p><b>The council of war.</b> The leader puts it to the council in the hall that evening. Each councillor
 * votes by what it cares about (a Guardian for, a Merchant or a Provider against, a folk with kin over there
 * against), the odds and its own temper; the leader's word counts three. A dove council can say no. The
 * vote goes on the board and into the chronicle.
 *
 * <p><b>The goal, the herald, the ultimatum.</b> Every war is for something: a border where we say it runs,
 * tribute, a trade deal on our terms, satisfaction for the wrongs done us, or a colony of ours left in
 * peace. A herald walks to the other town with the demands and is heard before its board. Its elder yields
 * (the goal met there and then: the coin carried home in the herald's purse), bargains (the lesser part of
 * it), or refuses. Refused, it is war: the bell rung in both towns, the war banner hung over the gate (a
 * real banner out of the stores), the chronicle and the gazette, the neighbours taking sides by how they
 * feel about each, and the allies called.
 *
 * <p><b>A war is a standoff.</b> Nobody marches; the towns scout each other, stand on a war footing and
 * count its cost. Allies who answer the call send guards, who walk over and stand on the walls of the town
 * they are sworn to, raising its strength as anybody reckons it, and go home at the peace. After the war
 * has stood a while (three days for a soft elder, a week for a prickly one) the side that reckons itself
 * the weaker, by its scouts' reports or by rumour, sues for peace under a white flag. If neither reckons
 * itself the weaker, the cost of the war footing (the militia's pay and the work lost to it, a day at a
 * time in the war's books) wears them both down, and they talk.
 *
 * <p><b>War-weariness.</b> Every day of a war wears its towns down: the longer it lasts, the more it costs
 * (the danger pay and the hours lost to the militia), the day of rest given to drill, a larder kept for a
 * siege or gone short, the trade with the enemy lost, an enemy reckoned the stronger, their spies held by
 * the enemy and their guards away on an ally's walls; and every one it costs. It shows in the town's
 * contentment, in its folk's spirits and what they say, on their cards and the board. Weary, some look for
 * work elsewhere (JobSeekers); worn out, one in a while packs up for a neighbour at peace.
 *
 * <p><b>The wartime election.</b> A weary town puts up somebody for peace at its next election, and a worn-out
 * one calls the election early. The voters weigh the war: weary, they lean to peace and away from the one who
 * led them into it; a war going well keeps the hawk in. A peace candidate elected sues for peace at once.
 *
 * <p><b>The treaty.</b> Its terms come from what the war was for and the balance of strength between them:
 * the weaker side concedes the whole of the goal, or the part of it, or the war ends with no gain to
 * either (and an aggressor much the weaker pays reparations); the captives each holds of the other's go
 * home (Spies.exchange); a trade deal goal is struck at the table (TradeTalks). It is written into both
 * towns' books and onto both boards and keeps the peace for a town's year. Breaking it, a raid while it
 * holds, is a cause for war, and every town that hears of it thinks the worse of the town that did it.
 *
 * <p><b>Peace returns.</b> The banners come down; the militia's arms go back to the armoury and the
 * volunteers to their trades, there and then, and the danger pay ends with the footing; the town turns back
 * to its peacetime list of works; a feast is held for the peace at the next day of rest. Everybody the war
 * cost is remembered (or, with nobody lost, the war itself), with a plaque before the chapel or the
 * graveyard (Plaques) and Remembrance Day kept every year on the day of the peace, a minute's silence at
 * the dusk bell (Traditions).
 *
 * <p>Everything that must outlast a restart is in the towns' books (Ledger notes "wp.", WarBooks); the
 * war itself is the shared seam {@link Wars}.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class WarAndPeace {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private WarAndPeace() {}

    /** How old a scouts' report can be and still be trusted as it stands; older, the leader guesses. */
    static final int STALE = 10;
    /** After a council says no, the leader lets it lie this long. */
    static final int COOL_DAYS = 10;
    /** After an ultimatum is met, the matter is settled this long. */
    static final int SETTLED_DAYS = 20;
    /** A treaty keeps the peace for a town's year. */
    public static final int TREATY_DAYS = TownCalendar.YEAR_DAYS;
    /** No town goes to war again so soon after a war. */
    static final int AFTER_WAR = 14;
    /** How hawkish an elder must be to put war to the council. */
    static final int HAWK = 3;
    /** A town smaller than this does not go to war (nor is one smaller than three worth the trouble). */
    static final int MIN_FOLK = 5;
    /** A herald gone this long without a word is given up on. */
    static final int HERALD_DAYS = 4;
    /** What a hand taken off its trade for the militia costs the town a day, in the work it does not do. */
    static final int WORK_LOST = 3;

    // ------------------------------------------------------------------ what a war is for, and what is held against them

    /** What a war is fought for. */
    public enum Goal {
        BORDER("a border where we say it runs"),
        TRIBUTE("tribute"),
        TRADE("a trade deal on our terms"),
        REVENGE("satisfaction for the wrongs done us"),
        FREE_COLONY("our colony left in peace"),
        DEFENCE("to stand firm against their demands");

        public final String words;
        Goal(String words) { this.words = words; }
    }

    /** What a town holds against a neighbour, and how much it weighs. */
    public enum Wrong {
        LAND("a quarrel over the land", 2), THEFT("stolen goods", 2), INSULT("an insult", 1), BLOWS("blows at the boundary", 2),
        TRIBUTE("tribute demanded of us", 2), DEAL("a broken deal", 3), TREATY("a broken treaty", 5), BLOOD("our folk hurt", 4),
        DEMANDS("demands made of us", 1);

        public final String words;
        public final int weight;
        Wrong(String words, int weight) {
            this.words = words;
            this.weight = weight;
        }
    }

    /** The answer to an ultimatum. */
    enum Reply { YIELD, BARGAIN, REFUSE }

    /** How far a quarrel has got before it is a war. */
    enum Stage { COUNCIL, HERALD_DUE, HERALD }

    /**
     * A town's strength as somebody reckons it: its grown folk, its guards (of them in iron, with bows),
     * sections of wall, allies' guards on its walls; all of it as one number; where the figures came from;
     * and whether they are a guess.
     */
    public record Reckoning(int folk, int guards, int armoured, int archers, int walls, int garrison, int strength, String source,
                            boolean guessed) {}

    /** Whether a leader will put war to its council, and why (or why not); the odds as it sees them; the goal. */
    public record Decision(boolean go, String why, int hawk, @Nullable Reckoning ours, @Nullable Reckoning theirs, int allies,
                           @Nullable Goal goal, int amount, String goalText) {}

    /**
     * A peace's terms: who began it and who stood against it, what it was for, the balance of strength
     * between them (the one's over the other's), how much of the goal is conceded (0 none, 1 part, 2 the
     * whole), the coin that goes with it (from the defender), the reparations (from an aggressor that gains
     * nothing and is much the weaker, to the town it troubled), and all of it in words.
     */
    public record Terms(UUID aggressor, UUID defender, Goal goal, double balance, int share, int coins, int reparations, String words) {}

    // ------------------------------------------------------------------ the switch, and the seams

    @Nullable private static Boolean forced;

    /** Are wars on (the config's villageWars)? Off, nobody goes to war, and a war under way talks peace. */
    public static boolean on() {
        return forced != null ? forced : AssistantConfig.villageWars();
    }

    /** Tests: wars on or off whatever the config says (null: as the config says). */
    public static void switchForTests(@Nullable Boolean on) {
        forced = on;
    }

    private static final List<BiConsumer<ServerLevel, UUID>> STAND_DOWN = new CopyOnWriteArrayList<>();

    /**
     * Listen for a town standing down from a war footing at the peace (the militia home to their trades,
     * the war tax ended: the preparations' part). Called once the town is at war with nobody.
     */
    public static void listen(BiConsumer<ServerLevel, UUID> onPeace) {
        STAND_DOWN.add(onPeace);
    }

    /** The day each pair and each town last had its war's day (in memory: the day's work, not the war's state). */
    private static final Map<String, Long> PAIR_DAY = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> TOWN_DAY = new ConcurrentHashMap<>();

    public static void resetForTests() {
        PAIR_DAY.clear();
        TOWN_DAY.clear();
        forced = null;
    }

    private static String name(UUID v) {
        return Villages.name(v);
    }

    private static long today() {
        return Bonds.today();
    }

    // ------------------------------------------------------------------ the day

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 200 != 137) return;
        Guard.run("war and peace", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                long t = level.getDayTime() % 24000L;
                if (t < 1000L || t > 11000L) continue;                    // the business of war is done by day
                long day = level.getDayTime() / 24000L;
                List<Villages.Village> here = new ArrayList<>();
                for (Villages.Village v : Villages.every()) if (v.dim().equals(level.dimension())) here.add(v);
                for (Villages.Village v : here) {
                    if (TOWN_DAY.getOrDefault(v.id(), -1L) >= day || !level.isLoaded(v.centre())) continue;
                    TOWN_DAY.put(v.id(), day);
                    townDaily(level, v, day);
                }
                for (int i = 0; i < here.size(); i++) {
                    for (int j = i + 1; j < here.size(); j++) {
                        Villages.Village a = here.get(i), b = here.get(j);
                        if (!Diplomacy.neighbours(a, b) || !Ledger.knowEachOther(a.id(), b.id())) continue;
                        String key = Ledger.pair(a.id(), b.id());
                        if (PAIR_DAY.getOrDefault(key, -1L) >= day) continue;
                        PAIR_DAY.put(key, day);
                        daily(level, a, b, day, new Random((long) key.hashCode() * 31L + day * 7L + 3L));
                    }
                }
            }
        });
    }

    /** Once a day for two neighbours that know each other: their quarrel, their war, their allies' calls. */
    static void daily(ServerLevel level, Villages.Village a, Villages.Village b, long day, Random rng) {
        UUID x = a.id(), y = b.id();
        harvest(x, y, day);
        harvest(y, x, day);
        if (Wars.atWar(x, y)) {
            warDay(level, a, b, day);
            return;
        }
        // An ally's call to arms, waiting for an envoy to carry it.
        call(level, a, b, day);
        call(level, b, a, day);
        if (quarrel(x, y) != null) { step(level, a, b, day); return; }
        if (quarrel(y, x) != null) { step(level, b, a, day); return; }
        if (!on()) return;
        Villages.Village first = rng.nextBoolean() ? a : b, second = first == a ? b : a;
        for (Villages.Village[] p : new Villages.Village[][]{ { first, second }, { second, first } }) {
            if (rng.nextInt(3) != 0) continue;                       // even a hawk lets most days go by
            Decision d = weigh(level, p[0], p[1], day);
            if (d.go()) {
                callCouncil(level, p[0], p[1], day, d, Boolean.FALSE);
                return;
            }
        }
    }

    /** Once a day for each town: its banners, its allies' guards, the cost of its war, its strength noted. */
    static void townDaily(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (!Villages.folkOf(id).isEmpty()) Ledger.note(id, "wp.strength", Integer.toString(own(level, id).strength()));
        for (Map.Entry<String, String> n : Ledger.notes(id).entrySet()) {
            String key = n.getKey();
            if (key.startsWith("wp.banner/")) {
                // A banner still up for a war that is over (its town was out of reach at the peace): down now.
                UUID foe = WarBooks.id(key.substring(10));
                if (foe == null || !Wars.atWar(id, foe)) {
                    if (foe != null) WarBanner.takeDown(level, v, foe);
                    else Ledger.forget(id, key);
                }
            } else if (key.startsWith("wp.pledge/")) {
                dispatch(level, v, WarBooks.id(key.substring(10)), n.getValue(), day);
            }
        }
        for (UUID foe : Wars.enemies(id)) {
            // No cloth for the banner on the day: hung when the stores have it.
            if (WarBanner.where(id, foe) == null && level.isLoaded(v.centre()) && WarBanner.hang(level, v, foe) != null) {
                Villages.tell(id, day, "the war banner was hung at last, for the war with " + name(foe));
            }
            account(level, id, foe, day);
        }
        keepGarrison(level, v, day);
        wearyDay(level, v, day);
        Guard.run("war-weary leaving", () -> leave(level, v, day));
        earlyElection(v.id(), day);
        String feast = Ledger.note(id, "wp.feast");
        if (feast != null && !feast.isEmpty()) {
            long on = WarBooks.num(feast.split("\\|")[0], -1);
            if (on < day) Ledger.forget(id, "wp.feast");
            else if (!Gatherings.sponsored(id, on)) Gatherings.sponsor(id, feast.contains("|") ? feast.split("\\|", 2)[1] : "the peace", on);
        }
    }

    // ------------------------------------------------------------------ grievances

    /** What it remembers of them (Bonds) that it holds against them, written into its grievances. */
    static void harvest(UUID us, UUID them, long day) {
        long since = WarBooks.num(Ledger.note(us, "wp.harvest/" + them), -1L);
        for (Bonds.Memory m : Bonds.memories(us, them)) {
            if (m.weight() >= 0 || m.day() < since) continue;
            WarBooks.wrong(us, them, m.day(), classify(m.what()), m.what());
        }
        Ledger.note(us, "wp.harvest/" + them, Long.toString(day));
    }

    static Wrong classify(String what) {
        String t = what.toLowerCase(Locale.ROOT);
        if (t.contains("treaty")) return Wrong.TREATY;
        if (t.contains("blows") || t.contains("scuffle")) return Wrong.BLOWS;
        if (t.contains("sheep") || t.contains("missing") || t.contains("stole") || t.contains("raid")) return Wrong.THEFT;
        if (t.contains("boundary") || t.contains("land") || t.contains("felling") || t.contains("border")) return Wrong.LAND;
        if (t.contains("tribute")) return Wrong.TRIBUTE;
        if (t.contains("torn up") || t.contains("deal") || t.contains("pact")) return Wrong.DEAL;
        if (t.contains("killed") || t.contains("hurt")) return Wrong.BLOOD;
        if (t.contains("demand")) return Wrong.DEMANDS;
        return Wrong.INSULT;
    }

    /**
     * Something done to a town by a neighbour, in its books: for the other work to write in (a broken deal
     * by the trade between towns, a spy caught by the watch).
     */
    public static void grievance(UUID us, UUID them, Wrong kind, String what, long day) {
        if (us == null || them == null || us.equals(them)) return;
        WarBooks.wrong(us, them, day, kind, what);
    }

    /** What it holds against them from the last month, all told. */
    static int wronged(UUID us, UUID them, long day) {
        int w = 0;
        for (WarBooks.Grievance g : WarBooks.wrongs(us, them)) if (day - g.day() <= Bonds.WARM_DAYS) w += g.kind().weight;
        return w;
    }

    /** A real grievance: something that weighs (not an insult alone) in the last month. */
    static boolean realGrievance(UUID us, UUID them, long day) {
        for (WarBooks.Grievance g : WarBooks.wrongs(us, them)) if (day - g.day() <= Bonds.WARM_DAYS && g.kind().weight >= 2) return true;
        return false;
    }

    /** A treaty broken, or blood: no truce or treaty holds a town back after that. */
    static boolean brokenFaith(UUID us, UUID them, long day) {
        for (WarBooks.Grievance g : WarBooks.wrongs(us, them)) {
            if (day - g.day() <= Bonds.WARM_DAYS && (g.kind() == Wrong.TREATY || g.kind() == Wrong.BLOOD)) return true;
        }
        return false;
    }

    /** The heaviest of its recent grievances, in its own words, or null. */
    @Nullable
    static WarBooks.Grievance worst(UUID us, UUID them, long day) {
        WarBooks.Grievance best = null;
        for (WarBooks.Grievance g : WarBooks.wrongs(us, them)) {
            if (day - g.day() > Bonds.WARM_DAYS) continue;
            if (best == null || g.kind().weight > best.kind().weight || g.kind().weight == best.kind().weight && g.day() > best.day()) best = g;
        }
        return best;
    }

    // ------------------------------------------------------------------ hawks and doves, and the odds

    /** How ready to fight the leader is, and why: its temper, what it cares about, and how much it has been wronged. */
    record Stance(int hawk, String why) {}

    static Stance stance(UUID us, UUID them, long day) {
        Envoys.Temper t = Envoys.temper(us);
        int h = switch (t) {
            case PRICKLY -> 3;
            case SHREWD -> 2;
            case STEADY, CURIOUS -> 0;
            case EASY, WARY -> -2;
            case WARM, FRIENDLY, GENEROUS -> -3;
        };
        VillageFolkEntity leader = Envoys.leader(us);
        Values.Value cares = leader == null ? null : Values.top(leader);
        if (cares != null) {
            h += switch (cares) {
                case SAFETY -> 2;
                case TRADITION -> 1;
                case PROGRESS -> 0;
                case FOOD, HOMES, LEISURE -> -1;
                case WEALTH -> -2;
            };
        }
        int w = wronged(us, them, day);
        if (w >= 8) h += 2;
        else if (w >= 4) h += 1;
        if (brokenFaith(us, them, day)) h += 2;
        if (Ledger.relation(us, them) <= -80) h += 1;
        String who = "the elder is " + t.words + (cares == null ? "" : ", a " + cares.type + " at heart");
        return new Stance(h, who + (h >= HAWK ? " (a hawk, " + h + ")" : " (a dove, " + h + ")"));
    }

    /** Is this guard in iron or better? */
    static boolean armoured(VillageFolkEntity g) {
        ItemStack chest = g.getItemBySlot(EquipmentSlot.CHEST);
        return chest.getItem() instanceof ArmorItem ai && ai.getDefense() >= 6;
    }

    /** Has this guard a bow (or a crossbow)? */
    static boolean archer(VillageFolkEntity g) {
        if (g.getMainHandItem().getItem() instanceof ProjectileWeaponItem) return true;
        for (ItemStack s : g.getInventoryItems()) if (s.getItem() instanceof ProjectileWeaponItem) return true;
        return false;
    }

    /** One number for a town's strength: its guards most, their iron and bows, its wall, and a little for its numbers. */
    static int strength(int folk, int guards, int armoured, int archers, int walls) {
        return Math.max(1, guards * 3 + armoured * 2 + archers + walls * 2 + folk / 5);
    }

    static int grown(UUID v) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(v)) if (!a.isBaby()) n++;
        return n;
    }

    /**
     * A town's strength as it knows it itself: its own watch (less any standing on an ally's walls), the
     * allies' guards on its own walls, its wall. Out of reach (its folk unloaded), as it was last counted.
     */
    public static Reckoning own(ServerLevel level, UUID v) {
        if (Villages.folkOf(v).isEmpty()) {
            int s = (int) WarBooks.num(Ledger.note(v, "wp.strength"), 1);
            return new Reckoning(Villages.headcount(v), 0, 0, 0, 0, 0, Math.max(1, s), "as last counted", false);
        }
        int guards = 0, armoured = 0, archers = 0, garrison = 0;
        for (VillageFolkEntity g : WarFooting.militia(v)) {
            if (awayOnGarrison(g)) continue;
            guards++;
            if (armoured(g)) armoured++;
            if (archer(g)) archers++;
        }
        for (VillageFolkEntity g : garrisonAt(level, v)) {
            guards++;
            garrison++;
            if (armoured(g)) armoured++;
            if (archer(g)) archers++;
        }
        int folk = grown(v);
        int walls = Villages.hasBuilt(v, "fortify") ? 4 : 0;
        return new Reckoning(folk, guards, armoured, archers, walls, garrison, strength(folk, guards, armoured, archers, walls),
            "our own count", false);
    }

    /** How a leader's temper colours a guess at a town it has no fresh word of: a prickly one thinks little of them, a wary one fears the worst. */
    static double colour(Envoys.Temper t) {
        return switch (t) {
            case PRICKLY -> 0.7;
            case SHREWD -> 0.9;
            case STEADY, CURIOUS -> 1.0;
            case EASY, WARM, FRIENDLY, GENEROUS -> 1.15;
            case WARY -> 1.4;
        };
    }

    /**
     * Their strength as we reckon it: from our scouts' last report (Intel) as it stands, if it is fresh;
     * if it is old, from it as far as it goes and the leader's guess at the rest; with no report at all, on
     * rumour (about how many they are, one in six of them on the watch, a wall if anybody has seen one, and
     * any allies' guards gone to them, for that is talked of on every road). A guess is coloured by the
     * leader's temper.
     */
    public static Reckoning of(ServerLevel level, UUID us, UUID them, long day) {
        Intel.Report r = Intel.latest(us, them);
        double tint = colour(Envoys.temper(us));
        if (r != null) {
            long age = Intel.age(r, day);
            int base = strength(r.folk(), r.guards(), r.armoured(), r.archers(), Math.min(4, r.walls()));
            if (age <= STALE) {
                return new Reckoning(r.folk(), r.guards(), r.armoured(), r.archers(), r.walls(), 0, base,
                    "the scouts' report of day " + r.day(), false);
            }
            return new Reckoning(r.folk(), r.guards(), r.armoured(), r.archers(), r.walls(), 0, Math.max(1, (int) Math.round(base * tint)),
                "the scouts' report of day " + r.day() + ", " + age + " days old: the elder guesses the rest", true);
        }
        int folk = Math.max(5, (int) Math.round(Villages.headcount(them) / 5.0) * 5);
        int garrison = garrisonCount(them);
        int guards = Math.max(1, folk / 6) + garrison;
        int walls = Villages.hasBuilt(them, "fortify") ? 4 : 0;
        int s = Math.max(1, (int) Math.round(strength(folk, guards, 0, 0, walls) * tint));
        return new Reckoning(folk, guards, 0, 0, walls, garrison, s, "rumour: the elder's guess", true);
    }

    /** The guards a town's sworn allies could spare it (half their watch, three at most each), as strength. */
    static int allyHelp(UUID v, UUID against) {
        int help = 0;
        for (Villages.Village o : Villages.every()) {
            UUID l = o.id();
            if (l.equals(v) || l.equals(against) || !Envoys.allied(v, l) || Envoys.allied(l, against)) continue;
            help += spare(l) * 3;
        }
        return help;
    }

    /** How many guards a town can spare an ally: half its watch at home, three at most. */
    static int spare(UUID v) {
        int home = 0;
        for (VillageFolkEntity g : WarFooting.militia(v)) if (!awayOnGarrison(g)) home++;
        return Math.min(3, home / 2);
    }

    /** Would this town's leader put war with that one to its council today, and why or why not. */
    public static Decision weigh(ServerLevel level, Villages.Village us, Villages.Village them, long day) {
        UUID x = us.id(), y = them.id();
        String n = name(y);
        if (!on()) return no("wars are switched off (villageWars)");
        if (Wars.atWar(x, y)) return no("already at war with " + n);
        if (!Wars.enemies(x).isEmpty() || !Wars.enemies(y).isEmpty()) return no("one war at a time");
        if (quarrel(x, y) != null || quarrel(y, x) != null) return no("a quarrel with " + n + " is already under way");
        if (Villages.headcount(x) < MIN_FOLK || Villages.headcount(y) < 3) return no("too few folk for a war");
        int r = Ledger.relation(x, y);
        if (r > Diplomacy.FEUD) return no("not in a feud with " + n + " (" + r + ")");
        if (!realGrievance(x, y, day)) return no("nothing real held against " + n);
        boolean broken = brokenFaith(x, y, day);
        WarBooks.Treaty tr = WarBooks.treaty(x, y);
        if (WarBooks.inForce(x, y, day) && !broken) return no("the treaty with " + n + " keeps the peace until day " + (tr == null ? day : tr.until()));
        if (Bonds.truce(x, y, day) && !broken) return no("a truce with " + n);
        if (WarBooks.num(Ledger.note(x, "wp.cool/" + y), -1) >= day) return no("the matter was settled, or the council said no, lately");
        if (day - WarBooks.num(Ledger.note(x, "wp.lastwar"), -1000) < AFTER_WAR) return no("a war only lately over");
        Stance st = stance(x, y, day);
        if (st.hawk() < HAWK) return new Decision(false, st.why() + ": a dove does not go to war", st.hawk(), null, null, 0, null, 0, "");
        Reckoning ours = own(level, x), theirs = of(level, x, y, day);
        int help = allyHelp(x, y);
        String odds = "us " + ours.strength() + (help > 0 ? " (and allies " + help + ")" : "") + " against " + n + " " + theirs.strength()
            + " by " + theirs.source();
        if (theirs.strength() * 2 >= ours.strength() * 3 && ours.strength() + help < theirs.strength()) {
            return new Decision(false, st.why() + "; but " + n + " is much the stronger: " + odds, st.hawk(), ours, theirs, help, null, 0, "");
        }
        Envoys.Temper t = Envoys.temper(x);
        double need = t == Envoys.Temper.PRICKLY ? 0.8 : t == Envoys.Temper.SHREWD ? 1.0 : 1.1;
        double ratio = (ours.strength() + help) / (double) Math.max(1, theirs.strength());
        if (ratio < need) return new Decision(false, st.why() + "; but the odds are against us: " + odds, st.hawk(), ours, theirs, help, null, 0, "");
        Pick g = goalFor(level, us, them, day);
        return new Decision(true, st.why() + "; " + odds, st.hawk(), ours, theirs, help, g.goal(), g.amount(), g.text());
    }

    private static Decision no(String why) {
        return new Decision(false, why, 0, null, null, 0, null, 0, "");
    }

    // ------------------------------------------------------------------ goals

    record Pick(Goal goal, int amount, String text) {}

    /** What this war would be for, by what lies between them and what sort of elder asks. */
    static Pick goalFor(ServerLevel level, Villages.Village us, Villages.Village them, long day) {
        UUID x = us.id(), y = them.id();
        // A colony of ours that they are at odds with, or squeezing for tribute.
        for (Map.Entry<UUID, UUID> link : Ledger.links().entrySet()) {
            UUID colony = link.getKey();
            if (!link.getValue().equals(x) || colony.equals(y) || Villages.get(colony) == null) continue;
            if (Ledger.knowEachOther(y, colony) && Ledger.relation(y, colony) <= Diplomacy.UNEASY) {
                return new Pick(Goal.FREE_COLONY, 0, "our colony " + name(colony) + " left in peace, no quarrel and no tribute@" + colony);
            }
        }
        WarBooks.Grievance worst = worst(x, y, day);
        if (worst != null && worst.kind() == Wrong.LAND && Diplomacy.apart(us, them) < Diplomacy.CROWDED && !Bonds.border(x, y)) {
            return new Pick(Goal.BORDER, 0, "a border where we say it runs, with stones to mark it");
        }
        Envoys.Temper t = Envoys.temper(x);
        VillageFolkEntity leader = Envoys.leader(x);
        if (t == Envoys.Temper.SHREWD || leader != null && Values.top(leader) == Values.Value.WEALTH) {
            String deal = deal(level, us, them, "ours");
            Ledger.note(x, "wp.dealwant/" + y, deal);
            return new Pick(Goal.TRADE, 0, dealWords(deal, y));
        }
        int ours = Villages.headcount(x), theirs = Villages.headcount(y);
        if (ours * 10 >= theirs * 13 && (t == Envoys.Temper.PRICKLY || t == Envoys.Temper.SHREWD)) {
            int amount = 8 + theirs / 2;
            return new Pick(Goal.TRIBUTE, amount, amount + " coins in tribute");
        }
        int amount = Math.min(20, 4 + 2 * wronged(x, y, day));
        return new Pick(Goal.REVENGE, amount, amount + " coins for the wrongs done us" + (worst == null ? "" : " (" + worst.what() + ")"));
    }

    /**
     * A trade deal written down for the trade between towns to take up: who sells what to whom, on whose
     * terms, for how long. "deal&amp;seller=&lt;id&gt;&amp;buyer=&lt;id&gt;&amp;sell=&lt;items&gt;&amp;buy=&lt;items&gt;&amp;terms=ours|fair&amp;days=28".
     */
    static String deal(ServerLevel level, Villages.Village us, Villages.Village them, String terms) {
        List<String> sell = new ArrayList<>(), buy = new ArrayList<>();
        for (ItemStack s : Caravans.load(level, us, them.id(), false, false)) {
            if (sell.size() < 3) sell.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        }
        for (ItemStack s : Caravans.load(level, them, us.id(), true, false)) {
            if (buy.size() < 3) buy.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        }
        return "deal&seller=" + us.id() + "&buyer=" + them.id() + "&sell=" + String.join(",", sell) + "&buy=" + String.join(",", buy)
            + "&terms=" + terms + "&days=" + TREATY_DAYS;
    }

    static String dealWords(String deal, UUID them) {
        String sell = "", buy = "", terms = "ours";
        for (String p : deal.split("&")) {
            if (p.startsWith("sell=")) sell = items(p.substring(5));
            else if (p.startsWith("buy=")) buy = items(p.substring(4));
            else if (p.startsWith("terms=")) terms = p.substring(6);
        }
        return "a trade deal on " + (terms.equals("ours") ? "our" : "fair") + " terms: " + name(them) + " to buy our "
            + (sell.isEmpty() ? "goods" : sell) + (buy.isEmpty() ? "" : " and sell us its " + buy) + (terms.equals("ours") ? " at our prices" : "")
            + ", for " + TREATY_DAYS + " days";
    }

    private static String items(String ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids.split(",")) {
            if (id.isEmpty()) continue;
            String p = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            out.add(p.replace('_', ' '));
        }
        return String.join(", ", out);
    }

    /** The goal's words, without the colony's id that rides along in a FREE_COLONY goal's text. */
    static String words(String goalText) {
        int at = goalText.indexOf('@');
        return at < 0 ? goalText : goalText.substring(0, at);
    }

    @Nullable
    static UUID colonyOf(String goalText) {
        int at = goalText.indexOf('@');
        return at < 0 ? null : WarBooks.id(goalText.substring(at + 1));
    }

    // ------------------------------------------------------------------ the quarrel, before it is a war

    /** A quarrel on its way to war: where it has got to, since when, and what it is for. */
    static final class Quarrel {
        Stage stage;
        long day;
        Goal goal;
        int amount;
        int tries;
        String text;

        Quarrel(Stage stage, long day, Goal goal, int amount, int tries, String text) {
            this.stage = stage;
            this.day = day;
            this.goal = goal;
            this.amount = amount;
            this.tries = tries;
            this.text = text;
        }

        String encode() {
            return stage.name() + "|" + day + "|" + goal.name() + "|" + amount + "|" + tries + "|" + WarBooks.clean(text);
        }
    }

    @Nullable
    static Quarrel quarrel(UUID us, UUID them) {
        String s = Ledger.note(us, "wp.q/" + them);
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split("\\|", 6);
        if (p.length < 6) return null;
        try {
            return new Quarrel(Stage.valueOf(p[0]), WarBooks.num(p[1], 0), Goal.valueOf(p[2]), (int) WarBooks.num(p[3], 0),
                (int) WarBooks.num(p[4], 0), p[5]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static void save(UUID us, UUID them, Quarrel q) {
        Ledger.note(us, "wp.q/" + them, q.encode());
        tense(us, them, true);
    }

    /** The quarrel is over (met, refused by the council, or become a war); nothing more for a while. */
    static void drop(UUID us, UUID them, long day, int quiet) {
        Ledger.forget(us, "wp.q/" + them);
        tense(us, them, false);
        tense(them, us, false);
        if (quiet > 0) Ledger.note(us, "wp.cool/" + them, Long.toString(day + quiet));
    }

    /** [Envoys.consider] No greetings, trade or tribute between towns at war, or with a quarrel between them: the war's own envoys only. */
    static boolean quiet(UUID a, UUID b) {
        return Wars.atWar(a, b) || quarrel(a, b) != null || quarrel(b, a) != null;
    }

    /** A town on its guard over another (a quarrel, an ultimatum, a pledge to an ally at war), for its footing. */
    static void tense(UUID v, UUID over, boolean on) {
        List<String> all = WarBooks.list(v, "wp.tense");
        String o = over.toString();
        if (on ? all.contains(o) : !all.remove(o)) return;
        if (on) all.add(o);
        WarBooks.list(v, "wp.tense", all);
    }

    /**
     * Is this town on its guard short of war: a quarrel or an ultimatum between it and another, a pledge of
     * its guards to an ally at war, or a feud no treaty holds? (Wars.footing's TENSION.)
     */
    public static boolean onGuard(UUID village) {
        if (!WarBooks.list(village, "wp.tense").isEmpty()) return true;
        long day = today();
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(village) || !Ledger.knowEachOther(village, o.id())) continue;
            if (Ledger.relation(village, o.id()) <= Diplomacy.FEUD && !WarBooks.inForce(village, o.id(), day)) return true;
        }
        return false;
    }

    /** Where a quarrel stands, each day: the council that never sat votes in the hall; the herald goes; a herald lost is given up. */
    static void step(ServerLevel level, Villages.Village us, Villages.Village them, long day) {
        UUID x = us.id(), y = them.id();
        Quarrel q = quarrel(x, y);
        if (q == null) return;
        if (!on()) {
            drop(x, y, day, 0);
            Villages.tell(x, day, "the elder let the quarrel with " + name(y) + " drop");
            return;
        }
        switch (q.stage) {
            case COUNCIL -> {
                // The council of war never sat (rain, the bell, nobody came): it votes in the hall after all.
                if (day > q.day) decided(level, x, y, vote(level, x, y, q, day), day);
            }
            case HERALD_DUE -> sendHerald(level, us, them, day, q);
            case HERALD -> {
                if (day - q.day <= HERALD_DAYS || Envoys.travelling(x, y)) return;
                if (q.tries < 2) {
                    q.stage = Stage.HERALD_DUE;
                    save(x, y, q);
                } else {
                    drop(x, y, day, COOL_DAYS);
                    Villages.tell(x, day, "our herald never came back from " + name(y) + "; the elder let the matter lie");
                }
            }
        }
    }

    // ------------------------------------------------------------------ the council of war

    /**
     * The leader calls the council to sit as a council of war over them: this evening in the hall ({@code now}
     * false), at once (true: the command), or with no gathering at all (null: it votes in the hall there and
     * then, as the tests and a quiet evening have it).
     */
    static void callCouncil(ServerLevel level, Villages.Village us, Villages.Village them, long day, Decision d, @Nullable Boolean now) {
        UUID x = us.id(), y = them.id();
        Quarrel q = new Quarrel(Stage.COUNCIL, day, d.goal() == null ? Goal.REVENGE : d.goal(), d.amount(), 0, d.goalText());
        save(x, y, q);
        String elder = Villages.elderName(x);
        WarBooks.Grievance w = worst(x, y, day);
        Villages.tell(x, day, (elder.isEmpty() ? "the elder" : "Elder " + elder) + " called the council to sit as a council of war over "
            + name(y) + (w == null ? "" : ": " + w.what()));
        VillageFolkEntity leader = Envoys.leader(x);
        if (leader != null) FolkTalk.speak(leader, "The council sits tonight. " + name(y) + " has gone too far.");
        if (now != null) Assemblies.councilOfWar(level, us, "war|" + y, now);
        LOG.info("[MCA-WAR] {} calls a council of war over {}: {} (goal {} {})", name(x), name(y), d.why(), q.goal, words(q.text));
    }

    /** One councillor's vote: for or against, how much its word counts, and what it said. */
    record Voice(UUID id, String name, boolean aye, int weight, String said) {}

    /** The council's vote: the leader's case, each voice, the count, and the result in words. */
    record Vote(List<Voice> voices, int ayes, int nays, boolean passed, String opening, String result) {}

    /** How a councillor leans: by what it cares about, its nature, the odds, a hurt of its own, kin over there, and the leader. */
    static int lean(VillageFolkEntity m, UUID us, UUID them, double odds, @Nullable VillageFolkEntity leader, long day) {
        int s = switch (Values.top(m)) {
            case SAFETY -> 2;
            case TRADITION -> 1;
            case PROGRESS -> 0;
            case FOOD, HOMES -> -1;
            case LEISURE, WEALTH -> -2;
        };
        s += (Values.weight(m, Values.Value.SAFETY) - Values.weight(m, Values.Value.WEALTH)) / 30;
        Social.Life l = m.life();
        if (l.has(Social.Trait.GRUMPY)) s += 1;
        if (l.has(Social.Trait.GENEROUS) || l.has(Social.Trait.CHEERFUL)) s -= 1;
        if (l.has(Social.Trait.SHY)) s -= 1;
        if (odds >= 1.5) s += 1;
        else if (odds < 1.0) s -= 1;
        if (day - m.persona().hurtDay <= 3) s += 1;
        if (JobSeekers.kinIn(m, them) != null) s -= 3;
        if (leader != null) {
            int a = m.life().affinity(leader.getUUID());
            if (a >= 30) s += 1;
            else if (a <= -30) s -= 1;
        }
        s += Math.floorMod(Objects.hash(m.getUUID(), them, day), 3) - 1;       // and a little of its own mind
        return s;
    }

    static String sayAye(VillageFolkEntity m, UUID them, double odds) {
        String n = name(them);
        return switch (Values.top(m)) {
            case SAFETY -> "They've wronged us once too often. Aye.";
            case TRADITION -> "Our elders never stood for the like of it. Aye.";
            default -> odds >= 1.5 ? "We'd win, and " + n + " knows it. Aye." : "Somebody has to stand up to " + n + ". Aye.";
        };
    }

    static String sayNay(VillageFolkEntity m, UUID them, double odds) {
        String n = name(them);
        if (JobSeekers.kinIn(m, them) != null) return "My own kin live in " + n + ". Nay.";
        if (odds < 1.0) return n + " is too strong for us. Nay.";
        return switch (Values.top(m)) {
            case WEALTH -> "War is ruin for trade. Nay.";
            case FOOD -> "Who'll bring the harvest in while we stand on the walls? Nay.";
            case HOMES -> "Half the town wants a roof over its head, not a war. Nay.";
            case LEISURE -> "I want no part of it. Nay.";
            case PROGRESS -> "Every pick on the wall is a pick out of the mine. Nay.";
            default -> "Not over this. Nay.";
        };
    }

    /** The council votes on the ultimatum: each by its lights, the leader's word counting three. */
    static Vote vote(ServerLevel level, UUID us, UUID them, Quarrel q, long day) {
        Reckoning ours = own(level, us), theirs = of(level, us, them, day);
        int help = allyHelp(us, them);
        double odds = (ours.strength() + help) / (double) Math.max(1, theirs.strength());
        UUID elder = Villages.elder(us);
        VillageFolkEntity leader = Envoys.leader(us);
        List<Voice> voices = new ArrayList<>();
        int ayes = 0, nays = 0;
        for (VillageFolkEntity m : Council.members(us)) {
            boolean isLeader = m.getUUID().equals(elder);
            boolean aye;
            String said;
            if (isLeader) {
                aye = true;
                said = "I say we send " + name(them) + " our demands: " + words(q.text) + ". If they will not, it is war.";
            } else {
                aye = lean(m, us, them, odds, leader, day) > 0;
                said = aye ? sayAye(m, them, odds) : sayNay(m, them, odds);
            }
            int weight = isLeader ? 3 : 1;
            if (aye) ayes += weight;
            else nays += weight;
            voices.add(new Voice(m.getUUID(), m.displayNameCap(), aye, weight, said));
        }
        boolean passed = ayes > nays;
        WarBooks.Grievance w = worst(us, them, day);
        String opening = name(them) + " has wronged us" + (w == null ? "" : ": " + w.what()) + ". By " + theirs.source() + " they are "
            + theirs.strength() + " to our " + ours.strength() + (help > 0 ? ", and our allies would send " + help / 3 + " guards" : "") + ".";
        String result = passed
            ? "The council votes " + ayes + " to " + nays + ": the herald goes to " + name(them) + " in the morning."
            : "The council votes " + ayes + " to " + nays + " against. There will be no war with " + name(them) + ".";
        return new Vote(voices, ayes, nays, passed, opening, result);
    }

    /**
     * [Assemblies.script] The council's lines as a council of war: the leader's case, each councillor's
     * vote and why, the count, and what comes of it. False if this council is the ordinary one.
     */
    static boolean councilScript(ServerLevel level, UUID village, String subject, List<Assemblies.Line> s, RandomSource r) {
        if (subject == null || !subject.startsWith("war|")) return false;
        s.clear();
        UUID them = WarBooks.id(subject.substring(4));
        Quarrel q = them == null ? null : quarrel(village, them);
        long day = level.getDayTime() / 24000L;
        if (q == null || q.stage != Stage.COUNCIL) {
            s.add(new Assemblies.Line(null, "The council of war has nothing before it tonight.", ' ', null));
            return true;
        }
        Vote v = vote(level, village, them, q, day);
        s.add(new Assemblies.Line(null, "The council sits tonight as a council of war, over " + name(them) + ".", '~', null));
        s.add(new Assemblies.Line(null, v.opening(), '?', null));
        for (Voice voice : v.voices()) {
            if (voice.weight() > 1) s.add(new Assemblies.Line(null, voice.said(), '!', null));
            else s.add(new Assemblies.Line(voice.id(), voice.said(), voice.aye() ? '!' : '?', null));
        }
        s.add(new Assemblies.Line(null, v.result(), v.passed() ? '!' : '~', () -> decided(level, village, them, v, day)));
        return true;
    }

    /** The vote is in: on the board and in the chronicle; the herald to go, or the matter let lie. */
    static void decided(ServerLevel level, UUID us, UUID them, Vote v, long day) {
        Quarrel q = quarrel(us, them);
        if (q == null || q.stage != Stage.COUNCIL) return;
        List<String> votes = new ArrayList<>();
        for (Voice voice : v.voices()) votes.add(voice.id() + ":" + (voice.aye() ? 1 : 0));
        Ledger.note(us, "wp.voted/" + them, String.join(",", votes));
        Ledger.note(us, "wp.council/" + them, day + "|" + v.ayes() + "|" + v.nays() + "|" + (v.passed() ? 1 : 0) + "|" + WarBooks.clean(words(q.text)));
        for (Voice voice : v.voices()) {
            if (level.getEntity(voice.id()) instanceof VillageFolkEntity f) {
                f.persona().remember(day, "I voted " + (voice.aye() ? "for" : "against") + " war with " + name(them) + " in the council", 4);
            }
        }
        if (v.passed()) {
            q.stage = Stage.HERALD_DUE;
            q.day = day;
            save(us, them, q);
            Villages.tell(us, day, "the council of war voted " + v.ayes() + " to " + v.nays() + " to send " + name(them) + " our demands: "
                + words(q.text) + ", or war");
        } else {
            drop(us, them, day, COOL_DAYS);
            Villages.tell(us, day, "the council of war would not have it: " + v.ayes() + " to " + v.nays() + " against war with " + name(them));
        }
        LOG.info("[MCA-WAR] {} council of war over {}: {} to {}, {}", name(us), name(them), v.ayes(), v.nays(), v.passed() ? "the herald goes" : "no war");
    }

    // ------------------------------------------------------------------ the herald and the ultimatum

    static void sendHerald(ServerLevel level, Villages.Village us, Villages.Village them, long day, Quarrel q) {
        UUID x = us.id(), y = them.id();
        if (Envoys.travelling(x, y)) return;
        if (!Envoys.send(level, us, them, Envoys.Errand.WAR, day)) return;
        q.stage = Stage.HERALD;
        q.day = day;
        q.tries++;
        save(x, y, q);
        tense(y, x, true);
    }

    /** What the demands are, in the herald's mouth. */
    static String demand(Quarrel q) {
        return switch (q.goal) {
            case TRIBUTE -> q.amount + " coins in tribute";
            case REVENGE -> q.amount + " coins for the wrongs you have done us";
            default -> words(q.text);
        };
    }

    /** The herald, the call to arms or the white flag: what the envoy says on arriving (Envoys.asks), or null if it is none of these. */
    @Nullable
    static String asks(UUID from, UUID host, Envoys.Errand errand, Caravans.Trip t) {
        String fn = name(from), elder = Villages.elderName(from);
        String sender = elder.isEmpty() ? "The folk of " + fn : "Elder " + elder + " of " + fn;
        if (errand == Envoys.Errand.WAR) {
            Quarrel q = quarrel(from, host);
            if (q != null && !Wars.atWar(from, host)) {
                WarBooks.Grievance w = worst(from, host, today());
                return sender + " says: " + (w == null ? "you have wronged us" : w.what()) + ". We demand " + demand(q) + ". Yield, or it is war.";
            }
            List<UUID> foes = Wars.enemies(from);
            if (!foes.isEmpty()) return sender + " is at war with " + name(foes.get(0)) + ", and calls on you, our sworn allies, to stand with us.";
            return sender + " sends word of war.";
        }
        if (errand == Envoys.Errand.PEACE && Wars.atWar(from, host)) {
            return sender + " comes under a white flag. Enough of this standing at arms: let us talk of peace.";
        }
        return null;
    }

    /** [Envoys.answer] The host's answer to a herald, a call to arms, or a white flag; null for any other errand. */
    @Nullable
    static Envoys.Answer answer(ServerLevel level, UUID host, UUID from, Envoys.Errand errand, VillageFolkEntity envoy, Caravans.Trip t) {
        long day = level.getDayTime() / 24000L;
        if (errand == Envoys.Errand.WAR) {
            Quarrel q = quarrel(from, host);
            if (q != null && !Wars.atWar(from, host)) return ultimatum(level, host, from, q, t, day);
            if (Wars.atWar(from, host)) return new Envoys.Answer(false, "We're at war already. Go home, and tell them so.", () -> t.outcome = "we are at war already");
            if (!Wars.enemies(from).isEmpty()) return callToArms(level, host, from, t, day);
            return new Envoys.Answer(false, "War? There's no quarrel between us that I know of.", () -> t.outcome = "nobody knew what I meant");
        }
        if (errand == Envoys.Errand.PEACE && Wars.atWar(from, host)) return peaceTalks(level, host, from, envoy, t, day);
        return null;
    }

    /**
     * What the host makes of an ultimatum: its strength (and its allies') against theirs as it reckons
     * them, by its temper. A prickly elder never yields; a soft one yields to a stronger town rather than
     * fight it; most bargain when it is close.
     */
    static Reply reply(ServerLevel level, UUID host, UUID from, Quarrel q, long day) {
        Reckoning mine = own(level, host), theirs = of(level, host, from, day);
        double ratio = (mine.strength() + allyHelp(host, from)) / (double) Math.max(1, theirs.strength());
        Envoys.Temper t = Envoys.temper(host);
        double yieldBelow, bargainBelow;
        switch (t) {
            case PRICKLY -> { yieldBelow = 0.0; bargainBelow = 0.5; }
            case SHREWD -> { yieldBelow = 0.6; bargainBelow = 1.2; }
            case WARY -> { yieldBelow = 0.9; bargainBelow = 1.4; }
            case STEADY, CURIOUS -> { yieldBelow = 0.8; bargainBelow = 1.3; }
            default -> { yieldBelow = 1.0; bargainBelow = 1.6; }
        }
        Reply r = ratio < yieldBelow ? Reply.YIELD : ratio < bargainBelow ? Reply.BARGAIN : Reply.REFUSE;
        // Coin it has not got, it cannot give: half of it, at a pinch, or none, and a war.
        if ((q.goal == Goal.TRIBUTE || q.goal == Goal.REVENGE) && r != Reply.REFUSE) {
            int coins = Ledger.coins(host);
            if (r == Reply.YIELD && coins < q.amount) r = coins >= q.amount / 2 ? Reply.BARGAIN : Reply.REFUSE;
            else if (r == Reply.BARGAIN && coins < Math.max(1, q.amount / 2)) r = Reply.REFUSE;
        }
        LOG.info("[MCA-WAR] {} weighs {}'s ultimatum: us {} (and allies {}) against {} by {} (temper {}): {}", name(host), name(from),
            mine.strength(), allyHelp(host, from), theirs.strength(), theirs.source(), t, r);
        return r;
    }

    static Envoys.Answer ultimatum(ServerLevel level, UUID host, UUID from, Quarrel q, Caravans.Trip t, long day) {
        String fn = name(from);
        if (!on()) {
            return new Envoys.Answer(false, "War? Nobody goes to war these days. Go home.", () -> {
                drop(from, host, day, COOL_DAYS);
                t.outcome = "they would not hear of war, and the elder let it drop";
            });
        }
        WarBooks.wrong(host, from, day, Wrong.DEMANDS, fn + " sent us its demands: " + demand(q));
        Reply rep = reply(level, host, from, q, day);
        if (rep == Reply.BARGAIN && Envoys.temper(from) == Envoys.Temper.PRICKLY) {
            // Its elder will take nothing less than the whole, and said so before the herald left.
            return new Envoys.Answer(false, "We'll meet you halfway, and not a step further.", () -> {
                Villages.tell(host, day, "our offer of half was not enough for " + fn);
                declare(level, from, host, day, q.goal, q.amount, q.text);
                t.outcome = "they offered half, and the elder will not have half: it is war";
            });
        }
        return switch (rep) {
            case YIELD -> new Envoys.Answer(true, FolkTalk.pick(level.getRandom(), "We want no war. Take it, and go.",
                "Very well. You shall have it — and we shan't forget it."), () -> {
                String met = meet(level, from, host, q.goal, q.amount, q.text, 2, t, day);
                settled(from, host, day, "yielded to " + fn + "'s ultimatum: " + met);
                t.outcome = "they gave in: " + met;
            });
            case BARGAIN -> new Envoys.Answer(true, "Not all of it. Half, and an end to it.", () -> {
                String met = meet(level, from, host, q.goal, q.amount, q.text, 1, t, day);
                settled(from, host, day, "met part of " + fn + "'s ultimatum: " + met);
                t.outcome = "they bargained, and gave part of it: " + met;
            });
            case REFUSE -> new Envoys.Answer(false, Envoys.temper(host) == Envoys.Temper.PRICKLY
                ? "Demands? From " + fn + "? Get out, and tell them we'll be waiting."
                : "We'll give you nothing. If it's war you want, so be it.", () -> {
                declare(level, from, host, day, q.goal, q.amount, q.text);
                t.outcome = "they refused our demands: it is war";
            });
        };
    }

    /**
     * The goal met, the whole of it ({@code share} 2) or the part of it (1): the coin out of the yielding
     * town's treasury into the herald's or envoy's purse (or out of the envoy's purse into the treasury,
     * when it is the yielding side's envoy that carries it), the border walked, the deal made, the colony
     * let be. With no envoy there (a peace on a player's word), no coin moves. What was given, in words.
     */
    static String meet(ServerLevel level, UUID aggressor, UUID defender, Goal goal, int amount, String text, int share,
                       @Nullable Caravans.Trip t, long day) {
        if (share <= 0) return "nothing";
        String dn = name(defender), an = name(aggressor);
        switch (goal) {
            case TRIBUTE, REVENGE -> {
                int owed = share >= 2 ? amount : amount / 2;
                int paid = pay(defender, aggressor, owed, t);
                return paid + " coins " + (goal == Goal.TRIBUTE ? "in tribute" : "for the wrongs done") + (paid < owed ? " (of " + owed + ")" : "");
            }
            case BORDER -> {
                if (!Bonds.border(aggressor, defender)) Bonds.agreeBorder(aggressor, defender, day);
                return share >= 2 ? "a border where " + an + " says it runs" : "a border halfway, as both can live with";
            }
            case TRADE -> {
                Villages.Village a = Villages.get(aggressor), d = Villages.get(defender);
                String deal = Ledger.note(aggressor, "wp.dealwant/" + defender);
                if (deal == null || deal.isEmpty() || share < 2) deal = a == null || d == null ? "" : deal(level, a, d, share >= 2 ? "ours" : "fair");
                if (!Envoys.pact(aggressor, defender)) Envoys.sign(aggressor, defender, day);
                Ledger.note(aggressor, "wp.deal/" + defender, deal);
                Ledger.note(defender, "wp.deal/" + aggressor, deal);
                // And sat down to it there and then (TradeTalks): a standing deal, if the two towns' books lay a table.
                String struck = strike(level, aggressor, defender, day);
                return (deal.isEmpty() ? "a trade pact" : dealWords(deal, defender)) + (struck == null ? "" : "; " + struck);
            }
            case FREE_COLONY -> {
                UUID colony = colonyOf(text);
                if (colony == null) return "nothing";
                Bonds.callTruce(defender, colony, day);
                if (share >= 2) {
                    int r = Ledger.relation(defender, colony);
                    if (r < 0) Ledger.relate(defender, colony, -r);
                    Ledger.note(colony, "wp.freed/" + defender, Long.toString(day));
                    Villages.tell(colony, day, dn + " promised to leave us in peace, at " + an + "'s word");
                    return dn + " to leave " + name(colony) + " in peace";
                }
                return "a truce between " + dn + " and " + name(colony);
            }
            default -> {
                return "nothing";
            }
        }
    }

    /**
     * Coin owed from one town to another, moved by whoever walks between them: out of the paying side's envoy's
     * purse when its envoy carries it, or out of the paying town's treasury into the visiting envoy's purse, to
     * be carried home (Envoys.home). With nobody walking ({@code t} null: a peace on a player's word), none.
     * What was paid.
     */
    static int pay(UUID payer, UUID payee, int owed, @Nullable Caravans.Trip t) {
        if (t == null || owed <= 0) return 0;
        if (t.from.equals(payer)) {
            int paid = Math.min(t.purse, owed);
            t.purse -= paid;
            Ledger.addCoins(payee, paid);
            return paid;
        }
        int paid = Ledger.takeCoins(payer, owed);
        t.purse += paid;
        return paid;
    }

    /** A trade deal struck at the peace (TradeTalks, TradeDeals) on the two towns' books as they stand: its words, or null. */
    @Nullable
    static String strike(ServerLevel level, UUID aggressor, UUID defender, long day) {
        try {
            TradeTalks.Talk k = TradeTalks.negotiate(level, aggressor, defender);
            if (!k.deal()) return null;
            TradeDeals.strike(level, k, day, null);
            return "a standing deal: " + TradeTalks.terms(k);
        } catch (RuntimeException e) {
            LOG.warn("[MCA-WAR] no deal at the peace between {} and {}: {}", name(aggressor), name(defender), e.toString());
            return null;
        }
    }

    /** An ultimatum met: no war, the matter settled for a while, in both books. */
    static void settled(UUID aggressor, UUID defender, long day, String how) {
        drop(aggressor, defender, day, SETTLED_DAYS);
        int r = Ledger.relation(aggressor, defender);
        if (r < Diplomacy.FEUD + 15) Ledger.relate(aggressor, defender, Diplomacy.FEUD + 15 - r);
        Bonds.callTruce(aggressor, defender, day);
        // Remembered as demands given way to, not as what was given (a "tribute" in the memory would read, to
        // the town that took it, as tribute demanded of it).
        Bonds.remember(aggressor, defender, day, -3, name(defender) + " gave way to " + name(aggressor) + "'s demands");
        WarBooks.forgive(aggressor, defender);
        WarBooks.treaty(aggressor, defender, day, day + SETTLED_DAYS, name(defender) + " " + how);
        Villages.tell(defender, day, "we " + how);
        Villages.tell(aggressor, day, name(defender) + " " + how);
    }

    // ------------------------------------------------------------------ declaration day

    /**
     * War, on both towns' books, from today: the bell rung in both, the war banner hung in both, the
     * chronicle, the neighbours taking sides, the allies called. False if it could not be (wars off, the
     * same town, at war already).
     */
    public static boolean declare(ServerLevel level, UUID a, UUID b, long day, Goal goal, int amount, String text) {
        if (!on() || a.equals(b) || Wars.atWar(a, b)) return false;
        String an = name(a), bn = name(b);
        Wars.begin(a, b, day);
        WarBooks.Book ba = new WarBooks.Book(), bb = new WarBooks.Book();
        ba.began = bb.began = day;
        ba.aggressor = true;
        ba.goal = goal;
        ba.amount = amount;
        ba.goalText = text;
        ba.watch = WarFooting.militia(a).size();
        bb.goal = Goal.DEFENCE;
        bb.goalText = "to stand firm against " + an + "'s demands: " + words(text);
        bb.watch = WarFooting.militia(b).size();
        WarBooks.save(a, b, ba);
        WarBooks.save(b, a, bb);
        drop(a, b, day, 0);
        // Who led each town into it: judged on it at the next election (electionLean).
        for (UUID side : new UUID[]{ a, b }) {
            UUID elder = Villages.elder(side);
            if (elder != null) Ledger.note(side, "wp.warleader", elder.toString());
        }
        Ledger.note(a, "wp.lastwar", Long.toString(day));
        Ledger.note(b, "wp.lastwar", Long.toString(day));
        int r = Ledger.relation(a, b);
        if (r > -60) Ledger.relate(a, b, -60 - r);
        Bonds.remember(a, b, day, -8, an + " declared war on " + bn);
        WarBooks.course(a, b, day, "the ultimatum refused: we declared war on " + bn + " for " + words(text));
        WarBooks.course(b, a, day, an + " declared war on us when we would not meet its demands: " + words(text));
        Villages.tell(a, day, "we declared war on " + bn + ", who would not meet our demands: " + words(text));
        Villages.tell(b, day, an + " declared war on us, for refusing its demands: " + words(text));
        for (UUID side : new UUID[]{ a, b }) {
            UUID other = side.equals(a) ? b : a;
            Villages.Village v = Villages.get(side);
            if (v == null) continue;
            ring(level, v, true);
            if (level.isLoaded(v.centre())) {
                BlockPos at = WarBanner.hang(level, v, other);
                if (at != null) {
                    WarBooks.course(side, other, day, "the war banner hung at " + at.getX() + ", " + at.getY() + ", " + at.getZ());
                } else {
                    Villages.tell(side, day, "there was no cloth in the stores for a war banner");
                }
            }
            Raids.tellNear(level, v.centre(), 200, Component.literal("War! " + an + " has declared war on " + bn + ": " + words(text) + ".")
                .withStyle(ChatFormatting.DARK_RED), false);
            VillageFolkEntity leader = Envoys.leader(side);
            if (leader != null) {
                FolkTalk.speak(leader, side.equals(a) ? "They would not listen. It's war with " + bn + "!"
                    : an + " wants a war? Then it shall have one. To the walls!");
            }
        }
        sides(level, a, b, day);
        callAllies(a, b, day);
        callAllies(b, a, day);
        LOG.info("[MCA-WAR] {} declares war on {} (goal {}: {})", an, bn, goal, words(text));
        return true;
    }

    /** The bell, three strokes: for war, deep; for peace, bright. At the town's bell, else the board. */
    static void ring(ServerLevel level, Villages.Village v, boolean war) {
        if (!level.isLoaded(v.centre())) return;
        BlockPos at = TownBell.bellAt(level, v);
        if (at == null) at = VillageBoards.lectern(v.id());
        if (at == null) at = v.centre();
        float pitch = war ? 0.7F : 1.3F;
        for (int i = 0; i < 3; i++) level.playSound(null, at, SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 3.0F, pitch + i * 0.05F);
    }

    /** The neighbours take sides by how they feel about each; the war's books note who stands where. */
    static void sides(ServerLevel level, UUID a, UUID b, long day) {
        Villages.Village va = Villages.get(a), vb = Villages.get(b);
        if (va == null || vb == null) return;
        String key = Ledger.pair(a, b);
        for (Villages.Village n : Villages.every()) {
            UUID x = n.id();
            if (x.equals(a) || x.equals(b)) continue;
            if (!(Diplomacy.neighbours(n, va) && Ledger.knowEachOther(x, a)) && !(Diplomacy.neighbours(n, vb) && Ledger.knowEachOther(x, b))) continue;
            int ra = Ledger.knowEachOther(x, a) ? Ledger.relation(x, a) : 0, rb = Ledger.knowEachOther(x, b) ? Ledger.relation(x, b) : 0;
            UUID side = null;
            if (Envoys.allied(x, a) && !Envoys.allied(x, b)) side = a;
            else if (Envoys.allied(x, b) && !Envoys.allied(x, a)) side = b;
            else if (ra - rb >= 25 && ra >= 0) side = a;
            else if (rb - ra >= 25 && rb >= 0) side = b;
            String nn = name(x);
            if (side == null) {
                Villages.tell(x, day, "we will keep out of the war between " + name(a) + " and " + name(b));
                continue;
            }
            UUID against = side.equals(a) ? b : a;
            Ledger.note(x, "wp.side/" + key, side.toString());
            if (Ledger.knowEachOther(x, against)) Ledger.relate(x, against, -8);
            Bonds.remember(x, against, day, -3, nn + " sided with " + name(side) + " in the war");
            Villages.tell(x, day, "we declared for " + name(side) + " in its war with " + name(against));
            WarBooks.course(side, against, day, nn + " declared for us");
            WarBooks.course(against, side, day, nn + " declared for " + name(side));
        }
    }

    // ------------------------------------------------------------------ the allies

    /** The principal's sworn allies (not also sworn to the enemy) are to be called, each by an envoy. */
    static void callAllies(UUID principal, UUID enemy, long day) {
        for (Villages.Village o : Villages.every()) {
            UUID l = o.id();
            if (l.equals(principal) || l.equals(enemy) || !Envoys.allied(principal, l) || Envoys.allied(l, enemy)) continue;
            Ledger.note(principal, "wp.call/" + l, enemy.toString());
        }
    }

    /** A call to arms waiting to go from this town to that ally: the envoy sent, if one can go. */
    static void call(ServerLevel level, Villages.Village principal, Villages.Village ally, long day) {
        String foe = Ledger.note(principal.id(), "wp.call/" + ally.id());
        if (foe == null || foe.isEmpty()) return;
        UUID enemy = WarBooks.id(foe);
        if (enemy == null || !Wars.atWar(principal.id(), enemy)) {
            Ledger.forget(principal.id(), "wp.call/" + ally.id());
            return;
        }
        if (Envoys.travelling(principal.id(), ally.id())) return;
        if (!Envoys.send(level, principal, ally, Envoys.Errand.WAR, day)) return;
        Ledger.forget(principal.id(), "wp.call/" + ally.id());
        Ledger.note(principal.id(), "wp.calling/" + ally.id(), enemy.toString());
        WarBooks.course(principal.id(), enemy, day, "we called on our allies in " + name(ally.id()));
    }

    /**
     * An ally hears the call to arms: it comes if it is sworn to the town and not to the enemy, not at war
     * itself, not fond of the enemy, and not a wary elder's town (unless the bond is very close). It sends
     * what guards it can spare, to stand on the town's walls till the peace.
     */
    static Envoys.Answer callToArms(ServerLevel level, UUID host, UUID from, Caravans.Trip t, long day) {
        String fromName = name(from);
        UUID enemy = WarBooks.id(Ledger.note(from, "wp.calling/" + host));
        if (enemy == null || !Wars.atWar(from, enemy)) enemy = Wars.enemies(from).get(0);
        UUID foe = enemy;
        boolean join = Envoys.allied(host, from) && !Envoys.allied(host, foe) && Wars.enemies(host).isEmpty()
            && Ledger.relation(host, foe) < Diplomacy.FRIENDLY
            && (Envoys.temper(host) != Envoys.Temper.WARY || Ledger.relation(host, from) >= 80);
        if (!join) {
            return new Envoys.Answer(false, "We wish " + fromName + " well, but we'll not be drawn into it.", () -> {
                Ledger.relate(host, from, -5);
                Villages.tell(host, day, "we would not join " + fromName + "'s war with " + name(foe));
                WarBooks.course(from, foe, day, "our allies in " + name(host) + " would not join the war");
                t.outcome = "they will not join the war";
            });
        }
        int pledge = spare(host);
        return new Envoys.Answer(true, pledge > 0 ? "We stand with " + fromName + ". " + pledge + (pledge == 1 ? " of our guards goes" : " of our guards go")
            + " back with you to your walls." : "We stand with " + fromName + " — though we've no guards to spare.", () -> {
            WarBooks.push(from, "wp.allies", host + "|" + foe + "|" + pledge + "|" + day, 8);
            Ledger.note(host, "wp.pledge/" + from, foe + "|" + pledge + "|0|" + day);
            tense(host, from, true);
            if (Ledger.knowEachOther(host, foe)) Ledger.relate(host, foe, -15);
            Bonds.remember(host, foe, day, -5, name(host) + " took " + fromName + "'s side in the war");
            Villages.tell(host, day, "we answered " + fromName + "'s call to arms against " + name(foe)
                + (pledge > 0 ? ": " + pledge + " of our guards go to stand on its walls" : ""));
            WarBooks.course(from, foe, day, "our allies in " + name(host) + " answered the call" + (pledge > 0 ? ", sending " + pledge + " guards" : ""));
            WarBooks.course(foe, from, day, name(host) + " joined " + fromName + "'s side");
            Villages.tell(foe, day, name(host) + " has joined " + fromName + " in the war against us");
            t.outcome = pledge > 0 ? "our allies stand with us, and send " + pledge + " guards" : "our allies stand with us, though they have no guards to spare";
        });
    }

    /** The towns that answered this town's call and stand with it in a war it is fighting now. */
    public static List<UUID> alliesOf(UUID village) {
        Set<UUID> out = new LinkedHashSet<>();
        for (String e : WarBooks.list(village, "wp.allies")) {
            String[] p = e.split("\\|");
            if (p.length < 2) continue;
            UUID ally = WarBooks.id(p[0]), foe = WarBooks.id(p[1]);
            if (ally != null && foe != null && Wars.atWar(village, foe) && Envoys.allied(village, ally)) out.add(ally);
        }
        return new ArrayList<>(out);
    }

    /** An ally's pledge, each day: the guards it promised, not yet gone, set out for the town's walls. */
    static void dispatch(ServerLevel level, Villages.Village ally, @Nullable UUID to, String pledge, long day) {
        String[] p = pledge.split("\\|");
        UUID foe = p.length > 0 ? WarBooks.id(p[0]) : null;
        if (to == null || foe == null || !Wars.atWar(to, foe)) {
            if (to != null) {
                Ledger.forget(ally.id(), "wp.pledge/" + to);
                tense(ally.id(), to, false);
            }
            return;
        }
        int want = p.length > 1 ? (int) WarBooks.num(p[1], 0) : 0, sent = p.length > 2 ? (int) WarBooks.num(p[2], 0) : 0;
        Villages.Village dest = Villages.get(to);
        if (sent >= want || dest == null) return;
        List<String> names = new ArrayList<>();
        for (VillageFolkEntity g : WarFooting.militia(ally.id())) {
            if (sent >= want) break;
            if (g.trip() != null || Scouts.out(g) || g.isSleeping() || awayOnGarrison(g)) continue;
            g.clearQueue();
            g.getNavigation().stop();
            Caravans.Trip t = new Caravans.Trip(ally.id(), to, Caravans.way(ally, dest));
            t.errand = Envoys.Errand.WAR;
            t.gainedTick = g.tickCount;
            g.trip(t);
            WarBooks.push(to, "wp.garrison", g.getUUID() + "|" + ally.id() + "|" + foe + "|" + day, 12);
            FolkTalk.speak(g, "Off to " + name(to) + ", to stand on our allies' walls.");
            g.persona().remember(day, "I went to stand guard on " + name(to) + "'s walls in its war with " + name(foe), 5);
            names.add(g.displayNameCap());
            sent++;
        }
        Ledger.note(ally.id(), "wp.pledge/" + to, foe + "|" + want + "|" + sent + "|" + (p.length > 3 ? p[3] : day));
        if (!names.isEmpty()) Villages.tell(ally.id(), day, String.join(" and ", names) + " set out to stand on our allies' walls in " + name(to));
    }

    /** This town's garrison of allied guards: {guard, ally, enemy, day}. */
    static List<String[]> garrison(UUID host) {
        List<String[]> out = new ArrayList<>();
        for (String e : WarBooks.list(host, "wp.garrison")) {
            String[] p = e.split("\\|");
            if (p.length >= 4) out.add(p);
        }
        return out;
    }

    static boolean inGarrison(UUID host, UUID guard) {
        String g = guard.toString();
        for (String[] p : garrison(host)) if (p[0].equals(g)) return true;
        return false;
    }

    /** How many allied guards are in this town's garrison (written down: what rumour tells of). */
    static int garrisonCount(UUID host) {
        return garrison(host).size();
    }

    /** The allied guards standing on this town's walls now. */
    public static List<VillageFolkEntity> garrisonAt(ServerLevel level, UUID host) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (String[] p : garrison(host)) {
            UUID id = WarBooks.id(p[0]);
            if (id != null && level.getEntity(id) instanceof VillageFolkEntity g && g.isAlive() && g.trip() != null && g.trip().waiting
                    && host.equals(g.trip().to)) out.add(g);
        }
        return out;
    }

    /** Is this guard away on an ally's walls (or on the road there)? */
    public static boolean awayOnGarrison(VillageFolkEntity g) {
        Caravans.Trip t = g.trip();
        return t != null && t.errand == Envoys.Errand.WAR && !t.to.equals(g.ownerId()) && inGarrison(t.to, g.getUUID());
    }

    /**
     * [Envoys.arrived] An allied guard come to the town's walls takes its place in the garrison (no
     * audience: it reports to the watch); one come home from them is done. False for any other envoy.
     */
    static boolean garrisonArrived(ServerLevel level, VillageFolkEntity f, Caravans.Trip t) {
        if (t.errand != Envoys.Errand.WAR) return false;
        long day = level.getDayTime() / 24000L;
        if (t.to.equals(f.ownerId())) {
            List<String> home = WarBooks.list(t.to, "wp.homeward");
            if (!home.remove(f.getUUID().toString())) return false;
            WarBooks.list(t.to, "wp.homeward", home);
            f.trip(null);
            Villages.tell(t.to, day, f.displayNameCap() + " came home from standing guard on " + name(t.from) + "'s walls");
            FolkTalk.speak(f, "Home again! It's good to see our own walls.");
            return true;
        }
        if (!inGarrison(t.to, f.getUUID())) return false;
        t.waiting = true;
        t.waitSince = level.getGameTime();
        Villages.tell(t.to, day, f.displayNameCap() + ", a guard of our allies in " + name(t.from) + ", came to stand on our walls");
        FolkTalk.speak(f, "Reporting from " + name(t.from) + "! Show me where to stand.");
        for (String[] p : garrison(t.to)) {
            UUID foe = WarBooks.id(p[2]);
            if (p[0].equals(f.getUUID().toString()) && foe != null) WarBooks.course(t.to, foe, day, f.displayNameCap() + " of " + name(t.from) + " joined our garrison");
        }
        return true;
    }

    /**
     * [Envoys.waitThere] An allied guard in the garrison: on its post facing the enemy (inside the gate
     * that looks their way, else out from the board toward them) for as long as the war lasts; at the
     * peace, home. False for any other envoy.
     */
    static boolean onGarrison(ServerLevel level, VillageFolkEntity f, Caravans.Trip t) {
        if (t.errand != Envoys.Errand.WAR || t.to.equals(f.ownerId()) || !inGarrison(t.to, f.getUUID())) return false;
        UUID foe = null;
        int index = 0, i = 0;
        for (String[] p : garrison(t.to)) {
            if (p[0].equals(f.getUUID().toString())) { foe = WarBooks.id(p[2]); index = i; }
            i++;
        }
        Villages.Village host = Villages.get(t.to), enemy = foe == null ? null : Villages.get(foe);
        if (foe == null || host == null || !Wars.atWar(t.to, foe)) {
            sendHome(level, f, t.to);
            return true;
        }
        BlockPos post = post(level, host, enemy, index);
        if (f.blockPosition().distSqr(post) > 9) {
            if (f.getNavigation().isDone() || f.tickCount % 100 == 0) f.walkTo(post, 0.7D);
        } else if (enemy != null) {
            f.getLookControl().setLookAt(enemy.centre().getX() + 0.5, post.getY() + 1.5, enemy.centre().getZ() + 0.5);
        }
        return true;
    }

    /** Where an allied guard stands: inside the gate that faces the enemy, else out from the board toward them, side by side. */
    static BlockPos post(ServerLevel level, Villages.Village host, @Nullable Villages.Village enemy, int index) {
        Direction toward = Direction.NORTH;
        if (enemy != null) {
            double dx = enemy.centre().getX() - host.centre().getX(), dz = enemy.centre().getZ() - host.centre().getZ();
            toward = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        }
        for (Watch.Gate g : Watch.gates(level, host.id())) {
            if (g.out() == toward) return g.inside().relative(toward.getClockWise(), index % 3 - 1);
        }
        BlockPos from = VillageBoards.lectern(host.id());
        if (from == null) from = host.centre();
        BlockPos p = from.relative(toward, 6).relative(toward.getClockWise(), index % 3 - 1);
        return new BlockPos(p.getX(), level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ());
    }

    /** The war is over: an allied guard off the town's walls and home. */
    static void sendHome(ServerLevel level, VillageFolkEntity f, UUID host) {
        List<String> left = new ArrayList<>();
        for (String e : WarBooks.list(host, "wp.garrison")) if (!e.startsWith(f.getUUID().toString())) left.add(e);
        WarBooks.list(host, "wp.garrison", left);
        UUID home = f.ownerId();
        Villages.Village from = Villages.get(host), to = home == null ? null : Villages.get(home);
        if (from == null || to == null) {
            f.trip(null);
            return;
        }
        Caravans.Trip t = new Caravans.Trip(host, home, Caravans.way(from, to));
        t.errand = Envoys.Errand.WAR;
        t.gainedTick = f.tickCount;
        f.trip(t);
        WarBooks.push(home, "wp.homeward", f.getUUID().toString(), 12);
        FolkTalk.speak(f, "The war's over. Home to " + name(home) + "!");
    }

    /**
     * The garrison seen to once a day: a guard that lost its trip in a restart, still in the town, takes up
     * its post again; at the peace, any still here go home; one that is nowhere to be found is let go.
     */
    static void keepGarrison(ServerLevel level, Villages.Village v, long day) {
        for (String[] p : garrison(v.id())) {
            UUID id = WarBooks.id(p[0]), ally = WarBooks.id(p[1]), foe = WarBooks.id(p[2]);
            boolean war = foe != null && Wars.atWar(v.id(), foe);
            VillageFolkEntity g = id != null && level.getEntity(id) instanceof VillageFolkEntity f && f.isAlive() ? f : null;
            if (g == null) {
                if (!war) sendHomeGone(v.id(), p[0]);
                continue;
            }
            if (!war) {
                // Still on the walls: home. Lost its trip in a restart: home on its own feet if it is here, else let go.
                if (g.trip() != null && g.trip().waiting || g.trip() == null && g.blockPosition().distSqr(v.centre()) < 96 * 96) sendHome(level, g, v.id());
                else if (g.trip() == null) sendHomeGone(v.id(), p[0]);
            } else if (g.trip() == null && ally != null && g.blockPosition().distSqr(v.centre()) < 96 * 96) {
                Villages.Village av = Villages.get(ally);
                if (av == null) continue;
                Caravans.Trip t = new Caravans.Trip(ally, v.id(), Caravans.way(av, v));
                t.errand = Envoys.Errand.WAR;
                t.at = t.way.size();
                t.waiting = true;
                t.waitSince = level.getGameTime();
                g.trip(t);
            }
        }
    }

    private static void sendHomeGone(UUID host, String guard) {
        List<String> left = new ArrayList<>();
        for (String e : WarBooks.list(host, "wp.garrison")) if (!e.startsWith(guard)) left.add(e);
        WarBooks.list(host, "wp.garrison", left);
    }

    // ------------------------------------------------------------------ the war's days

    /** How long an elder lets a war stand before it will talk: a soft one three days, a prickly one a week. */
    static int standDays(UUID v) {
        return switch (Envoys.temper(v)) {
            case PRICKLY -> 7;
            case SHREWD -> 6;
            case STEADY, CURIOUS -> 5;
            case WARY -> 4;
            default -> 3;
        };
    }

    /**
     * The day's cost of the war footing, in the war's book (once a day): the danger pay on top of the wages and
     * the hours of work lost to the volunteers and the militia's muster, in coin, as the preparations reckon it
     * (WarFooting.dailyCost); with nothing reckoned there (a town whose footing nobody has set), the work lost
     * of every hand on the watch over what it kept before the war. And the scouts' latest report, if there is a
     * new one, in its course.
     */
    static void account(ServerLevel level, UUID us, UUID them, long day) {
        WarBooks.Book b = WarBooks.book(us, them);
        if (b == null) return;
        boolean changed = false;
        if (b.costDay < day && !Villages.folkOf(us).isEmpty()) {
            int cost = WarFooting.dailyCost(us).coins();
            if (cost <= 0) cost = Math.max(0, WarFooting.militia(us).size() - b.watch) * WORK_LOST;
            b.cost += cost;
            b.costDay = day;
            changed = true;
        }
        Intel.Report r = Intel.latest(us, them);
        if (r != null && r.day() > b.intelDay) {
            b.intelDay = r.day();
            changed = true;
            WarBooks.course(us, them, r.day(), "the scouts reported from " + name(them) + ": " + r.guards() + " guards ("
                + r.armoured() + " in iron, " + r.archers() + " with bows), " + (r.walls() > 0 ? "a wall" : "no wall") + ", food for "
                + r.foodDays() + " days");
        }
        if (changed) WarBooks.save(us, them, b);
    }

    /** Is the cost of the war footing telling on this town (by its books)? */
    static boolean tired(UUID v, WarBooks.Book b, long day) {
        return b.cost >= Math.max(15, Ledger.coins(v) / 4) || day - b.began >= standDays(v) + 5;
    }

    /**
     * A day of the war between them: each side's costs; then, once a side has let the war stand its
     * while, the one that reckons itself the weaker (its own count against its reckoning of them) sues for
     * peace; if neither does, and the cost has told, the one it tells on most.
     */
    static void warDay(ServerLevel level, Villages.Village a, Villages.Village b, long day) {
        UUID x = a.id(), y = b.id();
        // A war begun on the shared seam alone (Wars.begin, with no declaration): its pages opened now, neither side's.
        for (UUID[] p : new UUID[][]{ { x, y }, { y, x } }) {
            if (WarBooks.book(p[0], p[1]) != null) continue;
            WarBooks.Book nb = new WarBooks.Book();
            nb.began = Math.max(0, Wars.since(p[0], p[1]));
            nb.goalText = "to stand firm";
            nb.watch = WarFooting.militia(p[0]).size();
            WarBooks.save(p[0], p[1], nb);
        }
        account(level, x, y, day);
        account(level, y, x, day);
        if (Envoys.travelling(x, y)) return;                          // a white flag (or a herald) already on the road
        WarBooks.Book bx = WarBooks.book(x, y), by = WarBooks.book(y, x);
        if (bx == null || by == null) return;
        if (!on()) {                                                   // wars switched off: everybody talks
            if (day - bx.lastPeaceTry >= 2) sue(level, a, b, day, "wars are over");
            return;
        }
        // A town that has voted for peace (its peace candidate elected) talks, whatever its elder's temper.
        if (mandate(x, day) && day - bx.lastPeaceTry >= 2) { sue(level, a, b, day, "the town voted for peace"); return; }
        if (mandate(y, day) && day - by.lastPeaceTry >= 2) { sue(level, b, a, day, "the town voted for peace"); return; }
        boolean xStood = day - bx.began >= standDays(x), yStood = day - by.began >= standDays(y);
        double rx = own(level, x).strength() / (double) Math.max(1, of(level, x, y, day).strength());
        double ry = own(level, y).strength() / (double) Math.max(1, of(level, y, x, day).strength());
        boolean xWeak = xStood && rx < 0.8 && day - bx.lastPeaceTry >= 2, yWeak = yStood && ry < 0.8 && day - by.lastPeaceTry >= 2;
        if (xWeak && (!yWeak || rx <= ry)) { sue(level, a, b, day, "we are the weaker"); return; }
        if (yWeak) { sue(level, b, a, day, "we are the weaker"); return; }
        if (!xStood || !yStood) return;
        boolean xTired = tired(x, bx, day) && day - bx.lastPeaceTry >= 2, yTired = tired(y, by, day) && day - by.lastPeaceTry >= 2;
        if (!xTired && !yTired) return;
        double bitesX = bx.cost / (double) Math.max(1, Ledger.coins(x)), bitesY = by.cost / (double) Math.max(1, Ledger.coins(y));
        if (xTired && (!yTired || bitesX >= bitesY)) sue(level, a, b, day, "the war footing has cost us " + bx.cost + " coins");
        else sue(level, b, a, day, "the war footing has cost us " + by.cost + " coins");
    }

    /** Under a white flag: an envoy (the elder itself, as a rule) to sue for peace. Whether one went. */
    static boolean sue(ServerLevel level, Villages.Village us, Villages.Village them, long day, String why) {
        UUID x = us.id(), y = them.id();
        WarBooks.Book b = WarBooks.book(x, y);
        if (b == null || Envoys.travelling(x, y)) return false;
        if (!Envoys.send(level, us, them, Envoys.Errand.PEACE, day)) return false;
        b.peaceTries++;
        b.lastPeaceTry = day;
        WarBooks.save(x, y, b);
        Villages.tell(x, day, "the elder sent word to " + name(y) + " under a white flag, to talk peace: " + why);
        WarBooks.course(x, y, day, "we sued for peace under a white flag: " + why);
        WarBooks.course(y, x, day, name(x) + " sent a white flag, to talk peace");
        LOG.info("[MCA-WAR] {} sues {} for peace: {}", name(x), name(y), why);
        return true;
    }

    /** [Envoys.send] Coin a suing envoy carries to pay what its side will concede (the elder's own reckoning of the terms). */
    static int peacePurse(ServerLevel level, UUID from, UUID to, Envoys.Errand errand) {
        if (errand != Envoys.Errand.PEACE || !Wars.atWar(from, to)) return 0;
        Terms t = terms(level, from, to);
        if (t.coins() > 0 && t.defender().equals(from)) return Ledger.takeCoins(from, t.coins());
        if (t.reparations() > 0 && t.aggressor().equals(from)) return Ledger.takeCoins(from, t.reparations());
        return 0;
    }

    /**
     * The terms, from what the war was for and the balance of strength between the two as each knows its
     * own: the aggressor half as strong again, the whole of its goal; a tenth stronger, the part of it;
     * else no gain, and its demands withdrawn.
     */
    public static Terms terms(ServerLevel level, UUID x, UUID y) {
        WarBooks.Book bx = WarBooks.book(x, y), by = WarBooks.book(y, x);
        UUID aggressor = bx != null && bx.aggressor ? x : by != null && by.aggressor ? y : x;
        UUID defender = aggressor.equals(x) ? y : x;
        WarBooks.Book ab = aggressor.equals(x) ? bx : by;
        Goal goal = ab == null ? Goal.DEFENCE : ab.goal;
        double balance = own(level, aggressor).strength() / (double) Math.max(1, own(level, defender).strength());
        int share = goal == Goal.DEFENCE ? 0 : balance >= 1.5 ? 2 : balance >= 1.1 ? 1 : 0;
        int coins = 0;
        if (share > 0 && ab != null && (goal == Goal.TRIBUTE || goal == Goal.REVENGE)) coins = share >= 2 ? ab.amount : ab.amount / 2;
        // An aggressor that gains nothing and is much the weaker pays for the trouble it made: a day's coin for each
        // day of the war and five more, out of a fifth of its treasury at most.
        int reparations = 0;
        if (share == 0 && ab != null && ab.aggressor && balance <= 2.0 / 3.0) {
            long days = Math.max(0, today() - ab.began);
            reparations = (int) Math.min(Math.max(0, Ledger.coins(aggressor) / 5), 5 + days);
        }
        String an = name(aggressor), dn = name(defender);
        String what = ab == null ? "" : words(ab.goalText);
        String words = share >= 2 ? dn + " concedes the whole of " + an + "'s demand: " + what
            : share == 1 ? dn + " concedes part of " + an + "'s demand (" + what + ")"
            : reparations > 0 ? "no gain to " + an + ", which withdraws its demands and pays " + dn + " " + reparations + " coins in reparations"
            : "no gain to either: " + an + " withdraws its demands";
        return new Terms(aggressor, defender, goal, balance, share, coins, reparations, words);
    }

    /**
     * Will the host make peace? Out of a war that has stood its while for it, yes; a host that reckons
     * itself the weaker, or a soft elder, at once; a hard one that has not yet had its war, not yet.
     */
    static boolean acceptPeace(ServerLevel level, UUID host, UUID from, long day) {
        if (!on() || mandate(host, day)) return true;
        WarBooks.Book b = WarBooks.book(host, from);
        if (b == null || day - b.began >= standDays(host)) return true;
        if (own(level, host).strength() < of(level, host, from, day).strength()) return true;
        return Envoys.temper(host).kindly();
    }

    static Envoys.Answer peaceTalks(ServerLevel level, UUID host, UUID from, VillageFolkEntity envoy, Caravans.Trip t, long day) {
        if (!acceptPeace(level, host, from, day)) {
            return new Envoys.Answer(false, FolkTalk.pick(level.getRandom(), "Not yet. Let " + name(from) + " stand on its walls a while longer.",
                "Peace? When we're ready to talk, you'll hear from us."), () -> {
                WarBooks.course(host, from, day, "we sent " + name(from) + "'s white flag home: not yet");
                WarBooks.course(from, host, day, name(host) + " would not talk peace yet");
                t.outcome = "they would not talk peace yet";
            });
        }
        Terms terms = terms(level, from, host);
        return new Envoys.Answer(true, "Then let there be peace: " + terms.words() + ".", () -> {
            Envoys.unloadGifts(level, envoy, host);
            String done = makePeace(level, from, host, day, terms, "under a white flag", t);
            t.outcome = "we made peace: " + done;
        });
    }

    /**
     * Peace between them, on these terms: the war ended on both books, what is conceded given, the treaty
     * written into both towns' books and onto both boards (its peace kept for a town's year, a truce while
     * it lasts), the banners down, the allies' guards home, the militia stood down (onPeace), and the bell
     * rung for it. {@code how}: "under a white flag", "on Steve's word". {@code t}: the envoy's trip, which
     * carries any coin. The treaty's terms in words, as signed.
     */
    public static String makePeace(ServerLevel level, UUID x, UUID y, long day, Terms terms, String how, @Nullable Caravans.Trip t) {
        if (!Wars.atWar(x, y)) return "";
        UUID a = terms.aggressor(), d = terms.defender();
        WarBooks.Book ab = WarBooks.book(a, d), db = WarBooks.book(d, a);
        String given = terms.share() > 0 && ab != null ? meet(level, a, d, ab.goal, ab.amount, ab.goalText, terms.share(), t, day) : "nothing";
        int repaid = terms.reparations() > 0 ? pay(a, d, terms.reparations(), t) : 0;
        // The spies each holds of the other's, sent home (Spies).
        int freed = Spies.exchange(level, a, d);
        String text = terms.share() >= 2 ? name(d) + " concedes " + given
            : terms.share() == 1 ? name(d) + " concedes part: " + given
            : repaid > 0 ? "no gain to " + name(a) + ", which withdraws its demands and pays " + repaid + " coins in reparations"
            : "no gain to either; " + name(a) + " withdraws its demands";
        if (freed > 0) text += "; " + freed + (freed == 1 ? " captive" : " captives") + " sent home";
        text += "; peace until day " + (day + TREATY_DAYS);
        Wars.end(a, d);
        Arms.peace(level, terms.share() >= 1 ? a : repaid > 0 ? d : null, terms.share() >= 1 ? d : a);   // [arms] a charge for the war won
        WarBooks.treaty(a, d, day, day + TREATY_DAYS, text);
        // The treaty's peace is a truce for as long as it lasts: no brawls at the boundary, no falling back into a feud.
        Ledger.note(a, "truce/" + d, Long.toString(day + TREATY_DAYS));
        Ledger.note(d, "truce/" + a, Long.toString(day + TREATY_DAYS));
        int r = Ledger.relation(a, d);
        if (r < -30) Ledger.relate(a, d, -30 - r);
        Diplomacy.announce(a, d, Ledger.relation(a, d), day);
        Bonds.remember(a, d, day, 4, "we signed the treaty of day " + day);
        WarBooks.forgive(a, d);
        WarBooks.forgive(d, a);
        String key = Ledger.pair(a, d);
        for (Villages.Village n : Villages.every()) Ledger.forget(n.id(), "wp.side/" + key);
        for (UUID side : new UUID[]{ a, d }) {
            UUID other = side.equals(a) ? d : a;
            WarBooks.Book b = side.equals(a) ? ab : db;
            long began = b == null ? day : b.began;
            WarBooks.course(side, other, day, "the treaty, " + how + ": " + text);
            List<String> course = WarBooks.course(side, other);
            course.add(0, "The war with " + name(other) + ", day " + began + " to day " + day);
            WarBooks.list(side, "wp.lastcourse", course);
            WarBooks.past(side, "day " + began + " to " + day + ": the war with " + name(other) + " (" + (b != null && b.aggressor ? "ours, for " + words(b.goalText)
                : "theirs, against us") + "), " + (day - began) + " days, the war footing cost " + (b == null ? 0 : b.cost) + " coins; " + text);
            WarBooks.close(side, other);
            Ledger.note(side, "wp.lastwar", Long.toString(day));
            Ledger.forget(side, "wp.calling/" + other);
            Ledger.forget(side, "wp.early/" + other);
            tense(side, other, false);
            // The allies' part is done: their pledges let go, their guards on our walls sent home.
            List<String> allies = new ArrayList<>();
            for (String e : WarBooks.list(side, "wp.allies")) {
                String[] p = e.split("\\|");
                if (p.length > 1 && p[1].equals(other.toString())) {
                    UUID ally = WarBooks.id(p[0]);
                    if (ally != null) {
                        Ledger.forget(ally, "wp.pledge/" + side);
                        tense(ally, side, false);
                    }
                } else allies.add(e);
            }
            WarBooks.list(side, "wp.allies", allies);
            for (String[] p : garrison(side)) {
                UUID id = WarBooks.id(p[0]);
                if (p[2].equals(other.toString()) && id != null && level.getEntity(id) instanceof VillageFolkEntity g) sendHome(level, g, side);
            }
            Villages.Village v = Villages.get(side);
            if (v != null) {
                if (WarBanner.takeDown(level, v, other)) Villages.tell(side, day, "the war banner was taken down and put away");
                ring(level, v, false);
                Raids.tellNear(level, v.centre(), 200, Component.literal("Peace between " + name(a) + " and " + name(d) + ", " + how + ": " + text + ".")
                    .withStyle(ChatFormatting.GREEN), false);
            }
            Villages.tell(side, day, name(a) + " and " + name(d) + " made peace " + how + ": " + text);
            memorialise(level, side, other, began, day);
            feast(level, side, other, day);
            onPeace(level, side);
        }
        // [fireworks] The war won: the winner's feast for the peace is a victory, and its fireworks maker makes for it.
        UUID won = terms.share() >= 1 ? a : repaid > 0 ? d : null;
        if (won != null) FireworksMaker.victory(won, won.equals(a) ? d : a, feastDay(won));
        LOG.info("[MCA-WAR] peace between {} and {} {} (balance {}): {}", name(a), name(d), how, String.format(Locale.ROOT, "%.2f", terms.balance()), text);
        return text;
    }

    /**
     * A town at war with nobody any more stands down, there and then rather than at the next morning's
     * muster: the militia's arms back to the armoury and the hands to their trades (Militia.standDown), the
     * volunteers off the watch to the trades they left (WarFooting.sendHome), the footing told; danger pay
     * ends with the footing. The gates rehung if any were lost (out of the stores), and the town back to its
     * peace order (the defences not begun go from the head of its list: WarWorks.wanted). Whatever else
     * listens for the peace is told. A town still on its guard against somebody else stays on it.
     */
    public static void onPeace(ServerLevel level, UUID village) {
        if (!Wars.enemies(village).isEmpty()) return;
        long day = level.getDayTime() / 24000L;
        Villages.Village v = Villages.get(village);
        if (v != null && level.isLoaded(v.centre())) Watch.keep(level, v, false);
        if (v != null && WarFooting.footing(village) == Wars.Footing.PEACE) {
            Wars.Footing was = WarFooting.lastFooting(village);
            Militia.standDown(level, v, day);
            WarFooting.sendHome(level, village, day);
            if (was != Wars.Footing.PEACE) {
                WarFooting.announce(level, v, day, was, Wars.Footing.PEACE);
                Ledger.note(village, "war.footing", Wars.Footing.PEACE.name() + "|" + day);
            }
            String next = Villages.nextProject(village);
            Villages.tell(village, day, "the town turned back to its peacetime work" + (next == null ? "" : ": next to go up is " + Villages.spoken(next)));
        }
        Ledger.forget(village, "wp.peacecand");
        Ledger.note(village, "wp.stooddown", Long.toString(day));
        for (BiConsumer<ServerLevel, UUID> l : STAND_DOWN) Guard.run("stand down", () -> l.accept(level, village));
    }

    // ------------------------------------------------------------------ war-weariness

    /** Weary enough that some folk look for work elsewhere (JobSeekers), and a peace candidate stands. */
    static final int WEARY = 50;
    /** So weary that folk up and leave for a town at peace, and an early election is called. */
    static final int WORN_OUT = 70;
    /** How much a town's weariness eases a day at peace. */
    static final int EASES = 5;

    /** How weary of war the town is, 0 (not at all) to 100 (worn out). */
    public static int weariness(@Nullable UUID village) {
        return village == null ? 0 : (int) Math.max(0, Math.min(100, WarBooks.num(Ledger.note(village, "wp.weary"), 0)));
    }

    static void weariness(UUID village, int w) {
        Ledger.note(village, "wp.weary", Integer.toString(Math.max(0, Math.min(100, w))));
    }

    /** What wore the town down the last day of its war, in words ("" at peace). */
    public static String wearyWhy(UUID village) {
        String s = Ledger.note(village, "wp.weary.why");
        return s == null ? "" : s;
    }

    /** Weary enough of the war that a folk would look for a fresh start elsewhere (JobSeekers). */
    public static boolean wearyOfWar(@Nullable UUID village) {
        return village != null && weariness(village) >= WEARY && !Wars.enemies(village).isEmpty();
    }

    static String wearyWord(int w) {
        return w >= 85 ? "worn out by the war" : w >= WORN_OUT ? "sick of the war" : w >= WEARY ? "weary of the war"
            : w >= 25 ? "tiring of the war" : w > 0 ? "uneasy" : "at peace with itself";
    }

    /** One day's wear, and what did it. */
    record Wear(int by, List<String> why) {}

    /**
     * What a day of its war takes out of a town: the war's length (a day more each day, more after ten days and
     * twenty); its cost (WarFooting.dailyCost: the danger pay and the hours lost to the militia, in coin); the
     * day of rest given to the militia's drill instead of the games; hunger, short commons, or the larder kept
     * for a siege (Leader.plan); trade lost with the enemy (a pact or a deal it once had); an enemy stronger than
     * itself, as it reckons them; its spies held by the enemy; and its own guards away on an ally's walls (an ally
     * pledged is worn by that too, at war or not).
     */
    static Wear wear(ServerLevel level, UUID id, long day) {
        List<String> why = new ArrayList<>();
        int by = 0;
        List<UUID> foes = Wars.enemies(id);
        int away = 0;
        for (VillageFolkEntity g : WarFooting.militia(id)) if (awayOnGarrison(g)) away++;
        if (foes.isEmpty() && away == 0) return new Wear(0, why);
        if (!foes.isEmpty()) {
            long since = Long.MAX_VALUE;
            for (UUID e : foes) since = Math.min(since, Math.max(0, Wars.since(id, e)));
            long length = Math.max(0, day - since);
            by += length >= 20 ? 3 : length >= 10 ? 2 : 1;
            why.add("the war, " + length + (length == 1 ? " day" : " days") + " long");
            WarFooting.Cost c = WarFooting.dailyCost(id);
            // A point for any cost at all, one more for every six coins of it a day, and one for a working day lost; four at most.
            int cost = c.coins() <= 0 && c.hoursLost() <= 0 ? 0 : Math.min(4, 1 + c.coins() / 6 + (c.hoursLost() >= 10.0 ? 1 : 0));
            if (cost > 0) {
                by += cost;
                why.add("its cost (" + c.coins() + " coins a day in danger pay and work lost)");
            }
            if (RestDay.today(id, day) && !Militia.members(id).isEmpty()) {
                by += 2;
                why.add("the militia drilling on the day of rest instead of the games");
            }
            Leader.Plan plan = Leader.plan(id);
            if (plan == Leader.Plan.FAMINE) { by += 4; why.add("hunger"); }
            else if (plan == Leader.Plan.SHORT) { by += 2; why.add("short commons"); }
            else if (plan == Leader.Plan.WAR) { by += 1; why.add("the larder kept for a siege"); }
            for (UUID e : foes) {
                if (Ledger.note(id, "pact/" + e) != null || Ledger.note(id, "deal/" + e) != null || Ledger.note(id, "talks.last/" + e) != null) {
                    by += 1;
                    why.add("the trade with " + name(e) + " lost");
                    break;
                }
            }
            for (UUID e : foes) {
                Reckoning theirs = of(level, id, e, day);
                if (own(level, id).strength() < theirs.strength() * 0.8) {
                    by += 2;
                    why.add(name(e) + " the stronger (" + theirs.source() + ")");
                    break;
                }
            }
            int held = 0;
            for (Spies.Captive c2 : Spies.ofOurs(id)) if (foes.contains(c2.holder())) held++;
            if (held > 0) {
                by += 2 * held;
                why.add(held + (held == 1 ? " of ours" : " of ours") + " held by the enemy");
            }
        }
        if (away > 0) {
            by += away;
            why.add(away + (away == 1 ? " of our guards" : " of our guards") + " away on our allies' walls");
        }
        return new Wear(by, why);
    }

    /** The town's spirits, once a day: worn by its war, or easing at peace. */
    static void wearyDay(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (WarBooks.num(Ledger.note(id, "wp.weary.day"), -1) >= day) return;
        Ledger.note(id, "wp.weary.day", Long.toString(day));
        int w = weariness(id);
        Wear wear = wear(level, id, day);
        if (wear.by() > 0) {
            weariness(id, w + wear.by());
            Ledger.note(id, "wp.weary.why", String.join(", ", wear.why()));
            int now = weariness(id);
            for (UUID e : Wars.enemies(id)) {
                if (w < WEARY && now >= WEARY) WarBooks.course(id, e, day, "the town grew weary of the war: " + String.join(", ", wear.why()));
                if (w < WORN_OUT && now >= WORN_OUT) WarBooks.course(id, e, day, "the town is sick of the war");
            }
            if (w < WEARY && now >= WEARY) Villages.tell(id, day, "the town is weary of the war: " + String.join(", ", wear.why()));
        } else if (w > 0) {
            weariness(id, w - EASES);
            if (weariness(id) == 0) Ledger.forget(id, "wp.weary.why");
        }
    }

    /**
     * Worn out by a long war: once in three days a folk with least to keep it (Contentment.leaver) packs up and
     * goes to the happiest neighbour at peace with room for it. Never below a town of eight, never to the
     * enemy, and only in a war that has lasted a week.
     */
    static void leave(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (weariness(id) < WORN_OUT || Wars.enemies(id).isEmpty()) return;
        long since = Long.MAX_VALUE;
        for (UUID e : Wars.enemies(id)) since = Math.min(since, Wars.since(id, e));
        if (day - since < 7 || day - WarBooks.num(Ledger.note(id, "wp.left"), -100) < 3) return;
        if (Villages.headcount(id) <= Contentment.KEEP_AT_LEAST) return;
        Villages.Village to = null;
        int best = 40;
        for (Villages.Village o : Diplomacy.neighboursOf(id)) {
            if (!Wars.enemies(o.id()).isEmpty() || Wars.footing(o.id()) == Wars.Footing.WAR) continue;
            if (Ledger.relation(id, o.id()) <= Diplomacy.UNEASY || Villages.headcount(o.id()) >= Villages.housing(o.id())) continue;
            int s = Contentment.score(o.id());
            if (s > best) { best = s; to = o; }
        }
        if (to == null) return;
        VillageFolkEntity who = Contentment.leaver(id);
        if (who == null) return;
        Ledger.note(id, "wp.left", Long.toString(day));
        String name = who.displayNameCap(), there = name(to.id());
        FolkTalk.speak(who, FolkTalk.pick(who.getRandom(), "I can't stand another day of this war. I'm off to " + there + ".",
            "Enough. " + there + " is at peace, and that's where I'll be."));
        who.persona().remember(day, "I left " + name(id) + " for " + there + ", sick of the war", 8);
        who.leaveFor(level, to);
        Villages.tell(id, day, name + " left for " + there + ", sick of the war");
        Villages.tell(to.id(), day, name + " came from " + name(id) + " to live here, away from the war");
        for (UUID e : Wars.enemies(id)) WarBooks.course(id, e, day, name + " left for " + there + ", sick of the war");
        LOG.info("[MCA-WAR] {} leaves {} for {} (weariness {})", name, name(id), there, weariness(id));
    }

    /** [Contentment.compute] The war on the town's spirits: less content the wearier it is. */
    public static int contentment(UUID village, List<String> good, List<String> bad) {
        int w = weariness(village);
        if (w < 10) return 0;
        bad.add(0, w >= WORN_OUT ? "folk are sick of the war" : w >= WEARY ? "the war wears on us" : "the war");
        return -Math.min(12, w / 8);
    }

    /**
     * [VillageFolkEntity mood] A folk's spirits in a war: low, the more so the wearier the town and the more
     * it cares for its wages or its leisure; a Guardian early in a war stands the taller for it.
     */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        UUID v = f.ownerId();
        if (v == null || f.isBaby()) return m;
        int w = weariness(v);
        if (w < 10) return m;                          // (a town at peace, or a war only begun: nothing read)
        Values.Value cares = Values.top(f);
        if (cares == Values.Value.SAFETY && w < WEARY && !Wars.enemies(v).isEmpty()) {
            why.add(new Object[]{ "warproud", 2 });
            return m + 2;
        }
        int hit = Math.min(12, 1 + w / 10) + (cares == Values.Value.WEALTH || cares == Values.Value.LEISURE ? 2 : 0);
        why.add(new Object[]{ "warweary", hit });
        return m - hit;
    }

    /** [FolkTalk] What a folk says of the war, when it is on its mind. */
    public static String moodWords(VillageFolkEntity f, String key) {
        UUID v = f.ownerId();
        List<UUID> foes = v == null ? List.of() : Wars.enemies(v);
        String them = foes.isEmpty() ? "them" : name(foes.get(0));
        RandomSource r = f.getRandom();
        if (key.equals("warproud")) {
            return FolkTalk.pick(r, "Let " + them + " come. Our walls will hold.", "I'm proud of our watch. " + capital(them) + " won't scare us.");
        }
        int w = v == null ? 0 : weariness(v);
        if (foes.isEmpty()) return FolkTalk.pick(r, "The war's over, but I've not got over it.", "I still lie awake thinking about that war.");
        if (w >= WORN_OUT) return FolkTalk.pick(r, "This war with " + them + "... I'm sick to death of it.",
            "If it goes on much longer I'm packing up and going somewhere at peace.");
        return FolkTalk.pick(r, "This war with " + them + " wears on everybody.", "When will it end, this war?",
            "Half the town on the walls and nobody at their work. It can't go on.");
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** [TownMeeting] The leader's answer to a grumble about the war at the town meeting. */
    public static String meetingAnswer(UUID village) {
        List<UUID> foes = Wars.enemies(village);
        if (foes.isEmpty()) return "The war is over. We'll mend what it cost us, and remember who it cost.";
        String them = name(foes.get(0));
        if (mandate(village, today())) return "You voted for peace, and we've sent to " + them + " under a white flag. It's coming.";
        if (weariness(village) >= WORN_OUT) return "I hear you. If " + them + " will talk, we'll talk. And there's an election to be had.";
        return "I know it's hard. We'll talk peace with " + them + " the day they'll talk sense.";
    }

    // ------------------------------------------------------------------ the wartime election

    /** The folk standing for peace at the town's next election, or null. */
    @Nullable
    public static UUID peaceCandidate(UUID village) {
        String s = Ledger.note(village, "wp.peacecand");
        return s == null || s.isEmpty() ? null : WarBooks.id(s.split("\\|")[0]);
    }

    /** Who led the town into its war (its elder on declaration day), or null. */
    @Nullable
    static UUID warLeader(UUID village) {
        return WarBooks.id(Ledger.note(village, "wp.warleader"));
    }

    /** Is the war going well for the town: every enemy reckoned a good deal the weaker? */
    static boolean goingWell(ServerLevel level, UUID village) {
        List<UUID> foes = Wars.enemies(village);
        if (foes.isEmpty()) return false;
        long day = level.getDayTime() / 24000L;
        int mine = own(level, village).strength();
        for (UUID e : foes) if (mine < of(level, village, e, day).strength() * 1.25) return false;
        return true;
    }

    /** How much a folk leans for peace: by what it cares about, its nature, and kin over there. */
    static int dove(VillageFolkEntity f, @Nullable UUID enemy) {
        int d = switch (Values.top(f)) {
            case SAFETY -> -10;
            case TRADITION -> -3;
            case PROGRESS -> 2;
            case FOOD, HOMES -> 6;
            case WEALTH, LEISURE -> 8;
        };
        if (f.life().has(Social.Trait.GRUMPY)) d -= 3;
        if (f.life().has(Social.Trait.GENEROUS) || f.life().has(Social.Trait.CHEERFUL)) d += 3;
        if (enemy != null && JobSeekers.kinIn(f, enemy) != null) d += 6;
        return d;
    }

    /**
     * [Elections.nominate] A town at war and weary of it puts up somebody for peace: the most dovish of its
     * grown folk the town thinks well of (never the leader who took it to war), standing for what it cares
     * about and pledged to make peace. If it stands already it takes up the peace; else it stands in place of
     * the last of the others (or beside them, if there is room).
     */
    public static void peaceCandidate(ServerLevel level, UUID village, List<Elections.Candidate> out, List<VillageFolkEntity> folk, int stand, long day) {
        List<UUID> foes = Wars.enemies(village);
        if (foes.isEmpty() || weariness(village) < WEARY) return;
        UUID leader = Villages.elder(village), enemy = foes.get(0);
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (VillageFolkEntity f : folk) {
            if (f.getUUID().equals(leader) || f.isBaby()) continue;
            int s = 4 * dove(f, enemy);
            for (VillageFolkEntity o : folk) if (o != f) s += o.life().affinity(f.getUUID()) / 4;
            if (f.life().has(Social.Trait.SOCIABLE)) s += 5;
            if (f.life().has(Social.Trait.SHY)) s -= 10;
            if (s > bestScore) { bestScore = s; best = f; }
        }
        if (best == null || dove(best, enemy) <= 0) return;
        Values.Value p = Values.top(best) == Values.Value.SAFETY ? Values.second(best) : Values.top(best);
        Values.Value q = p == Values.top(best) ? Values.second(best) : Values.top(best);
        Elections.Candidate peace = new Elections.Candidate(best.getUUID(), best.displayNameCap(), p, q,
            "peace with " + name(enemy) + ": the militia home, the walls stood down, and our trade back");
        // In place of itself if it stands already; else of the last of the others who is not the leader (the
        // hawk stands to be judged on its war); else beside them.
        int at = -1, last = -1;
        UUID warLeader = warLeader(village);
        for (int i = 0; i < out.size(); i++) {
            UUID id = out.get(i).id();
            if (id.equals(best.getUUID())) at = i;
            else if (!id.equals(leader) && !id.equals(warLeader)) last = i;
        }
        if (at >= 0) out.set(at, peace);
        else if (out.size() < 2 || last < 0) out.add(peace);
        else out.set(last, peace);
        Ledger.note(village, "wp.peacecand", best.getUUID() + "|" + day);
        Villages.tell(village, day, best.displayNameCap() + " stood for " + Elections.title(village) + " on the promise of peace with " + name(enemy));
        WarBooks.course(village, enemy, day, best.displayNameCap() + " stood at the election for peace");
        best.persona().remember(day, "I stood for peace with " + name(enemy), 6);
    }

    /**
     * [Elections.judge] The war at the ballot: a weary town leans to the one standing for peace (the more, the
     * more the voter cares for its wages, its rest or its kin over there), and away from the leader who took it
     * to war; a war going well (the enemy reckoned the weaker) keeps the hawk in.
     */
    public static double electionLean(ServerLevel level, VillageFolkEntity voter, Elections.Candidate c) {
        UUID v = voter.ownerId();
        if (v == null) return 0;
        List<UUID> foes = Wars.enemies(v);
        if (foes.isEmpty()) return 0;
        int w = weariness(v);
        boolean well = goingWell(level, v);
        int lean = dove(voter, foes.get(0));
        if (c.id().equals(peaceCandidate(v))) return w * 0.6 + lean - (well ? 40 : 0);
        if (c.id().equals(warLeader(v)) || c.id().equals(Villages.elder(v))) return (well ? 30 : 0) - w * 0.3 - lean / 2.0;
        return 0;
    }

    /**
     * [Elections.install] The count is in: a peace candidate elected sues for peace at once (and the town keeps
     * talking until it has it); the war leader kept in, or another, and the war goes on.
     */
    public static void elected(ServerLevel level, UUID village, UUID winner, long day) {
        UUID pc = peaceCandidate(village);
        Ledger.forget(village, "wp.peacecand");
        List<UUID> foes = Wars.enemies(village);
        if (foes.isEmpty() || pc == null) return;
        String who = Villages.elderName(village);
        if (!pc.equals(winner)) {
            Villages.tell(village, day, "the town would not vote for peace: the war with " + name(foes.get(0)) + " goes on");
            WarBooks.course(village, foes.get(0), day, "the town voted, and not for peace");
            return;
        }
        Ledger.note(village, "wp.mandate", Long.toString(day));
        Villages.tell(village, day, (who.isEmpty() ? "the peace candidate" : who) + " was elected on the promise of peace, and sues for peace with "
            + name(foes.get(0)) + " at once");
        Villages.Village us = Villages.get(village);
        for (UUID e : foes) {
            WarBooks.course(village, e, day, "the town elected " + (who.isEmpty() ? "its peace candidate" : who) + " on the promise of peace");
            Villages.Village them = Villages.get(e);
            if (us != null && them != null) sue(level, us, them, day, "the town voted for peace");
        }
    }

    /** Has the town voted for peace in the last fortnight? */
    static boolean mandate(UUID village, long day) {
        return day - WarBooks.num(Ledger.note(village, "wp.mandate"), -100) <= 14;
    }

    /**
     * A town worn out by its war calls its election early, where it has elections at all (four voters or more)
     * and the next is more than two days off: once a war, and the peace candidate stands at it.
     */
    static void earlyElection(UUID village, long day) {
        List<UUID> foes = Wars.enemies(village);
        if (foes.isEmpty() || weariness(village) < WORN_OUT || Elections.voters(village).size() < 4) return;
        String key = "wp.early/" + foes.get(0);
        if (Ledger.note(village, key) != null) return;
        long next = WarBooks.num(Ledger.note(village, "election.next"), -1);
        if (next >= 0 && next - day <= Elections.CALL) return;
        Ledger.note(village, key, Long.toString(day));
        Ledger.note(village, "election.next", Long.toString(day + Elections.CALL));
        Villages.tell(village, day, "worn out by the war with " + name(foes.get(0)) + ", the town called its election early, for day " + (day + Elections.CALL));
        WarBooks.course(village, foes.get(0), day, "the town called an early election");
    }

    // ------------------------------------------------------------------ the fallen, the memorial and the remembrance

    /**
     * [VillageFolkEntity.die] A death the war is to blame for: a spy of ours dead on its errand against a
     * town we are at odds with, an ally's guard dead on another town's walls, or anybody killed by the hand of
     * a folk of a town we are at war with. Into the books, to be remembered at the peace.
     */
    public static void died(VillageFolkEntity f, @Nullable net.minecraft.world.damagesource.DamageSource cause, long day) {
        // (Whatever goes wrong here, the folk's death itself goes on.)
        Guard.run("war dead", () -> diedNow(f, cause, day));
    }

    private static void diedNow(VillageFolkEntity f, @Nullable net.minecraft.world.damagesource.DamageSource cause, long day) {
        UUID us = f.ownerId();
        if (us == null || f.isShowcase()) return;
        String name = f.displayNameCap();
        Spying.Mission m = Spying.missionOf(f);
        if (m != null && (Wars.atWar(us, m.them()) || Spying.hostile(us, m.them()))) {
            fallen(us, m.them(), name + ", our spy", day);
            return;
        }
        if (awayOnGarrison(f)) {
            UUID host = f.trip().to;
            for (String[] p : garrison(host)) {
                UUID foe = WarBooks.id(p[2]);
                if (!p[0].equals(f.getUUID().toString()) || foe == null) continue;
                fallen(us, foe, name + ", on " + name(host) + "'s walls", day);
                fallen(host, foe, name + " of " + name(us), day);
                return;
            }
        }
        if (cause != null && cause.getEntity() instanceof VillageFolkEntity k && k.ownerId() != null && Wars.atWar(us, k.ownerId())) {
            fallen(us, k.ownerId(), name, day);
        }
    }

    /** One the war cost this town, in its books (once): the town the wearier for it. */
    public static void fallen(UUID us, UUID them, String name, long day) {
        for (String[] f : WarBooks.fallen(us)) if (f[1].equals(name) && WarBooks.num(f[0], -1) == day) return;
        WarBooks.fallen(us, day, name, name(them));
        weariness(us, weariness(us) + 8);
        if (WarBooks.book(us, them) != null) WarBooks.course(us, them, day, name + " was lost to the war");
    }

    /**
     * At the peace: everybody the war cost this town remembered (the chronicle, a plaque before the chapel or
     * the graveyard, else by the board: Plaques), or with nobody lost, the war itself and the peace; and the day
     * of the peace kept every year as Remembrance Day, a minute's silence at the dusk bell (Traditions).
     */
    static void memorialise(ServerLevel level, UUID side, UUID other, long began, long day) {
        String foe = name(other);
        List<String> names = new ArrayList<>();
        for (String[] f : WarBooks.fallen(side)) if (f[2].equals(foe) && WarBooks.num(f[0], -1) >= began) names.add(f[1]);
        String[] lines = names.isEmpty()
            ? new String[]{ "The war with", foe, "day " + (began + 1) + " to " + (day + 1), "and the peace" }
            : new String[]{ "Remember", clip(names.get(0).split(",")[0] + (names.size() > 1 ? " +" + (names.size() - 1) : "")), "war with " + foe,
                "day " + (began + 1) + " to " + (day + 1) };
        Plaques.memorial(side, memorialMark(side), lines, day);
        Traditions.remember(side, day, "Remembrance Day", names.isEmpty() ? "the war with " + foe + ", and the peace"
            : "those the war with " + foe + " cost: " + String.join(", ", names));
        Villages.tell(side, day, names.isEmpty()
            ? "the town will put up a plaque for the war with " + foe + ", which cost it no lives, and keep the day of the peace every year"
            : "the town remembered those the war with " + foe + " cost (" + String.join("; ", names) + "), with a plaque, and Remembrance Day every year");
    }

    private static String clip(String s) {
        return s.length() <= 15 ? s : s.substring(0, 15);
    }

    /**
     * Where the memorial goes: before the chapel (or the graveyard), out from its front; else out on the
     * square before the board's face, past the far end of the board from the war banner's pole (which tries
     * the near end first: WarBanner.byBoard), never behind the board; else by the middle of the town.
     */
    static BlockPos memorialMark(UUID village) {
        for (String s : new String[]{ "chapel", "graveyard" }) {
            for (Ledger.Building b : Ledger.buildings(village)) {
                if (!b.structure().equals(s)) continue;
                int[] half = com.jrpetty.mcassistant.entity.goal.Blueprints.fullHalf(s);
                return b.anchor().relative(b.facing(), Math.max(half[0], half[1]) + 2);
            }
        }
        BlockPos at = VillageBoards.lectern(village);
        Direction f = VillageBoards.facingOf(village);
        int far = com.jrpetty.mcassistant.block.VillageBoardBlock.WIDE - com.jrpetty.mcassistant.block.VillageBoardBlock.WIDE / 2 + 1;
        if (at != null && f != null && f.getAxis().isHorizontal()) return at.relative(com.jrpetty.mcassistant.block.VillageBoardBlock.right(f), far).relative(f, 2);
        if (at != null) return at.offset(3, 0, 3);
        Villages.Village v = Villages.get(village);
        return v == null ? BlockPos.ZERO : v.centre().offset(4, 0, 4);
    }

    /** The town's newest war memorial (wanted or up), or null if it has made no peace. */
    @Nullable
    private static Plaques.Plaque newestMemorial(UUID village) {
        Plaques.Plaque out = null;
        for (Plaques.Plaque p : Plaques.plaques(village)) if (p.site() == Plaques.Site.MEMORIAL && (out == null || p.day() >= out.day())) out = p;
        return out;
    }

    /**
     * The command (/village war memorial, for the pictures): the town's newest war memorial put up now,
     * whatever the hour and without waiting for a hand to walk over (as /village decor now does), but out of
     * the stores as ever: a sign and a post, or planks. "MEMORIAL x y z &lt;facing&gt; &lt;its words&gt;" (the post,
     * the sign on top of it, its face looking {@code facing}), or why it is not up.
     */
    public static String memorialForPictures(ServerLevel level, Villages.Village v) {
        Plaques.Plaque m = newestMemorial(v.id());
        if (m == null) return "NO-MEMORIAL " + name(v.id()) + " has made no peace to remember";
        if (!m.up()) {
            boolean was = TownJobs.instantNow();
            TownJobs.instantForTests(true);
            try {
                for (int i = 0; i < 12 && !m.up(); i++) {
                    if (!Plaques.putUp(level, v)) break;                    // nothing more it can put up
                    m = newestMemorial(v.id());
                }
            } finally {
                TownJobs.instantForTests(was);
            }
        }
        if (m == null || !m.up()) {
            String s = Plaques.shortForTests(v.id());
            return "NO-MEMORIAL " + name(v.id()) + "'s memorial is not up: " + (s == null ? "nowhere to put it" : "waiting on " + s);
        }
        String facing = "south";
        net.minecraft.world.level.block.state.BlockState sign = level.getBlockState(m.at().above());
        if (sign.hasProperty(net.minecraft.world.level.block.StandingSignBlock.ROTATION)) {
            int rot = sign.getValue(net.minecraft.world.level.block.StandingSignBlock.ROTATION);
            if (rot % 4 == 0) facing = Direction.from2DDataValue(rot / 4).getName();
        }
        return "MEMORIAL " + m.at().getX() + " " + m.at().getY() + " " + m.at().getZ() + " " + facing + " " + String.join(" / ", m.lines());
    }

    /** [TownCalendar] The town's next Remembrance Day, for the board and the books, or null. */
    @Nullable
    public static String calendarLine(UUID village, long day) {
        Traditions.Custom next = null;
        long when = Long.MAX_VALUE;
        for (Traditions.Custom c : Traditions.customs(village)) {
            if (c.why() != Traditions.Why.WAR) continue;
            long n = Traditions.today(village, c, day) ? day : Traditions.next(village, c, day);
            if (n >= 0 && n < when) { when = n; next = c; }
        }
        if (next == null) return null;
        return "Remembrance Day" + (when == day ? " is today: a minute's silence at the dusk bell" : " on day " + (when + 1)
            + (when - day <= 7 ? " (in " + (when - day) + (when - day == 1 ? " day)" : " days)") : "")) + ", for " + next.toWhom() + ".";
    }

    /**
     * A feast for the peace: at the town's next day of rest within the week (else tomorrow evening), on the
     * town (Gatherings: the feast out of its own stores), and in the chronicle and the gazette.
     */
    static void feast(ServerLevel level, UUID side, UUID other, long day) {
        long on = day + 1;
        for (long d = day + 1; d <= day + 7; d++) if (RestDay.today(side, d)) { on = d; break; }
        String what = "the peace with " + name(other);
        Gatherings.sponsor(side, what, on);
        Ledger.note(side, "wp.feast", on + "|" + what);
        Villages.tell(side, day, "a feast for the peace with " + name(other) + " was called for day " + (on + 1)
            + (RestDay.today(side, on) ? ", the day of rest" : ""));
    }

    /** The day of the town's peace feast, or -1. */
    public static long feastDay(UUID village) {
        String f = Ledger.note(village, "wp.feast");
        return f == null || f.isEmpty() ? -1 : WarBooks.num(f.split("\\|")[0], -1);
    }

    // ------------------------------------------------------------------ broken faith

    /**
     * A raid (or any harm done) by one town on another it is not at war with: if a treaty or a truce holds
     * between them, the treaty is broken, a cause for war; and every town that knows the raider thinks
     * the worse of it. For whatever work does the raiding.
     */
    public static void breach(ServerLevel level, UUID raider, UUID victim, long day, String what) {
        if (raider == null || victim == null || raider.equals(victim) || Wars.atWar(raider, victim)) return;
        boolean faith = WarBooks.inForce(raider, victim, day) || Bonds.truce(raider, victim, day);
        WarBooks.wrong(victim, raider, day, faith ? Wrong.TREATY : Wrong.THEFT, what);
        if (!faith) return;
        Ledger.note(raider, "wp.broken/" + victim, "broken");
        Ledger.note(victim, "wp.brokenby/" + raider, Long.toString(day));
        String rn = name(raider), line = rn + " broke its treaty with " + name(victim) + ": " + what;
        for (Villages.Village o : Villages.every()) {
            UUID x = o.id();
            if (x.equals(raider) || !Ledger.knowEachOther(x, raider)) continue;
            Ledger.relate(x, raider, x.equals(victim) ? -25 : -10);
            Villages.tell(x, day, line);
        }
        Villages.tell(raider, day, line);
        Bonds.remember(victim, raider, day, -8, "the treaty was broken");
        LOG.info("[MCA-WAR] {}", line);
    }

    // ------------------------------------------------------------------ a player's word

    /**
     * [Diplomacy.peace, at war] A player held in honour in both towns carries the gifts between them (the
     * olive branch's ten coins) and brokers the peace on its word: the terms as the balance of strength
     * makes them, no coin moving (nobody carries it). Its standing in both rises.
     */
    public static String broker(VillageFolkEntity f, Player p, Villages.Village o) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "Peace? I've no village to make it for.";
        String on = name(o.id());
        long now = level.getGameTime();
        boolean here = Standing.of(village, p.getUUID(), now).title().atLeast(Standing.Title.HONOURED);
        boolean there = Standing.of(o.id(), p.getUUID(), now).title().atLeast(Standing.Title.HONOURED);
        if (!here || !there) {
            return "We're at war with " + on + ". Only somebody both sides hold in honour could carry peace between us, and "
                + (here ? on + " doesn't know you well enough." : "we don't know you well enough.");
        }
        int coins = Market.coinsHeld(p);
        if (coins < Diplomacy.PEACE_COST) return "It would take gifts for both elders: " + Diplomacy.PEACE_COST + " coins' worth. You've " + coins + ".";
        Market.payOut(p, Diplomacy.PEACE_COST);
        Ledger.addCoins(village, Diplomacy.PEACE_COST / 2);
        Ledger.addCoins(o.id(), Diplomacy.PEACE_COST - Diplomacy.PEACE_COST / 2);
        long day = level.getDayTime() / 24000L;
        String name = p.getName().getString();
        String text = makePeace(level, village, o.id(), day, terms(level, village, o.id()), "on " + name + "'s word", null);
        for (UUID v : new UUID[]{ village, o.id() }) {
            for (AssistantEntity a : Villages.folkOf(v)) if (a instanceof VillageFolkEntity g) g.persona().feelFor(p.getUUID(), name, 6);
            Standing.stir(v, p.getUUID());
            Villages.tell(v, day, name + " brokered the peace between " + name(village) + " and " + on);
        }
        return "On your word, then. Peace with " + on + ": " + text + ". Thank you, " + name + ".";
    }

    // ------------------------------------------------------------------ where the player sees it

    /** The war's footing for this town, in a word. */
    static String footingWord(UUID v) {
        return switch (Wars.footing(v)) {
            case WAR -> "at war";
            case TENSION -> "on its guard";
            case PEACE -> "at peace";
        };
    }

    /** For a war under way: who is likelier to sue, as the books stand. */
    static String outlook(ServerLevel level, UUID us, UUID them, long day) {
        WarBooks.Book b = WarBooks.book(us, them);
        if (b == null) return "";
        double r = own(level, us).strength() / (double) Math.max(1, of(level, us, them, day).strength());
        long stood = day - b.began, wait = standDays(us);
        String side = r < 0.8 ? "we reckon ourselves the weaker: the elder will sue for peace" : r > 1.25 ? "we reckon ourselves the stronger: they should sue"
            : "neither side clearly the stronger: the cost will tell";
        return side + (stood < wait ? " (the elder lets it stand " + (wait - stood) + " more " + (wait - stood == 1 ? "day" : "days") + ")" : "");
    }

    /** The board's lines (VillageBoards): the war, the quarrel, the council's vote, the treaty in force. */
    public static List<String> board(ServerLevel level, UUID v, long day) {
        List<String> out = new ArrayList<>();
        for (UUID foe : Wars.enemies(v)) {
            WarBooks.Book b = WarBooks.book(v, foe);
            String what = b == null ? "" : (b.aggressor ? ": for " + words(b.goalText) : ": standing firm against its demands");
            out.add("RB|AT WAR with " + name(foe) + " since day " + Wars.since(v, foe) + what + ". The war footing has cost "
                + (b == null ? 0 : b.cost) + " coins.");
            List<UUID> allies = alliesOf(v);
            List<String> names = new ArrayList<>();
            for (UUID l : allies) names.add(name(l));
            int g = garrisonCount(v);
            if (!names.isEmpty() || g > 0) out.add("RN|Our allies: " + (names.isEmpty() ? "none" : String.join(", ", names))
                + (g > 0 ? "; " + g + " of their guards on our walls" : "") + ".");
        }
        int w = weariness(v);
        if (w >= 10) out.add((w >= WEARY ? "RW" : "RN") + "|The town is " + wearyWord(w) + " (" + w + "/100)"
            + (wearyWhy(v).isEmpty() ? "" : ": " + wearyWhy(v)) + ".");
        UUID pc = peaceCandidate(v);
        if (pc != null) {
            String n = "";
            for (AssistantEntity a : Villages.folkOf(v)) if (a.getUUID().equals(pc)) n = a.displayNameCap();
            out.add("RW|" + (n.isEmpty() ? "One of us" : n) + " stands at the election for peace.");
        }
        long feast = feastDay(v);
        if (feast >= day) out.add("RG|A feast for the peace" + (feast == day ? " tonight" : " on day " + (feast + 1)) + ": everybody welcome.");
        for (Villages.Village o : Villages.every()) {
            UUID y = o.id();
            if (y.equals(v)) continue;
            Quarrel q = quarrel(v, y);
            if (q != null) {
                out.add("RW|" + switch (q.stage) {
                    case COUNCIL -> "The council sits as a council of war over " + name(y) + ".";
                    case HERALD_DUE -> "The council of war has voted: our herald goes to " + name(y) + " with our demands.";
                    case HERALD -> "Our herald has gone to " + name(y) + ": " + demand(q) + ", or war.";
                });
            }
            Quarrel theirs = quarrel(y, v);
            if (theirs != null && theirs.stage == Stage.HERALD) out.add("RW|" + name(y) + " has sent us its demands: " + demand(theirs) + ".");
            String council = Ledger.note(v, "wp.council/" + y);
            if (council != null && !council.isEmpty()) {
                String[] p = council.split("\\|", 5);
                if (p.length == 5 && day - WarBooks.num(p[0], -100) <= 3) {
                    out.add("RM|The council of war voted " + p[1] + " to " + p[2] + ("1".equals(p[3]) ? " for our demands of " : " against war with ") + name(y) + ".");
                }
            }
            WarBooks.Treaty t = WarBooks.treaty(v, y);
            long broke = WarBooks.num(Ledger.note(v, "wp.brokenby/" + y), -100);
            if (day - broke <= 14) out.add("RB|" + name(y) + " broke its treaty with us on day " + (broke + 1) + ": a cause for war.");
            else if ("broken".equals(Ledger.note(v, "wp.broken/" + y)) && t != null && t.until() >= day) {
                out.add("RW|We broke our treaty with " + name(y) + ", and every town knows it.");
            } else if (t != null && t.until() >= day) out.add("RN|Treaty with " + name(y) + " (day " + t.day() + "): " + t.terms() + ".");
        }
        return out;
    }

    /** The gazette's war page: the war or the quarrel, the treaty, or nothing (null) in a quiet time. */
    @Nullable
    public static String gazette(ServerLevel level, UUID v, long day) {
        List<String> lines = new ArrayList<>();
        for (UUID foe : Wars.enemies(v)) {
            WarBooks.Book b = WarBooks.book(v, foe);
            lines.add("At war with " + name(foe) + " since day " + Wars.since(v, foe) + (b == null ? "" : ", for " + words(b.goalText)) + ".");
            if (b != null) lines.add("The war footing has cost " + b.cost + " coins.");
            List<String> course = WarBooks.course(v, foe);
            if (!course.isEmpty()) lines.add(course.get(course.size() - 1) + ".");
        }
        for (String l : board(level, v, day)) {
            if (l.startsWith("RW|") || l.startsWith("RN|Treaty")) lines.add(l.substring(3));
        }
        int w = weariness(v);
        if (w >= 10) lines.add("The town is " + wearyWord(w) + ".");
        long feast = feastDay(v);
        if (feast >= day) lines.add("A feast for the peace" + (feast == day ? " tonight." : " on day " + (feast + 1) + "."));
        String rem = calendarLine(v, day);
        if (rem != null) lines.add(rem);
        if (lines.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("§lWar and peace§r");
        for (int i = 0; i < Math.min(5, lines.size()); i++) sb.append('\n').append(lines.get(i));
        return sb.toString();
    }

    /** A folk's card: its town's war, and how it voted in the council of war. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        UUID v = f.ownerId();
        if (v == null || f.isBaby()) return null;
        if (awayOnGarrison(f)) return "Standing guard on our allies' walls in " + name(f.trip().to) + ".";
        String vote = null, about = null;
        for (Map.Entry<String, String> n : Ledger.notes(v).entrySet()) {
            if (!n.getKey().startsWith("wp.voted/")) continue;
            for (String one : n.getValue().split(",")) {
                if (one.startsWith(f.getUUID() + ":")) {
                    vote = one.endsWith(":1") ? "voted for" : "voted against";
                    about = WarBooks.id(n.getKey().substring(9)) == null ? null : name(WarBooks.id(n.getKey().substring(9)));
                }
            }
        }
        List<UUID> foes = Wars.enemies(v);
        int w = weariness(v);
        String war = foes.isEmpty() ? null : "At war with " + name(foes.get(0)) + " since day " + Wars.since(v, foes.get(0))
            + (w >= 25 ? " (the town " + wearyWord(w) + (Values.top(f) == Values.Value.SAFETY && w < WEARY ? ", though it would see it through" : "") + ")" : "");
        if (f.getUUID().equals(peaceCandidate(v))) war = (war == null ? "" : war + "; ") + "standing for peace at the election";
        String council = vote == null || about == null ? null : "On the council of war it " + vote + " war with " + about;
        if (war == null && council == null) return null;
        return war == null ? council + "." : council == null ? war + "." : war + "; " + council.substring(0, 1).toLowerCase(Locale.ROOT) + council.substring(1) + ".";
    }

    /** /village war, in lines. */
    public static List<String> lines(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        List<String> out = new ArrayList<>();
        out.add("WAR " + name(id) + ": " + footingWord(id) + " (" + Wars.footing(id) + "); elder " + Envoys.temper(id).words
            + ", lets a war stand " + standDays(id) + " days; wars " + (on() ? "on" : "off") + ".");
        Reckoning mine = own(level, id);
        int weary = weariness(id);
        out.add("Weariness: " + weary + "/100, " + wearyWord(weary) + (wearyWhy(id).isEmpty() ? "" : " (" + wearyWhy(id) + ")")
            + (peaceCandidate(id) == null ? "" : "; a peace candidate stands") + (mandate(id, day) ? "; the town has voted for peace" : "") + ".");
        String rem = calendarLine(id, day);
        if (rem != null) out.add(rem);
        out.add("Our strength: " + mine.strength() + " (" + mine.guards() + " guards, " + mine.armoured() + " in iron, " + mine.archers()
            + " with bows" + (mine.garrison() > 0 ? ", " + mine.garrison() + " of them our allies'" : "") + (mine.walls() > 0 ? ", a wall" : "") + ").");
        for (UUID foe : Wars.enemies(id)) {
            WarBooks.Book b = WarBooks.book(id, foe);
            Reckoning theirs = of(level, id, foe, day);
            out.add("At war with " + name(foe) + " since day " + Wars.since(id, foe) + " (" + (day - Wars.since(id, foe)) + " days)"
                + (b == null ? "" : b.aggressor ? ", ours, for " + words(b.goalText) : ", theirs: " + words(b.goalText))
                + ". They are " + theirs.strength() + " by " + theirs.source() + ". The war footing has cost " + (b == null ? 0 : b.cost)
                + " coins. " + outlook(level, id, foe, day) + ".");
            for (String c : WarBooks.course(id, foe)) out.add("  " + c);
        }
        List<String> allies = new ArrayList<>();
        for (UUID l : alliesOf(id)) allies.add(name(l));
        if (!allies.isEmpty()) out.add("Allies in the war: " + String.join(", ", allies) + "; on our walls: " + garrisonCount(id) + " of their guards.");
        for (Villages.Village o : Diplomacy.neighboursOf(id)) {
            UUID y = o.id();
            if (!Ledger.knowEachOther(id, y)) continue;
            Quarrel q = quarrel(id, y);
            String griev = "";
            List<WarBooks.Grievance> gs = WarBooks.wrongs(id, y);
            if (!gs.isEmpty()) griev = "; held against them: " + gs.get(gs.size() - 1).what() + " (" + wronged(id, y, day) + " this month)";
            out.add(name(y) + ": " + Ledger.relation(id, y) + (q == null ? "" : "; quarrel at " + q.stage + " for " + words(q.text)) + griev
                + "; " + weigh(level, v, o, day).why() + ".");
        }
        for (String[] t : WarBooks.treaties(id)) out.add("Treaty of day " + t[0] + " with " + t[2] + ": " + t[3] + ".");
        for (String p : WarBooks.past(id)) out.add("Past: " + p + ".");
        for (String[] f : WarBooks.fallen(id)) out.add("Lost to the war with " + f[2] + ", day " + (WarBooks.num(f[0], 0) + 1) + ": " + f[1] + ".");
        for (Plaques.Plaque p : Plaques.plaques(id)) {
            if (p.site() != Plaques.Site.MEMORIAL) continue;
            out.add(p.up() ? "Memorial at " + p.at().getX() + " " + p.at().getY() + " " + p.at().getZ() + ": " + String.join(" / ", p.lines()) + "."
                : "Memorial to go up: " + String.join(" / ", p.lines()) + ".");
        }
        return out;
    }

    /** The War page of the town's books (CityScreen): everything above, for the page to lay out. */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        CompoundTag out = new CompoundTag();
        out.putString("footing", Wars.footing(id).name());
        out.putString("footing_word", footingWord(id));
        out.putBoolean("on", on());
        out.putString("temper", Envoys.temper(id).words);
        out.putInt("stand", standDays(id));
        Reckoning mine = own(level, id);
        out.putInt("strength", mine.strength());
        out.putString("strength_words", mine.guards() + " guards, " + mine.armoured() + " in iron, " + mine.archers() + " with bows"
            + (mine.garrison() > 0 ? ", " + mine.garrison() + " allies'" : "") + (mine.walls() > 0 ? ", a wall" : ", no wall"));
        ListTag wars = new ListTag();
        for (UUID foe : Wars.enemies(id)) {
            WarBooks.Book b = WarBooks.book(id, foe);
            Reckoning theirs = of(level, id, foe, day);
            CompoundTag w = new CompoundTag();
            w.putString("enemy", name(foe));
            w.putLong("since", Wars.since(id, foe));
            w.putLong("days", day - Wars.since(id, foe));
            w.putBoolean("ours", b != null && b.aggressor);
            w.putString("goal", b == null ? "" : words(b.goalText));
            w.putInt("cost", b == null ? 0 : b.cost);
            w.putInt("us", mine.strength());
            w.putInt("them", theirs.strength());
            w.putString("source", theirs.source());
            w.putString("outlook", outlook(level, id, foe, day));
            BlockPos at = WarBanner.where(id, foe);
            w.putString("banner", at == null ? "no banner yet" : "the war banner at " + at.getX() + ", " + at.getY() + ", " + at.getZ());
            w.put("course", strings(WarBooks.course(id, foe)));
            wars.add(w);
        }
        out.put("wars", wars);
        List<String> quarrels = new ArrayList<>(), griev = new ArrayList<>(), treaties = new ArrayList<>(), allies = new ArrayList<>();
        for (String l : board(level, id, day)) if (l.startsWith("RW|") || l.startsWith("RM|")) quarrels.add(l.substring(3));
        for (Villages.Village o : Diplomacy.neighboursOf(id)) {
            for (WarBooks.Grievance g : WarBooks.wrongs(id, o.id())) {
                if (day - g.day() <= Bonds.WARM_DAYS) griev.add("day " + g.day() + ", " + name(o.id()) + ": " + g.what() + " (" + g.kind().words + ")");
            }
        }
        while (griev.size() > 8) griev.remove(0);
        for (String[] t : WarBooks.treaties(id)) treaties.add("day " + t[0] + ", with " + t[2] + ": " + t[3]);
        for (Villages.Village o : Villages.every()) {
            if (!o.id().equals(id) && Envoys.allied(id, o.id())) {
                String pledge = Ledger.note(o.id(), "wp.pledge/" + id);
                allies.add(name(o.id()) + (pledge != null && !pledge.isEmpty() ? ": " + pledge.split("\\|")[1] + " guards pledged to us" : ": sworn allies"));
            }
        }
        for (String[] g : garrison(id)) {
            UUID ally = WarBooks.id(g[1]), guard = WarBooks.id(g[0]);
            if (ally != null && guard != null && level.getEntity(guard) instanceof VillageFolkEntity f) {
                allies.add(f.displayNameCap() + " of " + name(ally) + " on our walls since day " + g[3]);
            }
        }
        out.put("quarrels", strings(quarrels));
        out.put("grievances", strings(griev));
        out.put("treaties", strings(treaties));
        out.put("allies", strings(allies));
        out.put("past", strings(WarBooks.past(id)));
        out.put("last_course", strings(WarBooks.list(id, "wp.lastcourse")));
        int weary = weariness(id);
        out.putInt("weary", weary);
        out.putString("weary_word", wearyWord(weary));
        out.putString("weary_why", wearyWhy(id));
        List<String> fallen = new ArrayList<>();
        for (String[] f : WarBooks.fallen(id)) fallen.add("day " + (WarBooks.num(f[0], 0) + 1) + ": " + f[1] + " (the war with " + f[2] + ")");
        out.put("fallen", strings(fallen));
        String rem = calendarLine(id, day);
        out.putString("remembrance", rem == null ? "" : rem);
        List<String> election = new ArrayList<>();
        UUID pc = peaceCandidate(id);
        if (pc != null) {
            for (AssistantEntity a : Villages.folkOf(id)) if (a.getUUID().equals(pc)) election.add(a.displayNameCap() + " stands at the next election for peace");
        }
        if (mandate(id, day)) election.add("the town has voted for peace, and talks until it has it");
        long feast = feastDay(id);
        if (feast >= day) election.add("a feast for the peace on day " + (feast + 1));
        out.put("election", strings(election));
        return out;
    }

    private static ListTag strings(List<String> list) {
        ListTag t = new ListTag();
        for (String s : list) t.add(StringTag.valueOf(s));
        return t;
    }

    // ------------------------------------------------------------------ for the tests and the commands

    /** Tests and the command: a quarrel set at the herald's turn, the council's vote taken, for this goal. */
    public static void quarrelForTests(UUID us, UUID them, Goal goal, int amount, String text, long day) {
        save(us, them, new Quarrel(Stage.HERALD_DUE, day, goal, amount, 0, text));
    }

    /** Tests: where a quarrel stands ("COUNCIL", "HERALD_DUE", "HERALD"), or null. */
    @Nullable
    public static String quarrelStage(UUID us, UUID them) {
        Quarrel q = quarrel(us, them);
        return q == null ? null : q.stage.name();
    }

    /** Tests and the command: the leader's decision acted on now (the dice aside): the council called and its vote taken in the hall. */
    public static String councilNow(ServerLevel level, Villages.Village us, Villages.Village them, boolean assembly) {
        long day = level.getDayTime() / 24000L;
        Decision d = weigh(level, us, them, day);
        if (!d.go()) return "no council: " + d.why();
        callCouncil(level, us, them, day, d, assembly ? Boolean.TRUE : null);
        if (assembly) return "the council of war is called to the hall now: " + d.why();
        Quarrel q = quarrel(us.id(), them.id());
        Vote v = vote(level, us.id(), them.id(), q, day);
        decided(level, us.id(), them.id(), v, day);
        List<String> said = new ArrayList<>();
        for (Voice voice : v.voices()) said.add(voice.name() + (voice.aye() ? " aye" : " nay") + (voice.weight() > 1 ? " (x" + voice.weight() + ")" : ""));
        return v.result() + " [" + String.join(", ", said) + "] " + d.why();
    }

    /** The command: the council of war called to the hall now, whatever the elder would choose today (for the pictures). */
    public static String councilForPictures(ServerLevel level, Villages.Village us, Villages.Village them) {
        long day = level.getDayTime() / 24000L;
        if (Wars.atWar(us.id(), them.id())) return "at war already";
        Decision d = weigh(level, us, them, day);
        if (!d.go()) {
            Pick g = goalFor(level, us, them, day);
            d = new Decision(true, "called by order (" + d.why() + ")", d.hawk(), d.ours(), d.theirs(), d.allies(), g.goal(), g.amount(), g.text());
        }
        drop(us.id(), them.id(), day, 0);
        callCouncil(level, us, them, day, d, Boolean.TRUE);
        return d.why();
    }

    /**
     * Where the council of war sits ({@code at}, the middle of its circle), which way that place is
     * looked at from ({@code facing}: the hall's front, else the way the board faces) and whether it is
     * under a roof.
     */
    public record CouncilSpot(BlockPos at, Direction facing, boolean indoors) {}

    /**
     * Where the council of war sits: in the leader's hall, else the meeting hall; else out on the square
     * before the face of the board, the middle of its circle four blocks out from the board's foot, so the
     * whole ring stands in front of the board (at the foot itself half of it stood behind the board, out of
     * sight of anyone on the square); else the middle of the town. Null for a town not known.
     */
    @Nullable
    public static CouncilSpot councilSpot(UUID village) {
        for (String s : new String[]{ "townhall", "hall" }) {
            BlockPos hall = Villages.builtAt(village, s);
            if (hall == null) continue;
            Ledger.Building b = Villages.builtStructure(village, s);
            Direction f = b != null ? b.facing() : VillageBoards.facingOf(village);
            return new CouncilSpot(hall, f != null && f.getAxis().isHorizontal() ? f : Direction.SOUTH, true);
        }
        BlockPos lectern = VillageBoards.lectern(village);
        Direction f = VillageBoards.facingOf(village);
        if (lectern != null && f != null) return new CouncilSpot(lectern.relative(f, 4), f, false);
        Villages.Village v = Villages.get(village);
        return v == null ? null : new CouncilSpot(v.centre(), f != null ? f : Direction.SOUTH, false);
    }

    /** The command: a red banner into the town's stores, as a player might bring one (for the pictures). Whether it went in. */
    public static boolean clothForPictures(ServerLevel level, Villages.Village v) {
        ItemStack banner = new ItemStack(net.minecraft.world.item.Items.RED_BANNER);
        TownWork.give(level, v, banner);
        return banner.isEmpty();
    }

    /** Tests and the command: the herald (or the call, or the white flag) sent now, if one is due. */
    public static boolean heraldNow(ServerLevel level, Villages.Village us, Villages.Village them) {
        Quarrel q = quarrel(us.id(), them.id());
        if (q == null || q.stage != Stage.HERALD_DUE) return false;
        sendHerald(level, us, them, level.getDayTime() / 24000L, q);
        return quarrel(us.id(), them.id()) != null && quarrel(us.id(), them.id()).stage == Stage.HERALD;
    }

    /** Tests: an envoy at its host heard at once, as the audience before the board would hear it. What the host said. */
    public static String audienceForTests(ServerLevel level, VillageFolkEntity envoy) {
        Caravans.Trip t = envoy.trip();
        if (t == null || t.errand == null) return "no envoy";
        String asked = Envoys.asks(t.from, t.to, t.errand, envoy, t);
        Envoys.Answer a = Envoys.answer(level, t.to, t.from, t.errand, envoy, t);
        if (a.effect() != null) a.effect().run();
        Envoys.heard(envoy.getUUID());
        Villages.Village host = Villages.get(t.to);
        Envoys.homeward(level, envoy, t, host);
        return asked + " / " + a.said();
    }

    /** Tests: does a treaty keep the peace between them today? */
    public static boolean treatyHoldsForTests(UUID a, UUID b, long day) {
        return WarBooks.inForce(a, b, day);
    }

    /** Tests: the town's weariness set. */
    public static void wearyForTests(UUID village, int w) {
        weariness(village, w);
    }

    /** Tests: what a day of its war would take out of the town today, "points|what did it" (nothing is changed). */
    public static String wearForTests(ServerLevel level, Villages.Village v, long day) {
        Wear w = wear(level, v.id(), day);
        return w.by() + "|" + String.join(", ", w.why());
    }

    /** Tests: one the war cost the town, as the death of a spy or a guard on an ally's walls would write it. */
    public static void fallenForTests(UUID us, UUID them, String name, long day) {
        fallen(us, them, name, day);
    }

    /** Tests: the coin in an envoy's purse (-1 with no trip). */
    public static int purseForTests(VillageFolkEntity f) {
        return f.trip() == null ? -1 : f.trip().purse;
    }

    /** Tests: a war's beginning moved back so many days (it has stood that long). */
    public static void backdateForTests(UUID a, UUID b, int days) {
        for (UUID[] p : new UUID[][]{ { a, b }, { b, a } }) {
            WarBooks.Book book = WarBooks.book(p[0], p[1]);
            if (book == null) continue;
            book.began -= days;
            WarBooks.save(p[0], p[1], book);
            Ledger.note(p[0], "war/" + p[1], Long.toString(book.began));
        }
    }

    /** Tests: what keeping the war footing has cost this side so far, set. */
    public static void costForTests(UUID us, UUID them, int cost) {
        WarBooks.Book b = WarBooks.book(us, them);
        if (b == null) return;
        b.cost = cost;
        WarBooks.save(us, them, b);
    }

    /** Tests: the pair's day of war and peace now. */
    public static void dailyForTests(ServerLevel level, Villages.Village a, Villages.Village b, long day) {
        daily(level, a, b, day, new Random(day * 31L + 7L));
    }

    /** Tests: a town's day of war and peace now. */
    public static void townDailyForTests(ServerLevel level, Villages.Village v, long day) {
        townDaily(level, v, day);
    }

    /** Tests: both sides' war books exist, and the treaty count of each. */
    public static String booksForTests(UUID a, UUID b) {
        return "book " + (WarBooks.book(a, b) != null) + "/" + (WarBooks.book(b, a) != null) + ", treaties " + WarBooks.treaties(a).size()
            + "/" + WarBooks.treaties(b).size() + (WarBooks.treaty(a, b) == null ? "" : ", treaty: " + WarBooks.treaty(a, b).terms());
    }

    /** Tests: write a grievance as if it were remembered (a feud's quarrel over the land, a stolen sheep). */
    public static void wrongForTests(UUID us, UUID them, long day, Wrong kind, String what) {
        WarBooks.wrong(us, them, day, kind, what);
    }

    /** Tests and the command: where this town's war banner hangs for the war with that one, or null. */
    @Nullable
    public static BlockPos bannerAt(UUID us, UUID them) {
        return WarBanner.where(us, them);
    }
}
