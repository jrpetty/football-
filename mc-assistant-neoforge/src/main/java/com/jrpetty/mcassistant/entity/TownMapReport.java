package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [ui53] The town map on the board (client/TownMapView), as it stands now: the land under the town read off the world
 * (water, the streets, the fields, trees, sand, rock, what others have built), every building's address, household and
 * business, the sites going up and how far along they are, the plots and houses for sale or to let, every folk where it
 * is and what it is at (working, on its way, at leisure, asleep, laid up), the carts on the roads and the stores' door.
 * The viewer's own side ({@link #viewer}): where the player stands and faces, and what of the town is theirs.
 *
 * <p>The land is read at most every ten seconds a town (it does not change faster than that), and only off chunks that
 * are loaded already: a cell of unloaded ground is sent as unknown, never loaded for the map. Every part is made on its
 * own, so a part that fails leaves the rest of the map.
 */
public final class TownMapReport {

    private TownMapReport() {}

    /** The land's kinds, as the client colours them. */
    public static final byte UNKNOWN = 0, GRASS = 1, WATER = 2, ROAD = 3, FARM = 4, TREES = 5, SAND = 6, ROCK = 7, SNOW = 8,
        BUILT = 9, LAVA = 10;
    /** The most cells a side the land is read in (the step grows with the town). */
    static final int MOST_CELLS = 128;
    /** How far out the map may reach from the heart, in blocks. */
    static final int MOST_REACH = 200;
    /** The folk on the map, at most. */
    static final int MOST_FOLK = 300;

    private record LandScan(long tick, int reach, int step, byte[] cells) {}

    private static final Map<UUID, LandScan> LAND = new ConcurrentHashMap<>();

    public static CompoundTag report(ServerLevel level, Villages.Village v, List<VillageFolkEntity> folk) {
        CompoundTag out = new CompoundTag();
        UUID id = v.id();
        BlockPos heart = v.centre();
        int reach = reachOf(id, heart);
        out.putInt("reach", reach);
        safely("land", () -> {
            LandScan l = land(level, id, heart, reach);
            out.putInt("step", l.step());
            out.putInt("land_reach", l.reach());
            out.put("land", new ByteArrayTag(l.cells()));
        });
        safely("buildings", () -> out.put("bx", buildings(level, v)));
        safely("sites", () -> out.put("sites", sites(level, v)));
        safely("sale", () -> out.put("sale", forSale(v)));
        safely("folk", () -> out.put("folk", folk(v, folk)));
        safely("carts", () -> out.put("carts", carts(level, heart, reach)));
        safely("stores", () -> {
            BlockPos door = Storehouses.doorFor(level, id);
            if (door != null) out.putIntArray("stores", new int[]{ door.getX() - heart.getX(), door.getZ() - heart.getZ() });
        });
        return out;
    }

    /** The viewer's side: where it stands and which way it faces, and its own houses, properties and businesses. */
    public static void viewer(CompoundTag books, ServerLevel level, Villages.Village v, ServerPlayer player) {
        CompoundTag map = books.getCompound("townmap");
        if (map.isEmpty()) return;
        BlockPos heart = v.centre();
        CompoundTag me = new CompoundTag();
        me.putDouble("dx", player.getX() - heart.getX());
        me.putDouble("dz", player.getZ() - heart.getZ());
        me.putFloat("yaw", player.getYRot());
        me.putString("name", player.getName().getString());
        map.put("me", me);
        safely("theirs", () -> {
            ListTag mine = new ListTag();
            UUID who = player.getUUID();
            for (Homes.Home h : Homes.homes(v.id()).values()) {
                if (h.tenure == Homes.Tenure.PLAYER && who.equals(h.landlord)) mine.add(box(heart, h.anchor, h.structure, "your house"));
            }
            for (PropertyMarket.Property p : PropertyMarket.ownedBy(v.id(), who)) mine.add(box(heart, p.anchor(), p.drawing(), "your " + p.name()));
            for (Business b : Businesses.all(v.id())) {
                if (b.at != null && b.ownedBy(who)) mine.add(box(heart, b.at, "", "your business, " + b.name));
            }
            map.put("mine", mine);
        });
    }

