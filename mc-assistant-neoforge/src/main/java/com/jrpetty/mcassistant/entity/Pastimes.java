package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.item.LeisureItems;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [leisure] Home, play and the town's evenings: who makes the seven things of item/LeisureItems, when the town wants
 * them, and the town's round that puts them to use. Each thing's own part is a class of its own:
 * <ul>
 * <li><b>the patchwork quilt</b>: Quilts (made of odd wool, bought by households, a wedding gift, slept under);</li>
 * <li><b>the lute</b>: Lutes (the buskers' and the band's instrument);</li>
 * <li><b>the draughts board</b>: Draughts (games at the tavern and in the park, a player's challenge, the fair's
 *     tournament);</li>
 * <li><b>the kite</b>: Kites (the children's afternoons, a player's kite);</li>
 * <li><b>the leather football</b>: Kickabout (the children's game) and Football (the league's ball);</li>
 * <li><b>the paper lanterns</b>: Lanterns (strung across the square on a festival night);</li>
 * <li><b>the slate and chalk</b>: Slates (the schoolchildren's).</li>
 * </ul>
 *
 * <p><b>The makers.</b> Each is made from the stores by its real recipe, by the trade it belongs to, at its bench,
 * between its own work (Crafts.now): the tailor the quilts, kites, footballs and lanterns; the shop's workshop the
 * lutes, the boards, the kites, the lanterns and the slates (and the tailor's things in a town with no tailor), and they
 * are on the shop's order book too (Workshop.demand). A town with neither tailor nor shop yet has them made at the
 * storehouse's bench by a hand it can spare (TownJobs): the rancher the quilts and the kites, the woodcutter the
 * lutes and the boards, the smelter (the town's mason) the slates. What each is short of is on the books.
 *
 * <p><b>Where the player sees it.</b> The folk's card (its quilt, its game record, its kite, its lute, its slate), what
 * it is doing (a game of draughts at the tavern, flying a kite, a kickabout), the gazette's "Home and play" (the
 * football and the draughts, yesterday's kites and lanterns), the chronicle (a tournament's winner, a lit festival),
 * the town's books (the stores' pages list them as they list everything) and /village items leisure.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Pastimes {

    private Pastimes() {}

    static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The town's bench, where a town has neither tailor nor shop: a piece of work at most this often (ticks). */
    static final long BENCH_EVERY = 600L;

    /** Each town's wants, worked out at most every ten seconds: {when, wants}. */
    private static final Map<UUID, Object[]> WANTS = new ConcurrentHashMap<>();
    /** What each town is short of for each thing, in words (the books, /village items leisure). */
    private static final Map<UUID, Map<Item, String>> SHORT = new ConcurrentHashMap<>();
    /** When each town's bench last made something. */
    private static final Map<UUID, Long> BENCH = new ConcurrentHashMap<>();
    /** What each town made, and who, the last few (the books). */
    private static final Map<UUID, List<String>> MADE = new ConcurrentHashMap<>();
    private static volatile boolean demanded;

    public static void resetForTests() {
        WANTS.clear();
        SHORT.clear();
        BENCH.clear();
        MADE.clear();
        AFTERNOON.clear();
        Quilts.resetForTests();
        Lutes.resetForTests();
        Draughts.resetForTests();
        Kites.resetForTests();
        Kickabout.resetForTests();
        Lanterns.resetForTests();
        Slates.resetForTests();
    }

    // ------------------------------------------------------------------ the town's round, every second

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % 20 != 15) return;
        com.jrpetty.mcassistant.Guard.run("pastimes", () -> {
            demandOnce();
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    round(level, v);
                }
            }
        });
    }

    /** A second of the town's play: the games of draughts, the quilts slept under, the buskers' lutes, the town's bench. */
    static void round(ServerLevel level, Villages.Village v) {
        if (Villages.headcount(v.id()) <= 0) return;
        long now = level.getGameTime(), dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        Draughts.tick(level, v, day, t);
        Kickabout.tick(level, v, day, t);
        Kites.tick(level, v, day, t);
        if (now % 40 < 20) Quilts.tick(level, v, day, t);
        Lutes.tick(level, v, day, t);
        Lutes.staged(level, v);
        Slates.tick(level, v, day, t);
        Long last = BENCH.get(v.id());
        if (last == null || now - last >= BENCH_EVERY || now < last) {
            BENCH.put(v.id(), now);
            bench(level, v);
        }
    }

    /**
     * The shop's workshop keeps the town's pastimes on its book (and so the shop stocks them): what Bench can make of the
     * game's recipes as they are, the lutes, the boards, the slates, the footballs and the lanterns in their colours. The
     * quilt (three colours of wool) and the kite (its dye's colour) are made by hand (craft). Put on the book at the
     * first round, when the game's registries are ready.
     */
    static void demandOnce() {
        if (demanded) return;
        demanded = true;
        Workshop.demand("the town's pastimes", (level, v, want) -> {
            for (Map.Entry<Item, Integer> e : wanted(level, v).entrySet()) {
                Item it = e.getKey();
                if (it == LeisureItems.QUILT_ITEM.get() || it == LeisureItems.KITE.get()) continue;
                want.accept(it, e.getValue());
            }
        });
    }

    // ------------------------------------------------------------------ what the town wants kept

    /**
     * How many of each the town wants in its stores, worked out at most every ten seconds: the quilts the households
     * would buy and a wedding's gift, a lute for each busker without one of its own, a board for the tavern and the
     * park, kites enough for the children to share, a football for the kickabouts and one for the pitch, the festival's
     * lanterns, a slate for each pupil and a spare.
     */
    static Map<Item, Integer> wanted(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        Object[] cached = WANTS.get(v.id());
        if (cached != null && now - (Long) cached[0] < 200L && now >= (Long) cached[0]) {
            @SuppressWarnings("unchecked") Map<Item, Integer> m = (Map<Item, Integer>) cached[1];
            return m;
        }
        Map<Item, Integer> out = new LinkedHashMap<>();
        put(out, LeisureItems.QUILT_ITEM.get(), Quilts.wanted(level, v));
        put(out, LeisureItems.LUTE.get(), Lutes.wanted(level, v));
        put(out, LeisureItems.DRAUGHTS_BOARD_ITEM.get(), Draughts.wanted(level, v));
        put(out, LeisureItems.KITE.get(), Kites.wanted(level, v));
        put(out, LeisureItems.LEATHER_FOOTBALL.get(), Kickabout.wanted(level, v));
        for (Map.Entry<Item, Integer> e : Lanterns.wanted(level, v).entrySet()) put(out, e.getKey(), e.getValue());
        put(out, LeisureItems.SLATE.get(), Slates.wanted(level, v));
        WANTS.put(v.id(), new Object[]{ now, out });
        return out;
    }

    private static void put(Map<Item, Integer> out, Item it, int n) {
        if (n > 0) out.merge(it, n, Integer::sum);
    }

    /** How many of this the town has: in the stores, on the shop's shelves. */
    static int have(ServerLevel level, UUID village, Item it) {
        return Market.stock(level, village, s -> s.is(it)) + ShopStock.held(level, village, s -> s.is(it));
    }

    /**
     * The age these belong to where the rule of things (Tiers) would read them later than they are: the football is
     * leather and the paper lantern has a torch in it, both of the Stone Age by the rule, but a Wood Age town has both
     * (its hunters' hides, the torches on its lamp posts), and both are the Wood Age's. Null for anything else.
     */
    @Nullable
    public static Villages.Age age(Item it) {
        if (it == LeisureItems.LEATHER_FOOTBALL.get()) return Villages.Age.WOOD;
        if (it instanceof net.minecraft.world.item.BlockItem b && b.getBlock() instanceof com.jrpetty.mcassistant.block.PaperLanternBlock) {
            return Villages.Age.WOOD;
        }
        return null;
    }

    /** Whose work each thing is: the tailor's cloth and leather, the shop's woodwork and slates (and the tailor's too, with none). */
    static boolean makes(UUID village, StationTask t, Item it) {
        boolean tailored = it == LeisureItems.QUILT_ITEM.get() || it == LeisureItems.KITE.get() || it == LeisureItems.LEATHER_FOOTBALL.get()
            || LeisureItems.isLantern(new ItemStack(it));
        if (t == StationTask.TAILOR) return tailored;
        if (t == StationTask.SHOP) {
            if (it == LeisureItems.QUILT_ITEM.get() || it == LeisureItems.LEATHER_FOOTBALL.get()) return !hasTrade(village, StationTask.TAILOR);
            return true;
        }
        return false;
    }

    static boolean hasTrade(UUID village, StationTask t) {
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == t && !a.isBaby() && a.isAlive()) return true;
        return false;
    }

    /** Does anybody in the town make it: a trade whose work it is, or the shop's workshop? */
    static boolean madeHere(UUID village, Item it) {
        for (StationTask t : new StationTask[]{ StationTask.TAILOR, StationTask.SHOP }) if (makes(village, t, it) && hasTrade(village, t)) return true;
        return Workshop.stands(village) && Workshop.keeper(village) != null;
    }

    // ------------------------------------------------------------------ the makers

    /**
     * A turn at the town's pastimes (Crafts.now, one turn in two while any are wanted): the first thing the stores are
     * short of that is this trade's work and the age allows, made out of the stores by its recipe. What was made, or null.
     */
    @Nullable
    public static String craft(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (Math.floorMod(level.getGameTime() / Crafts.EVERY + f.getUUID().hashCode(), 2L) != 0) return null;
        return craftNow(level, v, f, f.stationTask());
    }

    @Nullable
    static String craftNow(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity f, @Nullable StationTask as) {
        Map<Item, Integer> want = wanted(level, v);
        if (want.isEmpty()) return null;
        Villages.Age age = Villages.ageOf(v.id());
        Bench.Hand hand = null;
        for (Map.Entry<Item, Integer> e : want.entrySet()) {
            Item it = e.getKey();
            if (as != null && !makes(v.id(), as, it)) continue;
            if (!Tiers.allows(level, age, it)) {
                shortOf(v.id(), it, Tiers.refusal(level, age, it));
                continue;
            }
            if (have(level, v.id(), it) >= e.getValue()) continue;
            if (hand == null) hand = Bench.handOf(level, v, f, f == null ? null : VillageFolkEntity.buildingFor(f.stationTask()));
            ItemStack out = make(level, v, f, hand, it);
            if (out.isEmpty()) continue;
            WANTS.remove(v.id());
            String words = Bench.words(out.getItem(), out.getCount());
            String why = why(it);
            String who = f == null ? "the town's bench" : f.displayNameCap() + " (" + f.stationTask().title.toLowerCase(java.util.Locale.ROOT) + ")";
            made(v.id(), who + " made " + words + ", " + why);
            LOG.info("[MCA-LEISURE] {} at {} made {} {}", who, Villages.name(v.id()), words, why);
            return words + ", " + why;
        }
        return null;
    }

    /** One of this, made out of the stores by its recipe (the quilt and the kite by hand, the rest at Bench). Empty if not. */
    static ItemStack make(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity f, Bench.Hand hand, Item it) {
        if (it == LeisureItems.QUILT_ITEM.get()) return Quilts.make(level, v, f, hand);
        if (it == LeisureItems.KITE.get()) return Kites.make(level, v, f, hand);
        Bench.Plan p = Bench.plan(level, v, it, 1, hand);
        if (!p.ok()) {
            shortOf(v.id(), it, p.chain());
            return ItemStack.EMPTY;
        }
        ItemStack out = Bench.make(level, v, p, f, hand);
        if (!out.isEmpty()) forgetShort(v.id(), it);
        return out;
    }

    /** Why the town wants it, in a few words. */
    static String why(Item it) {
        if (it == LeisureItems.QUILT_ITEM.get()) return "for the households' beds";
        if (it == LeisureItems.LUTE.get()) return "for the buskers";
        if (it == LeisureItems.DRAUGHTS_BOARD_ITEM.get()) return "for the tavern's and the park's tables";
        if (it == LeisureItems.KITE.get()) return "for the children's kites";
        if (it == LeisureItems.LEATHER_FOOTBALL.get()) return "for the kickabouts and the pitch";
        if (it == LeisureItems.SLATE.get()) return "for the school";
        return "for the festival's lanterns";
    }

    static void shortOf(UUID village, Item it, String why) {
        SHORT.computeIfAbsent(village, k -> new ConcurrentHashMap<>()).put(it, why);
    }

    static void forgetShort(UUID village, Item it) {
        Map<Item, String> m = SHORT.get(village);
        if (m != null) m.remove(it);
    }

    static void made(UUID village, String line) {
        List<String> l = MADE.computeIfAbsent(village, k -> java.util.Collections.synchronizedList(new ArrayList<>()));
        l.add(line);
        while (l.size() > 8) l.remove(0);
    }

    /**
     * The town's own bench, in a town with neither the trade nor the shop to make a thing it wants: a hand it can spare
     * (TownJobs) goes to the storehouse and makes it there, out of the stores: the rancher the quilts and the kites and
     * the footballs, the woodcutter the lutes and the boards, the smelter (the mason) the slates, anybody the lanterns.
     * One thing a visit, a visit every half minute at most.
     */
    static void bench(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Map<Item, Integer> want = wanted(level, v);
        if (want.isEmpty() || Villages.headcount(id) < TownJobs.SETTLED) return;
        for (Map.Entry<Item, Integer> e : want.entrySet()) {
            Item it = e.getKey();
            if (madeHere(id, it) || have(level, id, it) >= e.getValue()) continue;
            if (!Tiers.allows(level, Villages.ageOf(id), it)) continue;
            List<BlockPos> chests = Villages.storeChests(level, id);
            BlockPos at = chests.isEmpty() ? v.centre() : chests.get(0);
            String words = Bench.words(it, 1);
            if (!TownJobs.atWork(level, v, "pastimes", at, "making " + words + " at the storehouse bench", benchHand(it))) return;
            Bench.Hand hand = Bench.handOf(level, v, null, null);
            ItemStack out = make(level, v, null, hand, it);
            if (out.isEmpty()) continue;
            WANTS.remove(id);
            made(id, "the town's bench made " + Bench.words(out.getItem(), out.getCount()) + ", " + why(it));
            LOG.info("[MCA-LEISURE] the town's bench at {} made {} {}", Villages.name(id), Bench.words(out.getItem(), out.getCount()), why(it));
            return;
        }
    }

    /** Whose hand the town's bench asks for first, for this. */
    static StationTask benchHand(Item it) {
        if (it == LeisureItems.LUTE.get() || it == LeisureItems.DRAUGHTS_BOARD_ITEM.get()) return StationTask.WOOD;
        if (it == LeisureItems.SLATE.get()) return StationTask.SMELT;
        if (it == LeisureItems.QUILT_ITEM.get() || it == LeisureItems.KITE.get() || it == LeisureItems.LEATHER_FOOTBALL.get()) return StationTask.RANCH;
        return StationTask.HAUL;
    }

    // ------------------------------------------------------------------ the children's afternoons

    /** What the children's afternoon is (each half of it): their own games (Families), a kickabout, or kites. */
    static final int GAMES = 0, BALL = 1, KITES = 2;
    /** Each town's afternoon, as decided for the half it is: {day * 2 + half, what}. */
    private static final Map<UUID, long[]> AFTERNOON = new ConcurrentHashMap<>();

    /**
     * The children's afternoon in this town now (Families' play time, after school and before supper; in its two halves):
     * a kickabout when the town has a football to take out, kites on a dry afternoon with wind enough and kites to fly
     * (the windier, the likelier), or their own games of tag and hide-and-seek. Decided once for each half and kept, so
     * the children are not called from one to the other.
     */
    static int afternoon(ServerLevel level, UUID village, long day, long t) {
        if (t < Families.PLAY_FROM || t >= Families.PLAY_TO || level.isRaining()) return GAMES;
        int half = t < (Families.PLAY_FROM + Families.PLAY_TO) / 2 ? 0 : 1;
        long key = day * 2 + half;
        long[] c = AFTERNOON.get(village);
        if (c != null && c[0] == key) return (int) c[1];
        boolean ball = Kickabout.on(village) || Market.stock(level, village, Kickabout::isFootball) > 0;
        boolean kites = Kites.flyable(level, village, day) && (Market.stock(level, village, Kites::isKite) > 0 || childrenHaveKites(village));
        double wind = Kites.wind(level, village, day)[0];
        int roll = Math.floorMod((int) (day * 7 + half * 3) + village.hashCode(), 5);
        int what = GAMES;
        if (ball && kites) what = roll <= 1 ? (wind >= 2.2 ? KITES : BALL) : roll == 2 ? (wind >= 2.2 ? BALL : KITES) : roll == 3 ? KITES : GAMES;
        else if (ball) what = roll <= 2 ? BALL : GAMES;
        else if (kites) what = roll <= 2 ? KITES : GAMES;
        AFTERNOON.put(village, new long[]{ key, what });
        return what;
    }

    private static boolean childrenHaveKites(UUID village) {
        for (AssistantEntity a : Villages.folkOf(village)) if (a.isBaby() && a.countCarried(Kites::isKite) > 0) return true;
        return false;
    }

    /** The park, else the square: where the children play. {where, its name}. */
    static Object[] playground(UUID village, Villages.Village v) {
        Ledger.Building park = Park.nearest(village, v.centre(), 80);
        if (park != null) return new Object[]{ Park.layout(park).centre(), "the park" };
        return new Object[]{ v.centre(), "the square" };
    }

    /** Tests: the children's afternoon set to this (GAMES 0, BALL 1, KITES 2) for the rest of the day, or forgotten (-1). */
    public static void afternoonForTests(ServerLevel level, UUID village, int what) {
        if (what < 0) {
            AFTERNOON.remove(village);
            return;
        }
        long dt = level.getDayTime(), day = dt / 24000L;
        long t = dt % 24000L;
        int half = t < (Families.PLAY_FROM + Families.PLAY_TO) / 2 ? 0 : 1;
        AFTERNOON.put(village, new long[]{ day * 2 + half, what });
    }

    // ------------------------------------------------------------------ the folk (Families.hold)

    /**
     * A folk's own time with the town's pastimes (Families.hold, before the children's own games): a game of draughts,
     * a kickabout or a kite for a child, a quilt carried home to its bed. What it is doing, or null.
     */
    @Nullable
    public static String hold(ServerLevel level, VillageFolkEntity f, UUID village, long t, long day) {
        String d = Draughts.hold(level, f, village, t, day);
        if (d == null && f.isBaby() && Kites.staged(f, level.getGameTime())) d = Kites.hold(level, f, village, t, day);
        if (d == null && f.isBaby()) d = Kickabout.hold(level, f, village, t, day);
        if (d == null && f.isBaby()) d = Kites.hold(level, f, village, t, day);
        if (d == null && !f.isBaby()) d = Quilts.hold(level, f, village, t, day);
        return d;
    }

    /**
     * Its spirits (VillageFolkEntity.refreshMood): a night under a quilt (and warm in winter), a game of draughts won,
     * a kite flown or a kickabout, a festival lit with lanterns.
     */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        m = Quilts.mood(f, day, m, why);
        m = Draughts.mood(f, day, m, why);
        m = Kites.mood(f, day, m, why);
        m = Kickabout.mood(f, day, m, why);
        m = Lanterns.mood(f, day, m, why);
        return m;
    }

    /** How it puts these, in its own words (FolkTalk.reason). */
    public static String moodWords(VillageFolkEntity f, String key) {
        var r = f.getRandom();
        return switch (key) {
            case "quilt" -> FolkTalk.pick(r, "I slept like a log under my patchwork quilt.", "Nothing like a good quilt on the bed.");
            case "warmquilt" -> FolkTalk.pick(r, "Snug as anything under the quilt, cold as it is.", "The quilt kept the frost off me all night.");
            case "draughts" -> FolkTalk.pick(r, "I won at draughts last night — crowned three, I did!", "Beat them at draughts. Still smiling.");
            case "kite" -> FolkTalk.pick(r, "My kite went ever so high!", "We flew kites in the park! Mine was the best.");
            case "kickabout" -> FolkTalk.pick(r, "We had a kickabout! I scored!", "Football in the park with everybody!");
            case "lanterns" -> FolkTalk.pick(r, "Wasn't the square lovely, all those lanterns?", "The lanterns at the festival — like stars, they were.");
            default -> "";
        };
    }

    /** The folk's card: its quilt, its draughts, its kite, its lute, its slate. Null with none. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        List<String> bits = new ArrayList<>();
        for (String s : new String[]{ Quilts.card(f), Draughts.card(f), Kites.card(f), Lutes.card(f), Slates.card(f) }) {
            if (s != null && !s.isEmpty()) bits.add(s);
        }
        return bits.isEmpty() ? null : String.join("; ", bits);
    }

    /** The gazette's "Home and play": yesterday's football and its goals, the draughts, the kites, the lanterns. Null with nothing. */
    @Nullable
    public static String gazette(ServerLevel level, UUID village, long day) {
        List<String> lines = new ArrayList<>();
        lines.addAll(Kickabout.gazette(village, day));
        lines.addAll(Draughts.gazette(village, day));
        lines.addAll(Kites.gazette(village, day));
        lines.addAll(Lanterns.gazette(village, day));
        if (lines.isEmpty()) return null;
        return "§lHome and play§r\n" + String.join("\n", lines.subList(0, Math.min(6, lines.size())));
    }

    /** Something of the day's play, kept a few days for the gazette: by the day, a line at a time. */
    static void news(UUID village, long day, String line) {
        String key = "leisure.news/" + day;
        String was = Ledger.note(village, key);
        Ledger.note(village, key, (was == null || was.isEmpty() ? "" : was + "\n") + line.replace("\n", " "));
        Ledger.forget(village, "leisure.news/" + (day - 4));
    }

    /** Yesterday's (or any day's) lines kept for the gazette, of this kind ("football:", "draughts:"...). */
    static List<String> newsOf(UUID village, long day, String kind) {
        List<String> out = new ArrayList<>();
        String s = Ledger.note(village, "leisure.news/" + day);
        if (s == null || s.isEmpty()) return out;
        for (String l : s.split("\n")) if (l.startsWith(kind)) out.add(l.substring(kind.length()).trim());
        return out;
    }

    // ------------------------------------------------------------------ a town's ball left about

    /** A town's football nobody has played with for a minute: back into the stores it came out of. */
    static void ballHome(ServerLevel level, FootballEntity ball) {
        Villages.Village v = Villages.get(ball.town());
        if (v == null) return;
        Crafts.store(level, v, ball.getItem().copy());
        ball.discard();
    }

    // ------------------------------------------------------------------ the books and the command

    /** The town's pastimes in lines: what it has of each, what it wants, who made what lately, what it is short of. */
    public static List<String> status(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        out.add("Home and play in " + Villages.name(id) + ":");
        Map<Item, Integer> want = wanted(level, v);
        Map<Item, String> shortOf = SHORT.getOrDefault(id, Map.of());
        for (Item it : LeisureItems.all()) {
            int have = have(level, id, it), w = want.getOrDefault(it, 0);
            if (have == 0 && w == 0) continue;
            String name = new ItemStack(it).getHoverName().getString();
            out.add("  " + name + ": " + have + " in the stores" + (w > 0 ? ", wants " + w : "") + " (" + Tiers.of(level, it).label + ", "
                + String.format(java.util.Locale.ROOT, "%.2f", Prices.each(it)) + " coins)" + (shortOf.containsKey(it) ? "; short of " + shortOf.get(it) : ""));
        }
        List<String> made = MADE.get(id);
        if (made != null && !made.isEmpty()) out.add("  Lately: " + String.join("; ", new ArrayList<>(made)));
        out.add("  Quilts: " + Quilts.status(level, v));
        out.add("  Lutes: " + Lutes.status(level, v));
        out.add("  Draughts: " + Draughts.status(level, v));
        out.add("  Kites: " + Kites.status(level, v));
        out.add("  Football: " + Kickabout.status(level, v));
        out.add("  Lanterns: " + Lanterns.status(level, v));
        out.add("  Slates: " + Slates.status(level, v));
        return out;
    }

    /**
     * /village items leisure: the town's pastimes. For operators: /village items leisure stage (every one of them out at
     * once for the photographs: a showcase wall of the seven, a quilt on a bed, the tavern's draughts game, the children's
     * kites and kickabout, the lanterns strung over the square, the pupils' slates), and make (the town's makers at it now).
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("items").then(Commands.literal("leisure").executes(Pastimes::tell)
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> say(ctx, withTown(ctx, (level, v) ->
                String.join("\n", LeisureStage.stage(level, v, BlockPos.containing(ctx.getSource().getPosition())))))))
            .then(Commands.literal("make").requires(src -> src.hasPermission(2)).executes(ctx -> say(ctx, withTown(ctx, (level, v) -> {
                WANTS.remove(v.id());
                List<String> did = new ArrayList<>();
                for (int i = 0; i < 12; i++) {
                    String m = craftNow(level, v, null, null);
                    if (m == null) break;
                    did.add(m);
                }
                return did.isEmpty() ? "MAKE nothing wanted that the stores can run to" : "MAKE " + String.join("; ", did);
            })))));
    }

    interface TownJob { String run(ServerLevel level, Villages.Village v); }

    static String withTown(CommandContext<CommandSourceStack> ctx, TownJob job) {
        BlockPos here = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(ctx.getSource().getLevel(), here, Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        return v == null ? "no village yet" : job.run(ctx.getSource().getLevel(), v);
    }

    static int say(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int tell(CommandContext<CommandSourceStack> ctx) {
        return say(ctx, withTown(ctx, (level, v) -> String.join("\n", status(level, v))));
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the town's wants now (afresh). */
    public static Map<Item, Integer> wantedForTests(ServerLevel level, Villages.Village v) {
        WANTS.remove(v.id());
        return wanted(level, v);
    }

    /** Tests: a turn at the pastimes for this folk's trade now (afresh): what it made, or null. */
    @Nullable
    public static String craftForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = f.ownerId() == null ? null : Villages.get(f.ownerId());
        if (v == null) return null;
        WANTS.remove(v.id());
        return craftNow(level, v, f, f.stationTask());
    }

    /** Tests: the town's bench now (no tailor, no shop): what it made, or null. */
    @Nullable
    public static String benchForTests(ServerLevel level, Villages.Village v) {
        WANTS.remove(v.id());
        List<String> before = new ArrayList<>(MADE.getOrDefault(v.id(), List.of()));
        bench(level, v);
        List<String> after = MADE.getOrDefault(v.id(), List.of());
        return after.size() > before.size() || !after.equals(before) ? after.get(after.size() - 1) : null;
    }

    /** Tests: the town's round now. */
    public static void roundForTests(ServerLevel level, Villages.Village v) {
        round(level, v);
    }

    /** Tests: what the town is short of for this, in words, or null. */
    @Nullable
    public static String shortForTests(UUID village, Item it) {
        return SHORT.getOrDefault(village, Map.of()).get(it);
    }
}
