package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.datafixers.util.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [cartographer] The old places the cartographer finds, and the explorer maps it makes of them.
 *
 * <p><b>The search.</b> The world's own structure search (the one the game's explorer maps and a cartographer
 * villager's trades use: {@code ChunkGenerator.findNearestMapStructure}) round the town, but only so far as the town
 * has been: its own country (its reach and the fields and woods its folk work beyond it), every way its scouts have
 * walked (the atlas's rings and bearings), round everything in the atlas and the caves' report, and along the roads
 * to its colonies and neighbours. A place further out than that is not found, however near the world would put it:
 * no cheating past what the town has seen. The structures the world holds in ground already loaded about the town
 * are looked over too (and are all a world with structures turned off has). Dungeons are no structure the game can
 * search for: the spawners of the loaded ground, on their mossy floors, are.
 *
 * <p><b>What it finds, and who gets it.</b> Mineshafts, dungeons, the trial chambers, ancient cities and (from the
 * Diamond Age) strongholds go to the cave team: into its report as a find to make for (its next trip heads that
 * way: CaveDwellers), and an explorer map into its leader's hands. Villages, pillager outposts, temples and witches'
 * huts go to the scouts: into the atlas, and a map to a scout. Ruined portals go to the Nether runners, and ocean
 * monuments to the kelp diver, once a town has those trades (looked up by name, so a later trade of that name gets
 * them without a word changed here); till then their maps wait in the stores, for sale. Everything found goes on the
 * atlas and in the town's books (the Maps page).
 *
 * <p><b>The maps.</b> Real explorer maps, made the way the game makes them: an empty locator map (eight paper and a
 * compass out of the stores, the game's recipe), centred on the place at the explorer's scale, the land shown from
 * its biomes as an explorer map shows it, and the place marked with its own mark (the monument's, the mansion's, a
 * red cross for treasure). The ocean, woodland and treasure maps carry the game's own names.
 */
public final class MapFinds {

    private MapFinds() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** Who a place's map goes to. */
    public enum Hands {
        CAVE_TEAM("the cave team"), SCOUTS("the scouts"), NETHER("the Nether runners"), DIVER("the kelp diver"), TOWN("the stores, for sale");

        public final String words;

        Hands(String words) { this.words = words; }
    }

    /** What the cartographer can find: in words, how the world is searched for it, who it goes to, from which age,
     *  how rare (for its price), its mark, the explorer map's scale, and the game's own name for the map (or null). */
    public enum Place {
        MINESHAFT("a mineshaft", StructureTags.MINESHAFT, null, Hands.CAVE_TEAM, Villages.Age.STONE, 1.0, 2, null),
        DUNGEON("a dungeon", null, null, Hands.CAVE_TEAM, Villages.Age.STONE, 1.2, 1, null),
        TRIAL_CHAMBERS("the trial chambers", StructureTags.ON_TRIAL_CHAMBERS_MAPS, null, Hands.CAVE_TEAM, Villages.Age.IRON, 3.0, 2,
            "filled_map.trial_chambers"),
        ANCIENT_CITY("an ancient city", null, BuiltinStructures.ANCIENT_CITY, Hands.CAVE_TEAM, Villages.Age.IRON, 4.0, 2, null),
        STRONGHOLD("a stronghold", StructureTags.EYE_OF_ENDER_LOCATED, null, Hands.CAVE_TEAM, Villages.Age.DIAMOND, 5.0, 2, null),
        VILLAGE("a village of villagers", StructureTags.VILLAGE, null, Hands.SCOUTS, Villages.Age.STONE, 1.0, 2, null),
        OUTPOST("a pillager outpost", null, BuiltinStructures.PILLAGER_OUTPOST, Hands.SCOUTS, Villages.Age.STONE, 1.5, 2, null),
        DESERT_TEMPLE("a desert temple", null, BuiltinStructures.DESERT_PYRAMID, Hands.SCOUTS, Villages.Age.STONE, 2.0, 2, null),
        JUNGLE_TEMPLE("a jungle temple", StructureTags.ON_JUNGLE_EXPLORER_MAPS, null, Hands.SCOUTS, Villages.Age.STONE, 2.0, 2,
            "filled_map.explorer_jungle"),
        SWAMP_HUT("a witch's hut", StructureTags.ON_SWAMP_EXPLORER_MAPS, null, Hands.SCOUTS, Villages.Age.STONE, 2.0, 2,
            "filled_map.explorer_swamp"),
        RUINED_PORTAL("a ruined portal", StructureTags.RUINED_PORTAL, null, Hands.NETHER, Villages.Age.STONE, 1.5, 2, null),
        MONUMENT("an ocean monument", StructureTags.ON_OCEAN_EXPLORER_MAPS, null, Hands.DIVER, Villages.Age.STONE, 4.0, 2, "filled_map.monument"),
        MANSION("a woodland mansion", StructureTags.ON_WOODLAND_EXPLORER_MAPS, null, Hands.SCOUTS, Villages.Age.STONE, 5.0, 2, "filled_map.mansion"),
        TREASURE("buried treasure", StructureTags.ON_TREASURE_MAPS, null, Hands.TOWN, Villages.Age.STONE, 3.0, 1, "filled_map.buried_treasure");

        public final String words;
        @Nullable final TagKey<Structure> tag;
        @Nullable final ResourceKey<Structure> key;
        public final Hands hands;
        final Villages.Age from;
        public final double rarity;
        final int zoom;
        @Nullable final String vanillaName;

        Place(String words, @Nullable TagKey<Structure> tag, @Nullable ResourceKey<Structure> key, Hands hands, Villages.Age from,
              double rarity, int zoom, @Nullable String vanillaName) {
            this.words = words;
            this.tag = tag;
            this.key = key;
            this.hands = hands;
            this.from = from;
            this.rarity = rarity;
            this.zoom = zoom;
            this.vanillaName = vanillaName;
        }

        /** "mineshaft", "ocean monument": the words without the article. */
        public String bare() {
            return words.replaceFirst("^(a|an|the) ", "");
        }
    }

    /** One place found: what, where, when, and who has its map. */
    public record Found(Place place, BlockPos at, long day, Hands hands, boolean mapped, String label) {
        String encode() {
            return place.name() + "|" + at.getX() + "|" + at.getY() + "|" + at.getZ() + "|" + day + "|" + hands.name() + "|" + (mapped ? 1 : 0)
                + "|" + label.replace('|', ' ').replace('\n', ' ');
        }

        @Nullable
        static Found decode(String s) {
            String[] p = s.split("\\|", -1);
            if (p.length < 8) return null;
            try {
                return new Found(Place.valueOf(p[0]), new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])),
                    Long.parseLong(p[4]), Hands.valueOf(p[5]), p[6].equals("1"), p[7]);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** A spot the search turned up: where, and which structure (null for a dungeon). */
    record Spot(BlockPos at, @Nullable Holder<Structure> structure) {}

    // ------------------------------------------------------------------ how far the town has been

    /** The town's own country: its reach and the fields, woods and mine its folk work beyond it. */
    static int country(UUID village) {
        return Villages.townReach(village) + 128;
    }

    /** The places the region's map marks and the roads lead to: colonies and the mother town, neighbours met, the mine. */
    static List<BlockPos> regionPlaces(ServerLevel level, Villages.Village v) {
        List<BlockPos> out = new ArrayList<>();
        UUID id = v.id();
        for (Map.Entry<UUID, UUID> e : Ledger.links().entrySet()) {
            UUID other = e.getValue().equals(id) ? e.getKey() : e.getKey().equals(id) ? e.getValue() : null;
            Villages.Village o = other == null ? null : Villages.get(other);
            if (o != null && o.dim().equals(level.dimension())) out.add(o.centre());
        }
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(id) || !o.dim().equals(level.dimension()) || out.contains(o.centre())) continue;
            if (Scouts.met(id, o.id())) out.add(o.centre());
        }
        BlockPos mine = TownMine.siteOf(id);
        if (mine != null) out.add(mine);
        return out;
    }

    /** Has the town been there, or near enough to know it? */
    public static boolean explored(ServerLevel level, Villages.Village v, BlockPos p) {
        UUID id = v.id();
        BlockPos home = v.centre();
        int dx = p.getX() - home.getX(), dz = p.getZ() - home.getZ();
        double d = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (d <= country(id)) return true;
        int ring = (int) (d / Scouts.RING);
        if (ring < Scouts.RINGS && Scouts.been(Scouts.explored(id), Scouts.bearingOf(dx, dz), ring)) return true;
        for (Scouts.Find x : Scouts.atlas(id)) if (Scouts.flat(x.at(), p) <= 96.0 * 96.0) return true;
        for (CaveDwellers.Find x : CaveDwellers.report(id)) if (Scouts.flat(x.at(), p) <= 64.0 * 64.0) return true;
        for (BlockPos t : regionPlaces(level, v)) {
            if (Scouts.flat(t, p) <= 96.0 * 96.0 || nearRoad(home, t, p, 48)) return true;
        }
        return false;
    }

    /** Within so many blocks of the straight way between two places (the road a caravan walks). */
    static boolean nearRoad(BlockPos a, BlockPos b, BlockPos p, int within) {
        double ax = a.getX(), az = a.getZ(), bx = b.getX() - ax, bz = b.getZ() - az, px = p.getX() - ax, pz = p.getZ() - az;
        double len = bx * bx + bz * bz;
        if (len < 1) return false;
        double t = Math.max(0, Math.min(1, (px * bx + pz * bz) / len));
        double ex = px - t * bx, ez = pz - t * bz;
        return ex * ex + ez * ez <= (double) within * within;
    }

    /** How far out the town has been at the furthest (the search's radius): never past six hundred and forty blocks. */
    public static int reach(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos home = v.centre();
        int r = country(id);
        long[] bits = Scouts.explored(id);
        for (int ring = 0; ring < Scouts.RINGS; ring++) {
            for (int b = 0; b < Scouts.BEARINGS; b++) if (Scouts.been(bits, b, ring)) r = Math.max(r, (ring + 1) * Scouts.RING);
        }
        for (Scouts.Find x : Scouts.atlas(id)) r = Math.max(r, (int) Math.sqrt(Scouts.flat(home, x.at())) + 96);
        for (CaveDwellers.Find x : CaveDwellers.report(id)) r = Math.max(r, (int) Math.sqrt(Scouts.flat(home, x.at())) + 64);
        for (BlockPos t : regionPlaces(level, v)) r = Math.max(r, (int) Math.sqrt(Scouts.flat(home, t)) + 96);
        return Math.min(640, r);
    }

    // ------------------------------------------------------------------ the search

    /** The structures a place is, as the world's registry has them; null if the world has none of them. */
    @Nullable
    static HolderSet<Structure> set(ServerLevel level, Place p) {
        Registry<Structure> reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        if (p.tag != null) return reg.getTag(p.tag).map(h -> (HolderSet<Structure>) h).orElse(null);
        if (p.key != null) return reg.getHolder(p.key).map(h -> (HolderSet<Structure>) HolderSet.direct(h)).orElse(null);
        return null;
    }

    /**
     * The nearest of a place to {@code from} that the town has been near, or null: the world's own search (where the
     * world makes structures at all), then the structures already in the loaded ground round the town. Never one of
     * the town's own (a vanilla village a town grew out of is not a find), never one already found.
     */
    @Nullable
    static Spot search(ServerLevel level, Villages.Village v, Place p, BlockPos from) {
        if (p == Place.DUNGEON) return dungeon(level, v);
        HolderSet<Structure> set = set(level, p);
        if (set == null) return null;
        int reach = reach(level, v);
        if (level.getServer().getWorldData().worldGenOptions().generateStructures()) {
            try {
                Pair<BlockPos, Holder<Structure>> r = level.getChunkSource().getGenerator()
                    .findNearestMapStructure(level, set, from, Math.max(1, reach / 16 + 1), false);
                if (r != null && fits(level, v, p, r.getFirst())) return new Spot(r.getFirst(), r.getSecond());
            } catch (RuntimeException e) {
                LOG.debug("[MCA-CARTO] the world's search for {} failed: {}", p, e.toString());
            }
        }
        return known(level, v, p, set, from, Math.min(reach, 400));
    }

    /** A spot the town may have: explored, not its own, not found before. */
    static boolean fits(ServerLevel level, Villages.Village v, Place p, BlockPos at) {
        if (!explored(level, v, at)) return false;
        Villages.Village near = Villages.nearest(level, at, 64);
        if (near != null && p == Place.VILLAGE) return false;          // a town of folk, not a village of villagers
        for (Found x : found(v.id())) if (x.place() == p && Scouts.flat(x.at(), at) < 48.0 * 48.0) return false;
        return true;
    }

    /** The structures of a place standing in the loaded ground within so far: the nearest that fits, or null. */
    @Nullable
    static Spot known(ServerLevel level, Villages.Village v, Place p, HolderSet<Structure> set, BlockPos from, int within) {
        Registry<Structure> reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Spot best = null;
        double bd = Double.MAX_VALUE;
        int c0x = (from.getX() - within) >> 4, c1x = (from.getX() + within) >> 4, c0z = (from.getZ() - within) >> 4, c1z = (from.getZ() + within) >> 4;
        for (int cx = c0x; cx <= c1x; cx++) {
            for (int cz = c0z; cz <= c1z; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (Map.Entry<Structure, StructureStart> e : chunk.getAllStarts().entrySet()) {
                    StructureStart start = e.getValue();
                    if (start == null || !start.isValid()) continue;
                    Holder<Structure> h = reg.wrapAsHolder(e.getKey());
                    if (!set.contains(h)) continue;
                    BoundingBox bb = start.getBoundingBox();
                    BlockPos at = new BlockPos(bb.getCenter().getX(), bb.getCenter().getY(), bb.getCenter().getZ());
                    if (!fits(level, v, p, at)) continue;
                    double d = Scouts.flat(at, from);
                    if (d < bd) { bd = d; best = new Spot(at, h); }
                }
            }
        }
        return best;
    }

    /** A dungeon: a spawner on a cobbled, mossy floor in the loaded ground the town has been near, not found before. */
    @Nullable
    static Spot dungeon(ServerLevel level, Villages.Village v) {
        BlockPos home = v.centre();
        int within = Math.min(reach(level, v), 400);
        Spot best = null;
        double bd = Double.MAX_VALUE;
        for (int cx = (home.getX() - within) >> 4; cx <= (home.getX() + within) >> 4; cx++) {
            for (int cz = (home.getZ() - within) >> 4; cz <= (home.getZ() + within) >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                    if (!(e.getValue() instanceof SpawnerBlockEntity)) continue;
                    BlockPos at = e.getKey();
                    var floor = level.getBlockState(at.below());
                    if (!floor.is(Blocks.MOSSY_COBBLESTONE) && !floor.is(Blocks.COBBLESTONE)) continue;
                    if (!fits(level, v, Place.DUNGEON, at)) continue;
                    double d = Scouts.flat(at, home);
                    if (d < bd) { bd = d; best = new Spot(at.immutable(), null); }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the town's finds

    /** Everything the cartographer has found for the town, oldest first. */
    public static List<Found> found(UUID village) {
        List<Found> out = new ArrayList<>();
        String s = Ledger.note(village, "carto.found");
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            Found f = Found.decode(line);
            if (f != null) out.add(f);
        }
        return out;
    }

    static void record(UUID village, Found f) {
        List<Found> all = found(village);
        all.add(f);
        while (all.size() > 48) all.remove(0);
        StringBuilder sb = new StringBuilder();
        for (Found x : all) sb.append(sb.length() == 0 ? "" : "\n").append(x.encode());
        Ledger.note(village, "carto.found", sb.toString());
    }

    /** The places that may be looked for now, in the order they are taken in turn. */
    static List<Place> lookable(UUID village) {
        Villages.Age age = Villages.ageOf(village);
        List<Place> out = new ArrayList<>();
        for (Place p : Place.values()) if (age.ordinal() >= p.from.ordinal()) out.add(p);
        return out;
    }

    /**
     * One look, the next kind of place in turn (the cartographer's work at the table, a few times a day): searched for
     * round the town as far as it has been; found, it goes on the town's books and the atlas, to the hands it is for,
     * with an explorer map when the stores run to one. What was done, or null for nothing found.
     */
    @Nullable
    static String lookOnce(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day) {
        UUID id = v.id();
        List<Place> places = lookable(id);
        if (places.isEmpty()) return null;
        int i;
        try {
            String n = Ledger.note(id, "carto.next");
            i = n == null ? 0 : Integer.parseInt(n);
        } catch (NumberFormatException e) {
            i = 0;
        }
        Place p = places.get(Math.floorMod(i, places.size()));
        Ledger.note(id, "carto.next", Integer.toString(i + 1));
        Spot s = search(level, v, p, v.centre());
        if (s == null) return null;
        return hand(level, v, f, p, s, day);
    }

    /** Look for this place now (tests, the stage). */
    @Nullable
    public static String lookForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f, Place p) {
        Spot s = search(level, v, p, v.centre());
        return s == null ? null : hand(level, v, f, p, s, level.getDayTime() / 24000L);
    }

    /** A find handed on: its map made, to its hands, onto the books, the atlas and (the cave team's) the caves' report. */
    static String hand(ServerLevel level, Villages.Village v, VillageFolkEntity f, Place p, Spot s, long day) {
        UUID id = v.id();
        BlockPos home = v.centre();
        String town = Villages.name(id);
        int dist = (int) Math.sqrt(Scouts.flat(home, s.at()));
        String way = Guide.direction(home, s.at());
        String label = label(p, s);
        ItemStack map = payForExplorer(level, v) ? explorerMap(level, v, p, s, f.displayNameCap(), day) : ItemStack.EMPTY;
        Hands hands = p.hands;
        String to = handTo(level, v, hands, map);
        // The town's books and the atlas.
        record(id, new Found(p, s.at(), day, hands, !map.isEmpty(), label));
        Scouts.Kind kind = switch (p) {
            case VILLAGE -> Scouts.Kind.SETTLEMENT;
            case OUTPOST, SWAMP_HUT, MANSION, MONUMENT, ANCIENT_CITY -> Scouts.Kind.DANGER;
            default -> Scouts.Kind.RUIN;
        };
        Scouts.record(id, new Scouts.Find(kind, label, s.at(), day, "the cartographer's map"));
        if (hands == Hands.CAVE_TEAM) {
            CaveDwellers.Kind k = p == Place.MINESHAFT ? CaveDwellers.Kind.MINESHAFT : p == Place.DUNGEON ? CaveDwellers.Kind.DUNGEON
                : CaveDwellers.Kind.STRUCTURE;
            CaveDwellers.record(id, new CaveDwellers.Find(k, label, s.at(), day, "the cartographer's map", 0, 0));
        }
        Cartographers.count(id, "found", 1);
        if (!map.isEmpty()) Cartographers.count(id, "explorer", 1);
        Villages.tell(id, day, f.displayNameCap() + " found " + p.words + " " + dist + " blocks " + way + " of " + town
            + (map.isEmpty() ? ", and told " + hands.words : " and gave " + to + " a map to it"));
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There — " + p.words + ", " + dist + " blocks " + way + ". That's going on the map.",
            "Found it: " + p.words + " out " + way + ". " + (map.isEmpty() ? "A map to follow, when there's paper." : "A map for " + to + ".")));
        f.awardXp(3);
        f.note(AssistantEntity.Deed.THINGS_MADE, map.isEmpty() ? 0 : 1);
        LOG.info("[MCA-CARTO] {} found {} at {} ({} {}), map to {}", f.displayNameCap(), p, s.at().toShortString(), dist, way,
            map.isEmpty() ? "nobody (no paper)" : to);
        return p.words + " " + dist + " blocks " + way + (map.isEmpty() ? "" : ", its map to " + to);
    }

    /** What the atlas and the books call it: "a mineshaft", "a desert village", "the trial chambers". */
    static String label(Place p, Spot s) {
        if (p == Place.VILLAGE && s.structure() != null) {
            String path = s.structure().unwrapKey().map(k -> k.location().getPath()).orElse("");
            if (path.startsWith("village_")) return "a " + path.substring("village_".length()) + " village";
        }
        return p.words;
    }

    /**
     * The map to whoever it is for: the cave team's leader (or any of it), a scout at home, a folk of a Nether runners'
     * or a kelp diver's trade; else into the stores, where the cartographer sells it. Who got it, in words.
     */
    static String handTo(ServerLevel level, Villages.Village v, Hands hands, ItemStack map) {
        if (map.isEmpty()) return hands.words;
        UUID id = v.id();
        VillageFolkEntity to = null;
        switch (hands) {
            case CAVE_TEAM -> {
                to = CaveDwellers.leaderOf(id);
                if (to == null) for (VillageFolkEntity d : CaveDwellers.dwellers(id)) { to = d; break; }
            }
            case SCOUTS -> {
                for (AssistantEntity a : Villages.folkOf(id)) {
                    if (a instanceof VillageFolkEntity g && g.stationTask() == AssistantEntity.StationTask.SCOUT && !g.isBaby()) {
                        to = g;
                        if (g.expedition() == null) break;
                    }
                }
            }
            case NETHER -> to = ofTrade(id, "NETHER", "RUNNER");
            case DIVER -> to = ofTrade(id, "DIVER", "KELP");
            case TOWN -> { }
        }
        if (to != null) {
            ItemStack left = to.insertGiven(map);
            if (left.isEmpty()) return to.displayNameCap() + (hands == Hands.CAVE_TEAM ? " of the cave team" : hands == Hands.SCOUTS ? " the scout"
                : hands == Hands.NETHER ? " of the Nether runners" : "");   // [nether]
        }
        Crafts.store(level, v, map);
        return "the stores";
    }

    /** A grown folk of the town whose trade's name has one of these in it (a trade a later day may add), or null. */
    @Nullable
    static VillageFolkEntity ofTrade(UUID village, String... names) {
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity g) || g.isBaby()) continue;
            String t = g.stationTask().name();
            for (String n : names) if (t.contains(n)) return g;
        }
        return null;
    }

    // ------------------------------------------------------------------ the explorer map

    /** The makings of an explorer map out of the stores: an empty locator map's, eight paper and a compass. */
    static boolean explorerToHand(ServerLevel level, Villages.Village v) {
        return Crafts.stock(level, v, s -> s.is(Items.MAP)) > 0
            || Crafts.stock(level, v, s -> s.is(Items.PAPER)) >= 8 && Crafts.stock(level, v, s -> s.is(Items.COMPASS)) >= 1;
    }

    static boolean payForExplorer(ServerLevel level, Villages.Village v) {
        if (!explorerToHand(level, v)) return false;
        Economy.openCraft(v.id(), AssistantEntity.StationTask.CARTOGRAPHER);
        try {
            if (Crafts.take(level, v, s -> s.is(Items.MAP), 1)) return true;
            if (!Crafts.take(level, v, s -> s.is(Items.PAPER), 8)) return false;
            if (Crafts.take(level, v, s -> s.is(Items.COMPASS), 1)) return true;
            Crafts.store(level, v, new ItemStack(Items.PAPER, 8));
            return false;
        } finally {
            Economy.closeCraft();
        }
    }

    /** The mark a place has on its explorer map: the game's own where it has one, a village by its kind. */
    static Holder<MapDecorationType> mark(Place p, @Nullable Holder<Structure> structure) {
        return switch (p) {
            case MONUMENT -> MapDecorationTypes.OCEAN_MONUMENT;
            case MANSION -> MapDecorationTypes.WOODLAND_MANSION;
            case JUNGLE_TEMPLE -> MapDecorationTypes.JUNGLE_TEMPLE;
            case SWAMP_HUT -> MapDecorationTypes.SWAMP_HUT;
            case TRIAL_CHAMBERS -> MapDecorationTypes.TRIAL_CHAMBERS;
            case TREASURE, MINESHAFT, DUNGEON, RUINED_PORTAL -> MapDecorationTypes.RED_X;
            case VILLAGE -> {
                String path = structure == null ? "" : structure.unwrapKey().map(k -> k.location().getPath()).orElse("");
                yield path.contains("desert") ? MapDecorationTypes.DESERT_VILLAGE : path.contains("savanna") ? MapDecorationTypes.SAVANNA_VILLAGE
                    : path.contains("snowy") ? MapDecorationTypes.SNOWY_VILLAGE : path.contains("taiga") ? MapDecorationTypes.TAIGA_VILLAGE
                    : MapDecorationTypes.PLAINS_VILLAGE;
            }
            default -> MapDecorationTypes.TARGET_X;
        };
    }

    /**
     * The explorer map, as the game makes one (ExplorationMapFunction, a cartographer villager's trade): a locator map
     * centred on the place at the explorer's scale, the land filled in from its biomes, the place marked, named.
     */
    static ItemStack explorerMap(ServerLevel level, Villages.Village v, Place p, Spot s, String by, long day) {
        BlockPos at = s.at();
        ItemStack map = MapItem.create(level, at.getX(), at.getZ(), (byte) p.zoom, true, true);
        MapItem.renderBiomePreviewMap(level, map);
        MapItemSavedData.addTargetDecoration(map, at, "+", mark(p, s.structure()));
        map.set(DataComponents.ITEM_NAME, p.vanillaName != null ? Component.translatable(p.vanillaName)
            : Component.literal("Explorer Map: " + capital(label(p, s).replaceFirst("^(a|an|the) ", ""))));
        BlockPos home = v.centre();
        int dist = (int) Math.sqrt(Scouts.flat(home, at));
        map.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("Found by " + by + ", cartographer of " + Villages.name(v.id()) + ", day " + (day + 1)).withStyle(ChatFormatting.GRAY),
            Component.literal(capital(label(p, s)) + ", " + dist + " blocks " + Guide.direction(home, at) + " of the square").withStyle(ChatFormatting.GRAY))));
        Economy.tally(v.id(), AssistantEntity.StationTask.CARTOGRAPHER, map, 1, true);
        return map;
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Where an explorer map points: its target's spot (the first target decoration on it), or null. */
    @Nullable
    public static BlockPos target(ItemStack map) {
        MapDecorations d = map.get(DataComponents.MAP_DECORATIONS);
        if (d == null) return null;
        for (MapDecorations.Entry e : d.decorations().values()) return BlockPos.containing(e.x(), 64, e.z());
        return null;
    }

    /** The marks on an explorer map, by type. */
    public static List<Holder<MapDecorationType>> marks(ItemStack map) {
        List<Holder<MapDecorationType>> out = new ArrayList<>();
        MapDecorations d = map.get(DataComponents.MAP_DECORATIONS);
        if (d != null) for (MapDecorations.Entry e : d.decorations().values()) out.add(e.type());
        return out;
    }

    // ------------------------------------------------------------------ the region's marks

    /**
     * The region's sheet marked: the town's heart, its colonies and the neighbours its folk have met, the mine, every
     * cave and ravine of the caves' report and every place found, those that lie on the sheet. How many.
     */
    static int markRegion(ServerLevel level, Villages.Village v, ItemStack sheet) {
        MapItemSavedData d = MapItem.getSavedData(sheet, level);
        if (d == null) return 0;
        int half = MapSurveys.width(d.scale) / 2;
        int n = 0;
        List<Object[]> marks = new ArrayList<>();
        marks.add(new Object[]{ v.centre(), MapDecorationTypes.PLAINS_VILLAGE });
        for (BlockPos t : regionPlaces(level, v)) {
            boolean mine = t.equals(TownMine.siteOf(v.id()));
            marks.add(new Object[]{ t, mine ? MapDecorationTypes.RED_MARKER : MapDecorationTypes.TAIGA_VILLAGE });
        }
        for (CaveDwellers.Find x : CaveDwellers.report(v.id())) {
            if (x.kind() == CaveDwellers.Kind.CAVE || x.kind() == CaveDwellers.Kind.RAVINE) marks.add(new Object[]{ x.at(), MapDecorationTypes.RED_X });
        }
        for (Found x : found(v.id())) marks.add(new Object[]{ x.at(), mark(x.place(), null) });
        for (Object[] m : marks) {
            BlockPos at = (BlockPos) m[0];
            if (Math.abs(at.getX() - d.centerX) >= half || Math.abs(at.getZ() - d.centerZ) >= half) continue;
            @SuppressWarnings("unchecked") Holder<MapDecorationType> type = (Holder<MapDecorationType>) m[1];
            MapItemSavedData.addTargetDecoration(sheet, at, "region" + (n++), type);
            if (n >= 24) break;
        }
        return n;
    }

    /** "the town's own maps": for a map of a known place a player asks after, the find by its words, or null. */
    @Nullable
    static Found knownByWords(UUID village, String text) {
        String t = text.toLowerCase(Locale.ROOT);
        Found best = null;
        for (Found x : found(village)) {
            String w = x.place().bare();
            String l = x.label().toLowerCase(Locale.ROOT);
            boolean asked = t.contains(w) || t.contains(l.replaceFirst("^(a|an|the) ", ""))
                || x.place() == Place.DESERT_TEMPLE && t.contains("desert") && t.contains("temple")
                || x.place() == Place.JUNGLE_TEMPLE && t.contains("jungle") && t.contains("temple")
                || x.place() == Place.VILLAGE && t.contains("village") && !t.contains("town")
                || x.place() == Place.OUTPOST && t.contains("outpost")
                || x.place() == Place.RUINED_PORTAL && t.contains("portal")
                || x.place() == Place.SWAMP_HUT && (t.contains("witch") || t.contains("hut"))
                || x.place() == Place.TRIAL_CHAMBERS && t.contains("trial")
                || x.place() == Place.ANCIENT_CITY && t.contains("city");
            if (asked) best = x;
        }
        return best;
    }

    /** The structure a found place is, for a map of it: the world's own where it stands loaded, else none. */
    @Nullable
    static Holder<Structure> structureAt(ServerLevel level, Found x) {
        if (x.place() == Place.DUNGEON || !level.isLoaded(x.at())) return null;
        HolderSet<Structure> set = set(level, x.place());
        if (set == null) return null;
        for (Holder<Structure> h : set) {
            StructureStart s = level.structureManager().getStructureWithPieceAt(x.at(), h.value());
            if (s.isValid()) return h;
        }
        return null;
    }

    /** Tests: the cartographer's finds for a town. */
    public static List<Found> foundForTests(UUID village) {
        return found(village);
    }

    /** Tests: is this spot one the town has been near? */
    public static boolean exploredForTests(ServerLevel level, Villages.Village v, BlockPos p) {
        return explored(level, v, p);
    }

    /** The vanilla id of a structure a spot is (for the log and the tests). */
    static String id(@Nullable Holder<Structure> h) {
        return h == null ? "dungeon" : h.unwrapKey().map(ResourceKey::location).map(ResourceLocation::toString).orElse("?");
    }
}
