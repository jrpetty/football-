package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Districts;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The park: a green among the homes, once a town has twenty folk to want one.
 *
 * <p>The council puts it on the builders' list with the other amenities (Villages), and the plan
 * gives it a lot in the homes quarter, the nearest the square (Districts). The builders put up what
 * is drawn (blueprints/park.txt) out of the stores like anything else: a fountain of dressed stone,
 * its basin and the spring on its pillar filled a bucket at a time from water the village has
 * (the bucket out of the stores, the water out of a pond or a river that never runs dry), benches
 * round it, lamp posts at the ways in, flowers along its edges. Then, a little at a time, its
 * keepers do the rest: a tree in each corner from the woodcutters' saplings in the stores (and a
 * handful of bone meal now and then, if the stores can spare it); paths of trodden earth round the
 * fountain and out to the ways in, paved in stone bricks from the Iron Age; the fountain's rough
 * stone dressed; the torches swapped for lanterns once the smith makes them; whatever flowers and
 * water the builder had none of. Its ground is made level before it is laid out, and its fountain kept
 * from spilling a drop (ParkGround).
 *
 * <p>Folk spend their free time there. Some evenings (more often the ones who live by it, the
 * walkers, the readers, the sociable and the easygoing) and some breaks, a folk walks over, sits on
 * a bench looking at the fountain, chats with whoever sits by it (and likes them the better for
 * it), gets up and strolls the paths. Children run about it of an afternoon. A home within
 * twenty-four blocks of it is the happier, and sells and lets for a little more (Quarters).
 */
public final class Park {

    private Park() {}

    public static final String STRUCTURE = "park";
    /** The town's size when it wants a park. */
    public static final int FOLK = 20;
    /** A home this near a park is by it. */
    public static final int REACH = 24;
    /** What living by the park, and an hour in it today, do for a folk's spirits. */
    static final int MOOD_BY = 4, MOOD_VISIT = 3;

    /** The corners of the drawing where its trees go: across (right is +), toward the back. */
    private static final int[][] TREES = { { -5, 5 }, { 5, 5 }, { -5, -5 }, { 5, -5 } };

    public static void resetForTests() {
        LAYOUTS.clear();
        TENDED.clear();
        VISITS.clear();
        VISITED.clear();
        SEATED.clear();
    }

    /** Does the village want a park now: twenty folk or more, and none yet. (Villages, with the amenities.) */
    public static boolean wanted(UUID village, int folk) {
        return folk >= FOLK && !Villages.hasBuilt(village, STRUCTURE) && parks(village).isEmpty();
    }

    // ------------------------------------------------------------------ the park's ground

    /** A bench seat: the stair, and the way somebody sitting on it looks. */
    public record Seat(BlockPos at, Direction looks) {}

    /** What a park's drawing says about it, laid out where it stands: worked out once a park. */
    public record Layout(BlockPos centre, Direction facing, List<Seat> seats, List<BlockPos> paths, List<BlockPos> trees,
                         List<BlockPos> flowers, List<BlockPos> lights, List<BlockPos> water, List<BlockPos> stone) {}

    private static final Map<String, Layout> LAYOUTS = new ConcurrentHashMap<>();

    public static Layout layout(BlockPos anchor, Direction facing) {
        return LAYOUTS.computeIfAbsent(anchor.asLong() + "/" + facing.getName(), k -> lay(anchor, facing));
    }

    public static Layout layout(Ledger.Building b) {
        return layout(b.anchor(), b.facing());
    }

    /** The park laid out so far (built, or being built) whose fountain has water here, or null. */
    @Nullable
    static Layout layoutWithWater(BlockPos p) {
        for (Layout l : LAYOUTS.values()) if (l.water().contains(p)) return l;
        return null;
    }

