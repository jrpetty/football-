package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tourists. [batchG] A town of renown draws people from away to see it: its museum, its great works, the
 * statues on its square, its park. Its renown (Villages.renown: ten a great work and what the museum has on
 * show, the rarer the more) and five more for every statue to a hero make its draw; at fifteen it has
 * visitors, one or two at a time, a few days apart (the more renowned, the oftener).
 * <ul>
 * <li>A tourist walks in from the edge with a purse of four to ten coins of its own (money from outside,
 *     kept small: it is the town's takings from being worth the walk).</li>
 * <li>It walks the sights in turn, the museum first, stops before each a while and says what it thinks.</li>
 * <li>Then it has a drink or a bite at the café, and buys itself a souvenir at the shop, at their prices, as
 *     any folk does (Cafe.folkBuys, Cafe.folkShops): the coin goes into the treasury and the shops' books.</li>
 * <li>With an inn in the town it stays the night there and goes home in the morning; with none, it goes
 *     home at dusk.</li>
 * </ul>
 * The town's books count the visitors of the week and what they spent (Visitors.book).
 */
public final class Tourists {

    private Tourists() {}

    /** The draw a town needs for tourists. */
    static final int DRAW = 15;
    /** How long a tourist stands before a sight, looking (ticks). */
    static final int LOOK = 300;

    /** Since when each tourist has stood before the sight it is at, and whether it has said its piece. */
    private static final Map<UUID, Long> LOOKING = new ConcurrentHashMap<>();

    static void resetForTests() {
        LOOKING.clear();
    }

    /** What draws visitors to a town: its renown, and five for every statue on its square. */
    static int draw(UUID town) {
        return Villages.renown(town) + 5 * Ledger.statues(town);
    }

    /** Tests: the town's draw for tourists. */
    public static int drawForTests(UUID town) {
        return draw(town);
    }

    // ------------------------------------------------------------------ coming

    /** Should tourists come today (the town's round)? A town of renown, every two to five days, two at a time at most. */
    static void consider(ServerLevel level, Villages.Village v, long day, long t) {
        UUID id = v.id();
        if (t < 1500L || t > 6000L) return;
        int draw = draw(id);
        if (draw < DRAW || Raids.underAlarm(id) || Weather.stormy(level) || level.isRaining()) return;
        long last = Bard.parse(Ledger.note(id, "visit/tourist"));
        int gap = Math.max(2, 5 - draw / 20);
        if (last >= 0 && day >= last && day - last < gap) return;
        if (!Visitors.inTown(level, id, Visitors.Kind.TOURIST).isEmpty()) return;
        int party = draw >= 30 && Math.floorMod(id.hashCode() + (int) day, 2) == 0 ? 2 : 1;
        for (int i = 0; i < party; i++) come(level, v, day);
    }

    /** A tourist comes in from the edge with a purse of its own; a night at the inn, if the town has one. */
    @Nullable
    static VillageFolkEntity come(ServerLevel level, Villages.Village v, long day) {
        RandomSource r = level.getRandom();
        int purse = 4 + r.nextInt(7);
        int nights = Visitors.inn(v.id()) != null ? 1 : 0;
        VillageFolkEntity f = Visitors.arrive(level, v, Visitors.Kind.TOURIST, day, nights, purse, List.of(), "a visitor from far away");
        if (f != null) Ledger.note(v.id(), "visit/tourist", Long.toString(day));
        return f;
    }

    /** /village visitors tourist, and the game tests: a tourist comes now. */
    @Nullable
    static VillageFolkEntity comeForTests(ServerLevel level, Villages.Village v) {
        return come(level, v, level.getDayTime() / 24000L);
    }

    /** Tests: a tourist comes to this town now (walking in from the edge). */
    @Nullable
    public static VillageFolkEntity arriveNowForTests(ServerLevel level, Villages.Village v) {
        return comeForTests(level, v);
    }

    /** At the heart: the town takes note of it. */
    static void arrived(ServerLevel level, Villages.Village town, VillageFolkEntity f, Visitors.Visit v, long day) {
        List<Sight> sights = sights(level, town);
        String name = Villages.name(town.id());
        String first = sights.isEmpty() ? "streets" : sights.get(0).words();
        Villages.tell(town.id(), day, v.name + ", a visitor from afar, came to see " + name + "'s " + first);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "So this is " + name + "! Where's the " + first + "?",
            "All this way to see " + name + "'s " + first + ", and here I am!"));
    }

    // ------------------------------------------------------------------ the sights

    /** A thing worth the walk: what it is, in words, and where to stand to see it. */
    record Sight(String key, String words, BlockPos at) {}

    /** The town's sights, the best first: the museum, the statues, its great works, the park, and the rest. */
    static List<Sight> sights(ServerLevel level, Villages.Village town) {
        UUID id = town.id();
        List<Sight> out = new ArrayList<>();
        add(out, id, "museum", "museum");
        int statues = Ledger.statues(id);
        for (int i = 0; i < Math.min(statues, Citizens.STATUES.length); i++) {
            int[] o = Citizens.STATUES[i];
            out.add(new Sight("statue" + i, "statue to its hero", town.centre().offset(o[0], 0, o[1])));
        }
        add(out, id, "monument", "monument");
        add(out, id, "park", "park");
        add(out, id, "fountain", "fountain");
        add(out, id, "belltower", "bell tower");
        add(out, id, "chapel", "chapel");
        add(out, id, "lighthouse", "lighthouse");
        add(out, id, "barracks", "barracks");
        add(out, id, "granary", "great granary");
        add(out, id, "townhall", "leader's hall");
        return out;
    }

    private static void add(List<Sight> out, UUID id, String structure, String words) {
        Ledger.Building b = Visitors.building(id, structure);
        if (b != null) out.add(new Sight(structure, words, b.anchor()));
    }

    /**
     * A look at a tourist's stay, every half second (Visitors): the next sight and a while before it, then
     * the café, then the shop; the inn at dusk if there is one. True when it is time it went home.
     */
    static boolean stay(ServerLevel level, Villages.Village town, VillageFolkEntity f, Visitors.Visit v, long day, long t) {
        Ledger.Building inn = Visitors.inn(town.id());
        // Dusk: home, or to the inn for the night.
        if (t >= 11500L || t < 500L) {
            if (inn == null || day >= v.leave) return true;
            if (!Visitors.bedDown(level, f, v, inn)) return true;      // no bed to be had: it goes home after all
            return false;
        }
        Visitors.rise(level, f, v);
        List<Sight> sights = sights(level, town);
        int step = v.sight;
        if (step < sights.size()) {
            look(level, f, v, sights.get(step));
            return false;
        }
        step -= sights.size();
        if (step == 0) {                                          // a drink or a bite at the café
            shopAt(level, town, f, v, "cafe");
            return false;
        }
        if (step == 1) {                                          // a souvenir at the shop
            shopAt(level, town, f, v, "shop");
            return false;
        }
        // Seen it all: home now, or (staying the night at the inn) about the square till dusk.
        if (inn == null || day >= v.leave) return true;
        Visitors.walk(f, level, town.centre(), 4.0, 0.6D, v.walk);
        return false;
    }

    /** At a sight: there, a while before it, and a word about it. */
    private static void look(ServerLevel level, VillageFolkEntity f, Visitors.Visit v, Sight s) {
        if (!Visitors.walk(f, level, s.at(), 7.0, 0.7D, v.walk)) return;
        f.getLookControl().setLookAt(s.at().getX() + 0.5, s.at().getY() + 2.0, s.at().getZ() + 0.5);
        long now = level.getGameTime();
        Long since = LOOKING.get(f.getUUID());
        if (since == null) {
            LOOKING.put(f.getUUID(), now);
            RandomSource r = f.getRandom();
            FolkTalk.speak(f, FolkTalk.pick(r, "Look at that " + s.words() + "!", "So that's the famous " + s.words() + ".",
                "The " + s.words() + "! It's even finer than they said.", "I must remember every bit of this " + s.words() + "."));
            return;
        }
        if (now - since < LOOK) return;
        LOOKING.remove(f.getUUID());
        v.sight++;
        Visitors.save(f, v);
    }

    /** At the café (or the shop): in, and out with something, paid for out of its purse; on to the next either way. */
    private static void shopAt(ServerLevel level, Villages.Village town, VillageFolkEntity f, Visitors.Visit v, String structure) {
        Ledger.Building b = Visitors.building(town.id(), structure);
        if (b == null) {
            v.sight++;
            Visitors.save(f, v);
            return;
        }
        if (!Visitors.walk(f, level, b.anchor(), 4.0, 0.7D, v.walk)) return;
        String had = buy(level, town, f, structure);
        if (had != null) {
            FolkTalk.speak(f, structure.equals("cafe") ? FolkTalk.pick(f.getRandom(), "Mm! " + FolkTalk.cap(had) + ". Lovely.", "Just what I needed after all that walking.")
                : FolkTalk.pick(f.getRandom(), "A little something to remember " + Villages.name(town.id()) + " by.", "This'll go on my mantelpiece!"));
        }
        v.sight++;
        Visitors.save(f, v);
    }

    /** What it buys at the café or the shop, out of its own purse, as a folk does; null if nothing. */
    @Nullable
    static String buy(ServerLevel level, Villages.Village town, VillageFolkEntity f, String structure) {
        int before = f.purse();
        String had = structure.equals("cafe") ? Cafe.folkBuys(level, town, f) : Cafe.folkShops(level, town, f, false);
        int paid = before - f.purse();
        if (had != null) {
            LOG.info("[MCA-VISIT] {} bought {} at the {} of {} for {} coins", f.displayNameCap(), had, structure, Villages.name(town.id()), paid);
        }
        return had;
    }

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** What it is doing, for its card. */
    static String doing(VillageFolkEntity f, Visitors.Visit v) {
        if (!(f.level() instanceof ServerLevel level)) return "Seeing the town";
        Villages.Village town = Villages.get(v.town);
        if (town == null) return "Seeing the town";
        List<Sight> sights = sights(level, town);
        if (v.sight < sights.size()) return "Seeing the " + sights.get(v.sight).words();
        int step = v.sight - sights.size();
        return step == 0 ? "Off to the café" : step == 1 ? "Buying a souvenir at the shop" : "Seen the sights";
    }

    /** Tests: the tourist's round, done at once: every sight seen, the café and the shop. What it spent. */
    public static int roundForTests(ServerLevel level, VillageFolkEntity f) {
        Visitors.Visit v = Visitors.visit(f);
        Villages.Village town = v == null ? null : Villages.get(v.town);
        if (town == null) return 0;
        int before = f.purse();
        v.sight = sights(level, town).size();
        buy(level, town, f, "cafe");
        buy(level, town, f, "shop");
        v.sight += 2;
        Visitors.save(f, v);
        return before - f.purse();
    }

    /** Tests: the sights a tourist would see in this town, in words. */
    public static List<String> sightsForTests(ServerLevel level, Villages.Village town) {
        List<String> out = new ArrayList<>();
        for (Sight s : sights(level, town)) out.add(s.words());
        return out;
    }
}
