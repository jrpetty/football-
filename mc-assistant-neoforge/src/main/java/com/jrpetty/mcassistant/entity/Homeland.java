package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The land a village stands in, and what it makes of the village. Every place is different: a
 * town on the coast lives by its boats and its trade and looks to a harbourmaster; one up in the
 * mountains digs, smelts, and chooses a hard, steady hand to lead it; a forest town cuts timber
 * and hunts; a desert town builds in sandstone round its well.
 *
 * <p>The land is looked over once, when the village is founded (or the first morning after, for
 * a village from before this), from the ground round its heart: the lie of it (sea, river,
 * mountain), and what grows on it. Then:
 * <ul>
 * <li><b>Its trades</b> lean to the land: a coast or river town takes up fishing early and keeps
 *     more fishers, a forest town more woodcutters and hunters, a mountain town more miners, a
 *     savanna or meadow town more stock.</li>
 * <li><b>Its leader.</b> The elder is called what the land calls its headman (harbourmaster,
 *     warden, thane, wellkeeper...), and the folk look for the nature the land asks for in whoever
 *     leads them — open and easy on the coast, hard and steady in the hills, quiet and watchful in
 *     the woods — which in turn is how its elder treats the neighbours (Envoys).</li>
 * <li><b>Its houses</b> are rebuilt in the land's own stone when it comes to rebuilding them:
 *     sandstone in the desert, terracotta in the badlands, andesite in the hills, mossy stone in
 *     the jungle — while the stores have it; the timber is already whatever its woods grow.</li>
 * <li><b>Its name</b>, for a village founded on it: a haven or a mere on the coast, a crag or a
 *     fell in the hills, a fen in the swamp.</li>
 * </ul>
 */
public final class Homeland {

    private Homeland() {}

    public enum Land {
        COAST("on the coast", "a fishing town", "harbourmaster"),
        RIVER("on a river", "a river town", "reeve"),
        FOREST("in the forest", "a forest town", "warden"),
        TAIGA("in the pine woods", "a timber town", "warden"),
        SNOW("in the snowfields", "a hardy northern town", "hearthkeeper"),
        MOUNTAIN("in the mountains", "a mining town", "thane"),
        DESERT("in the desert", "a desert town", "wellkeeper"),
        SAVANNA("on the savanna", "a herding town", "herdmaster"),
        JUNGLE("in the jungle", "a jungle town", "chief"),
        SWAMP("in the swamp", "a fen town", "fen-reeve"),
        BADLANDS("in the badlands", "a mesa town", "headman"),
        MEADOW("among the cherry groves", "a meadow town", "mayor"),
        PLAINS("on the plains", "a farming town", "elder");

        public final String where, kind, leader;

        Land(String where, String kind, String leader) {
            this.where = where;
            this.kind = kind;
            this.leader = leader;
        }
    }

    private static final Map<UUID, Land> KNOWN = new ConcurrentHashMap<>();

    public static void resetForTests() {
        KNOWN.clear();
    }

    /** Tests: a village on this land (the test world is all plains). */
    public static void setForTests(UUID village, Land l) {
        KNOWN.put(village, l);
        Ledger.note(village, "land", l.name());
    }

    /** Tests: the name a village founded on this land would get. */
    @Nullable
    public static String nameForTests(UUID village, Land l) {
        return nameFor(village, "Ash", l);
    }

    /** The village's land as it was surveyed, or null before it has been. */
    @Nullable
    public static Land known(@Nullable UUID village) {
        if (village == null) return null;
        Land l = KNOWN.get(village);
        if (l != null) return l;
        String note = Ledger.note(village, "land");
        if (note == null || note.isEmpty()) return null;
        try {
            l = Land.valueOf(note);
        } catch (IllegalArgumentException e) {
            return null;
        }
        KNOWN.put(village, l);
        return l;
    }

    /** The village's land, plains until it has been looked over. */
    public static Land of(@Nullable UUID village) {
        Land l = known(village);
        return l == null ? Land.PLAINS : l;
    }

    /** Look the land over, once (the founding, or the first morning after for an older village). */
    public static Land survey(ServerLevel level, Villages.Village v) {
        Land l = known(v.id());
        if (l != null) return l;
        l = read(level, v.centre());
        if (l == null) return Land.PLAINS;                                   // not loaded yet: another morning
        KNOWN.put(v.id(), l);
        Ledger.note(v.id(), "land", l.name());
        return l;
    }