    private static CompoundTag box(BlockPos heart, BlockPos at, String structure, String what) {
        CompoundTag c = new CompoundTag();
        c.putInt("dx", at.getX() - heart.getX());
        c.putInt("dz", at.getZ() - heart.getZ());
        int[] half = structure == null || structure.isEmpty() ? new int[]{ 3, 3 } : BuildGoal.footprint(structure);
        c.putInt("hx", half[0]);
        c.putInt("hz", half[1]);
        c.putString("what", what);
        return c;
    }

    /** How far out the map reaches: the furthest building and site, and a margin, within bounds. */
    private static int reachOf(UUID id, BlockPos heart) {
        int reach = 40;
        for (Ledger.Building b : Ledger.buildings(id)) {
            int[] half = BuildGoal.footprint(b.structure());
            reach = Math.max(reach, Math.max(Math.abs(b.anchor().getX() - heart.getX()), Math.abs(b.anchor().getZ() - heart.getZ()))
                + Math.max(half[0], half[1]) + 8);
        }
        for (Villages.Site s : Villages.sitesOf(id).values()) {
            reach = Math.max(reach, Math.max(Math.abs(s.anchor().getX() - heart.getX()), Math.abs(s.anchor().getZ() - heart.getZ())) + 16);
        }
        return Math.min(MOST_REACH, reach);
    }

    // ------------------------------------------------------------------ the land

