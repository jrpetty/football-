package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Donkey;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Mule;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Horses, donkeys and the stable.
 *
 * <p><b>Wild ones, gentled.</b> Horses and donkeys come into the world wild, on the plains and
 * the savanna. The rancher goes out to one with something it eats in its hand — wheat, an apple,
 * a golden carrot if the stores have any — and walks it home with the animal following the hand
 * (Drover's way with a sheep). At home it is the village's catch, and the rancher gentles it a
 * little at a time, as a player does: a bite to eat (wheat and apples and sugar sweeten its temper
 * by three, a golden carrot by five), and then up on its back. It bucks; nine times in ten the
 * rancher is thrown and the horse is a little calmer for it (five more temper), and once its temper
 * is up it stands for the rider and is tamed: the village's own, its owner the village, a name of
 * its own (Bay, Dapple, Ned...). Two tamed horses given a golden carrot each make a foal, which the
 * rancher gentles in its turn when it is grown.
 *
 * <p><b>Saddles and chests.</b> Nobody can make a saddle. The fishers land one now and then, the
 * scouts find them in old chests, and on market day the traders sell one when the stable has a
 * horse without. The rancher puts a saddle from the stores on a tamed horse, and a chest from the
 * stores on a donkey or a mule when the village sends caravans. Leads are two from four string and
 * a slime ball, plaited by the rancher when the stores have the makings.
 *
 * <p><b>The stable</b> (a building of its own, planned once the village has horses, or once an
 * Iron Age town has a rancher and a saddle): four stalls fenced off either side of an aisle, hay
 * at the front and in the loft, a sunken trough and a cauldron, and three gates across the door.
 * Each horse has its stall and stands in it when it is not out; the gates open for whoever is
 * going through and are shut behind; every evening the rancher goes round with the feed. Riding
 * them (couriers, scouts) and leading them (the caravans' donkeys) is {@link Riding}'s.
 */
public final class Stables {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Stables() {}

    /** The tag on the village's own horses, donkeys and mules (besides Drover.HERD). */
    public static final String STEED = "mca_steed";
    /** The tag on a wild one the rancher brought home and is gentling. */
    public static final String CATCH = "mca_catch";
    /** Kept on the animal: whose catch it is, its name, who tamed it, how many goes it has taken. */
    static final String OF = "mca_catch_of", NAME = "mca_horse_name", TAMER = "mca_tamed_by", GOES = "mca_goes";

    /** How far round the village its animals are looked for. */
    static final int SPREAD = 96;
    /** How far the rancher goes for a wild one. */
    static final int RANGE = 64;
    /** Nearer than this to its place, an animal walks back in by itself; further, the rancher fetches it. */
    static final int YARD = 24;
    /** What the traders ask for a saddle (a leatherworker's six emeralds, near enough). */
    static final int SADDLE_PRICE = 24;
    /** Stalls in the stable. */
    public static final int STALLS = 4;

    /** What a horse eats that sweetens its temper. */
    static final Predicate<ItemStack> FEED = s -> s.is(Items.WHEAT) || s.is(Items.APPLE) || s.is(Items.SUGAR) || s.is(Items.GOLDEN_CARROT);
    /** The evening feed: wheat, an apple, or a hay bale. */
    static final Predicate<ItemStack> FODDER = s -> s.is(Items.WHEAT) || s.is(Items.APPLE) || s.is(Items.HAY_BLOCK);
    /** What brings two horses into foal. */
    static final Predicate<ItemStack> GOLDEN = s -> s.is(Items.GOLDEN_CARROT) || s.is(Items.GOLDEN_APPLE);

    public static void resetForTests() {
        rest = 600L;
        WORK.clear();
        HERDS.clear();
        UPKEPT.clear();
        OPENED.clear();
        FED.clear();
        GAVE_UP.clear();
        GENTLED.clear();
        BOUGHT.clear();
        STABLES.clear();
        STABLE_LOOKED.clear();
        Riding.resetForTests();
    }

    // ------------------------------------------------------------------ whose they are

    /** A horse, donkey or mule (not a llama, a camel, a skeleton's or a zombie's). */
    static boolean kind(AbstractHorse h) {
        return h instanceof Horse || h instanceof Donkey || h instanceof Mule;
    }

    /** The village a horse is the village's own (tamed, the village its owner) or being gentled for; null if it is wild or a player's. */
    @Nullable
    public static UUID villageOf(AbstractHorse h) {
        if (h.isTamed()) {
            UUID o = h.getOwnerUUID();
            return o != null && Villages.get(o) != null ? o : null;
        }
        if (!h.getTags().contains(CATCH)) return null;
        try {
            return UUID.fromString(h.getPersistentData().getString(OF));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Is this tamed one the village's own? */
    static boolean ours(AbstractHorse h, UUID village) {
        return h.isAlive() && h.isTamed() && village.equals(h.getOwnerUUID());
    }

    /** The village's animals round about: its own and the ones it is gentling. */
    static List<AbstractHorse> animals(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v == null || !v.dim().equals(level.dimension())) return List.of();
        List<AbstractHorse> out = new ArrayList<>(level.getEntitiesOfClass(AbstractHorse.class,
            new AABB(v.centre()).inflate(SPREAD, 48, SPREAD), h -> h.isAlive() && kind(h) && village.equals(villageOf(h))));
        // And any out with a rider further off.
        for (Riding.Ride r : Riding.RIDES.values()) {
            if (!r.village.equals(village)) continue;
            Entity e = level.getEntity(r.horse);
            if (e instanceof AbstractHorse h && h.isAlive() && !out.contains(h)) out.add(h);
        }
        return out;
    }

    /** What it is called: the name it was given when it was tamed, or what it is. */
    public static String name(AbstractHorse h) {
        String n = h.getPersistentData().getString(NAME);
        if (!n.isEmpty()) return n;
        if (h.hasCustomName() && h.getCustomName() != null) return h.getCustomName().getString();
        return "the " + what(h);
    }

    /** "brown horse", "donkey", "mule". */
    static String what(AbstractHorse h) {
        if (h instanceof Donkey) return "donkey";
        if (h instanceof Mule) return "mule";
        if (h instanceof Horse horse) {
            return switch (horse.getVariant()) {
                case WHITE -> "white horse";
                case CREAMY -> "cream horse";
                case CHESTNUT -> "chestnut horse";
                case BROWN -> "brown horse";
                case BLACK -> "black horse";
                case GRAY -> "grey horse";
                case DARK_BROWN -> "dark bay horse";
            };
        }
        return "horse";
    }

    /** A name for a newly tamed one, by its colour, not one the village's others already have. */
    static String newName(ServerLevel level, UUID village, AbstractHorse h) {
        String[] names;
        if (h instanceof Donkey) names = new String[]{ "Ned", "Jenny", "Dusty", "Biscuit", "Clover", "Pip" };
        else if (h instanceof Mule) names = new String[]{ "Molly", "Jack", "Bramble", "Nutmeg" };
        else if (h instanceof Horse horse) {
            names = switch (horse.getVariant()) {
                case WHITE -> new String[]{ "Snowy", "Pearl", "Frost", "Lily" };
                case CREAMY -> new String[]{ "Butter", "Honey", "Primrose", "Sandy" };
                case CHESTNUT -> new String[]{ "Chestnut", "Copper", "Rusty", "Ginger" };
                case BROWN -> new String[]{ "Bay", "Hazel", "Conker", "Acorn" };
                case BLACK -> new String[]{ "Midnight", "Raven", "Coal", "Shadow" };
                case GRAY -> new String[]{ "Dapple", "Smoke", "Pewter", "Misty" };
                case DARK_BROWN -> new String[]{ "Duke", "Mahogany", "Bay", "Walnut" };
            };
        } else names = new String[]{ "Star" };
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (AbstractHorse o : animals(level, village)) taken.add(o.getPersistentData().getString(NAME));
        int start = Math.floorMod(h.getUUID().hashCode(), names.length);
        for (int k = 0; k < names.length; k++) {
            String n = names[(start + k) % names.length];
            if (!taken.contains(n)) return n;
        }
        return names[start] + " the " + (taken.size() + 1) + (taken.size() + 1 == 2 ? "nd" : taken.size() + 1 == 3 ? "rd" : "th");
    }

    // ------------------------------------------------------------------ the stable

    /** The stable as built: where its stalls, its gates and its aisle are, from the drawing (stable.txt). */
    public record Stable(BlockPos anchor, Direction facing) {
        /** A spot in the stable: so far across (right is +) and so deep (the back is +), from the middle. */
        public Vec3 at(double across, double deep) {
            Direction right = facing.getClockWise();
            return new Vec3(anchor.getX() + 0.5 + right.getStepX() * across + facing.getStepX() * deep, anchor.getY(),
                anchor.getZ() + 0.5 + right.getStepZ() * across + facing.getStepZ() * deep);
        }

        public BlockPos block(int across, int deep) {
            return anchor.relative(facing.getClockWise(), across).relative(facing, deep);
        }

        /** The middle of a stall: two to a side, by the back wall and in front of it. */
        public Vec3 stall(int i) {
            return at(i < 2 ? -2.5 : 2.5, i % 2 == 0 ? 2.5 : -0.5);
        }

        /** The block a horse is walked to for a stall: the corner a two-wide animal stands on (the game's way) to fill it square. */
        public BlockPos stallNode(int i) {
            int a0 = i < 2 ? -3 : 2, d0 = i % 2 == 0 ? 2 : -1;
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (int a = a0; a <= a0 + 1; a++) {
                for (int d = d0; d <= d0 + 1; d++) {
                    BlockPos b = block(a, d);
                    minX = Math.min(minX, b.getX());
                    minZ = Math.min(minZ, b.getZ());
                }
            }
            return new BlockPos(minX, anchor.getY(), minZ);
        }

        public List<BlockPos> gates() {
            return List.of(block(-1, -4), block(0, -4), block(1, -4));
        }

        public BlockPos door() { return block(0, -4); }

        /** Out in front of the door. */
        public BlockPos outside() { return block(0, -7); }

        public BlockPos aisle() { return block(0, 0); }

        /** Within its walls? */
        public boolean inside(Vec3 p) {
            Direction right = facing.getClockWise();
            double dx = p.x - (anchor.getX() + 0.5), dz = p.z - (anchor.getZ() + 0.5);
            double across = dx * right.getStepX() + dz * right.getStepZ(), deep = dx * facing.getStepX() + dz * facing.getStepZ();
            return Math.abs(across) <= 3.6 && Math.abs(deep) <= 3.6 && Math.abs(p.y - anchor.getY()) < 3;
        }
    }

    private static final Map<UUID, Stable> STABLES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> STABLE_LOOKED = new ConcurrentHashMap<>();

    /** The village's stable, or null before it has one. */
    @Nullable
    public static Stable stable(UUID village) {
        Ledger.Building b = Villages.builtStructure(village, "stable");
        if (b == null) {
            STABLES.remove(village);
            return null;
        }
        Stable s = new Stable(b.anchor().immutable(), b.facing());
        STABLES.put(village, s);
        return s;
    }

    /** The stable, looked up at most every half-minute (the gates are minded every half-second). */
    @Nullable
    static Stable stableNow(UUID village, long now) {
        Long at = STABLE_LOOKED.get(village);
        if (at != null && now - at < 600 && now >= at) return STABLES.get(village);
        STABLE_LOOKED.put(village, now);
        return stable(village);
    }

    /** Where the village's animals live while there is no stable: the pen, the rancher's ground, or the heart. */
    @Nullable
    static BlockPos home(UUID village) {
        Drover.Pen pen = Drover.pen(village);
        if (pen != null) return pen.centre();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.stationTask() == StationTask.RANCH && a.workZone() != null) return a.workZone().center();
        }
        Villages.Village v = Villages.get(village);
        return v == null ? null : v.centre();
    }

    /** Each animal's place: a stall each for the tamed (fastest first) and then the ones being gentled; the rest in the aisle. */
    static List<AbstractHorse> byStall(List<AbstractHorse> herd) {
        List<AbstractHorse> out = new ArrayList<>(herd);
        out.sort(Comparator.comparing((AbstractHorse h) -> !h.isTamed()).thenComparing(Entity::getUUID));
        return out;
    }

    /** The spot this one belongs at, and how far it may wander from it. */
    static Vec3 placeOf(@Nullable Stable st, UUID village, List<AbstractHorse> ordered, AbstractHorse h) {
        int i = ordered.indexOf(h);
        if (st != null) return i >= 0 && i < STALLS ? st.stall(i) : Vec3.atBottomCenterOf(st.aisle());
        BlockPos home = home(village);
        return home == null ? h.position() : Vec3.atBottomCenterOf(home);
    }

    /** Where to send an animal to stand at its place: for a stall, the block that puts a horse square in it. */
    static Vec3 walkTarget(@Nullable Stable st, Vec3 place) {
        if (st != null) {
            for (int i = 0; i < STALLS; i++) {
                if (st.stall(i).distanceToSqr(place) < 0.01) {
                    BlockPos n = st.stallNode(i);
                    return new Vec3(n.getX() + 0.5, n.getY(), n.getZ() + 0.5);
                }
            }
        }
        return place;
    }

    static double flat(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Is it at its place, near enough to be seen to there (fed, saddled, gentled)? In a stall, mostly in it. */
    static boolean inPlace(@Nullable Stable st, Vec3 place, AbstractHorse h) {
        return flat(h.position(), place) <= (st != null ? 1.6 : 3.5) && Math.abs(h.getY() - place.y) < 2.5;
    }

    /** Square in its stall (or about its place, with no stable): no need to walk it in. */
    static boolean settled(@Nullable Stable st, Vec3 place, AbstractHorse h) {
        return flat(h.position(), place) <= (st != null ? 0.6 : 3.5) && Math.abs(h.getY() - place.y) < 2.5;
    }

    /**
     * Walk an animal (or the horse under a rider) right on to a spot: a path that ends on the very
     * block, not the game's usual one that stops a block short of it — a block short of a stall is a
     * horse standing half out in the aisle, and the next walk-in from there was taken as already done.
     */
    static void walkInto(net.minecraft.world.entity.ai.navigation.PathNavigation nav, Vec3 to, double speed) {
        net.minecraft.world.level.pathfinder.Path exact = nav.createPath(BlockPos.containing(to), 0);
        if (exact != null && exact.canReach()) nav.moveTo(exact, speed);
        else nav.moveTo(to.x, to.y, to.z, speed);
    }

    // ------------------------------------------------------------------ the stable's gates

    /** Gates the village opened, and when: shut again behind whoever went through. */
    private static final Map<Long, Long> OPENED = new ConcurrentHashMap<>();

    /** From a folk's tick: the stable's gates minded when it is near them. */
    public static void gate(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        if (id == null) return;
        Stable st = stableNow(id, level.getGameTime());
        if (st == null || !level.isLoaded(st.door())) return;
        if (f.distanceToSqr(Vec3.atCenterOf(st.door())) > 10.0 * 10.0) return;
        mindGates(level, id, st);
    }

    /** Open while anybody of the village (or one of its animals on the move) is at the door; shut a couple of seconds after. */
    static void mindGates(ServerLevel level, UUID village, Stable st) {
        BlockPos door = st.door();
        if (!level.isLoaded(door)) return;
        BlockState mid = level.getBlockState(door);
        if (!(mid.getBlock() instanceof FenceGateBlock)) return;
        boolean open = mid.getValue(FenceGateBlock.OPEN);
        long now = level.getGameTime();
        AABB box = new AABB(door).inflate(3.5, 2.0, 3.5);
        boolean someone = !level.getEntitiesOfClass(VillageFolkEntity.class, box,
            x -> village.equals(x.ownerId()) && x.isAlive() && !x.isSleeping()).isEmpty();
        if (!someone) {
            someone = !level.getEntitiesOfClass(AbstractHorse.class, box.inflate(-0.5),
                h -> village.equals(villageOf(h)) && (h.isVehicle() || h.isLeashed() || !h.getNavigation().isDone())).isEmpty();
        }
        if (!someone) someone = Riding.comingThrough(level, village, door);
        if (someone && !open) {
            setGates(level, st, true);
            OPENED.put(door.asLong(), now);
        } else if (!someone && open) {
            Long since = OPENED.get(door.asLong());
            if (since != null && (now - since > 40 || now < since)) {
                setGates(level, st, false);
                OPENED.remove(door.asLong());
            }
        } else if (someone) {
            OPENED.computeIfPresent(door.asLong(), (k, t) -> now);           // still going through: the clock starts again
        }
    }

    /** Open the gates now, for one coming through (they are shut behind it by mindGates). */
    static void openGates(ServerLevel level, UUID village, Stable st) {
        BlockPos door = st.door();
        if (!level.isLoaded(door)) return;
        BlockState mid = level.getBlockState(door);
        if (!(mid.getBlock() instanceof FenceGateBlock) || mid.getValue(FenceGateBlock.OPEN)) {
            OPENED.computeIfPresent(door.asLong(), (k, t) -> level.getGameTime());
            return;
        }
        setGates(level, st, true);
        OPENED.put(door.asLong(), level.getGameTime());
    }

    private static void setGates(ServerLevel level, Stable st, boolean open) {
        boolean any = false;
        for (BlockPos g : st.gates()) {
            BlockState s = level.getBlockState(g);
            if (!(s.getBlock() instanceof FenceGateBlock) || s.getValue(FenceGateBlock.OPEN) == open) continue;
            level.setBlock(g, s.setValue(FenceGateBlock.OPEN, open), 10);
            any = true;
        }
        if (any) {
            level.playSound(null, st.door(), open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 0.8F, 1.0F);
        }
    }

    // ------------------------------------------------------------------ the herd, counted

    /** The village's horses and what goes with them, counted every half-minute for the books, the plan and the talk. */
    public record Herd(int horses, int donkeys, int mules, int saddled, int chested, int foals, int catches, int saddles,
                       int leads, int chests, boolean rancher, long at) {
        public int all() { return horses + donkeys + mules; }
    }

    static final Map<UUID, Herd> HERDS = new ConcurrentHashMap<>();

    /** As last counted, or null. */
    @Nullable
    public static Herd herd(UUID village) {
        return HERDS.get(village);
    }

    static Herd count(ServerLevel level, Villages.Village v, List<AbstractHorse> herd) {
        int horses = 0, donkeys = 0, mules = 0, saddled = 0, chested = 0, foals = 0, catches = 0;
        for (AbstractHorse h : herd) {
            if (!h.isTamed()) {
                catches++;
                if (h.isBaby()) foals++;
                continue;
            }
            if (h.isBaby()) foals++;
            if (h instanceof Donkey) donkeys++;
            else if (h instanceof Mule) mules++;
            else horses++;
            if (h.isSaddled()) saddled++;
            if (h instanceof AbstractChestedHorse c && c.hasChest()) chested++;
        }
        boolean rancher = false;
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a.stationTask() == StationTask.RANCH && !a.isBaby()) rancher = true;
        Herd out = new Herd(horses, donkeys, mules, saddled, chested, foals, catches,
            Market.stock(level, v.id(), s -> s.is(Items.SADDLE)), Market.stock(level, v.id(), s -> s.is(Items.LEAD)),
            Market.stock(level, v.id(), s -> s.is(Items.CHEST)), rancher, level.getGameTime());
        HERDS.put(v.id(), out);
        return out;
    }

    /**
     * Does the village want a stable? Once it has horses of its own (or is gentling one), or once a
     * town of the Iron Age has a rancher and a saddle in its stores. (Villages.projectsWanted.)
     */
    public static boolean wanted(UUID village) {
        Herd h = HERDS.get(village);
        if (h == null) return false;
        if (h.all() + h.catches() > 0) return true;
        return Villages.age(village).ordinal() >= Villages.Age.IRON.ordinal() && h.rancher() && h.saddles() > 0;
    }

    // ------------------------------------------------------------------ the stable's day

    private static final Map<UUID, Long> UPKEPT = new ConcurrentHashMap<>();
    /** The day the village last bought a saddle off the traders. */
    private static final Map<UUID, Long> BOUGHT = new ConcurrentHashMap<>();

    /**
     * From every folk's tick, but once in five seconds a village: the herd counted, each animal at
     * home in its stall (walked back in if it has strayed into the yard), the foals born in the
     * stable taken in hand, a pack animal home with goods still on it unpacked, the gates seen to,
     * and on market day a saddle from the traders for a horse that has none.
     */
    public static void tick(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        if (id == null) return;
        long now = level.getGameTime();
        Long last = UPKEPT.get(id);
        if (last != null && now - last < 100 && now >= last) return;
        UPKEPT.put(id, now);
        Villages.Village v = Villages.get(id);
        if (v == null || !v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) return;
        Riding.sweep(level, id);
        scoutsHome(level, v);
        Herd counted = HERDS.get(id);
        boolean recount = counted == null || now - counted.at() >= 600 || now < counted.at();
        Stable st = stableNow(id, now);
        // A village with no horses, no stable and nobody out on one: counted again every half-minute, no more.
        if (!recount && counted.all() + counted.catches() == 0 && st == null && !Riding.anyOf(id)) return;
        List<AbstractHorse> herd = animals(level, id);
        if (recount) counted = count(level, v, herd);
        if (herd.isEmpty()) {
            if (st != null) mindGates(level, id, st);
            return;
        }
        settle(level, v, st, herd);
        foals(level, v, st);
        unpackAtHome(level, v, st, herd);
        if (st != null) mindGates(level, id, st);
        buySaddle(level, v, st, counted);
    }

    /** Each animal not out at work in its place: walked back in when it has strayed no further than the yard. */
    static void settle(ServerLevel level, Villages.Village v, @Nullable Stable st, List<AbstractHorse> herd) {
        List<AbstractHorse> ordered = byStall(herd);
        for (AbstractHorse h : ordered) {
            if (atWork(h)) continue;
            Vec3 place = placeOf(st, v.id(), ordered, h);
            // Kept to its place: in a stall it does not stroll at all (a two-wide animal let stroll a
            // block from its stall's middle ends up half out in the aisle); in a pen it wanders a little.
            h.restrictTo(BlockPos.containing(place), st != null ? 0 : 3);
            if (settled(st, place, h)) continue;
            if (flat(h.position(), place) > YARD) continue;            // a stray: the rancher's to fetch
            h.setEating(false);
            walkInto(h.getNavigation(), walkTarget(st, place), 1.0D);
            if (st != null && flat(h.position(), Vec3.atCenterOf(st.door())) < 8) openGates(level, v.id(), st);
        }
    }

    /** Out with a rider, on a lead, tied up while its rider is about its business, or in the rancher's hands. */
    static boolean atWork(AbstractHorse h) {
        return h.isVehicle() || h.isLeashed() || Riding.taken(h.getUUID()) || handled(h.getUUID());
    }

    /** A foal born in the stable is the village's to gentle when it is grown. */
    static void foals(ServerLevel level, Villages.Village v, @Nullable Stable st) {
        if (st == null) return;
        for (AbstractHorse h : level.getEntitiesOfClass(AbstractHorse.class, new AABB(st.anchor()).inflate(5, 3, 5),
                x -> x.isAlive() && kind(x) && x.isBaby() && !x.isTamed() && x.getOwnerUUID() == null && !x.getTags().contains(CATCH))) {
            if (!st.inside(h.position())) continue;
            takeIn(h, v.id());
            Villages.tell(v.id(), level.getDayTime() / 24000L, "a " + what(h).replace(" horse", "") + " foal was born in the stable");
        }
    }

    /** Make it the village's catch: kept (not despawned), not game, and gentled by the rancher in time. */
    static void takeIn(AbstractHorse h, UUID village) {
        h.addTag(CATCH);
        h.addTag(Drover.HERD);
        h.getPersistentData().putString(OF, village.toString());
        h.setPersistenceRequired();
    }

    /** A pack animal standing at home with goods still in its chest (a caravan cut short by a restart): unpacked into the stores. */
    static void unpackAtHome(ServerLevel level, Villages.Village v, @Nullable Stable st, List<AbstractHorse> herd) {
        for (AbstractHorse h : herd) {
            if (!(h instanceof AbstractChestedHorse c) || !c.hasChest() || atWork(h)) continue;
            BlockPos home = st != null ? st.aisle() : home(v.id());
            if (home == null || flat(h.position(), Vec3.atBottomCenterOf(home)) > 8) continue;
            int moved = 0;
            Container box = c.getInventory();
            for (int i = AbstractHorse.INV_BASE_COUNT; i < box.getContainerSize(); i++) {
                ItemStack s = box.getItem(i);
                if (s.isEmpty()) continue;
                ItemStack left = Market.intoStores(level, v.id(), s.copy());
                moved += s.getCount() - left.getCount();
                box.setItem(i, left);
            }
            if (moved > 0) LOG.info("[MCA-STABLE] {}: {} unpacked at home, {} goods into the stores", Villages.name(v.id()), name(h), moved);
        }
    }

    /** A scout home with a saddle or a lead it found in an old chest (Riding.oldChests): into the stores with it. */
    static void scoutsHome(ServerLevel level, Villages.Village v) {
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != StationTask.SCOUT || f.expedition() != null) continue;
            if (f.blockPosition().distSqr(v.centre()) > 24 * 24) continue;
            var inv = f.getInventoryItems();
            for (int i = 0; i < inv.size(); i++) {
                ItemStack s = inv.get(i);
                if (s.isEmpty() || !(s.is(Items.SADDLE) || s.is(Items.LEAD))) continue;
                int n = s.getCount();
                ItemStack left = Market.intoStores(level, v.id(), s.copy());
                inv.set(i, left);
                if (n > left.getCount() && s.is(Items.SADDLE)) {
                    Villages.tell(v.id(), level.getDayTime() / 24000L, f.displayNameCap() + " brought home a saddle from an old chest, for the stable");
                }
            }
        }
    }

    /** On market day, a saddle off the traders for a tamed horse that has none, if the treasury can spare it. */
    static void buySaddle(ServerLevel level, Villages.Village v, @Nullable Stable st, Herd herd) {
        if (st == null || herd.saddles() > 0 || herd.horses() <= herd.saddled()) return;
        long day = level.getDayTime() / 24000L;
        if (!Market.marketDay(v.id(), day) || BOUGHT.getOrDefault(v.id(), -1L) == day) return;
        BOUGHT.put(v.id(), day);
        int spare = Ledger.coins(v.id()) - Market.saved(v.id(), level.getGameTime());
        if (spare < SADDLE_PRICE + 20) return;                               // the wages come first
        int paid = Ledger.takeCoins(v.id(), SADDLE_PRICE);
        Economy.spent(v.id(), paid);
        ItemStack left = Market.intoStores(level, v.id(), new ItemStack(Items.SADDLE));
        if (!left.isEmpty()) net.minecraft.world.level.block.Block.popResource(level, v.centre().above(), left);
        Villages.tell(v.id(), day, "bought a saddle off the traders on market day for " + paid + " coin, for the stable");
        HERDS.remove(v.id());
    }

    // ------------------------------------------------------------------ the rancher's work with them

    enum Chore { FEED, STRAY, SADDLE, CHEST, GENTLE, CATCH, BREED }

    /** A piece of the rancher's work with the horses, under way. */
    static final class Work {
        final Chore chore;
        final UUID village;
        final List<UUID> animals = new ArrayList<>();
        final long started;
        int at;
        int step;
        long stepAt;
        int walked = -1000;
        @Nullable ItemStack lure;
        ItemStack heldBefore = ItemStack.EMPTY;
        int done;
        /** Where it is bringing the animal, worked out once. */
        @Nullable Vec3 goal;

        Work(Chore chore, UUID village, long started) {
            this.chore = chore;
            this.village = village;
            this.started = started;
        }
    }

    static final Map<UUID, Work> WORK = new ConcurrentHashMap<>();
    /** The day each village's horses were last fed. */
    private static final Map<UUID, Long> FED = new ConcurrentHashMap<>();
    /** Wild ones a catch failed on, and the day: not tried again that day. */
    private static final Map<UUID, Long> GAVE_UP = new ConcurrentHashMap<>();
    /** When each catch last had a go: a rest between. */
    private static final Map<UUID, Long> GENTLED = new ConcurrentHashMap<>();
    /** The rest between goes, in ticks (the tests' is short). */
    private static long rest = 600L;

    /** Busy with the horses: fetching one, putting one away, or the rancher at the stable. The day's own work waits. */
    public static boolean busy(VillageFolkEntity f) {
        return WORK.containsKey(f.getUUID()) || Riding.busy(f);
    }

    /** Is this animal in the rancher's hands just now? */
    static boolean handled(UUID animal) {
        for (Work w : WORK.values()) {
            if (w.chore == Chore.FEED) continue;
            if (w.animals.contains(animal)) return true;
        }
        return false;
    }

    /** A step of whatever it is doing with the horses (every quarter-second, from its tick). */
    public static void drive(VillageFolkEntity f, ServerLevel level) {
        if (Riding.busy(f)) {
            Riding.drive(f, level);
            return;
        }
        Work w = WORK.get(f.getUUID());
        if (w == null) return;
        long now = level.getGameTime();
        if (now - w.started > (w.chore == Chore.CATCH || w.chore == Chore.STRAY ? 3600L : 1800L) || now < w.started
                || Raids.underAlarm(w.village)) {
            if (w.chore == Chore.CATCH && !w.animals.isEmpty()) GAVE_UP.put(w.animals.get(0), level.getDayTime() / 24000L);
            stop(f, level, w, "ran out of time");
            return;
        }
        switch (w.chore) {
            case FEED -> feedRound(f, level, w);
            case SADDLE, CHEST, BREED -> fit(f, level, w);
            case GENTLE -> gentle(f, level, w);
            case CATCH, STRAY -> lead(f, level, w);
        }
    }

    private static void stop(VillageFolkEntity f, ServerLevel level, Work w, String why) {
        WORK.remove(f.getUUID());
        if (w.lure != null && f.getMainHandItem().is(w.lure.getItem())) f.setItemInHand(InteractionHand.MAIN_HAND, w.heldBefore);
        if (f.getVehicle() instanceof AbstractHorse) f.stopRiding();
        f.brain("the horses: " + why);
    }

    /**
     * The rancher's look at the horses (from its day's round, every half-minute): the evening feed,
     * a stray fetched in, a saddle or a chest put on, a catch gentled, a wild one fetched home, two
     * horses brought into foal, and leads plaited. Returns whether it set about one.
     */
    public static boolean ranch(VillageFolkEntity f, ServerLevel level) {
        if (f.stationTask() != StationTask.RANCH || f.isBaby() || busy(f) || Drover.busy(f)) return false;
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null || f.villageCentre() == null || Raids.underAlarm(id) || !level.isDay()) return false;
        long now = level.getGameTime(), time = level.getDayTime() % 24000L, day = level.getDayTime() / 24000L;
        plaitLeads(f, level, v);
        List<AbstractHorse> herd = animals(level, id);
        Stable st = stableNow(id, now);
        List<AbstractHorse> ordered = byStall(herd);
        // The evening feed.
        if (time >= 9000 && time < 11800 && FED.getOrDefault(id, -1L) != day && !herd.isEmpty()) {
            Work w = new Work(Chore.FEED, id, now);
            for (AbstractHorse h : ordered) if (!atWork(h) && inPlace(st, placeOf(st, id, ordered, h), h)) w.animals.add(h.getUUID());
            if (!w.animals.isEmpty()) {
                int got = f.countCarried(FODDER);
                if (got < w.animals.size()) got += f.drawFrom(f.villageCentre(), FODDER, w.animals.size() - got, Villages.storesRadius(id));
                FED.put(id, day);
                if (got > 0) return begin(f, w, "off to feed the horses");
            }
        }
        // One strayed beyond the yard: fetched in (ridden home if it has a saddle on).
        for (AbstractHorse h : ordered) {
            if (atWork(h) || !h.isAlive()) continue;
            Vec3 place = placeOf(st, id, ordered, h);
            double far = flat(h.position(), place);
            if (far <= YARD || !level.isLoaded(h.blockPosition())) continue;
            if (h.isTamed() && h.isSaddled() && h instanceof Horse && !Riding.lowOverhead(level, h) && Riding.fetchStray(f, level, h)) return true;
            if (lure(f, level, h) == null) continue;
            Work w = new Work(Chore.STRAY, id, now);
            w.animals.add(h.getUUID());
            return begin(f, w, "off to fetch " + name(h) + " home");
        }
        // A saddle on a tamed horse, a chest on a donkey or a mule (for the caravans).
        for (AbstractHorse h : ordered) {
            if (atWork(h) || !h.isTamed() || h.isBaby() || !inPlace(st, placeOf(st, id, ordered, h), h)) continue;
            if (h instanceof Horse && !h.isSaddled() && Market.stock(level, id, s -> s.is(Items.SADDLE)) > 0) {
                if (f.countCarried(s -> s.is(Items.SADDLE)) < 1) f.drawFrom(f.villageCentre(), s -> s.is(Items.SADDLE), 1, Villages.storesRadius(id));
                if (f.countCarried(s -> s.is(Items.SADDLE)) < 1) break;
                Work w = new Work(Chore.SADDLE, id, now);
                w.animals.add(h.getUUID());
                return begin(f, w, "putting a saddle on " + name(h));
            }
            if (h instanceof AbstractChestedHorse c && !c.hasChest() && caravans(id) && Market.stock(level, id, s -> s.is(Items.CHEST)) > 0) {
                if (f.countCarried(s -> s.is(Items.CHEST)) < 1) f.drawFrom(f.villageCentre(), s -> s.is(Items.CHEST), 1, Villages.storesRadius(id));
                if (f.countCarried(s -> s.is(Items.CHEST)) < 1) break;
                Work w = new Work(Chore.CHEST, id, now);
                w.animals.add(h.getUUID());
                return begin(f, w, "putting a chest on " + name(h) + " for the caravans");
            }
        }
        // A catch at home, gentled a little more.
        for (AbstractHorse h : ordered) {
            if (h.isTamed() || h.isBaby() || atWork(h) || !inPlace(st, placeOf(st, id, ordered, h), h)) continue;
            Long last = GENTLED.get(h.getUUID());
            if (last != null && now - last < rest && now >= last) continue;
            if (lure(f, level, h) == null) break;                       // nothing to sweeten it with
            Work w = new Work(Chore.GENTLE, id, now);
            w.animals.add(h.getUUID());
            return begin(f, w, "gentling " + name(h));
        }
        // Too few: a wild one fetched home.
        int want = st != null ? STALLS : 2;
        if (herd.size() < want && time < 9000) {
            AbstractHorse wild = wild(level, id, st != null ? st.anchor() : home(id), day, herd);
            if (wild != null && lure(f, level, wild) != null) {
                Work w = new Work(Chore.CATCH, id, now);
                w.animals.add(wild.getUUID());
                FolkTalk.speak(f, "There's a wild " + what(wild) + " out there. A bit of " + Crafts.named(lure(f, level, wild))
                    + " and it'll follow me home.");
                return begin(f, w, "off to coax a wild " + what(wild) + " home");
            }
        }
        // Two tamed horses with room in the stable: a golden carrot each, and a foal.
        if (st != null && herd.size() < STALLS && Market.stock(level, id, GOLDEN) >= 2) {
            List<AbstractHorse> pair = new ArrayList<>();
            for (AbstractHorse h : ordered) {
                if (h instanceof Horse && ours(h, id) && !h.isBaby() && h.getAge() == 0 && !h.isInLove() && !atWork(h)
                        && h.getHealth() >= h.getMaxHealth() - 4 && inPlace(st, placeOf(st, id, ordered, h), h)) pair.add(h);
            }
            if (pair.size() >= 2) {
                int got = f.countCarried(GOLDEN);
                if (got < 2) got += f.drawFrom(f.villageCentre(), GOLDEN, 2 - got, Villages.storesRadius(id));
                if (got >= 2) {
                    Work w = new Work(Chore.BREED, id, now);
                    w.animals.add(pair.get(0).getUUID());
                    w.animals.add(pair.get(1).getUUID());
                    return begin(f, w, "bringing " + name(pair.get(0)) + " and " + name(pair.get(1)) + " into foal");
                }
            }
        }
        return false;
    }

    private static boolean begin(VillageFolkEntity f, Work w, String brain) {
        WORK.put(f.getUUID(), w);
        f.clearQueue();                                                  // (as the drover's fetch does: this is the work now)
        f.getNavigation().stop();
        f.brain(brain);
        return true;
    }

    /** Does the village send caravans (a colony, or a trade pact)? Then its donkeys want chests. */
    static boolean caravans(UUID village) {
        for (Map.Entry<UUID, UUID> e : Ledger.links().entrySet()) {
            if (village.equals(e.getKey()) || village.equals(e.getValue())) return true;
        }
        for (Villages.Village o : Villages.every()) if (!o.id().equals(village) && Envoys.pact(village, o.id())) return true;
        return false;
    }

    /** Two leads plaited from four string and a slime ball, when the stores are short of leads and have the makings. */
    static void plaitLeads(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        if (Market.stock(level, v.id(), s -> s.is(Items.LEAD)) >= 2) return;
        if (Market.stock(level, v.id(), s -> s.is(Items.STRING)) < 4 || Market.stock(level, v.id(), s -> s.is(Items.SLIME_BALL)) < 1) return;
        if (!Crafts.take(level, v, s -> s.is(Items.STRING), 4)) return;
        if (!Crafts.take(level, v, s -> s.is(Items.SLIME_BALL), 1)) {
            Crafts.store(level, v, new ItemStack(Items.STRING, 4));
            return;
        }
        Crafts.store(level, v, new ItemStack(Items.LEAD, 2));
        f.swing(InteractionHand.MAIN_HAND);
        f.brain("plaited two leads from string and a slime ball");
        LOG.info("[MCA-STABLE] {} plaited two leads for {}", f.displayNameCap(), Villages.name(v.id()));
    }

    /** What it will hold out to this one: a bite it eats, out of its pack or the stores. Null if there is none. */
    @Nullable
    static ItemStack lure(VillageFolkEntity f, ServerLevel level, AbstractHorse h) {
        UUID id = f.ownerId();
        if (f.countCarried(FEED) < 1 && id != null && f.villageCentre() != null) {
            // The plain things first: a golden carrot is for when there is nothing else.
            f.drawFrom(f.villageCentre(), s -> s.is(Items.WHEAT) || s.is(Items.APPLE) || s.is(Items.SUGAR), 3, Villages.storesRadius(id));
            if (f.countCarried(FEED) < 1) f.drawFrom(f.villageCentre(), s -> s.is(Items.GOLDEN_CARROT), 1, Villages.storesRadius(id));
        }
        for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && FEED.test(s) && !s.is(Items.GOLDEN_CARROT)) return s.copyWithCount(1);
        for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && FEED.test(s)) return s.copyWithCount(1);
        return null;
    }

    /** A wild horse, donkey or mule near home: none with a name, a rider or an owner, nor one in a pen. A donkey first if the caravans want one. */
    @Nullable
    static AbstractHorse wild(ServerLevel level, UUID village, @Nullable BlockPos home, long today, List<AbstractHorse> herd) {
        if (home == null || !Land.areaLoaded(level, home, RANGE)) return null;
        List<AbstractHorse> about = level.getEntitiesOfClass(AbstractHorse.class, new AABB(home).inflate(RANGE, 16, RANGE),
            h -> h.isAlive() && kind(h) && !h.isBaby() && !h.isTamed() && h.getOwnerUUID() == null && !h.getTags().contains(CATCH)
                && !h.hasCustomName() && !h.isLeashed() && !h.isVehicle() && !h.isPassenger()
                && GAVE_UP.getOrDefault(h.getUUID(), -1L) != today && !Drover.penned(level, h));
        if (about.isEmpty()) return null;
        boolean donkeys = false;
        for (AbstractHorse h : herd) if (h instanceof AbstractChestedHorse) donkeys = true;
        boolean wantDonkey = !donkeys && caravans(village);
        AbstractHorse best = null;
        double bestScore = Double.MAX_VALUE;
        for (AbstractHorse h : about) {
            double score = h.distanceToSqr(Vec3.atBottomCenterOf(home));
            if (wantDonkey && h instanceof AbstractChestedHorse) score /= 16;
            if (!wantDonkey && h instanceof Horse) score /= 4;
            if (score < bestScore) { bestScore = score; best = h; }
        }
        return best;
    }

    /** One bite, as the game feeds a horse out of a player's hand: it heals, a foal grows, and a wild one's temper sweetens. */
    static void feed(ServerLevel level, AbstractHorse h, ItemStack food) {
        float heal = 0;
        int grow = 0, temper = 0;
        if (food.is(Items.WHEAT)) { heal = 2; grow = 20; temper = 3; }
        else if (food.is(Items.SUGAR)) { heal = 1; grow = 30; temper = 3; }
        else if (food.is(Items.HAY_BLOCK)) { heal = 20; grow = 180; }
        else if (food.is(Items.APPLE)) { heal = 3; grow = 60; temper = 3; }
        else if (food.is(Items.GOLDEN_CARROT)) { heal = 4; grow = 60; temper = 5; }
        else if (food.is(Items.GOLDEN_APPLE)) { heal = 10; grow = 240; temper = 10; }
        if (heal > 0 && h.getHealth() < h.getMaxHealth()) h.heal(heal);
        if (grow > 0 && h.isBaby()) h.ageUp(grow);
        if (temper > 0 && !h.isTamed() && h.getTemper() < h.getMaxTemper()) h.modifyTemper(temper);
        h.playSound(SoundEvents.HORSE_EAT, 1.0F, 1.0F);
        level.sendParticles(new net.minecraft.core.particles.ItemParticleOption(net.minecraft.core.particles.ParticleTypes.ITEM, food),
            h.getX(), h.getY() + h.getBbHeight() * 0.8, h.getZ(), 6, 0.3, 0.2, 0.3, 0.05);
    }

    /** Take one of what matches out of the pack: the stack itself (its components kept), or empty. */
    static ItemStack takeOne(VillageFolkEntity f, Predicate<ItemStack> what) {
        var inv = f.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !what.test(s)) continue;
            ItemStack one = s.split(1);
            if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
            return one;
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    static AbstractHorse animal(ServerLevel level, UUID id) {
        return level.getEntity(id) instanceof AbstractHorse h && h.isAlive() ? h : null;
    }

    /** Up to it (within arm's reach). True when there. */
    static boolean upTo(VillageFolkEntity f, ServerLevel level, Work w, Entity to, @Nullable Stable st) {
        if (f.distanceToSqr(to) <= 2.6 * 2.6) {
            f.getNavigation().stop();
            f.getLookControl().setLookAt(to);
            return true;
        }
        if (st != null && (f.distanceToSqr(Vec3.atCenterOf(st.door())) < 8 * 8 || to.distanceToSqr(Vec3.atCenterOf(st.door())) < 8 * 8)) {
            openGates(level, w.village, st);
        }
        if (f.getNavigation().isDone() || f.tickCount - w.walked > 40) {
            f.walkTo(to.blockPosition(), 0.9D);
            w.walked = f.tickCount;
        }
        return false;
    }

    /** The evening round: a bite each, wheat or an apple or hay out of its pack. */
    private static void feedRound(VillageFolkEntity f, ServerLevel level, Work w) {
        Stable st = stableNow(w.village, level.getGameTime());
        while (w.at < w.animals.size() && animal(level, w.animals.get(w.at)) == null) w.at++;
        if (w.at >= w.animals.size() || f.countCarried(FODDER) < 1) {
            if (w.done > 0) {
                Villages.tell(w.village, level.getDayTime() / 24000L, f.displayNameCap() + " fed the horses at "
                    + (st != null ? "the stable" : "the pen") + " (" + w.done + ")");
                if (level.getRandom().nextInt(2) == 0) FolkTalk.speak(f, "There. Fed and watered, every one of them.");
            }
            stop(f, level, w, "the horses fed");
            return;
        }
        AbstractHorse h = animal(level, w.animals.get(w.at));
        if (!upTo(f, level, w, h, st)) return;
        ItemStack bite = takeOne(f, FODDER);
        if (bite.isEmpty()) return;
        f.swing(InteractionHand.MAIN_HAND);
        feed(level, h, bite);
        w.done++;
        w.at++;
    }

    /** A saddle put on, a chest put on, or a golden carrot each for two horses. */
    private static void fit(VillageFolkEntity f, ServerLevel level, Work w) {
        Stable st = stableNow(w.village, level.getGameTime());
        while (w.at < w.animals.size() && animal(level, w.animals.get(w.at)) == null) w.at++;
        if (w.at >= w.animals.size()) {
            stop(f, level, w, "done");
            return;
        }
        AbstractHorse h = animal(level, w.animals.get(w.at));
        if (!upTo(f, level, w, h, st)) return;
        f.swing(InteractionHand.MAIN_HAND);
        long day = level.getDayTime() / 24000L;
        switch (w.chore) {
            case SADDLE -> {
                ItemStack saddle = takeOne(f, s -> s.is(Items.SADDLE));
                if (saddle.isEmpty() || h.isSaddled() || !h.isSaddleable()) {
                    if (!saddle.isEmpty()) f.insertItem(saddle);
                } else {
                    h.equipSaddle(saddle, SoundSource.NEUTRAL);
                    Villages.tell(w.village, day, f.displayNameCap() + " saddled " + name(h) + ": the couriers and scouts can ride it now");
                    FolkTalk.speak(f, "There you are, " + name(h) + ". Somebody'll ride you out tomorrow.");
                }
            }
            case CHEST -> {
                if (h instanceof AbstractChestedHorse c && !c.hasChest() && takeOne(f, s -> s.is(Items.CHEST)).getCount() == 1) {
                    c.getSlot(499).set(new ItemStack(Items.CHEST));
                    h.playSound(SoundEvents.DONKEY_CHEST, 1.0F, 1.0F);
                    Villages.tell(w.village, day, f.displayNameCap() + " put a chest on " + name(h) + ", to carry the caravans' loads");
                }
            }
            case BREED -> {
                ItemStack gold = takeOne(f, GOLDEN);
                if (!gold.isEmpty()) {
                    feed(level, h, gold);
                    if (h.isTamed() && h.getAge() == 0 && h.canFallInLove()) h.setInLove(null);
                }
                if (w.at == w.animals.size() - 1) {
                    FolkTalk.speak(f, "A golden carrot each. With luck there'll be a foal in the stable.");
                    LOG.info("[MCA-STABLE] {} brought two of {}'s horses into foal", f.displayNameCap(), Villages.name(w.village));
                }
            }
            default -> { }
        }
        w.at++;
        w.walked = -1000;
    }

    /**
     * A go at gentling a catch, as a player does it: a bite to eat out of its hand, then up on its
     * back. It bucks; if its temper is up it stands and is tamed, and if not the rancher is thrown
     * and it is a little calmer for next time.
     */
    private static void gentle(VillageFolkEntity f, ServerLevel level, Work w) {
        AbstractHorse h = animal(level, w.animals.get(0));
        long now = level.getGameTime();
        if (h == null || h.isTamed()) {
            stop(f, level, w, "nothing to gentle");
            return;
        }
        Stable st = stableNow(w.village, now);
        switch (w.step) {
            case 0 -> {
                if (!upTo(f, level, w, h, st)) return;
                ItemStack bite = takeOne(f, s -> FEED.test(s) && !s.is(Items.GOLDEN_CARROT));
                if (bite.isEmpty()) bite = takeOne(f, FEED);
                if (!bite.isEmpty()) {
                    f.swing(InteractionHand.MAIN_HAND);
                    feed(level, h, bite);
                }
                w.step = 1;
                w.stepAt = now;
            }
            case 1 -> {
                if (now - w.stepAt < 20) return;
                if (!upTo(f, level, w, h, st)) return;
                h.clearRestriction();
                h.setEating(false);
                if (f.startRiding(h)) {
                    w.step = 2;
                    w.stepAt = now;
                    h.makeMad();
                } else if (now - w.stepAt > 200) {
                    stop(f, level, w, "could not get up on it");
                }
            }
            default -> {
                if (f.getVehicle() == h && now - w.stepAt < 50) {
                    if ((now - w.stepAt) % 15 == 0) h.makeMad();          // it bucks
                    return;
                }
                int goes = h.getPersistentData().getInt(GOES) + 1;
                h.getPersistentData().putInt(GOES, goes);
                GENTLED.put(h.getUUID(), now);
                boolean tamed = h.getMaxTemper() > 0 && h.getRandom().nextInt(h.getMaxTemper()) < h.getTemper();
                if (f.getVehicle() == h) f.stopRiding();
                h.getNavigation().stop();                               // (where its rider's feet would have gone, not its own)
                if (tamed) {
                    tame(level, h, w.village, f);
                } else {
                    h.modifyTemper(5);
                    h.makeMad();
                    level.broadcastEntityEvent(h, (byte) 6);
                    f.brain("thrown by " + name(h) + " (its temper " + h.getTemper() + " of " + h.getMaxTemper() + ")");
                    if (level.getRandom().nextInt(3) == 0) {
                        FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "Whoa! Thrown again. It's coming round, though.",
                            "Not yet, then. A bit more wheat and another go tomorrow.", "Ow. Stubborn thing — but calmer than it was."));
                    }
                }
                stop(f, level, w, tamed ? "tamed it" : "thrown; another go later");
            }
        }
    }

    /** Tamed: the village's own, with a name, and in the chronicle. */
    static void tame(ServerLevel level, AbstractHorse h, UUID village, @Nullable VillageFolkEntity by) {
        h.setTamed(true);
        h.setOwnerUUID(village);
        h.removeTag(CATCH);
        h.addTag(STEED);
        h.addTag(Drover.HERD);
        h.setPersistenceRequired();
        String n = newName(level, village, h);
        h.getPersistentData().putString(NAME, n);
        if (by != null) h.getPersistentData().putString(TAMER, by.displayNameCap());
        level.broadcastEntityEvent(h, (byte) 7);                                    // the hearts
        long day = level.getDayTime() / 24000L;
        int goes = h.getPersistentData().getInt(GOES);
        Villages.tell(village, day, (by != null ? by.displayNameCap() : "The rancher") + " tamed a wild " + what(h) + " for "
            + Villages.name(village) + " after " + goes + (goes == 1 ? " go" : " goes") + ", and named it " + n);
        if (by != null) {
            FolkTalk.speak(by, "Easy, easy... there. You're " + n + " now, and you're ours.");
            by.persona().remember(day, "I tamed a wild " + what(h) + " and named it " + n, 5);
        }
        HERDS.remove(village);
        LOG.info("[MCA-STABLE] {} tamed for {}: {} after {} goes", what(h), Villages.name(village), n, goes);
    }

    /** Coaxing one home with a bite held out (a wild one, or one of the village's own that strayed): it follows the hand. */
    private static void lead(VillageFolkEntity f, ServerLevel level, Work w) {
        AbstractHorse h = animal(level, w.animals.get(0));
        long now = level.getGameTime();
        if (h == null || h.isVehicle() || h.isLeashed() || (w.chore == Chore.CATCH && (h.isTamed() || h.getTags().contains(CATCH)))) {
            stop(f, level, w, "it was gone");
            return;
        }
        if (!level.isDay()) {
            if (w.chore == Chore.CATCH) GAVE_UP.put(h.getUUID(), level.getDayTime() / 24000L);
            stop(f, level, w, "dusk: left it for today");
            return;
        }
        Stable st = stableNow(w.village, now);
        if (w.lure == null) {
            w.lure = lure(f, level, h);
            if (w.lure == null) {
                stop(f, level, w, "nothing to coax it with");
                return;
            }
            w.heldBefore = f.getMainHandItem().copy();
            f.setItemInHand(InteractionHand.MAIN_HAND, w.lure.copy());
        }
        if (w.goal == null) {
            if (w.chore == Chore.STRAY) {
                w.goal = placeOf(st, w.village, byStall(animals(level, w.village)), h);
            } else {
                BlockPos home = st != null ? st.aisle() : home(w.village);
                if (home == null) { stop(f, level, w, "no home to bring it to"); return; }
                w.goal = Vec3.atBottomCenterOf(home);
            }
        }
        Vec3 goal = w.goal;
        double gap = f.distanceToSqr(h);
        if (flat(h.position(), goal) <= (st != null ? 3.0 : 4.0)) {
            // Home: it has what it followed.
            ItemStack bite = takeOne(f, s -> s.is(w.lure.getItem()));
            if (!bite.isEmpty()) feed(level, h, bite);
            f.swing(InteractionHand.MAIN_HAND);
            h.getNavigation().stop();
            if (w.chore == Chore.CATCH) {
                takeIn(h, w.village);
                Villages.tell(w.village, level.getDayTime() / 24000L, f.displayNameCap() + " coaxed a wild " + what(h) + " home to "
                    + (st != null ? "the stable" : "the pen") + " with " + Crafts.named(w.lure) + ", to be gentled");
                FolkTalk.speak(f, "Home, and nobody hurt. Now for the hard part — getting it to stand for a rider.");
                HERDS.remove(w.village);
            }
            stop(f, level, w, "brought it home");
            return;
        }
        if (st != null && (flat(f.position(), Vec3.atCenterOf(st.door())) < 10 || flat(h.position(), Vec3.atCenterOf(st.door())) < 10)) {
            openGates(level, w.village, st);
        }
        if (gap > 7.0 * 7.0) {
            // Too far to smell it: up to it again.
            if (f.getNavigation().isDone() || f.tickCount - w.walked > 40) {
                f.walkTo(h.blockPosition(), 0.8D);
                w.walked = f.tickCount;
            }
            return;
        }
        h.clearRestriction();
        h.setEating(false);
        h.getNavigation().moveTo(f, 1.1D);
        h.getLookControl().setLookAt(f, 30.0F, 30.0F);
        f.getLookControl().setLookAt(h);
        if (gap > 4.0 * 4.0) {
            f.getNavigation().stop();                                    // let it catch up
            return;
        }
        if (f.getNavigation().isDone() || f.tickCount - w.walked > 40) {
            // On past the spot (to the back of the aisle, for a catch), so what follows the hand ends up on it.
            BlockPos walk = w.chore == Chore.CATCH && st != null ? st.block(0, 2) : BlockPos.containing(goal);
            f.walkTo(walk, 0.5D);
            w.walked = f.tickCount;
        }
    }

    // ------------------------------------------------------------------ shown

    /** What it is doing with the horses this minute (the talk screen's top line), or null. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        String ride = Riding.doing(f);
        if (ride != null) return ride;
        Work w = WORK.get(f.getUUID());
        if (w == null || !(f.level() instanceof ServerLevel level)) return null;
        AbstractHorse h = w.animals.isEmpty() ? null : animal(level, w.animals.get(Math.min(w.at, w.animals.size() - 1)));
        String n = h == null ? "the horse" : name(h);
        return switch (w.chore) {
            case FEED -> "Feeding the horses at the stable (" + w.done + " of " + w.animals.size() + ")";
            case STRAY -> "Coaxing " + n + " home to the stable";
            case SADDLE -> "Putting a saddle on " + n;
            case CHEST -> "Putting a chest on " + n + ", for the caravans";
            case BREED -> "A golden carrot each for two horses, for a foal";
            case CATCH -> "Coaxing a wild " + (h == null ? "horse" : what(h)) + " home with " + (w.lure == null ? "a bite to eat" : Crafts.named(w.lure));
            case GENTLE -> h == null ? "Gentling a horse" : "Gentling a wild " + what(h) + ": its temper " + h.getTemper() + " of " + h.getMaxTemper()
                + " after " + h.getPersistentData().getInt(GOES) + " goes";
        };
    }

    /** A line for the folk's card, about its horses (the rancher's gentling and the stable; a rider's rides), or null. */
    @Nullable
    public static String card(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || !(f.level() instanceof ServerLevel level)) return null;
        String rides = Riding.card(f);
        if (f.stationTask() != StationTask.RANCH) return rides;
        List<String> parts = new ArrayList<>();
        List<String> gentling = new ArrayList<>();
        int tamed = 0;
        for (AbstractHorse h : animals(level, id)) {
            if (h.isTamed()) tamed++;
            else if (!h.isBaby()) gentling.add("a " + what(h) + " (temper " + h.getTemper() + " of " + h.getMaxTemper() + ", "
                + h.getPersistentData().getInt(GOES) + " goes)");
        }
        if (!gentling.isEmpty()) parts.add("gentling " + String.join(", ", gentling));
        if (tamed > 0) parts.add(tamed + " tamed in " + (stable(id) != null ? "the stable" : "the pen"));
        Work w = WORK.get(f.getUUID());
        if (w != null && w.chore == Chore.CATCH) parts.add("bringing a wild one home");
        if (parts.isEmpty()) return rides;
        return String.join("; ", parts) + (rides == null ? "" : "; " + rides);
    }

    /** The stable and its horses, for the town's books (Annals.snapshot: the Jobs and Buildings pages). */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        CompoundTag t = new CompoundTag();
        List<AbstractHorse> herd = animals(level, v.id());
        Herd h = count(level, v, herd);
        Stable st = stable(v.id());
        t.putInt("horses", h.horses());
        t.putInt("donkeys", h.donkeys());
        t.putInt("mules", h.mules());
        t.putInt("saddled", h.saddled());
        t.putInt("chested", h.chested());
        t.putInt("foals", h.foals());
        t.putInt("catches", h.catches());
        t.putInt("saddles", h.saddles());
        t.putInt("leads", h.leads());
        t.putBoolean("stable", st != null);
        t.putInt("stalls", st != null ? STALLS : 0);
        t.putInt("rides", Riding.ridesToday(level, v.id()));
        ListTag names = new ListTag();
        List<AbstractHorse> ordered = byStall(herd);
        for (AbstractHorse a : ordered) {
            String state;
            if (!a.isTamed()) state = "being gentled, temper " + a.getTemper() + " of " + a.getMaxTemper();
            else if (Riding.taken(a.getUUID())) state = Riding.horseDoing(level, a);
            else if (inPlace(st, placeOf(st, v.id(), ordered, a), a)) state = st != null ? "in its stall" : "in the pen";
            else state = "out " + (int) flat(a.position(), Vec3.atBottomCenterOf(v.centre())) + " blocks " + Guide.direction(v.centre(), a.blockPosition());
            String kit = a.isSaddled() ? ", saddled" : a instanceof AbstractChestedHorse c && c.hasChest() ? ", with a chest" : "";
            names.add(StringTag.valueOf((a.isTamed() ? name(a) + " (" + what(a) + ")" : "a wild " + what(a)) + kit + ": " + state));
            if (names.size() >= 8) break;
        }
        t.put("names", names);
        return t;
    }

    /** The herd in a line ("3 horses (2 saddled), a donkey with a chest, 1 saddle in the stores; the stable, 4 stalls"), for the books and the commands. */
    public static String line(CompoundTag t) {
        List<String> parts = new ArrayList<>();
        int horses = t.getInt("horses"), donkeys = t.getInt("donkeys"), mules = t.getInt("mules");
        parts.add(horses + (horses == 1 ? " horse" : " horses") + (t.getInt("saddled") > 0 ? " (" + t.getInt("saddled") + " saddled)" : ""));
        if (donkeys + mules > 0) {
            parts.add(donkeys + (donkeys == 1 ? " donkey" : " donkeys") + (mules > 0 ? ", " + mules + (mules == 1 ? " mule" : " mules") : "")
                + (t.getInt("chested") > 0 ? " (" + t.getInt("chested") + " with a chest)" : ""));
        }
        if (t.getInt("catches") > 0) parts.add(t.getInt("catches") + " being gentled");
        parts.add(t.getInt("saddles") + (t.getInt("saddles") == 1 ? " saddle" : " saddles") + " in the stores");
        parts.add(t.getBoolean("stable") ? "the stable, " + t.getInt("stalls") + " stalls" : "no stable yet");
        if (t.getInt("rides") > 0) parts.add(t.getInt("rides") + (t.getInt("rides") == 1 ? " ride" : " rides") + " today");
        return String.join(" · ", parts);
    }

    /** The stable in chat (/village horses): the herd, each animal and where it is, and the rides under way. */
    public static String page(ServerLevel level, Villages.Village v) {
        CompoundTag t = report(level, v);
        StringBuilder sb = new StringBuilder(line(t));
        ListTag names = t.getList("names", net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < names.size(); i++) sb.append("\n  ").append(names.getString(i));
        for (Riding.Ride r : Riding.RIDES.values()) {
            if (!r.village.equals(v.id())) continue;
            Entity f = level.getEntity(r.folk);
            if (f instanceof VillageFolkEntity x) sb.append("\n  ").append(x.displayNameCap()).append(": ").append(Riding.doing(x));
        }
        for (Map.Entry<UUID, Work> e : WORK.entrySet()) {
            if (!e.getValue().village.equals(v.id())) continue;
            Entity f = level.getEntity(e.getKey());
            if (f instanceof VillageFolkEntity x) sb.append("\n  ").append(x.displayNameCap()).append(": ").append(doing(x));
        }
        return sb.toString();
    }

    /**
     * The stable stood up here to be looked at (/village horses showcase): on level ground, its door
     * to the south, a saddled horse and a donkey with a chest in their stalls and a courier on a
     * saddled horse at the door. Everything put down is tagged as the line-up is, to be cleared the
     * same way. Returns where to stand to look ("VIEW name x y z tx ty tz").
     */
    public static List<String> showcase(ServerLevel level, BlockPos at) {
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-8, 0, -8), at.offset(8, 12, 16))) {
            if (!level.getBlockState(p).isAir()) level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-8, -1, -8), at.offset(8, -1, 16))) {
            level.setBlock(p, net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(), 2 | 16);
        }
        com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, "stable", at, Direction.NORTH, 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Stable st = new Stable(at.immutable(), Direction.NORTH);
        record Put(net.minecraft.world.entity.EntityType<? extends AbstractHorse> type, Vec3 where, float yaw, String name) {}
        List<Put> puts = List.of(
            new Put(net.minecraft.world.entity.EntityType.HORSE, st.stall(0), 90.0F, "Bay"),
            new Put(net.minecraft.world.entity.EntityType.DONKEY, st.stall(1), 90.0F, "Ned"),
            new Put(net.minecraft.world.entity.EntityType.HORSE, st.stall(2), -90.0F, "Dapple"),
            new Put(net.minecraft.world.entity.EntityType.HORSE, Vec3.atBottomCenterOf(st.block(0, -9)), 0.0F, "Chestnut"));
        for (Put put : puts) {
            AbstractHorse h = put.type().create(level);
            if (h == null) continue;
            h.moveTo(put.where().x, put.where().y, put.where().z, put.yaw(), 0.0F);
            h.setYHeadRot(put.yaw());
            h.setYBodyRot(put.yaw());
            if (h instanceof Horse horse) {
                horse.setVariant(switch (put.name()) {
                    case "Bay" -> net.minecraft.world.entity.animal.horse.Variant.BROWN;
                    case "Dapple" -> net.minecraft.world.entity.animal.horse.Variant.GRAY;
                    default -> net.minecraft.world.entity.animal.horse.Variant.CHESTNUT;
                });
                h.equipSaddle(new ItemStack(Items.SADDLE), null);
            }
            if (h instanceof AbstractChestedHorse c) c.getSlot(499).set(new ItemStack(Items.CHEST));
            h.setTamed(true);
            h.getPersistentData().putString(NAME, put.name());
            h.setNoAi(true);
            h.setPersistenceRequired();
            h.addTag("folk_lineup");
            level.addFreshEntity(h);
            if (put.name().equals("Chestnut")) {
                VillageFolkEntity rider = com.jrpetty.mcassistant.McAssistantMod.VILLAGE_FOLK.get().create(level);
                if (rider != null) {
                    rider.moveTo(h.getX(), h.getY(), h.getZ(), 0.0F, 0.0F);
                    rider.makeShowcase(StationTask.HAUL);
                    rider.rename("Holt");
                    rider.addTag("folk_lineup");
                    level.addFreshEntity(rider);
                    rider.startRiding(h, true);
                }
            }
        }
        List<String> views = new ArrayList<>();
        views.add("VIEW h1-stable " + (at.getX() + 11) + " " + (at.getY() + 4) + " " + (at.getZ() + 17) + " " + at.getX() + " "
            + (at.getY() + 2) + " " + at.getZ());
        views.add("VIEW h2-stalls " + at.getX() + " " + (at.getY() + 1) + " " + (at.getZ() + 3) + " " + at.getX() + " "
            + at.getY() + " " + (at.getZ() - 3));
        views.add("VIEW h3-rider " + (at.getX() + 4) + " " + (at.getY() + 1) + " " + (at.getZ() + 15) + " " + at.getX() + " "
            + (at.getY() + 1) + " " + (at.getZ() + 9));
        return views;
    }

    /** Tests: a short rest between a catch's goes (true), or the ordinary half-minute. */
    public static void quickForTests(boolean quick) {
        rest = quick ? 40L : 600L;
    }

    /** Tests: the rancher's look at the horses, now. */
    public static boolean ranchForTests(VillageFolkEntity f, ServerLevel level) {
        return ranch(f, level);
    }

    /** Tests: what the rancher is doing with them, as the card shows it. */
    @Nullable
    public static String choreForTests(VillageFolkEntity f) {
        Work w = WORK.get(f.getUUID());
        return w == null ? null : w.chore.name();
    }

    /** Tests: the stall each of the village's animals has. */
    public static Vec3 stallForTests(Stable st, int i) {
        return st.stall(i);
    }
}