    private static Layout lay(BlockPos anchor, Direction facing) {
        List<Seat> seats = new ArrayList<>();
        List<BlockPos> flowers = new ArrayList<>(), lights = new ArrayList<>(), water = new ArrayList<>(), stone = new ArrayList<>();
        for (BuildGoal.Placement p : BuildGoal.plan(STRUCTURE, anchor, facing, 0)) {
            switch (p.part()) {
                case BLOCK -> {
                    if (p.style() == Blueprints.Style.ROOF_STAIR) {
                        seats.add(new Seat(p.pos(), Blueprints.world(p.way(), facing).getOpposite()));
                    } else if (p.style() == Blueprints.Style.MASONRY) {
                        stone.add(p.pos());
                    }
                }
                case FLOWER -> flowers.add(p.pos());
                case LANTERN -> lights.add(p.pos());
                case WATER -> water.add(p.pos());
                default -> { }
            }
        }
        Direction right = facing.getClockWise();
        List<BlockPos> paths = new ArrayList<>();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                boolean ring = Math.max(Math.abs(dx), Math.abs(dz)) == 3;
                boolean way = (dx == 0 && Math.abs(dz) >= 4) || (dz == 0 && Math.abs(dx) >= 4);
                if (ring || way) paths.add(anchor.relative(right, dx).relative(facing, dz).below());
            }
        }
        List<BlockPos> trees = new ArrayList<>();
        for (int[] t : TREES) trees.add(anchor.relative(right, t[0]).relative(facing, t[1]));
        return new Layout(anchor, facing, List.copyOf(seats), List.copyOf(paths), List.copyOf(trees), List.copyOf(flowers),
            List.copyOf(lights), List.copyOf(water), List.copyOf(stone));
    }

    /** The village's parks (the ledger's), oldest first. */
    public static List<Ledger.Building> parks(@Nullable UUID village) {
        if (village == null) return List.of();
        List<Ledger.Building> out = new ArrayList<>();
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals(STRUCTURE)) out.add(b);
        return out;
    }

    /** Is this spot (a home) by one of the village's parks? */
    public static boolean near(@Nullable UUID village, @Nullable BlockPos at) {
        if (village == null || at == null) return false;
        for (Ledger.Building b : parks(village)) {
            double dx = b.anchor().getX() - at.getX(), dz = b.anchor().getZ() - at.getZ();
            if (dx * dx + dz * dz <= (double) REACH * REACH && Math.abs(b.anchor().getY() - at.getY()) <= 12) return true;
        }
        return false;
    }

    /** The park nearest a spot, within so many blocks, or null. */
    @Nullable
    static Ledger.Building nearest(@Nullable UUID village, BlockPos from, int within) {
        Ledger.Building best = null;
        double bestD = (double) within * within;
        for (Ledger.Building b : parks(village)) {
            double d = b.anchor().distSqr(from.atY(b.anchor().getY()));
            if (d <= bestD) { bestD = d; best = b; }
        }
        return best;
    }

    /** Is this spot inside a park's lot? */
    static boolean inside(Layout l, BlockPos p) {
        return Math.abs(p.getX() - l.centre().getX()) <= 5 && Math.abs(p.getZ() - l.centre().getZ()) <= 5
            && Math.abs(p.getY() - l.centre().getY()) <= 3;
    }

    // ------------------------------------------------------------------ keeping it

    private static final Map<UUID, Long> TENDED = new ConcurrentHashMap<>();

    /** Every quarter of a minute: a little more done to the village's park (Quarters' clock). */
    public static void tend(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        if (now - TENDED.getOrDefault(v.id(), -100000L) < 300L) return;
        TENDED.put(v.id(), now);
        // Yesterday's visits, and the folk who are gone, forgotten.
        long day = level.getDayTime() / 24000L;
        VISITS.values().removeIf(x -> x.day < day - 1);
        VISITED.values().removeIf(d -> d < day - 1);
        for (Ledger.Building b : parks(v.id())) {
            if (!Land.areaLoaded(level, b.anchor(), 9)) continue;
            if (tendOne(level, v, b, 4) != null) return;              // one park, one thing, a visit
        }
    }

    /**
     * One visit's work on a park, up to so many blocks of one kind: the fountain's water, a tree, the
     * flowers, the lamps, the paths, the stone. By a hand of the village (TownJobs), out of the stores.
     * Returns what was done, in a few words, or null if nothing was (nothing to do, or nobody yet).
     */
    @Nullable
    public static String tendOne(ServerLevel level, Villages.Village v, Ledger.Building b, int budget) {
        Layout l = layout(b);
        boolean iron = Villages.ageOf(v.id()).ordinal() >= Villages.Age.IRON.ordinal();
        // A stone of the fountain gone (its floor, its rim, its pillar): put back first (ParkGround).
        String mended = ParkGround.mend(level, v, l, budget);
        if (mended != null) return mended;
        // The fountain's water: a bucket out of the stores, filled where the water never runs dry; poured only
        // where it will stay (ParkGround.holds: a floor under it and a wall round it).
        List<BlockPos> dry = new ArrayList<>();
        for (BlockPos p : l.water()) {
            BlockState st = level.getBlockState(p);
            FluidState fl = level.getFluidState(p);
            if (fl.is(FluidTags.WATER) && fl.isSource()) continue;
            if ((st.isAir() || (fl.is(FluidTags.WATER) && !fl.isSource())) && ParkGround.holds(level, l, p)) dry.add(p);
        }
        // The pillar's spring last: it wants the basin under it first.
        dry.sort(java.util.Comparator.comparingInt(BlockPos::getY));
        if (!dry.isEmpty() && Market.stock(level, v.id(), s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET)) > 0) {
            BlockPos spring = spring(level, v, l);
            if (spring != null) {
                if (!TownJobs.atWork(level, v, "park", dry.get(0), "filling the park's fountain")) return null;
                int n = 0;
                for (BlockPos p : dry) {
                    if (n >= budget) break;
                    if (!bucketOfWater(level, v)) break;
                    level.setBlock(p, Blocks.WATER.defaultBlockState(), 3);
                    n++;
                }
                if (n > 0) return "filled the park's fountain";
            }
        }
        // Its ground: the low ground round it banked up in steps, a bare patch of its lawn turfed (ParkGround).
        String banked = ParkGround.bank(level, v, l, budget);
        if (banked != null) return banked;
        // A tree in each corner: a sapling out of the stores; bone meal on one, if the stores can spare it.
        for (BlockPos t : l.trees()) {
            BlockState here = level.getBlockState(t);
            BlockState ground = level.getBlockState(t.below());
            if (here.getBlock() instanceof SaplingBlock sapling) {
                if (Market.stock(level, v.id(), s -> s.is(Items.BONE_MEAL)) >= 8
                        && TownJobs.atWork(level, v, "park", t, "tending the park's trees")
                        && Crafts.take(level, v, s -> s.is(Items.BONE_MEAL), 1)) {
                    if (sapling.isValidBonemealTarget(level, t, here) && sapling.isBonemealSuccess(level, level.getRandom(), t, here)) {
                        sapling.performBonemeal(level, level.getRandom(), t, here);
                    }
                    level.levelEvent(1505, t, 15);                     // the bone meal's sparkle
                    return "bone meal on the park's trees";
                }
                continue;
            }
            if (!(here.isAir() || (here.canBeReplaced() && here.getFluidState().isEmpty())) || !ground.is(BlockTags.DIRT)) continue;
            if (Market.stock(level, v.id(), Park::plantable) < 1) break;
            if (!TownJobs.atWork(level, v, "park", t, "planting a tree in the park")) return null;
            ItemStack one = Crafts.takeOne(level, v, Park::plantable);
            if (one.isEmpty()) break;
            Block grows = Block.byItem(one.getItem());
            if (grows == Blocks.AIR || !grows.defaultBlockState().canSurvive(level, t)) {
                Crafts.store(level, v, one);
                continue;
            }
            if (!here.isAir()) level.destroyBlock(t, false);
            level.setBlock(t, grows.defaultBlockState(), 3);
            level.playSound(null, t, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
            return "planted a tree in the park";
        }
        // The flowers the builder had none of: from the stores, else a wild one dug up and brought.
        List<BlockPos> bare = new ArrayList<>();
        for (BlockPos p : l.flowers()) {
            if (level.getBlockState(p).isAir() && level.getBlockState(p.below()).is(BlockTags.DIRT)) bare.add(p);
        }
        if (!bare.isEmpty()) {
            boolean stores = Market.stock(level, v.id(), s -> s.is(ItemTags.SMALL_FLOWERS)) > 0;
            BlockPos wild = stores ? null : Crafts.wildFlower(level, l.centre(), 24, v.id());
            if (stores || wild != null) {
                if (!TownJobs.atWork(level, v, "park", bare.get(0), "planting flowers in the park")) return null;
                int n = 0;
                for (BlockPos p : bare) {
                    if (n >= budget) break;
                    BlockState flower = null;
                    ItemStack one = Crafts.takeOne(level, v, s -> s.is(ItemTags.SMALL_FLOWERS));
                    if (!one.isEmpty()) {
                        Block b2 = Block.byItem(one.getItem());
                        if (b2 != Blocks.AIR && b2.defaultBlockState().canSurvive(level, p)) flower = b2.defaultBlockState();
                        else Crafts.store(level, v, one);
                    } else {
                        if (wild == null) wild = Crafts.wildFlower(level, l.centre(), 24, v.id());
                        if (wild == null) break;
                        BlockState w = level.getBlockState(wild);
                        if (w.is(BlockTags.SMALL_FLOWERS) && w.getBlock().defaultBlockState().canSurvive(level, p)) {
                            level.removeBlock(wild, false);
                            flower = w.getBlock().defaultBlockState();
                        }
                        wild = null;
                    }
                    if (flower == null) continue;
                    level.setBlock(p, flower, 3);
                    n++;
                }
                if (n > 0) return "planted flowers in the park";
            }
        }
        // The lamps: a light where there is none, and a lantern for a torch once the smith makes them.
        for (BlockPos p : l.lights()) {
            BlockState st = level.getBlockState(p);
            boolean torch = st.is(Blocks.TORCH);
            if (!st.isAir() && !torch) continue;
            // Its post gone: let be. (A post is whatever the builder had for it: a log, or a block of stone when
            // its own trade had used the last log; a lamp on a stone post is lit like any other.)
            if (!Block.canSupportCenter(level, p.below(), Direction.UP)) continue;
            if (torch && !Masonry.can(level, v, Items.LANTERN, 1)) continue;
            if (!torch && !Masonry.canLight(level, v) && !Masonry.can(level, v, Items.LANTERN, 1)) continue;
            if (!TownJobs.atWork(level, v, "park", p, "lighting the park's lamps")) return null;
            if (torch) {
                if (!Masonry.take(level, v, Items.LANTERN, 1)) continue;
                level.setBlock(p, Blocks.LANTERN.defaultBlockState(), 3);
                Crafts.store(level, v, new ItemStack(Items.TORCH));
                return "hung lanterns in the park";
            }
            Block light = Masonry.light(level, v);
            if (light == null) continue;
            level.setBlock(p, light.defaultBlockState(), 3);
            return "lit the park's lamps";
        }
        // The paths: trodden earth (a spade's work, no more); paved in stone bricks from the Iron Age.
        List<BlockPos> rough = new ArrayList<>();
        for (BlockPos p : l.paths()) {
            BlockState st = level.getBlockState(p), above = level.getBlockState(p.above());
            if (!(above.isAir() || (above.canBeReplaced() && above.getFluidState().isEmpty()))) continue;
            boolean earth = st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.PODZOL)
                || st.is(Blocks.ROOTED_DIRT) || st.is(Blocks.MYCELIUM);
            if (iron ? (earth || st.is(Blocks.DIRT_PATH) || st.is(Blocks.COBBLESTONE)) : earth) rough.add(p);
        }
        if (!rough.isEmpty() && (!iron || Masonry.can(level, v, Items.STONE_BRICKS, 1))) {
            if (!TownJobs.atWork(level, v, "park", rough.get(0).above(), iron ? "paving the park's paths" : "laying the park's paths")) return null;
            int n = 0;
            for (BlockPos p : rough) {
                if (n >= budget * 2) break;
                BlockState above = level.getBlockState(p.above());
                if (iron) {
                    BlockState was = level.getBlockState(p);
                    if (!Masonry.take(level, v, Items.STONE_BRICKS, 1)) break;
                    Crafts.store(level, v, new ItemStack(was.is(Blocks.COBBLESTONE) ? Items.COBBLESTONE : Items.DIRT));
                    if (!above.isAir()) level.destroyBlock(p.above(), false);
                    level.setBlock(p, Blocks.STONE_BRICKS.defaultBlockState(), 3);
                } else {
                    if (!above.isAir()) level.destroyBlock(p.above(), false);
                    level.setBlock(p, Blocks.DIRT_PATH.defaultBlockState(), 3);
                }
                n++;
            }
            if (n > 0) {
                level.playSound(null, rough.get(0), iron ? SoundEvents.STONE_PLACE : SoundEvents.SHOVEL_FLATTEN, SoundSource.BLOCKS, 0.8F, 1.0F);
                return iron ? "paved the park's paths" : "laid the park's paths";
            }
        }
        // The fountain's rough stone dressed, from the Iron Age.
        if (iron) {
            List<BlockPos> rugged = new ArrayList<>();
            for (BlockPos p : l.stone()) if (level.getBlockState(p).is(Blocks.COBBLESTONE)) rugged.add(p);
            if (!rugged.isEmpty() && Masonry.can(level, v, Items.STONE_BRICKS, 1)) {
                if (!TownJobs.atWork(level, v, "park", rugged.get(0), "dressing the park's fountain")) return null;
                int n = 0;
                for (BlockPos p : rugged) {
                    if (n >= budget || !Masonry.take(level, v, Items.STONE_BRICKS, 1)) break;
                    level.setBlock(p, Blocks.STONE_BRICKS.defaultBlockState(), 3);
                    Crafts.store(level, v, new ItemStack(Items.COBBLESTONE));
                    n++;
                }
                if (n > 0) return "dressed the park's fountain";
            }
        }
        return null;
    }

    /** A sapling that grows on its own into a tree that suits a park (not a dark oak, which wants four). */
    private static boolean plantable(ItemStack s) {
        return s.is(ItemTags.SAPLINGS) && !s.is(Items.DARK_OAK_SAPLING) && !s.is(Items.MANGROVE_PROPAGULE);
    }

    /** A bucket out of the stores filled and poured (the bucket back in): false if the stores have none. */
    private static boolean bucketOfWater(ServerLevel level, Villages.Village v) {
        if (Crafts.take(level, v, s -> s.is(Items.WATER_BUCKET), 1)) {
            Crafts.store(level, v, new ItemStack(Items.BUCKET));
            return true;
        }
        if (!Crafts.take(level, v, s -> s.is(Items.BUCKET), 1)) return false;
        Crafts.store(level, v, new ItemStack(Items.BUCKET));
        return true;
    }

    /**
     * Water that never runs dry, to fill a bucket at: the park's own basin once two cells of it side by
     * side hold water, else the nearest such pond, river or sea to the heart (a source with two more
     * beside it, which fills itself again as any does). Null if there is none within reach.
     */
    @Nullable
    static BlockPos spring(ServerLevel level, Villages.Village v, Layout park) {
        for (BlockPos p : park.water()) if (endless(level, p)) return p;
        BlockPos c = v.centre();
        for (int r = 4; r <= 48; r += 4) {
            for (int i = -r; i <= r; i += 2) {
                for (int[] d : new int[][]{ { i, -r }, { i, r }, { -r, i }, { r, i } }) {
                    int x = c.getX() + d[0], z = c.getZ() + d[1];
                    if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
                    BlockPos w = new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
                    if (endless(level, w)) return w;
                }
            }
        }
        return null;
    }

    private static boolean endless(ServerLevel level, BlockPos p) {
        FluidState f = level.getFluidState(p);
        if (!f.is(FluidTags.WATER) || !f.isSource()) return false;
        int n = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            FluidState o = level.getFluidState(p.relative(d));
            if (o.is(FluidTags.WATER) && o.isSource()) n++;
        }
        return n >= 2;
    }

    // ------------------------------------------------------------------ an hour in the park

    /** A folk's time in the park: when it set out, till when, what it is doing there. */
    static final class Visit {
        final long day;
        final int kind;                     // EVENING, BREAK or PLAY
        final long park;                    // the park's anchor
        final int until;
        int seat = -1;
        boolean seated, arrived, over;
        float yaw;
        @Nullable Vec3 spot;
        @Nullable BlockPos target;
        int sitUntil, moveTick, lastTick, walkStarted;
        String doing = "on the way to the park";

        Visit(long day, int kind, long park, int until, int now) {
            this.day = day;
            this.kind = kind;
            this.park = park;
            this.until = until;
            this.lastTick = now;
            this.moveTick = now;
            this.walkStarted = now;
        }
    }

    static final int EVENING = 0, BREAK = 1, PLAY = 2;

    private static final Map<UUID, Visit> VISITS = new ConcurrentHashMap<>();
    /** The last day each folk spent a while in a park. */
    private static final Map<UUID, Long> VISITED = new ConcurrentHashMap<>();
    /** Who sits where: a park's anchor, then its seat, then the folk on it. */
    private static final Map<Long, Map<Integer, UUID>> SEATED = new ConcurrentHashMap<>();

    /** The evening (VillageFolkEntity.eveningSocial): some evenings a folk spends a while in the park. */
    static boolean evening(VillageFolkEntity f, long timeOfDay) {
        return outing(f, EVENING);
    }

    /** Free time by day, on a break or the day of rest (VillageFolkEntity.socialise): a sit in the park, now and then. */
    static boolean visit(VillageFolkEntity f, ServerLevel level) {
        return outing(f, BREAK);
    }

    private static boolean outing(VillageFolkEntity f, int kind) {
        if (!(f.level() instanceof ServerLevel level) || f.isBaby() || f.isSleeping() || f.ownerId() == null) return false;
        long day = level.getDayTime() / 24000L;
        Visit vis = VISITS.get(f.getUUID());
        if (vis != null && vis.day == day && vis.kind == kind) {
            if (vis.over) return false;
            return drive(f, level, vis);
        }
        if (vis != null && !vis.over && vis.day == day) return drive(f, level, vis);       // a visit of another sort, going on
        if (!free(f, level, kind)) return false;
        BlockPos home = f.bedPos() != null ? f.bedPos() : f.villageCentre() != null ? f.villageCentre() : f.blockPosition();
        Ledger.Building park = nearest(f.ownerId(), home, 96);
        if (park == null) return false;
        if (kind == BREAK && park.anchor().distSqr(f.blockPosition().atY(park.anchor().getY())) > 48.0 * 48.0) return false;
        if (!goesToday(f, day, kind, park)) return false;
        int stay = kind == EVENING ? 1400 + f.getRandom().nextInt(1200) : 500 + f.getRandom().nextInt(500);
        vis = new Visit(day, kind, park.anchor().asLong(), f.tickCount + stay, f.tickCount);
        VISITS.put(f.getUUID(), vis);
        return drive(f, level, vis);
    }

    /** Is it this folk's day for the park? Partly its nature, partly where it lives; the same all day. */
    static boolean goesToday(VillageFolkEntity f, long day, int kind, Ledger.Building park) {
        int roll = Math.floorMod((int) (day * (kind == EVENING ? 31L : 57L)) + f.getUUID().hashCode() * 13 + kind * 5, 12);
        int chance = kind == EVENING ? 3 : 2;                          // in twelve
        if (near(f.ownerId(), f.bedPos())) chance += 3;
        Persona me = f.persona();
        if (me.rolled() && (me.hobby() == Persona.Hobby.WALKING || me.hobby() == Persona.Hobby.READING)) chance += 2;
        if (f.life().has(Social.Trait.SOCIABLE) || f.life().has(Social.Trait.EASYGOING)) chance += 1;
        if (f.life().has(Social.Trait.SHY)) chance -= 1;
        if (kind == BREAK && f.life().has(Social.Trait.HARDWORKING)) chance -= 1;
        return roll < chance;
    }

    /** Is the folk free for the park: off work, dry, no bell, no gathering, no town work, not bedtime? */
    private static boolean free(VillageFolkEntity f, ServerLevel level, int kind) {
        if (f.isSleeping() || f.ownerId() == null || level.isRaining() || Raids.underAlarm(f.ownerId())) return false;
        if (f.peekJob() != null || TownJobs.busy(f) || Assemblies.attending(f)) return false;
        long t = level.getDayTime() % 24000L;
        if (kind == PLAY) return f.isBaby() && t >= 6000L && t < 11500L;
        if (!f.offWorkNow()) return false;
        return t < 12000L || t < f.bedtimeTick();                     // never past its bedtime, whatever took it there
    }

    /** One step of a visit: walk there, sit, chat, stroll. True while it is in hand. */
    private static boolean drive(VillageFolkEntity f, ServerLevel level, Visit vis) {
        Ledger.Building b = parkAt(f.ownerId(), vis.park);
        if (b == null || f.tickCount > vis.until || !free(f, level, vis.kind)) {
            finish(f, vis);
            return false;
        }
        Layout l = layout(b);
        RandomSource r = f.getRandom();
        long day = level.getDayTime() / 24000L;
        vis.lastTick = f.tickCount;
        f.lastLeisureTick = f.tickCount;
        boolean in = inside(l, f.blockPosition());
        if (in && !vis.arrived) {
            vis.arrived = true;
            if (!f.isBaby()) {
                Long was = VISITED.put(f.getUUID(), day);
                if ((was == null || was != day) && r.nextInt(3) == 0) f.persona().remember(day, "I spent an hour in the park", 1);
            }
        }
        if (vis.kind == PLAY) return play(f, level, vis, l, in);
        if (vis.seated) {
            sitting(f, level, vis, l);
            return true;
        }
        if (vis.seat < 0 && vis.target == null) {
            if (r.nextInt(3) != 0 && claimSeat(f, level, vis, l)) {
                vis.walkStarted = f.tickCount;
            } else {
                vis.target = l.paths().get(r.nextInt(l.paths().size())).above();
                vis.walkStarted = f.tickCount;
            }
        }
        if (vis.seat >= 0) {
            Seat s = l.seats().get(vis.seat);
            Vec3 spot = seatSpot(s);
            if (f.distanceToSqr(spot) <= 1.8 * 1.8) {
                sit(f, level, vis, s, spot);
                return true;
            }
            if (f.tickCount - vis.walkStarted > 600) {                         // no way to it: walk about instead
                release(f, vis);
                vis.target = l.paths().get(r.nextInt(l.paths().size())).above();
                vis.walkStarted = f.tickCount;
                return true;
            }
            vis.doing = in ? "looking for a bench in the park" : "on the way to the park";
            if (f.getNavigation().isDone() || f.tickCount - vis.moveTick > 80) {
                f.walkTo(s.at(), 0.8D);
                vis.moveTick = f.tickCount;
            }
            f.hobbyNow = vis.doing;
            return true;
        }
        // Strolling the paths.
        BlockPos to = vis.target;
        if (to == null) return true;
        if (f.blockPosition().distSqr(to) <= 2.5 * 2.5 || f.tickCount - vis.walkStarted > 500) {
            if (in && r.nextInt(3) == 0 && claimSeat(f, level, vis, l)) {
                vis.target = null;
                vis.walkStarted = f.tickCount;
                return true;
            }
            vis.target = l.paths().get(r.nextInt(l.paths().size())).above();
            vis.walkStarted = f.tickCount;
            greet(f, level);
            to = vis.target;
        }
        vis.doing = in ? "strolling round the park" : "on the way to the park";
        if (f.getNavigation().isDone() || f.tickCount - vis.moveTick > 80) {
            f.walkTo(to, in ? 0.6D : 0.8D);
            vis.moveTick = f.tickCount;
        }
        f.hobbyNow = vis.doing;
        return true;
    }

    /** Where somebody sits on a bench: on its seat, a little toward the front edge. */
    private static Vec3 seatSpot(Seat s) {
        return new Vec3(s.at().getX() + 0.5 + s.looks().getStepX() * 0.2, s.at().getY() + 0.5,
            s.at().getZ() + 0.5 + s.looks().getStepZ() * 0.2);
    }

    /** A free seat for it: beside its partner or best friend if one is sitting, else any. */
    private static boolean claimSeat(VillageFolkEntity f, ServerLevel level, Visit vis, Layout l) {
        Map<Integer, UUID> taken = SEATED.computeIfAbsent(vis.park, k -> new ConcurrentHashMap<>());
        taken.values().removeIf(id -> !(VISITS.get(id) instanceof Visit o) || o.over || !o.seated && o.seat < 0);
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < l.seats().size(); i++) {
            if (taken.containsKey(i)) continue;
            BlockPos at = l.seats().get(i).at();
            if (!(level.getBlockState(at).getBlock() instanceof StairBlock) || !level.getBlockState(at.above()).isAir()) continue;
            if (Seats.claimed(at)) continue;                       // sat on by a folk on its break (Seats)
            free.add(i);
        }
        if (free.isEmpty()) return false;
        int pick = free.get(f.getRandom().nextInt(free.size()));
        UUID partner = f.life().partner(), best = f.life().bestFriend();
        for (Map.Entry<Integer, UUID> e : taken.entrySet()) {
            if (!e.getValue().equals(partner) && !e.getValue().equals(best)) continue;
            BlockPos theirs = l.seats().get(e.getKey()).at();
            for (int i : free) if (l.seats().get(i).at().distManhattan(theirs) == 1) { pick = i; break; }
        }
        taken.put(pick, f.getUUID());
        vis.seat = pick;
        return true;
    }

    private static void sit(VillageFolkEntity f, ServerLevel level, Visit vis, Seat s, Vec3 spot) {
        f.getNavigation().stop();
        vis.yaw = s.looks().toYRot();
        vis.spot = spot;
        f.moveTo(spot.x, spot.y, spot.z, vis.yaw, 0.0F);
        f.setYHeadRot(vis.yaw);
        f.setYBodyRot(vis.yaw);
        f.setPose(Pose.SITTING);
        vis.seated = true;
        vis.sitUntil = f.tickCount + 500 + f.getRandom().nextInt(900);
        vis.doing = "sitting on a bench by the fountain";
        f.hobbyNow = vis.doing;
    }

    /** Sat on a bench: looking at the water, or at whoever sits by it and chatting; then up to stroll. */
    private static void sitting(VillageFolkEntity f, ServerLevel level, Visit vis, Layout l) {
        if (f.tickCount > vis.sitUntil) {
            standUp(f, vis);
            release(f, vis);
            vis.target = l.paths().get(f.getRandom().nextInt(l.paths().size())).above();
            vis.walkStarted = f.tickCount;
            return;
        }
        VillageFolkEntity by = null;
        double nearest = 3.5 * 3.5;
        for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(3.5),
                o -> o != f && o.isAlive() && !o.isSleeping())) {
            double d = o.distanceToSqr(f);
            if (d < nearest) { nearest = d; by = o; }
        }
        if (by != null) {
            f.getLookControl().setLookAt(by, 20.0F, 20.0F);
            vis.doing = "sitting on a bench in the park with " + by.displayNameCap();
            if (f.tickCount - f.lastSmalltalk > 1200 && f.getRandom().nextInt(4) == 0 && Smalltalk.chat(f, by, level)) {
                f.lastSmalltalk = f.tickCount;
                by.lastSmalltalk = by.tickCount;
            }
            // An hour on a bench together: they like each other the better for it.
            if (f.getRandom().nextInt(6) == 0 && !by.isBaby() && f.life().affinity(by.getUUID()) > Social.RIVAL) {
                f.life().feel(by.getUUID(), by.displayNameCap(), 1);
            }
        } else {
            f.getLookControl().setLookAt(l.centre().getX() + 0.5, l.centre().getY() + 1.0, l.centre().getZ() + 0.5);
            vis.doing = "sitting on a bench by the fountain";
        }
        f.hobbyNow = vis.doing;
    }

    /** Children, of an afternoon: off to the park to run about (VillageFolkEntity.childhood). */
    static boolean play(VillageFolkEntity child, ServerLevel level) {
        if (!child.isBaby() || child.ownerId() == null) return false;
        long day = level.getDayTime() / 24000L;
        Visit vis = VISITS.get(child.getUUID());
        if (vis != null && vis.day == day && vis.kind == PLAY) return !vis.over && drive(child, level, vis);
        if (!free(child, level, PLAY)) return false;
        BlockPos from = child.villageCentre() != null ? child.villageCentre() : child.blockPosition();
        Ledger.Building park = nearest(child.ownerId(), from, 80);
        if (park == null) return false;
        if (!playsToday(child, day)) return false;
        vis = new Visit(day, PLAY, park.anchor().asLong(), child.tickCount + 2400 + child.getRandom().nextInt(1200), child.tickCount);
        VISITS.put(child.getUUID(), vis);
        return drive(child, level, vis);
    }

    /** Two afternoons in three, a child's own. */
    static boolean playsToday(VillageFolkEntity child, long day) {
        return Math.floorMod((int) (day * 17L) + child.getUUID().hashCode(), 3) != 0;
    }

    private static boolean play(VillageFolkEntity child, ServerLevel level, Visit vis, Layout l, boolean in) {
        RandomSource r = child.getRandom();
        if (vis.target == null || child.blockPosition().distSqr(vis.target) <= 2.0 * 2.0 || child.tickCount - vis.walkStarted > 200) {
            vis.target = l.paths().get(r.nextInt(l.paths().size())).above();
            vis.walkStarted = child.tickCount;
            if (in && r.nextInt(3) == 0) child.getJumpControl().jump();
            // Another child about: tag.
            if (in) {
                for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, child.getBoundingBox().inflate(4.0),
                        o -> o != child && o.isAlive() && o.isBaby())) {
                    if (r.nextInt(4) == 0) {
                        child.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                        FolkTalk.speak(child, FolkTalk.pick(r, "Tag! You're it!", "Can't catch me!", "Round the fountain!"));
                    }
                    break;
                }
            }
        }
        vis.doing = in ? "playing in the park" : "running to the park";
        if (child.getNavigation().isDone() || child.tickCount - vis.moveTick > 60) {
            child.walkTo(vis.target, in ? 1.15D : 1.0D);
            vis.moveTick = child.tickCount;
        }
        child.hobbyNow = vis.doing;
        return true;
    }

    /** A word for whoever it passes on the path, now and then. */
    private static void greet(VillageFolkEntity f, ServerLevel level) {
        if (f.getRandom().nextInt(5) != 0) return;
        for (VillageFolkEntity g : level.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(4.0),
                g -> g != f && g.isAlive() && !g.isSleeping() && !g.isBaby())) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Lovely in the park, isn't it, " + g.displayNameCap() + "?",
                "Evening, " + g.displayNameCap() + ".", "Hear that fountain? Lovely.", "Mind the flowers!"));
            return;
        }
    }

    /**
     * Every tick (VillageFolkEntity.aiStep): a folk sat on a bench stays sat, facing the way the bench
     * does, until it is time to get up, it is called away or its free time is over; between the
     * brain's looks, the walk round the park goes on.
     */
    static void tick(VillageFolkEntity f) {
        if (Culture.seated(f)) return;                         // [batchD] sat on a bench at the theatre (Culture): left sat
        Visit vis = VISITS.get(f.getUUID());
        if (vis == null) {
            // After a restart (but not a folk sat down on its break or at a gathering: Seats, or for a story: Families).
            if (f.getPose() == Pose.SITTING && f.tickCount % 20 == 0 && !Seats.seated(f) && !Families.seated(f)) f.setPose(Pose.STANDING);
            return;
        }
        if (!(f.level() instanceof ServerLevel level)) return;
        if (vis.over) {
            if (f.getPose() == Pose.SITTING && !Seats.seated(f) && !Families.seated(f)) standUp(f, vis);
            if (vis.day != level.getDayTime() / 24000L) VISITS.remove(f.getUUID(), vis);
            return;
        }
        // Somebody else has it now (the shop, supper, the tavern): the park lets it go till it comes back.
        boolean mine = vis.doing.equals(f.hobbyNow);
        if (vis.seated) {
            boolean onSeat = mine && f.getPose() == Pose.SITTING && vis.spot != null && f.position().distanceToSqr(vis.spot) < 0.5
                && !f.getNavigation().isInProgress();
            boolean stillFree = f.tickCount <= vis.until && (f.tickCount % 10 != 0 || free(f, level, vis.kind));
            if (!onSeat || !stillFree) {
                standUp(f, vis);
                release(f, vis);
                if (!free(f, level, vis.kind) || f.tickCount > vis.until) finish(f, vis);
                return;
            }
            f.setYBodyRot(vis.yaw);
            if (f.tickCount % 20 == 0) drive(f, level, vis);
            return;
        }
        // Between the brain's looks, the walk goes on.
        if (mine && (f.tickCount + f.getId()) % 20 == 0 && f.tickCount - vis.lastTick < 900) drive(f, level, vis);
    }

    private static void standUp(VillageFolkEntity f, @Nullable Visit vis) {
        if (f.getPose() == Pose.SITTING) f.setPose(Pose.STANDING);
        if (vis != null) {
            vis.seated = false;
            vis.spot = null;
        }
    }

    private static void release(VillageFolkEntity f, Visit vis) {
        Map<Integer, UUID> taken = SEATED.get(vis.park);
        if (taken != null && vis.seat >= 0) taken.remove(vis.seat, f.getUUID());
        vis.seat = -1;
    }

    private static void finish(VillageFolkEntity f, Visit vis) {
        standUp(f, vis);
        release(f, vis);
        vis.over = true;
    }

    @Nullable
    private static Ledger.Building parkAt(@Nullable UUID village, long anchor) {
        for (Ledger.Building b : parks(village)) if (b.anchor().asLong() == anchor) return b;
        return null;
    }

    /** What a folk is doing in the park now, for its card ("Sitting on a bench by the fountain"), or null. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        Visit vis = VISITS.get(f.getUUID());
        if (vis == null || vis.over || f.tickCount - vis.lastTick > 1200 || f.tickCount > vis.until) return null;
        String d = vis.doing;
        return d.isEmpty() ? null : Character.toUpperCase(d.charAt(0)) + d.substring(1);
    }

    /** Is it in the park now (not on its way)? */
    public static boolean there(VillageFolkEntity f) {
        Visit vis = VISITS.get(f.getUUID());
        return vis != null && !vis.over && vis.arrived && f.tickCount <= vis.until;
    }

    /** Did it spend a while in the park today? */
    public static boolean visitedToday(VillageFolkEntity f) {
        Long d = VISITED.get(f.getUUID());
        return d != null && d == f.level().getDayTime() / 24000L;
    }

    /** Somewhere on the park's paths for a walk (RestDay: walking out), or null if the town has no park near. */
    @Nullable
    public static BlockPos strollSpot(VillageFolkEntity f) {
        BlockPos from = f.villageCentre() != null ? f.villageCentre() : f.blockPosition();
        Ledger.Building park = nearest(f.ownerId(), from, 64);
        if (park == null) return null;
        List<BlockPos> paths = layout(park).paths();
        return paths.get(f.getRandom().nextInt(paths.size())).above();
    }

    /** The park in a line, for the books: where it stands and how it is coming on, or when it is wanted. */
    public static String status(ServerLevel level, Villages.Village v) {
        List<Ledger.Building> all = parks(v.id());
        if (all.isEmpty()) {
            int folk = Villages.headcount(v.id());
            if (Villages.sitesOf(v.id()).containsKey(STRUCTURE)) return "The park is going up.";
            if (folk < FOLK) return "A park is planned once the town has " + FOLK + " folk (it has " + folk + ").";
            return Villages.ageOf(v.id()).ordinal() < Villages.Age.STONE.ordinal()
                ? "A park is planned once the town builds in stone."
                : "A park is on the builders' list.";
        }
        Ledger.Building b = all.get(0);
        Layout l = layout(b);
        int dx = b.anchor().getX() - v.centre().getX(), dz = b.anchor().getZ() - v.centre().getZ();
        int trees = 0, paths = 0, water = 0;
        boolean loaded = Land.areaLoaded(level, b.anchor(), 8);
        if (loaded) {
            for (BlockPos t : l.trees()) {
                BlockState st = level.getBlockState(t);
                if (st.getBlock() instanceof SaplingBlock || st.is(BlockTags.LOGS)) trees++;
            }
            for (BlockPos p : l.paths()) if (level.getBlockState(p).is(Blocks.DIRT_PATH) || level.getBlockState(p).is(Blocks.STONE_BRICKS)) paths++;
            for (BlockPos p : l.water()) if (level.getFluidState(p).is(FluidTags.WATER) && level.getFluidState(p).isSource()) water++;
        }
        int there = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f && there(f)) there++;
        int[] other = { -1 };
        String side = Districts.sideWord(Districts.sideOf(dx, dz, other));
        int dist = (int) Math.round(Math.sqrt(dx * (double) dx + dz * (double) dz));
        return "The park, " + dist + " blocks " + side + " of the square: " + there + (there == 1 ? " folk" : " folk") + " there now"
            + (loaded ? "; " + trees + " of " + l.trees().size() + " trees planted, " + paths + " of " + l.paths().size()
            + " yards of path laid, the fountain " + (water >= l.water().size() ? "full" : water + " of " + l.water().size() + " filled") : "")
            + ".";
    }

    // ------------------------------------------------------------------ for the photographs and the tests

    /** /village districts park now: the park put up at once on its lot (as the showcase does), its ground made
     *  level first, its trees grown and paths laid, and everybody off work sent to it. Prints "PARK x y z facing dir". */
    static int now(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = Quarters.near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        List<Ledger.Building> have = parks(v.id());
        Ledger.Building b;
        if (have.isEmpty()) {
            Villages.Site site = Villages.siteFor(level, v.id(), STRUCTURE);
            if (site == null) {
                ctx.getSource().sendFailure(Component.literal("No lot for a park yet: the ground is still coming in, or the homes quarter is full."));
                return 0;
            }
            b = putUp(level, v.id(), site.anchor(), site.facing());
        } else {
            b = have.get(0);
            grow(level, b);
        }
        int sent = callEveryone(level, v);
        String text = "PARK " + b.anchor().getX() + " " + b.anchor().getY() + " " + b.anchor().getZ() + " facing " + b.facing().getName()
            + " in " + Quarters.districtOf(v.id(), v.centre(), b).words + "; " + sent + " folk sent to it";
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    /**
     * A park put up at once on its lot, for nothing, as the showcase puts a building up (the photographs, the
     * tests): its ground made level first as its builder and keepers would leave it (ParkGround.levelNow),
     * then the drawing, then grown; any water that would not stay in the fountain taken up again.
     */
    public static Ledger.Building putUp(ServerLevel level, UUID village, BlockPos anchor, Direction facing) {
        ParkGround.levelNow(level, anchor);
        BuildGoal.stamp(level, STRUCTURE, anchor, facing, 0,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Villages.noteProject(village, STRUCTURE, level.getGameTime());
        List<Ledger.Building> have = parks(village);
        if (have.isEmpty()) {
            Ledger.built(village, STRUCTURE, anchor, facing);
            have = parks(village);
        }
        Ledger.Building b = have.get(0);
        grow(level, b);
        ParkGround.stopLeaks(level, layout(b));
        return b;
    }

    /** The park finished at once, for nothing (the photographs, the tests): trees grown, paths laid, the spring running. */
    public static void grow(ServerLevel level, Ledger.Building b) {
        Layout l = layout(b);
        RandomSource r = level.getRandom();
        for (BlockPos t : l.trees()) {
            if (!level.getBlockState(t.below()).is(BlockTags.DIRT)) continue;
            if (!level.getBlockState(t).isAir() && !(level.getBlockState(t).getBlock() instanceof SaplingBlock)) continue;
            BlockState sapling = Blocks.OAK_SAPLING.defaultBlockState();
            level.setBlock(t, sapling, 3);
            for (int i = 0; i < 8 && level.getBlockState(t).getBlock() instanceof SaplingBlock s; i++) {
                s.advanceTree(level, t, level.getBlockState(t), r);
            }
        }
        for (BlockPos p : l.paths()) {
            BlockState st = level.getBlockState(p);
            if ((st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT)) && level.getBlockState(p.above()).canBeReplaced()) {
                if (!level.getBlockState(p.above()).isAir()) level.removeBlock(p.above(), false);
                level.setBlock(p, Blocks.DIRT_PATH.defaultBlockState(), 3);
            }
        }
        for (BlockPos p : l.water()) {
            if (level.getFluidState(p).isSource()) level.scheduleTick(p, net.minecraft.world.level.material.Fluids.WATER, 2);
        }
    }

    /** /village districts park visit: everybody off work goes to the park now, for a while. Returns how many. */
    static int callEveryone(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = Quarters.near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        int n = callEveryone(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal("PARK-VISIT " + n + " folk sent to the park"), false);
        return n;
    }

    public static int callEveryone(ServerLevel level, Villages.Village v) {
        List<Ledger.Building> all = parks(v.id());
        if (all.isEmpty()) return 0;
        long day = level.getDayTime() / 24000L;
        long park = all.get(0).anchor().asLong();
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isShowcase() || f.isSleeping()) continue;
            int kind = f.isBaby() ? PLAY : f.level().getDayTime() % 24000L >= 12000L ? EVENING : BREAK;
            Visit vis = new Visit(day, kind, park, f.tickCount + 2400, f.tickCount);
            VISITS.put(f.getUUID(), vis);
            if (drive(f, level, vis)) n++;
        }
        return n;
    }

    /** Tests: would this folk spend this day's evening in the park (its nature, where it lives, the day's roll)? */
    public static boolean eveningForTests(VillageFolkEntity f, long day) {
        BlockPos home = f.bedPos() != null ? f.bedPos() : f.blockPosition();
        Ledger.Building park = nearest(f.ownerId(), home, 96);
        return park != null && goesToday(f, day, EVENING, park);
    }

    /** Tests: does this child go to the park on this day's afternoon? */
    public static boolean playsForTests(VillageFolkEntity child, long day) {
        return playsToday(child, day);
    }

    /** Tests: the visit going on for a folk, as {kind, seated (1/0), arrived (1/0)}, or null. */
    @Nullable
    public static int[] visitForTests(VillageFolkEntity f) {
        Visit vis = VISITS.get(f.getUUID());
        return vis == null || vis.over ? null : new int[]{ vis.kind, vis.seated ? 1 : 0, vis.arrived ? 1 : 0 };
    }
}