    private static LandScan land(ServerLevel level, UUID id, BlockPos heart, int reach) {
        long now = level.getGameTime();
        LandScan had = LAND.get(id);
        if (had != null && had.reach() == reach && now - had.tick() >= 0 && now - had.tick() < 200) return had;
        int step = Math.max(1, (2 * reach + MOST_CELLS) / MOST_CELLS);
        int n = (2 * reach) / step + 1;
        byte[] cells = new byte[n * n];
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int x = heart.getX() - reach + i * step, z = heart.getZ() - reach + j * step;
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;    // unknown: never loaded for the map
                cells[j * n + i] = kindAt(level, p, x, z);
            }
        }
        LandScan l = new LandScan(now, reach, step, cells);
        LAND.put(id, l);
        return l;
    }

    private static byte kindAt(ServerLevel level, BlockPos.MutableBlockPos p, int x, int z) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        p.set(x, top, z);
        BlockState st = level.getBlockState(p);
        if (st.getFluidState().is(FluidTags.WATER)) return WATER;
        if (st.getFluidState().is(FluidTags.LAVA)) return LAVA;
        if (st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS)) return TREES;
        // A plant, a flower, snow on the ground, a torch: what it stands on.
        for (int down = 0; down < 3 && !st.isSolidRender(level, p) && !st.is(Blocks.DIRT_PATH) && !st.is(Blocks.FARMLAND)
                && !(st.getBlock() instanceof net.minecraft.world.level.block.SlabBlock); down++) {
            if (st.getBlock() instanceof CropBlock || st.is(Blocks.SWEET_BERRY_BUSH) || st.is(Blocks.PUMPKIN_STEM) || st.is(Blocks.MELON_STEM)) return FARM;
            if (st.is(Blocks.SNOW)) return SNOW;
            if (st.getFluidState().is(FluidTags.WATER)) return WATER;
            p.move(0, -1, 0);
            st = level.getBlockState(p);
        }
        Block b = st.getBlock();
        if (b == Blocks.DIRT_PATH || b == Blocks.GRAVEL || b == Blocks.COBBLESTONE || b == Blocks.STONE_BRICKS || b == Blocks.MOSSY_COBBLESTONE
            || b == Blocks.MOSSY_STONE_BRICKS || b == Blocks.CRACKED_STONE_BRICKS || b == Blocks.STONE_BRICK_SLAB || b == Blocks.COBBLESTONE_SLAB
            || b == Blocks.SMOOTH_STONE || b == Blocks.SMOOTH_STONE_SLAB || b == Blocks.POLISHED_ANDESITE) return ROAD;
        if (b == Blocks.FARMLAND || b instanceof CropBlock || b == Blocks.HAY_BLOCK) return FARM;
        if (st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS)) return TREES;
        if (st.is(BlockTags.SAND) || b == Blocks.SANDSTONE || b == Blocks.RED_SANDSTONE) return SAND;
        if (b == Blocks.SNOW_BLOCK || b == Blocks.SNOW || b == Blocks.POWDER_SNOW || b == Blocks.ICE || b == Blocks.PACKED_ICE) return SNOW;
        if (st.is(BlockTags.DIRT) || b == Blocks.GRASS_BLOCK || b == Blocks.MUD || b == Blocks.CLAY || b == Blocks.MOSS_BLOCK) return GRASS;
        if (st.is(BlockTags.BASE_STONE_OVERWORLD) || b == Blocks.STONE || b == Blocks.CALCITE || b == Blocks.DRIPSTONE_BLOCK) return ROCK;
        return BUILT;
    }

    // ------------------------------------------------------------------ the buildings, the sites, the plots

    /** Each building's address, household and business, by its anchor (dx, dz: the books' building list has the rest). */
    private static ListTag buildings(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        Map<Long, Homes.Home> homes = Homes.homes(id);
        Map<UUID, String> names = new HashMap<>();
        for (AssistantEntity a : Villages.folkOf(id)) names.put(a.getUUID(), a.displayNameCap());
        Map<Long, Business> firms = new HashMap<>();
        for (Business b : Businesses.all(id)) if (b.at != null) firms.put(b.at.asLong(), b);
        ListTag out = new ListTag();
        for (Ledger.Building b : Ledger.buildings(id)) {
            if (out.size() >= 400) break;
            CompoundTag c = new CompoundTag();
            c.putInt("dx", b.anchor().getX() - heart.getX());
            c.putInt("dz", b.anchor().getZ() - heart.getZ());
            String[] addr = TownLife.address(id, heart, b);
            if (addr != null) c.putString("addr", addr[0] + (addr.length > 1 && !addr[1].isEmpty() ? ", " + addr[1] : ""));
            Homes.Home h = homes.get(b.anchor().asLong());
            if (h != null) {
                StringBuilder who = new StringBuilder();
                for (int i = 0; i < h.members.size() && i < 4; i++) who.append(i == 0 ? "" : ", ").append(names.getOrDefault(h.members.get(i), "someone"));
                if (h.members.size() > 4) who.append(" and ").append(h.members.size() - 4).append(" more");
                c.putString("who", who.toString());
                if (h.tenure == Homes.Tenure.PLAYER && !h.landlordName.isEmpty()) c.putString("owner", h.landlordName);
                if (h.vacant()) c.putString("vacant", h.toLet || h.tenure == Homes.Tenure.PLAYER ? "to let" : "empty, the town's to let");
            }
            Business f = nearFirm(firms, b);
            if (f != null) {
                c.putString("biz", f.name);
                c.putString("biz_type", f.type.title);
                c.putString("biz_owner", f.ownersWords());
                c.putBoolean("open", f.open);
                c.putString("biz_id", f.id);
            }
            out.add(c);
        }
        return out;
    }

    /** The business whose premises are in this building's footing, if any. */
    private static Business nearFirm(Map<Long, Business> firms, Ledger.Building b) {
        Business at = firms.get(b.anchor().asLong());
        if (at != null || firms.isEmpty()) return at;
        int[] half = BuildGoal.footprint(b.structure());
        for (Business f : firms.values()) {
            if (Math.abs(f.at.getX() - b.anchor().getX()) <= half[0] + 1 && Math.abs(f.at.getZ() - b.anchor().getZ()) <= half[1] + 1
                && Math.abs(f.at.getY() - b.anchor().getY()) <= 8) return f;
        }
        return null;
    }

    /** The buildings going up: where, how big, and how far along (the blocks of the drawing laid, of all of them). */
    private static ListTag sites(ServerLevel level, Villages.Village v) {
        BlockPos heart = v.centre();
        ListTag out = new ListTag();
        for (Map.Entry<String, Villages.Site> e : Villages.sitesOf(v.id()).entrySet()) {
            if (out.size() >= 24) break;
            Villages.Site s = e.getValue();
            CompoundTag c = box(heart, s.anchor(), e.getKey().equals("fortify") ? "" : e.getKey(), TownLife.title(e.getKey()).replaceFirst("^The ", "").replaceAll("\\d+$", ""));
            if (e.getKey().equals("fortify")) { c.putInt("hx", s.radius()); c.putInt("hz", s.radius()); c.putBoolean("ring", true); }
            c.putString("kind", e.getKey());
            c.putInt("pct", progress(level, e.getKey(), s));
            out.add(c);
        }
        return out;
    }

    /** How far along a site is, 0..100; -1 when its ground is not loaded (nothing is loaded for the map). */
    private static int progress(ServerLevel level, String project, Villages.Site s) {
        if (project.equals("fortify")) return -1;
        int[] half = BuildGoal.footprint(project);
        if (!Land.areaLoaded(level, s.anchor(), Math.max(8, Math.max(half[0], half[1]) + 1))) return -1;
        int all = 0, laid = 0;
        for (BuildGoal.Placement p : BuildGoal.plan(project, s.anchor(), s.facing(), s.radius())) {
            if (p.part() != BuildGoal.Part.BLOCK) continue;
            all++;
            if (!BuildGoal.soft(level.getBlockState(p.pos()))) laid++;
        }
        return all == 0 ? -1 : Math.round(laid * 100f / all);
    }

    /** Plots and houses for sale or to let: the property office's listings, and the town's empty houses. */
    private static ListTag forSale(Villages.Village v) {
        BlockPos heart = v.centre();
        ListTag out = new ListTag();
        for (PropertyMarket.Property p : PropertyMarket.listed(v.id())) {
            if (out.size() >= 60) break;
            CompoundTag c = box(heart, p.anchor(), p.drawing(), p.name());
            c.putString("listing", p.listing());
            c.putInt("asking", p.asking());
            c.putInt("rent", p.rent());
            out.add(c);
        }
        return out;
    }

    // ------------------------------------------------------------------ the folk and the carts

    /** What a folk is at, for its dot: asleep, working, on its way, at leisure, laid up, at school. */
    public static final byte ASLEEP = 0, WORKING = 1, WALKING = 2, IDLE = 3, ILL = 4, SCHOOL = 5;

    private static ListTag folk(Villages.Village v, List<VillageFolkEntity> folk) {
        BlockPos heart = v.centre();
        ListTag out = new ListTag();
        for (VillageFolkEntity f : folk) {
            if (out.size() >= MOST_FOLK) break;
            if (!f.isAlive()) continue;
            CompoundTag c = new CompoundTag();
            c.putInt("x", (int) Math.floor(f.getX() - heart.getX()));
            c.putInt("z", (int) Math.floor(f.getZ() - heart.getZ()));
            FolkSchedule.Kind k = FolkSchedule.now(f);
            boolean moving = f.getNavigation().isInProgress() || f.getDeltaMovement().horizontalDistanceSqr() > 0.0025;
            byte s = switch (k) {
                case SLEEP -> moving ? WALKING : ASLEEP;                  // past its bedtime but still on its way home
                case LAID_UP -> ILL;
                case SCHOOL -> SCHOOL;
                case WORK, WATCH -> moving ? WALKING : WORKING;
                default -> moving ? WALKING : IDLE;
            };
            if (f.isSleeping()) s = ASLEEP;
            c.putByte("s", s);
            c.putString("n", f.displayNameCap());
            c.putString("t", f.isBaby() ? "Child" : f.stationTask() == AssistantEntity.StationTask.NONE ? "Unassigned" : f.stationTask().title);
            c.putString("d", FolkTalk.nowDoing(f));
            if (f.stationTask() == AssistantEntity.StationTask.HAUL) c.putBoolean("carry", true);
            c.putUUID("u", f.getUUID());
            out.add(c);
        }
        return out;
    }

    private static ListTag carts(ServerLevel level, BlockPos heart, int reach) {
        ListTag out = new ListTag();
        AABB box = new AABB(heart).inflate(reach, 64, reach);
        for (TradeWagon w : level.getEntitiesOfClass(TradeWagon.class, box)) {
            if (out.size() >= 40) break;
            out.add(new net.minecraft.nbt.IntArrayTag(new int[]{ (int) Math.floor(w.getX() - heart.getX()), (int) Math.floor(w.getZ() - heart.getZ()) }));
        }
        return out;
    }

    private static void safely(String part, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException | StackOverflowError e) {
            com.mojang.logging.LogUtils.getLogger().warn("[ui53] the town map's {} left out: {}", part, e.toString());
        }
    }

    /** Tests: the land read afresh. */
    public static void resetForTests() {
        LAND.clear();
    }
}