    /** What the ground within fifty blocks of the heart says the land is, or null if it is not loaded. */
    @Nullable
    static Land read(ServerLevel level, BlockPos centre) {
        int seen = 0, sea = 0, river = 0, water = 0, snow = 0, mountain = 0, desert = 0, badlands = 0, savanna = 0,
            jungle = 0, swamp = 0, taiga = 0, forest = 0, cherry = 0;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int dx = -48; dx <= 48; dx += 12) {
            for (int dz = -48; dz <= 48; dz += 12) {
                int x = centre.getX() + dx, z = centre.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockPos at = new BlockPos(x, y - 1, z);
                seen++;
                if (!level.getFluidState(at).isEmpty()) water++;
                else {
                    int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    lo = Math.min(lo, ground);
                    hi = Math.max(hi, ground);
                }
                Holder<Biome> b = level.getBiome(at);
                String key = b.unwrapKey().map(k -> k.location().getPath()).orElse("");
                if (b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_BEACH)) sea++;
                if (b.is(BiomeTags.IS_RIVER)) river++;
                if (b.is(BiomeTags.IS_MOUNTAIN) || key.contains("peaks") || key.contains("slopes") || key.contains("windswept")) mountain++;
                if (b.is(BiomeTags.IS_BADLANDS)) badlands++;
                if (key.contains("desert")) desert++;
                if (b.is(BiomeTags.IS_SAVANNA)) savanna++;
                if (b.is(BiomeTags.IS_JUNGLE) || key.contains("bamboo")) jungle++;
                if (key.contains("swamp") || key.contains("mangrove")) swamp++;
                if (b.is(BiomeTags.IS_TAIGA) && !key.contains("snowy")) taiga++;
                if (key.contains("snowy") || key.contains("frozen") || key.contains("ice") || key.contains("grove")) snow++;
                if (b.is(BiomeTags.IS_FOREST) && !key.contains("cherry")) forest++;
                if (key.contains("cherry")) cherry++;
            }
        }
        if (seen < 20) return null;
        double n = seen;
        if (sea / n >= 0.2) return Land.COAST;
        if (mountain / n >= 0.3 || hi != Integer.MIN_VALUE && hi - lo >= 32) return Land.MOUNTAIN;
        if (badlands / n >= 0.3) return Land.BADLANDS;
        if (desert / n >= 0.3) return Land.DESERT;
        if (snow / n >= 0.4) return Land.SNOW;
        if (jungle / n >= 0.3) return Land.JUNGLE;
        if (swamp / n >= 0.3) return Land.SWAMP;
        if (savanna / n >= 0.3) return Land.SAVANNA;
        if (taiga / n >= 0.3) return Land.TAIGA;
        if (cherry / n >= 0.25) return Land.MEADOW;
        if (forest / n >= 0.35) return Land.FOREST;
        if (river / n >= 0.1 || water / n >= 0.12) return Land.RIVER;
        return Land.PLAINS;
    }

    // ------------------------------------------------------------------ the trades

    /** How much more (or less) of a trade the land calls for than an ordinary village wants. */
    public static double lean(@Nullable UUID village, StationTask t) {
        Land l = known(village);
        if (l == null) return 1.0;
        return switch (l) {
            case COAST -> t == StationTask.FISH ? 3.0 : t == StationTask.FARM ? 0.9 : t == StationTask.MINE ? 0.8 : 1.0;
            case RIVER -> t == StationTask.FISH ? 2.0 : t == StationTask.FARM ? 1.2 : 1.0;
            case FOREST -> t == StationTask.WOOD ? 1.6 : t == StationTask.HUNT ? 1.5 : t == StationTask.FARM ? 0.9 : 1.0;
            case TAIGA -> t == StationTask.WOOD ? 1.5 : t == StationTask.HUNT ? 1.6 : t == StationTask.FISH ? 1.2 : t == StationTask.FARM ? 0.8 : 1.0;
            case SNOW -> t == StationTask.HUNT ? 1.8 : t == StationTask.FISH ? 1.5 : t == StationTask.MINE ? 1.2 : t == StationTask.FARM ? 0.7 : 1.0;
            case MOUNTAIN -> t == StationTask.MINE ? 1.6 : t == StationTask.SMELT ? 1.3 : t == StationTask.FARM ? 0.8 : 1.0;
            case DESERT -> t == StationTask.MINE ? 1.4 : t == StationTask.FARM ? 0.8 : t == StationTask.RANCH ? 0.7 : t == StationTask.HUNT ? 0.6 : 1.0;
            case SAVANNA -> t == StationTask.RANCH ? 2.0 : t == StationTask.HUNT ? 1.4 : t == StationTask.FARM ? 0.9 : 1.0;
            case JUNGLE -> t == StationTask.WOOD ? 1.4 : t == StationTask.HUNT ? 1.3 : t == StationTask.FARM ? 1.1 : 1.0;
            case SWAMP -> t == StationTask.FISH ? 1.6 : t == StationTask.WOOD ? 1.1 : t == StationTask.FARM ? 0.8 : 1.0;
            case BADLANDS -> t == StationTask.MINE ? 1.8 : t == StationTask.SMELT ? 1.3 : t == StationTask.FARM ? 0.7 : 1.0;
            case MEADOW -> t == StationTask.RANCH ? 1.3 : t == StationTask.BEEKEEP ? 1.5 : t == StationTask.FARM ? 1.1 : 1.0;
            case PLAINS -> 1.0;
        };
    }

    /**
     * The size of village at which the land takes up a trade, if sooner than anywhere else: a
     * coast town fishes from its first days, a forest or snowfield town hunts from eight. Or -1.
     */
    public static int sooner(@Nullable UUID village, StationTask t) {
        Land l = known(village);
        if (l == null) return -1;
        return switch (l) {
            case COAST -> t == StationTask.FISH ? 4 : -1;
            case RIVER, SWAMP -> t == StationTask.FISH ? 8 : -1;
            case FOREST, TAIGA -> t == StationTask.HUNT ? 8 : -1;
            case SNOW -> t == StationTask.HUNT ? 6 : t == StationTask.FISH ? 8 : -1;
            case SAVANNA -> t == StationTask.RANCH ? 8 : t == StationTask.HUNT ? 10 : -1;
            case MEADOW -> t == StationTask.RANCH ? 10 : -1;
            default -> -1;
        };
    }

    /** One more of a trade the land lives by than a village usually keeps at most. */
    public static int extraMost(@Nullable UUID village, StationTask t) {
        return lean(village, t) >= 1.5 ? 1 : 0;
    }

    // ------------------------------------------------------------------ the leader

    /** What the land calls the one who leads it: "harbourmaster", "thane", "elder". */
    public static String leaderTitle(@Nullable UUID village) {
        return of(village).leader;
    }

    /**
     * How well a folk's nature suits leading a village in this land, for the choosing of the
     * elder: open-handed and easy on the coast and the rivers, hard and steady in the hills and the
     * snow, quiet and watchful in the woods, shrewd and wary in the desert.
     */
    public static int leaderFit(@Nullable UUID village, VillageFolkEntity f) {
        Land l = known(village);
        if (l == null) return 0;
        Social.Trait[] want = switch (l) {
            case COAST -> new Social.Trait[]{ Social.Trait.SOCIABLE, Social.Trait.CHEERFUL };
            case RIVER -> new Social.Trait[]{ Social.Trait.GENEROUS, Social.Trait.EASYGOING };
            case FOREST, TAIGA -> new Social.Trait[]{ Social.Trait.SHY, Social.Trait.CURIOUS };
            case SNOW, MOUNTAIN -> new Social.Trait[]{ Social.Trait.HARDWORKING, Social.Trait.GRUMPY };
            case DESERT -> new Social.Trait[]{ Social.Trait.HARDWORKING, Social.Trait.SHY };
            case SAVANNA -> new Social.Trait[]{ Social.Trait.GENEROUS, Social.Trait.SOCIABLE };
            case JUNGLE -> new Social.Trait[]{ Social.Trait.CURIOUS, Social.Trait.CHEERFUL };
            case SWAMP -> new Social.Trait[]{ Social.Trait.GRUMPY, Social.Trait.SHY };
            case BADLANDS -> new Social.Trait[]{ Social.Trait.GRUMPY, Social.Trait.HARDWORKING };
            case MEADOW -> new Social.Trait[]{ Social.Trait.CHEERFUL, Social.Trait.GENEROUS };
            case PLAINS -> new Social.Trait[]{};
        };
        int fit = 0;
        for (Social.Trait t : want) if (f.life().has(t)) fit += 8;
        return fit;
    }

    // ------------------------------------------------------------------ the houses

    /** The land's own walling, and what pays for a block of it out of the stores; null for the usual. A
     *  block is paid for in itself or in what it is cut from, four for four (cut sandstone of sandstone,
     *  polished andesite of andesite); never in something it cannot be made of. */
    public record Stone(Block block, Predicate<ItemStack> pay, int each) {}

    @Nullable
    public static Stone walls(@Nullable UUID village) {
        Land l = known(village);
        if (l == null) return null;
        return switch (l) {
            case DESERT -> new Stone(Blocks.CUT_SANDSTONE, s -> s.is(Items.SANDSTONE) || s.is(Items.CUT_SANDSTONE), 1);
            case BADLANDS -> new Stone(Blocks.TERRACOTTA, s -> s.is(Items.TERRACOTTA) || s.is(Items.ORANGE_TERRACOTTA)
                || s.is(Items.RED_TERRACOTTA) || s.is(Items.BROWN_TERRACOTTA) || s.is(Items.YELLOW_TERRACOTTA), 1);
            case MOUNTAIN -> new Stone(Blocks.POLISHED_ANDESITE, s -> s.is(Items.ANDESITE) || s.is(Items.POLISHED_ANDESITE), 1);
            // Mossy stone bricks only of the masons' mossy bricks (stone bricks and the woodcutters' vines,
            // Masonry): moss is a nicety, and without it the walls go up in plain stone like anybody's.
            case JUNGLE, SWAMP -> new Stone(Blocks.MOSSY_STONE_BRICKS, s -> s.is(Items.MOSSY_STONE_BRICKS), 1);
            case SNOW -> new Stone(Blocks.STONE_BRICKS, s -> s.is(Items.STONE_BRICKS), 1);
            default -> null;
        };
    }

    // ------------------------------------------------------------------ the name

    private static final String[][] TAILS = {
        /* COAST */ {"haven", "mere", "port", "wick", "sands", "cliff", "strand"},
        /* RIVER */ {"ford", "brook", "bridge", "mouth", "wash", "bank"},
        /* FOREST */ {"wood", "hurst", "holt", "shaw", "grove", "leigh"},
        /* TAIGA */ {"pines", "holt", "fell", "wood", "hurst"},
        /* SNOW */ {"frost", "fell", "hope", "rime", "cold"},
        /* MOUNTAIN */ {"crag", "tor", "fell", "scar", "peak", "edge"},
        /* DESERT */ {"well", "sands", "dune", "spring", "rock"},
        /* SAVANNA */ {"veld", "field", "acre", "plain", "stead"},
        /* JUNGLE */ {"vale", "grove", "falls", "glade"},
        /* SWAMP */ {"fen", "marsh", "moss", "mire", "carr"},
        /* BADLANDS */ {"mesa", "butte", "red", "scar", "rock"},
        /* MEADOW */ {"lea", "bloom", "meadow", "blossom"},
        /* PLAINS */ {},
    };

    /** A name for a village founded on this land, from the head of its own name; null for the usual. */
    @Nullable
    static String nameFor(UUID village, String head, Land l) {
        String[] tails = TAILS[l.ordinal()];
        if (tails.length == 0) return null;
        long b = village.getLeastSignificantBits();
        String tail = tails[(int) Math.floorMod(b ^ (b >>> 31), (long) tails.length)];
        if (head.toLowerCase(java.util.Locale.ROOT).endsWith(tail)) tail = tails[(int) Math.floorMod(b + 1, (long) tails.length)];
        return head + tail;
    }

    /** The land, as a line: "on the coast — a fishing town; its harbourmaster, Bramble". */
    public static String line(UUID village) {
        Land l = known(village);
        if (l == null) return "not yet looked over";
        String elder = Villages.elderName(village);
        return l.where + " — " + l.kind + (elder == null || elder.isEmpty() ? "" : "; its " + l.leader + ", " + elder);
    }
}
