package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.item.RunnersSatchelItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.entity.monster.WitherSkeleton;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [nether] The work on the far side of the gateway, a runner's step at a time (NetherRuns.drive): what the leader picks
 * to do next by the plan (lead), what the others do with it (help), and the risks seen to first (safety, fight,
 * watchTheSky).
 *
 * <p><b>The work</b>, each for real, as a player does it:
 * <ul>
 * <li>quartz, glowstone, nether gold and ancient debris dug out of the rock round the outpost with the pick (glowstone
 *     on a ceiling reached by a pillar of cobblestone, taken down again after); the drops into the pack, and the
 *     satchel when the pack fills;</li>
 * <li>nether wart picked ripe in the fortress (one put back in each bed), and soul sand dug from beside it;</li>
 * <li>blazes shot from range at their spawner, with a potion of fire resistance drunk first; the rods picked up;</li>
 * <li>a barter: a gold ingot thrown to a piglin by a runner in gold, the piglin admiring it, and what it throws back
 *     (the game's own bartering table) picked up; a player along can watch it;</li>
 * <li>a way cut toward the fortress (NetherOutpost.cut) when there is no walking there: walled, bridged and lit, a
 *     little further every run.</li>
 * </ul>
 * A fortress the runs want and do not know of yet is found the way the game places them (its structures' own lie,
 * within sixteen chunks of the outpost) and noted in the report; the way there is still made, and walked, for real.
 *
 * <p><b>The risks</b>: a runner on fire drinks its fire resistance and gets out of the lava; hurt, it eats, and badly
 * hurt it falls back to the outpost; a ghast's fireball close enough is turned back (a shield raised, or the blade's
 * flat), and the ghast shot down; hoglins are kept clear of unless the food runs short; a piglin is never struck first,
 * and a runner without gold on is kept from them, since they turn on anybody without it (piglinManners).
 *
 * <p>Nothing here looks at the world every tick: a runner's step is every five ticks, its look round (scan) every two
 * seconds for the leader only, its fireballs every other tick within twelve blocks.
 */
public final class NetherWork {

    private NetherWork() {}

    /** What a runner can be at. */
    public enum Job {
        QUARTZ("Digging quartz"), GLOWSTONE("Knocking down glowstone"), GOLD("Digging nether gold"), DEBRIS("Digging out ancient debris"),
        WART("Picking nether wart"), SOUL("Digging soul sand"), BLAZE("Shooting blazes"), BARTER("Bartering with the piglins"),
        WAY("Cutting a way toward the fortress"), LOOK("Looking about for work"), HUNT("Hunting a hoglin for meat");

        public final String words;

        Job(String words) { this.words = words; }

        boolean block() {
            return this == QUARTZ || this == GLOWSTONE || this == GOLD || this == DEBRIS || this == SOUL || this == WART;
        }
    }

    /** The work in hand: what, where, the mob if it is one, since when, how much done. */
    public static final class Task {
        final Job job;
        @Nullable BlockPos at;
        @Nullable UUID mob;
        final long since;
        int done;
        boolean counted;
        long waitFrom = -1;

        Task(Job job, @Nullable BlockPos at, @Nullable UUID mob, long since) {
            this.job = job;
            this.at = at;
            this.mob = mob;
            this.since = since;
        }

        public Job job() { return job; }
        @Nullable public BlockPos at() { return at; }
        public String words() { return job.words; }
    }

    /** The leader's look round: how far each way, how often; how far from the outpost the work goes (but the fortress). */
    static final int SCAN = 14, SCAN_DOWN = 6, SCAN_UP = 12, FROM_OUTPOST = 64;
    /** Homeward, how close a foe has to come to be fought (the rest are walked on from). */
    static final double HOMEWARD_FIGHT = 8.0;
    /** A block the leader has not got at in this long (a minute) is left for another time. */
    static final long GIVE_UP = 1200L;
    static final long SCAN_EVERY = 40;
    /** How high a ceiling the runners pillar up to (glowstone); how many looks about before there is nothing left. */
    static final int PILLAR_MOST = 9, LOOKS = 8;
    /** Gold thrown to one piglin at most, in a run's barter with it. */
    static final int BARTER_EACH = 6;

    /** The leader's last look round, by town. */
    static final class Scan {
        final NetherRuns.Run run;
        long at = -100000;
        @Nullable BlockPos from;
        final EnumMap<Job, List<BlockPos>> found = new EnumMap<>(Job.class);
        final Map<Long, UUID> claims = new HashMap<>();
        int looked;
        @Nullable BlockPos look;
        @Nullable BlockPos spawner;
        boolean located;

        Scan(NetherRuns.Run run) { this.run = run; }
    }

    private static final Map<UUID, Scan> SCANS = new ConcurrentHashMap<>();
    /** Where a fallen runner's things lie, for the others to take up. */
    private static final Map<UUID, BlockPos> PICKUP = new ConcurrentHashMap<>();
    /** Tests: digging done quickly; the fortress where the test built it (found without looking). */
    private static boolean quick;
    @Nullable private static BlockPos fortressForTests;
    /** Tests: the leader's look round kept inside the test's pocket (the server's own Nether past its walls varies). */
    @Nullable private static net.minecraft.world.level.levelgen.structure.BoundingBox boundsForTests;

    public static void resetForTests() {
        SCANS.clear();
        PICKUP.clear();
        quick = false;
        fortressForTests = null;
        boundsForTests = null;
    }

    public static void boundsForTests(@Nullable net.minecraft.world.level.levelgen.structure.BoundingBox box) {
        boundsForTests = box;
    }

    public static void quickForTests(boolean on) {
        quick = on;
    }

    public static void fortressForTests(@Nullable BlockPos at) {
        fortressForTests = at;
    }

    /** Tests: a piglin's manners kept for this folk now (it turns on one without gold on that it can see). */
    public static void mannersForTests(ServerLevel level, VillageFolkEntity f) {
        piglinManners(level, f, new NetherRuns.Run(f.ownerId() != null ? f.ownerId() : f.getUUID(), 0));
    }

    static Scan scanOf(NetherRuns.Run r) {
        Scan s = SCANS.get(r.village);
        if (s == null || s.run != r) {
            s = new Scan(r);
            SCANS.put(r.village, s);
        }
        return s;
    }

    /** Where the work is reckoned from: the outpost's portal, else where they came through. */
    @Nullable
    static BlockPos home(NetherRuns.Run r) {
        NetherOutpost.Room room = NetherOutpost.room(r.village);
        return room != null ? room.portal() : r.netherPortal;
    }

    // ------------------------------------------------------------------ the haul

    /** The Nether's own rock and rubbish: not worth carrying home. */
    static boolean junk(ItemStack s) {
        return s.isEmpty() || s.is(Items.NETHERRACK) || s.is(Items.BASALT) || s.is(Items.SMOOTH_BASALT) || s.is(Items.BLACKSTONE)
            || s.is(Items.SOUL_SOIL) || s.is(Items.GRAVEL) || s.is(Items.ROTTEN_FLESH) || s.is(Items.MAGMA_BLOCK) || s.is(Items.NETHER_SPROUTS)
            || s.is(Items.CRIMSON_ROOTS) || s.is(Items.WARPED_ROOTS) || s.is(Items.NETHER_BRICKS) || s.is(Items.NETHER_BRICK_FENCE);
    }

    /** Into its pack, else its satchel, else on the ground; what went in is noted as the run's haul. Returns how many it kept. */
    static int take(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, @Nullable NetherRuns.Leg leg, ItemStack drop, BlockPos at) {
        if (drop.isEmpty()) return 0;
        ItemStack lot = drop.copy();
        ItemStack left = f.insertItem(drop.copy());
        if (!left.isEmpty()) left = intoSatchel(f, left);
        int in = lot.getCount() - left.getCount();
        if (in > 0) {
            Economy.gathered(f, lot, in);
            if (leg != null) leg.got.merge(lot.getItem(), in, Integer::sum);
            r.haul.merge(lot.getHoverName().getString().toLowerCase(Locale.ROOT), in, Integer::sum);
        }
        if (!left.isEmpty()) Block.popResource(level, at, left);
        if (f.isPackFull()) packSatchel(f, leg);
        return in;
    }

    /** As much of this as goes into a satchel it carries; what is left. */
    static ItemStack intoSatchel(VillageFolkEntity f, ItemStack what) {
        ItemStack left = what;
        for (ItemStack s : f.getInventoryItems()) {
            if (left.isEmpty()) break;
            if (s.getItem() instanceof RunnersSatchelItem) left = RunnersSatchelItem.pack(s, left);
        }
        return left;
    }

    /** The run's haul out of the pack into the satchel, to make room: what came out of the Nether (what the leg has
     *  noted), not what it carried through (its kit, and whatever else it happens to have on it). */
    static void packSatchel(VillageFolkEntity f, @Nullable NetherRuns.Leg leg) {
        var pack = f.getInventoryItems();
        ItemStack satchel = ItemStack.EMPTY;
        for (ItemStack s : pack) if (s.getItem() instanceof RunnersSatchelItem) { satchel = s; break; }
        if (satchel.isEmpty()) return;
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (s.isEmpty() || s == satchel || !haul(s)) continue;
            if (leg != null && leg.got.getOrDefault(s.getItem(), 0) <= 0) continue;
            ItemStack left = RunnersSatchelItem.pack(satchel, s);
            pack.set(i, left);
        }
    }

    /** Is this the run's haul (not its kit: arrows, cobblestone, food, potions, gold to barter, its tools)? */
    static boolean haul(ItemStack s) {
        return !s.isEmpty() && s.getMaxStackSize() > 1 && !s.is(Items.ARROW) && !s.is(Items.COBBLESTONE) && !s.is(Items.TORCH)
            && !s.is(Items.GOLD_INGOT) && !s.is(Items.STICK) && s.get(DataComponents.FOOD) == null && !NetherPlan.fireResistance(s)
            && !WatchKit.issued(s) && !s.is(Items.SOUL_LANTERN) && !s.is(Items.LANTERN);
    }

    /** Has it a satchel with room in it? */
    static boolean roomInSatchel(VillageFolkEntity f) {
        for (ItemStack s : f.getInventoryItems()) {
            if (!(s.getItem() instanceof RunnersSatchelItem)) continue;
            List<ItemStack> in = RunnersSatchelItem.contents(s);
            if (in.size() < RunnersSatchelItem.SLOTS) return true;
            for (ItemStack x : in) if (x.getCount() < x.getMaxStackSize()) return true;
        }
        return false;
    }

    /** Its satchels emptied for the storehouse (NetherRuns.putIn): what was in them. */
    static List<ItemStack> unpackSatchels(VillageFolkEntity f) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : f.getInventoryItems()) if (s.getItem() instanceof RunnersSatchelItem) out.addAll(RunnersSatchelItem.unpack(s));
        return out;
    }

    // ------------------------------------------------------------------ digging

    /**
     * One step at a block: to within reach of it, then the pick at it (as long as the pick takes); true when it is out
     * (or gone). The block's drops are not taken here: see mine.
     */
    static boolean dig(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg, BlockPos b) {
        BlockState st = level.getBlockState(b);
        if (st.isAir() || st.canBeReplaced() && level.getFluidState(b).isEmpty()) {
            leg.digging = null;
            return true;
        }
        if (leg.digging == null || !leg.digging.equals(b)) {
            leg.digging = b.immutable();
            leg.dug = 0;
            leg.digNeeded = 0;
            leg.digTick = level.getGameTime();
        }
        double reach = f.getEyePosition().distanceToSqr(Vec3.atCenterOf(b));
        if (reach > 4.6 * 4.6) {
            if (f.getNavigation().isDone() || level.getGameTime() - leg.stillTick > 40) {
                BlockPos to = CaveDwellers.standBy(level, f, b);
                BlockPos go = to != null ? to : b;
                f.getNavigation().moveTo(go.getX() + 0.5, go.getY(), go.getZ() + 0.5, 1.0D);
                leg.stillTick = level.getGameTime();
            }
            return false;
        }
        f.getNavigation().stop();
        if (leg.digNeeded <= 0) {
            CaveDwellers.equipPick(f);
            leg.digNeeded = quick ? 10 : Math.max(10, f.workTicksFor(st));
        }
        f.getLookControl().setLookAt(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
        leg.dug += (int) NetherRuns.STEP;
        if (leg.dug % 10 == 0) {
            f.swing(InteractionHand.MAIN_HAND);
            f.workHit(b);
        }
        if (leg.dug < leg.digNeeded) return false;
        leg.digging = null;
        leg.digNeeded = 0;
        // The cut-through rock: gone, nothing kept (netherrack is no use at home).
        if (!valuable(st)) {
            level.destroyBlock(b, false, f);
            f.damageHeldTool();
            return true;
        }
        mine(level, f, r, leg, b, st, true);
        return true;
    }

    /** Is this block worth its drops (an ore, glowstone, soul sand, wart)? */
    static boolean valuable(BlockState st) {
        return st.is(Blocks.NETHER_QUARTZ_ORE) || st.is(Blocks.GLOWSTONE) || st.is(Blocks.NETHER_GOLD_ORE) || st.is(Blocks.ANCIENT_DEBRIS)
            || st.is(Blocks.SOUL_SAND) || st.is(Blocks.NETHER_WART) || st.is(Blocks.GILDED_BLACKSTONE) || st.is(Blocks.SHROOMLIGHT);
    }

    /**
     * A block out with what it drops for the pick in hand (the game's own drops), into the pack: the run's haul, noted,
     * and (counted) the run's tally and the report.
     */
    static void mine(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, @Nullable NetherRuns.Leg leg, BlockPos b, BlockState st, boolean count) {
        if (!CaveDwellers.isPickaxe(f.getMainHandItem()) && !st.is(Blocks.SOUL_SAND) && !st.is(Blocks.NETHER_WART)) CaveDwellers.equipPick(f);
        ItemStack tool = f.getMainHandItem();
        List<ItemStack> drops = Block.getDrops(st, level, b, level.getBlockEntity(b), f, tool);
        level.destroyBlock(b, false, f);
        int kept = 0;
        for (ItemStack drop : drops) {
            if (drop.isEmpty() || junk(drop) && !count) continue;
            if (junk(drop)) continue;
            kept += take(level, f, r, leg, drop, b);
        }
        if (CaveDwellers.isPickaxe(tool)) f.damageHeldTool();
        f.note(AssistantEntity.Deed.BLOCKS_MINED, 1);
        if (!count && kept == 0) return;
        r.mined++;
        f.awardXp(2);
        NetherRuns.Kind kind = st.is(Blocks.NETHER_QUARTZ_ORE) ? NetherRuns.Kind.QUARTZ : st.is(Blocks.GLOWSTONE) ? NetherRuns.Kind.GLOWSTONE
            : st.is(Blocks.NETHER_GOLD_ORE) ? NetherRuns.Kind.GOLD : st.is(Blocks.ANCIENT_DEBRIS) ? NetherRuns.Kind.DEBRIS
            : st.is(Blocks.SOUL_SAND) ? NetherRuns.Kind.SOUL : st.is(Blocks.NETHER_WART) ? NetherRuns.Kind.WART : null;
        if (kind != null) note(r, new NetherRuns.Find(kind, kind.words, b.immutable(), level.getDayTime() / 24000L, f.displayNameCap(), 1, kept));
        if (st.is(Blocks.ANCIENT_DEBRIS)) {
            FolkTalk.speak(f, "Ancient debris! The smith won't believe it.");
            r.event(f.displayNameCap() + " dug out ancient debris");
        }
    }

    /** A find into the run's report (folded into one of its kind nearby). */
    static void note(NetherRuns.Run r, NetherRuns.Find x) {
        int near = x.kind() == NetherRuns.Kind.FORTRESS || x.kind() == NetherRuns.Kind.BASTION ? 64 : x.kind() == NetherRuns.Kind.SPAWNER ? 2 : 8;
        for (int i = 0; i < r.found.size(); i++) {
            NetherRuns.Find y = r.found.get(i);
            if (y.kind() != x.kind() || y.at().distSqr(x.at()) > near * near) continue;
            r.found.set(i, y.with(y.a() + x.a(), y.b() + x.b()));
            return;
        }
        r.found.add(x);
    }

    // ------------------------------------------------------------------ getting about

    /**
     * Toward a spot: walked when there is a way (the game's own path-finding), and where there is none (it has not got
     * nearer in three seconds), cut a block at a time (NetherOutpost.cut: walled, bridged, lit). True while it is still
     * on its way; false once it is there (within two blocks), or when no way can be made.
     */
    static boolean makeFor(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg, BlockPos to, double speed) {
        return makeFor(level, f, r, leg, to, speed, true);
    }

    /** As makeFor; without {@code mayCut} it only walks (a look about is never worth tunnelling for), and gives up where
     *  it gets no nearer. */
    static boolean makeFor(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg, BlockPos to, double speed, boolean mayCut) {
        long now = level.getGameTime();
        double d = f.position().distanceToSqr(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        if (d <= 2.2 * 2.2) {
            leg.goal = null;
            leg.cutting = false;
            leg.stuck = 0;
            return false;
        }
        if (leg.goal == null || !leg.goal.equals(to)) {
            leg.goal = to.immutable();
            leg.cutting = false;
            leg.stuck = 0;
            leg.stillAt = f.blockPosition();
            leg.stillTick = now;
            f.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, speed);
            return true;
        }
        if (leg.cutting) {
            int step = NetherOutpost.cut(level, f, r, leg, to);
            if (step < 0) {
                // No way to be made from here (bedrock, a portal, out of cobblestone over a drop): try walking again.
                leg.cutting = false;
                leg.stuck++;
                if (leg.stuck > 6) {
                    leg.goal = null;
                    return false;
                }
            }
            f.hobbyNow = "cutting a way through the Nether";
            return true;
        }
        if (now - leg.stillTick >= 60) {
            BlockPos here = f.blockPosition();
            boolean nearer = leg.stillAt == null || here.distSqr(to) < leg.stillAt.distSqr(to) - 2;
            leg.stillAt = here;
            leg.stillTick = now;
            if (!nearer) {
                if (!mayCut) {
                    leg.goal = null;
                    return false;
                }
                leg.cutting = true;
                leg.cutSince = now;
                return true;
            }
        }
        if (f.getNavigation().isDone()) {
            if (!f.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, speed)) {
                if (!mayCut) {
                    leg.goal = null;
                    return false;
                }
                leg.cutting = true;
                leg.cutSince = now;
            }
        }
        return true;
    }

    /** Up a pillar of cobblestone under its own feet, a block a step, to reach a ceiling. False if it cannot. */
    static boolean pillarUp(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg) {
        BlockPos feet = f.blockPosition();
        if (!level.getBlockState(feet.above(2)).isAir() || leg.pillar >= PILLAR_MOST) return false;
        if (f.countMatching(s -> s.is(Items.COBBLESTONE)) < 1) return false;
        if (leg.pillar == 0) leg.pillarFrom = feet.immutable();
        f.getNavigation().stop();
        f.setPos(feet.getX() + 0.5, feet.getY() + 1.0, feet.getZ() + 0.5);
        f.getJumpControl().jump();
        NetherOutpost.place(level, f, r, feet, Blocks.COBBLESTONE.defaultBlockState());
        leg.pillar++;
        return true;
    }

    /** Down its pillar again, a block a step, the cobblestone back into its pack. True while it is coming down. */
    static boolean pillarDown(ServerLevel level, VillageFolkEntity f, NetherRuns.Leg leg) {
        if (leg.pillar <= 0) return false;
        BlockPos under = f.blockPosition().below();
        if (level.getBlockState(under).is(Blocks.COBBLESTONE)) {
            level.destroyBlock(under, false, f);
            f.insertItem(new ItemStack(Items.COBBLESTONE));
            f.setPos(under.getX() + 0.5, under.getY(), under.getZ() + 0.5);
        }
        leg.pillar--;
        if (leg.pillar <= 0) leg.pillarFrom = null;
        return true;
    }

    // ------------------------------------------------------------------ looking round

    /** The leader's look round (every two seconds at most): the work in reach, and what is worth noting. */
    static Scan scan(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r) {
        Scan s = scanOf(r);
        long now = level.getGameTime();
        if (now - s.at < SCAN_EVERY && s.from != null && s.from.distSqr(f.blockPosition()) < 16) return s;
        s.at = now;
        s.from = f.blockPosition().immutable();
        s.found.clear();
        NetherOutpost.Room room = NetherOutpost.room(r.village);
        int bricks = 0, bastion = 0, lava = 0;
        BlockPos brick = null, bastionAt = null, lavaAt = null;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        BlockPos c = s.from;
        long day = level.getDayTime() / 24000L;
        for (int dx = -SCAN; dx <= SCAN; dx++) {
            for (int dz = -SCAN; dz <= SCAN; dz++) {
                for (int dy = -SCAN_DOWN; dy <= SCAN_UP; dy++) {
                    q.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    if (!level.isLoaded(q) || boundsForTests != null && !boundsForTests.isInside(q)) continue;
                    BlockState st = level.getBlockState(q);
                    if (st.isAir()) continue;
                    if (st.is(Blocks.LAVA)) {
                        lava++;
                        if (lavaAt == null) lavaAt = q.immutable();
                        continue;
                    }
                    if (st.is(Blocks.NETHER_BRICKS)) {
                        bricks++;
                        if (brick == null) brick = q.immutable();
                        continue;
                    }
                    if (st.is(Blocks.POLISHED_BLACKSTONE_BRICKS) || st.is(Blocks.GILDED_BLACKSTONE) || st.is(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS)) {
                        bastion++;
                        if (bastionAt == null) bastionAt = q.immutable();
                    }
                    Job j = jobOf(st);
                    if (j == null && st.is(Blocks.SPAWNER)) {
                        spawner(level, f, r, s, q.immutable(), day);
                        continue;
                    }
                    if (j == null || r.passed.contains(q.asLong())) continue;
                    if (room != null && room.has(q)) continue;
                    if (j != Job.WART && j != Job.SOUL && !exposed(level, q)) continue;
                    if (j == Job.SOUL && !level.getBlockState(q.above()).isAir()) continue;
                    s.found.computeIfAbsent(j, k -> new ArrayList<>()).add(q.immutable());
                }
            }
        }
        if (bricks >= 12 && NetherRuns.fortress(r.village) == null && !has(r, NetherRuns.Kind.FORTRESS)) {
            BlockPos from = home(r);
            int far = from == null ? 0 : (int) Math.sqrt(from.distSqr(brick));
            note(r, new NetherRuns.Find(NetherRuns.Kind.FORTRESS, "a fortress", brick, day, f.displayNameCap(), far, 0));
            FolkTalk.speak(f, "Nether brick! There's a fortress here. Wart, and blazes — and wither skeletons. Careful now.");
            r.event(f.displayNameCap() + " came on a fortress");
        }
        if (bastion >= 10 && NetherRuns.bastion(r.village) == null && !has(r, NetherRuns.Kind.BASTION)) {
            BlockPos from = home(r);
            note(r, new NetherRuns.Find(NetherRuns.Kind.BASTION, "a bastion", bastionAt, day, f.displayNameCap(),
                from == null ? 0 : (int) Math.sqrt(from.distSqr(bastionAt)), 0));
            FolkTalk.speak(f, "A bastion. That's the piglins' own house — we keep well out of it.");
            r.event(f.displayNameCap() + " saw a bastion, and kept clear");
        }
        if (lava >= 300 && !has(r, NetherRuns.Kind.LAVA)) note(r, new NetherRuns.Find(NetherRuns.Kind.LAVA, "a sea of lava", lavaAt, day, f.displayNameCap(), lava, 0));
        return s;
    }

    private static boolean has(NetherRuns.Run r, NetherRuns.Kind k) {
        for (NetherRuns.Find x : r.found) if (x.kind() == k) return true;
        return false;
    }

    /** The job a block is the work of, or null. */
    @Nullable
    static Job jobOf(BlockState st) {
        if (st.is(Blocks.NETHER_QUARTZ_ORE)) return Job.QUARTZ;
        if (st.is(Blocks.GLOWSTONE)) return Job.GLOWSTONE;
        if (st.is(Blocks.NETHER_GOLD_ORE)) return Job.GOLD;
        if (st.is(Blocks.ANCIENT_DEBRIS)) return Job.DEBRIS;
        if (st.is(Blocks.NETHER_WART) && st.getValue(NetherWartBlock.AGE) >= 3) return Job.WART;
        if (st.is(Blocks.SOUL_SAND)) return Job.SOUL;
        return null;
    }

    /** Is a face of it open to the air (it can be got at without cutting in)? */
    static boolean exposed(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockState n = level.getBlockState(p.relative(d));
            if (n.isAir() || n.canBeReplaced() && level.getFluidState(p.relative(d)).isEmpty()) return true;
        }
        return false;
    }

    /** A spawner seen: a blaze spawner is noted, and is the blaze work's place. */
    static void spawner(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, Scan s, BlockPos at, long day) {
        if (!(level.getBlockEntity(at) instanceof SpawnerBlockEntity sp)) return;
        String id = "";
        try {
            CompoundTag tag = sp.getSpawner().save(new CompoundTag());
            id = tag.getCompound("SpawnData").getCompound("entity").getString("id");
        } catch (RuntimeException ex) {
            NetherRuns.LOG.debug("[MCA-NETHER] spawner read failed: {}", ex.toString());
        }
        if (!id.endsWith("blaze")) return;
        s.spawner = at;
        if (!has(r, NetherRuns.Kind.SPAWNER)) {
            note(r, new NetherRuns.Find(NetherRuns.Kind.SPAWNER, "a blaze spawner", at, day, f.displayNameCap(), 0, 0));
            FolkTalk.speak(f, "A blaze spawner! Potions, everybody — and stand off and shoot.");
        }
    }

    /** The fortress the runs know of (the report's, or this run's, or the test's), or null. */
    @Nullable
    static BlockPos fortress(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, Scan s) {
        if (fortressForTests != null) return fortressForTests;
        NetherRuns.Find known = NetherRuns.fortress(r.village);
        if (known != null) return known.at();
        for (NetherRuns.Find x : r.found) if (x.kind() == NetherRuns.Kind.FORTRESS) return x.at();
        if (s.located) return null;
        s.located = true;
        BlockPos from = home(r);
        if (from == null) return null;
        // Which way the nearest fortress lies, by the lie of the land (the game's own placing of them): within sixteen
        // chunks of the outpost or not at all. Once a run, and noted: after that it is in the report.
        try {
            var reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            Holder<Structure> fort = reg.getHolderOrThrow(BuiltinStructures.FORTRESS);
            var found = level.getChunkSource().getGenerator().findNearestMapStructure(level, HolderSet.direct(fort), from, 16, false);
            if (found == null) return null;
            BlockPos at = new BlockPos(found.getFirst().getX(), from.getY(), found.getFirst().getZ());
            int far = (int) Math.sqrt(from.distSqr(at));
            note(r, new NetherRuns.Find(NetherRuns.Kind.FORTRESS, "a fortress", at, level.getDayTime() / 24000L, f.displayNameCap(), far, 0));
            FolkTalk.speak(f, "There's a fortress " + Guide.direction(from, at) + " of here, " + far + " blocks or so. That's where the wart is.");
            return at;
        } catch (RuntimeException ex) {
            NetherRuns.LOG.warn("[MCA-NETHER] looking for a fortress: {}", ex.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ the leader's work

    /** The jobs of the plan, in its order (what the town wants most first). */
    static List<Job> jobs(NetherRuns.Run r) {
        List<Job> out = new ArrayList<>();
        List<String> work = r.plan != null ? r.plan.work() : List.of("quartz");
        for (String w : work) {
            switch (w) {
                case "quartz" -> out.add(Job.QUARTZ);
                case "glowstone" -> out.add(Job.GLOWSTONE);
                case "wart" -> out.add(Job.WART);
                case "soul" -> out.add(Job.SOUL);
                case "blaze" -> out.add(Job.BLAZE);
                case "barter" -> out.add(Job.BARTER);
                default -> { }
            }
        }
        // Quartz always (the builders' trim), and ancient debris whenever it shows with a diamond pick to hand.
        if (!out.contains(Job.QUARTZ)) out.add(Job.QUARTZ);
        out.add(0, Job.DEBRIS);
        return out;
    }

    /** The team's gold to barter with, and does the leader wear gold (the piglins will not barter with one who does not)? */
    static int teamGold(ServerLevel level, NetherRuns.Run r) {
        int n = 0;
        for (VillageFolkEntity m : NetherRuns.here(level, r)) n += m.countMatching(s -> s.is(Items.GOLD_INGOT));
        return n;
    }

    /**
     * The leader's step: the work in hand, or the next by the plan from what it can see (the nearest of each, the town's
     * most wanted first), else on toward the fortress, else a look about.
     */
    static void lead(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg) {
        // Down its pillar once the glowstone it went up for is out (not while it is still at it).
        if ((r.task == null || r.task.job != Job.GLOWSTONE) && pillarDown(level, f, leg)) return;
        if (pickUp(level, f, r, leg)) return;
        collect(level, f, r, leg);
        if (r.task != null && !valid(level, f, r, r.task)) {
            Task done = r.task;
            r.task = null;
            if (done.job == Job.BARTER && done.done > 0) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Good trading. On.", "That's that piglin's best. On we go."));
            }
        }
        if (r.task != null && r.task.job != Job.BLAZE && r.task.job != Job.BARTER && r.task.job != Job.HUNT
                && level.getGameTime() % 20 < NetherRuns.STEP) {
            Task moving = mobFirst(level, f, r, level.getGameTime());
            if (moving != null) {
                if (r.task.at != null) scanOf(r).claims.remove(r.task.at.asLong());
                r.task = moving;
            }
        }
        if (r.task == null) r.task = next(level, f, r, leg);
        if (r.task == null) return;
        work(level, f, r, leg, r.task, true);
    }

    /** Is the work in hand still there to do? */
    static boolean valid(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, Task t) {
        long now = level.getGameTime();
        if (now - t.since > 2400 && t.job != Job.WAY && t.job != Job.BLAZE) {                 // two minutes at one thing: on
            if (t.at != null && t.job.block()) r.passed.add(t.at.asLong());                     // and not straight back to it
            return false;
        }
        return switch (t.job) {
            case QUARTZ, GLOWSTONE, GOLD, DEBRIS, SOUL, WART -> t.at != null && !r.passed.contains(t.at.asLong()) && jobOf(level.getBlockState(t.at)) == t.job;
            case BLAZE -> now - t.since < 6000 && f.countMatching(s -> s.is(Items.ARROW)) > 0
                && (t.mob != null && level.getEntity(t.mob) instanceof Blaze b && b.isAlive() || t.at != null && level.getGameTime() - t.since < 2400);
            case BARTER -> t.mob != null && level.getEntity(t.mob) instanceof Piglin p && p.isAlive()
                && (t.counted || p.getOffhandItem().is(Items.GOLD_INGOT) || t.done < BARTER_EACH && teamGold(level, r) > 0);
            case HUNT -> t.mob != null && level.getEntity(t.mob) instanceof Hoglin h && h.isAlive();
            case WAY -> t.at != null && f.blockPosition().distSqr(t.at) > 16 * 16 && now - t.since < 9000;
            case LOOK -> t.at != null && f.blockPosition().distSqr(t.at) > 9 && now - t.since < 1200;
        };
    }

    /** The next piece of work, or null (nothing in sight; nothing left). */
    @Nullable
    static Task next(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg) {
        Scan s = scan(level, f, r);
        long now = level.getGameTime();
        BlockPos home = home(r);
        // The food running short and a hoglin about: meat.
        int heads = 0, food = 0;
        for (VillageFolkEntity m : NetherRuns.here(level, r)) {
            heads++;
            food += m.countMatching(CaveDwellers::food);
        }
        if (food < heads * NetherPlan.MEALS) {
            Hoglin h = nearest(level, f, Hoglin.class, 20, x -> x.isAlive() && !x.isBaby());
            if (h != null) {
                FolkTalk.speak(f, "Food's short. That hoglin — together, everybody, and mind the tusks.");
                return new Task(Job.HUNT, h.blockPosition(), h.getUUID(), now);
            }
        }
        boolean piglinsNear = nearest(level, f, Piglin.class, 16, x -> x.isAlive()) != null;
        // What moves comes first: a blaze in sight is shot before anything is dug (it will not wait, and it shoots back),
        // and a piglin about is bartered with while it is here (the quartz will keep).
        Task moving = mobFirst(level, f, r, now);
        if (moving != null) return moving;
        for (Job j : jobs(r)) {
            switch (j) {
                case BLAZE -> {
                    if (f.countMatching(s2 -> s2.is(Items.ARROW)) < 4) continue;
                    if (s.spawner != null) return new Task(Job.BLAZE, s.spawner, null, now);
                }
                case BARTER -> { }
                default -> {
                    if (!j.block()) continue;
                    if (j == Job.DEBRIS && !CaveDwellers.bestPick(f).isCorrectToolForDrops(Blocks.ANCIENT_DEBRIS.defaultBlockState())) continue;
                    if (j == Job.GOLD && piglinsNear) continue;                 // a piglin sees its gold dug and turns on you
                    BlockPos b = nearestOf(level, f, r, s, j, home);
                    if (b != null) return new Task(j, b, null, now);
                }
            }
        }
        // Gold short for a barter that is wanted: nether gold ore near, its nuggets made into ingots at the table.
        if (r.plan != null && r.plan.wants("barter") && teamGold(level, r) == 0 && !piglinsNear) {
            BlockPos b = nearestOf(level, f, r, s, Job.GOLD, home);
            if (b != null) return new Task(Job.GOLD, b, null, now);
        }
        // Nothing of the plan's here: on toward the fortress when the plan wants what is there.
        if (r.plan != null && (r.plan.wants("wart") || r.plan.wants("soul") || r.plan.wants("blaze"))) {
            BlockPos fort = fortress(level, f, r, s);
            if (fort != null && f.blockPosition().distSqr(fort) > 16 * 16) {
                if (r.task == null || r.task.job != Job.WAY) FolkTalk.speak(f, "On to the fortress. Single file, and stay on the path.");
                return new Task(Job.WAY, fort, null, now);
            }
        }
        // Glowstone or quartz anywhere in sight, whatever the plan (the builders always want them).
        for (Job j : new Job[]{ Job.QUARTZ, Job.GLOWSTONE }) {
            BlockPos b = nearestOf(level, f, r, s, j, home);
            if (b != null) return new Task(j, b, null, now);
        }
        // A look about: out from the outpost a different way each time, as far as there is open ground to walk.
        if (home == null) return null;
        Direction[] ways = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };
        while (s.looked < LOOKS) {
            int i = s.looked++;
            Direction d = ways[i % 4];
            int far = 16 + 12 * (i / 4);
            for (int k = far; k >= 8; k -= 4) {
                BlockPos look = groundNear(level, home.relative(d, k).relative(d.getClockWise(), (i / 4) * 6));
                if (look == null) continue;
                s.look = look;
                return new Task(Job.LOOK, look, null, now);
            }
        }
        return null;
    }

    /**
     * The work that will not wait, if there is any: a blaze in sight and in reach of the bow (any blaze at all that has
     * one of the team for its target, whatever the plan; else one the plan wants rods of), or a piglin to barter with
     * when the plan wants a barter and the leader wears gold and the team has an ingot. Null if there is none.
     */
    @Nullable
    static Task mobFirst(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, long now) {
        List<Job> plan = jobs(r);
        if (f.countMatching(s -> s.is(Items.ARROW)) >= 4) {
            Blaze b = nearest(level, f, Blaze.class, 24, x -> x.isAlive() && f.hasLineOfSight(x)
                && (plan.contains(Job.BLAZE) || x.getTarget() instanceof VillageFolkEntity m && r.members.contains(m.getUUID())));
            if (b != null) {
                if (r.task == null || r.task.job != Job.BLAZE) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Blaze! Bows, everybody, and stand off.",
                    "A blaze — potions if you've not had one, and shoot."));
                return new Task(Job.BLAZE, null, b.getUUID(), now);   // the blaze, not a place: done when it is down
            }
        }
        if (plan.contains(Job.BARTER) && teamGold(level, r) > 0 && NetherRunners.wearsGold(f)) {
            Piglin p = nearest(level, f, Piglin.class, 24, x -> x.isAlive() && !x.isBaby() && x.getOffhandItem().isEmpty()
                && !barteredOut(r, x.getUUID()));
            if (p != null) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "A piglin. Stand back, all of you, and let me do the trading.",
                    "Gold out. Slowly — they're touchy."));
                return new Task(Job.BARTER, p.blockPosition(), p.getUUID(), now);
            }
        }
        return null;
    }

    /** A spot to stand near this one (the first open floor up or down from it), or null. */
    @Nullable
    static BlockPos groundNear(ServerLevel level, BlockPos p) {
        if (!level.isLoaded(p)) return null;
        for (int dy = 0; dy <= 8; dy++) {
            for (int sgn : new int[]{ 1, -1 }) {
                BlockPos q = p.above(dy * sgn);
                if (CaveDwellers.standable(level, q) && !NetherOutpost.lava(level, q.below())) return q;
            }
        }
        return null;
    }

    /** The nearest of a job's blocks in the last look round, within reach of the outpost, and not claimed. */
    @Nullable
    static BlockPos nearestOf(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, Scan s, Job j, @Nullable BlockPos home) {
        List<BlockPos> list = s.found.get(j);
        if (list == null) return null;
        BlockPos best = null;
        double near = Double.MAX_VALUE;
        for (BlockPos b : list) {
            if (r.passed.contains(b.asLong()) || s.claims.containsKey(b.asLong())) continue;
            if (home != null && b.distSqr(home) > FROM_OUTPOST * FROM_OUTPOST && (r.plan == null || !r.plan.fortress())) continue;
            if (jobOf(level.getBlockState(b)) != j) continue;
            double d = b.distSqr(f.blockPosition());
            if (d < near) {
                near = d;
                best = b;
            }
        }
        return best;
    }

    /** The nearest live mob of a kind within so many blocks that passes the test, or null. */
    @Nullable
    static <T extends Entity> T nearest(ServerLevel level, VillageFolkEntity f, Class<T> type, double r, java.util.function.Predicate<T> ok) {
        T best = null;
        double near = Double.MAX_VALUE;
        for (T e : level.getEntitiesOfClass(type, f.getBoundingBox().inflate(r), ok::test)) {
            double d = e.distanceToSqr(f);
            if (d < near) {
                near = d;
                best = e;
            }
        }
        return best;
    }

    /** Has this piglin had its gold off the team already this run? */
    static boolean barteredOut(NetherRuns.Run r, UUID piglin) {
        return r.passed.contains(piglin.getMostSignificantBits() ^ piglin.getLeastSignificantBits());
    }

    /** The work in hand, a step of it (for the leader, or a helper on its own share). */
    static void work(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg, Task t, boolean leading) {
        f.hobbyNow = t.job.words.toLowerCase(Locale.ROOT) + " in the Nether";
        switch (t.job) {
            case QUARTZ, GLOWSTONE, GOLD, DEBRIS, SOUL -> block(level, f, r, leg, t, leading);
            case WART -> wart(level, f, r, leg, t);
            case BLAZE -> blaze(level, f, r, leg, t);
            case BARTER -> barter(level, f, r, leg, t);
            case HUNT -> {
                if (t.mob != null && level.getEntity(t.mob) instanceof Hoglin h) {
                    f.setTarget(h);
                    leg.foe = h.getUUID();
                    if (f.distanceToSqr(h) > 9) f.getNavigation().moveTo(h, 1.15D);
                }
            }
            case WAY -> way(level, f, r, leg, t);
            case LOOK -> {
                if (t.at != null && !makeFor(level, f, r, leg, t.at, 1.05D, false)) {
                    scanOf(r).at = -100000;                // there: a fresh look round
                    r.task = null;
                }
            }
        }
    }

    /** A block of the work (quartz, glowstone, gold, debris, soul sand): to it (up a pillar to a ceiling), out. */
    static void block(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg, Task t, boolean leading) {
        BlockPos b = t.at;
        if (b == null) return;
        Scan s = scanOf(r);
        s.claims.put(b.asLong(), f.getUUID());
        BlockPos feet = f.blockPosition();
        int up = b.getY() - feet.getY();
        boolean under = Math.abs(b.getX() - feet.getX()) <= 1 && Math.abs(b.getZ() - feet.getZ()) <= 1;
        if (up > 3 && under) {
            // On a ceiling: up a pillar, as high as it takes.
            if (up > PILLAR_MOST + 3 || !pillarUp(level, f, r, leg)) {
                r.passed.add(b.asLong());
                s.claims.remove(b.asLong());
                r.task = leading ? null : r.task;
                return;
            }
            f.hobbyNow = "up a pillar after the glowstone";
            return;
        }
        if (up > 3) {
            // Under it first.
            BlockPos below = groundNear(level, new BlockPos(b.getX(), feet.getY(), b.getZ()));
            if (below != null && Math.abs(below.getY() - feet.getY()) <= 3 && makeFor(level, f, r, leg, below, 1.0D)) return;
            if (below == null || Math.abs(below.getY() - feet.getY()) > 3) {
                r.passed.add(b.asLong());
                if (leading) r.task = null;
                return;
            }
        }
        if (NetherOutpost.lava(level, b.above()) || NetherOutpost.lava(level, b.below()) && b.getY() < feet.getY()) {
            // Lava over or under it: left (it would come down on whoever took it out).
            r.passed.add(b.asLong());
            s.claims.remove(b.asLong());
            if (leading) r.task = null;
            return;
        }
        long now = level.getGameTime();
        if (now - t.since > GIVE_UP && t.done == 0 && (leg.digging == null || !leg.digging.equals(b) || leg.dug == 0)) {
            // A minute and no nearer to having it out (no way to it, or none worth cutting): left for another time.
            r.passed.add(b.asLong());
            s.claims.remove(b.asLong());
            leg.digging = null;
            if (leading) r.task = null;
            return;
        }
        if (dig(level, f, r, leg, b)) {
            s.claims.remove(b.asLong());
            t.done++;
            if (leading) r.task = null;
            leg.digTick = now;
            if (t.job == Job.GOLD) nuggetsToIngots(level, f);
            return;
        }
        if (leg.digging != null && now - leg.digTick > 400 && leg.dug == 0) {
            // Could not get at it in twenty seconds: left for another time.
            r.passed.add(b.asLong());
            s.claims.remove(b.asLong());
            leg.digging = null;
            if (leading) r.task = null;
        }
    }

    /** Ripe wart picked (the bed's own drops), and one planted back in its soul sand for the next run. */
    static void wart(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg, Task t) {
        BlockPos b = t.at;
        if (b == null) return;
        if (f.getEyePosition().distanceToSqr(Vec3.atCenterOf(b)) > 4.5 * 4.5) {
            BlockPos stand = CaveDwellers.standBy(level, f, b);
            if (!makeFor(level, f, r, leg, stand != null ? stand : b, 1.0D) || level.getGameTime() - t.since > GIVE_UP) {
                r.passed.add(b.asLong());                                   // no way to it, or a minute and not there: left
                if (r.task == t) r.task = null;
            }
            return;
        }
        BlockState st = level.getBlockState(b);
        if (!st.is(Blocks.NETHER_WART)) {
            r.task = null;
            return;
        }
        f.getLookControl().setLookAt(b.getX() + 0.5, b.getY() + 0.3, b.getZ() + 0.5);
        f.swing(InteractionHand.MAIN_HAND);
        mine(level, f, r, leg, b, st, true);
        if (level.getBlockState(b.below()).is(Blocks.SOUL_SAND) && level.getBlockState(b).isAir()
                && f.removeMatching(s -> s.is(Items.NETHER_WART), 1) == 1) {
            level.setBlockAndUpdate(b, Blocks.NETHER_WART.defaultBlockState());
            leg.got.merge(Items.NETHER_WART, -1, Integer::sum);
            r.haul.merge("nether wart", -1, Integer::sum);
        }
        t.done++;
        if (r.task == t) r.task = null;
    }

    /** At the blaze spawner (or a blaze): fire resistance drunk, off to shooting range, and the blaze shot down. */
    static void blaze(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg, Task t) {
        drinkIfWanted(level, f, r, leg, true);
        Blaze b = t.mob != null && level.getEntity(t.mob) instanceof Blaze x && x.isAlive() ? x : null;
        if (b == null) b = nearest(level, f, Blaze.class, 24, x -> x.isAlive() && f.hasLineOfSight(x));
        if (b == null) {
            // No blaze up just now: stand off from the spawner and wait for one.
            if (t.at != null && f.blockPosition().distSqr(t.at) > 10 * 10) makeFor(level, f, r, leg, t.at, 1.0D);
            else f.getNavigation().stop();
            f.hobbyNow = "waiting by the blaze spawner, bow drawn";
            return;
        }
        t.mob = b.getUUID();
        shoot(level, f, r, leg, b);
    }

    /** A mob shot at from range with the bow: set as its target (its own bow goal draws on it), and a shot of its own
     *  every second and a half it has a clear line. */
    static void shoot(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg, LivingEntity m) {
        f.setTarget(m);
        leg.foe = m.getUUID();
        double d = f.distanceToSqr(m);
        if (d > 18 * 18 || !f.hasLineOfSight(m)) {
            f.getNavigation().moveTo(m.getX(), m.getY(), m.getZ(), 1.0D);
            return;
        }
        if (d < 6 * 6 && !(m instanceof Ghast)) {
            // Too close for the bow: back off a few steps.
            Vec3 away = f.position().subtract(m.position()).normalize().scale(4.0);
            f.getNavigation().moveTo(f.getX() + away.x, f.getY(), f.getZ() + away.z, 1.1D);
        } else {
            f.getNavigation().stop();
        }
        f.getLookControl().setLookAt(m, 30.0F, 30.0F);
        long now = level.getGameTime();
        if (now - leg.shot >= 30 && f.countMatching(s -> s.is(Items.ARROW)) > 0) {
            leg.shot = now;
            if (!f.getMainHandItem().is(Items.BOW)) {
                for (int i = 0; i < f.getInventoryItems().size(); i++) {
                    ItemStack s = f.getInventoryItems().get(i);
                    if (!s.is(Items.BOW)) continue;
                    ItemStack old = f.getMainHandItem();
                    f.setItemSlot(EquipmentSlot.MAINHAND, s);
                    f.getInventoryItems().set(i, old);
                    break;
                }
            }
            f.performRangedAttack(m, 1.0F);
        }
    }

    /**
     * A barter: the leader (in gold) up to the piglin, a gold ingot thrown it; the piglin admires it a few seconds and
     * throws back what its bartering table gives; that picked up; again, up to a few ingots a piglin.
     */
    static void barter(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg, Task t) {
        if (t.mob == null || !(level.getEntity(t.mob) instanceof Piglin p) || !p.isAlive()) {
            r.task = null;
            return;
        }
        long now = level.getGameTime();
        f.getLookControl().setLookAt(p, 30.0F, 30.0F);
        boolean admiring = p.getOffhandItem().is(Items.GOLD_INGOT);
        if (admiring) {
            // Taken: it turns the gold over a few seconds (the game's own admiring).
            if (!t.counted) {
                t.counted = true;
                r.barters++;
                if (r.barters == 1) r.event(f.displayNameCap() + " bartered gold with the piglins");
            }
            t.waitFrom = -1;
            f.getNavigation().stop();
            f.hobbyNow = "watching a piglin turn the gold over";
            return;
        }
        if (t.counted) {
            // Done admiring: what it threw back comes down round it, and is picked up; then the next ingot.
            if (t.waitFrom < 0) t.waitFrom = now;
            if (now - t.waitFrom < 20) return;
            collectNear(level, f, r, leg, p.blockPosition(), 8);
            f.hobbyNow = "picking up what the piglin threw back";
            if (now - t.waitFrom < 100) return;
            t.counted = false;
            t.waitFrom = -1;
        }
        if (f.distanceToSqr(p) > 4.0 * 4.0) {
            f.getNavigation().moveTo(p, 0.9D);
            return;
        }
        if (now - leg.threw < 160) return;                              // the last ingot not taken yet: give it time
        if (f.removeMatching(s -> s.is(Items.GOLD_INGOT), 1) < 1) {
            // Its own gold gone: the next of the team with some hands it one.
            for (VillageFolkEntity m : NetherRuns.here(level, r)) {
                if (m == f || m.removeMatching(s -> s.is(Items.GOLD_INGOT), 1) < 1) continue;
                f.insertItem(new ItemStack(Items.GOLD_INGOT));
                break;
            }
            if (f.removeMatching(s -> s.is(Items.GOLD_INGOT), 1) < 1) {
                r.task = null;
                return;
            }
        }
        leg.threw = now;
        t.done++;
        t.counted = false;
        Vec3 from = f.getEyePosition().subtract(0, 0.3, 0);
        ItemEntity gold = new ItemEntity(level, from.x, from.y, from.z, new ItemStack(Items.GOLD_INGOT));
        Vec3 to = p.position().subtract(from).normalize().scale(0.3);
        gold.setDeltaMovement(to.x, 0.2, to.z);
        gold.setPickUpDelay(5);
        gold.setThrower(f);
        level.addFreshEntity(gold);
        NetherGuests.watching(level, r, f, p);
        f.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, f.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.5F, 0.8F);
        Economy.usedGiven(f, new ItemStack(Items.GOLD_INGOT), 1);
        t.waitFrom = -1;
        if (t.done >= BARTER_EACH) r.passed.add(p.getUUID().getMostSignificantBits() ^ p.getUUID().getLeastSignificantBits());
        f.hobbyNow = "throwing a piglin a gold ingot";
    }

    /** Toward the fortress: walked where there is a way, cut where there is none; the way's length noted. */
    static void way(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg, Task t) {
        if (t.at == null) return;
        int before = r.cut;
        boolean going = makeFor(level, f, r, leg, t.at, 1.05D);
        if (r.cut > before) {
            BlockPos home = home(r);
            int out = home == null ? 0 : (int) Math.sqrt(home.distSqr(f.blockPosition()));
            BlockPos fort = t.at;
            note(r, new NetherRuns.Find(NetherRuns.Kind.FORTRESS, "a fortress", fort, level.getDayTime() / 24000L, f.displayNameCap(), 0, 0));
            for (int i = 0; i < r.found.size(); i++) {
                NetherRuns.Find x = r.found.get(i);
                if (x.kind() == NetherRuns.Kind.FORTRESS) r.found.set(i, x.with(Math.max(x.a(), home == null ? 0 : (int) Math.sqrt(home.distSqr(fort))),
                    Math.max(x.b(), out)));
            }
        }
        if (!going) {
            scanOf(r).at = -100000;
            r.task = null;
        }
    }

    /** Nine nuggets into an ingot (crafting on the fly, from the pack: the game's own recipe), for the barter. */
    static void nuggetsToIngots(ServerLevel level, VillageFolkEntity f) {
        if (f.countMatching(s -> s.is(Items.GOLD_NUGGET)) < 9) return;
        Map<String, Integer> made = new HashMap<>();
        if (CaveCraft.once(level, f, Items.GOLD_INGOT, true, 0, made)) {
            FolkTalk.speak(f, "Nine nuggets, one ingot. That's another barter.");
        }
    }

    // ------------------------------------------------------------------ the others' work

    /** One of the team (not the leader): close to the leader; on a share of the same work beside it; picking up. */
    static void help(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg, VillageFolkEntity lead) {
        boolean atGlow = leg.claim != null && jobOf(level.getBlockState(leg.claim)) == Job.GLOWSTONE;
        if (!atGlow && pillarDown(level, f, leg)) return;
        if (pickUp(level, f, r, leg)) return;
        collect(level, f, r, leg);
        if (!NetherRuns.inNether(lead)) return;
        double d = f.distanceToSqr(lead);
        Task t = r.task;
        if (leg.claim != null && (t == null || !t.job.block() || jobOf(level.getBlockState(leg.claim)) != t.job)) {
            scanOf(r).claims.remove(leg.claim.asLong());
            leg.claim = null;
        }
        if (d > 12 * 12 || t == null) {
            if (d > NetherRuns.CLOSE * NetherRuns.CLOSE) makeFor(level, f, r, leg, lead.blockPosition(), 1.1D);
            else f.getNavigation().stop();
            f.hobbyNow = "keeping close to " + lead.displayNameCap() + " in the Nether";
            return;
        }
        switch (t.job) {
            case QUARTZ, GLOWSTONE, GOLD, SOUL, DEBRIS -> {
                // A block of the same near the leader's, not claimed: its own share.
                BlockPos mine = leg.claim;
                Scan s = scanOf(r);
                if (mine == null || jobOf(level.getBlockState(mine)) != t.job || r.passed.contains(mine.asLong())) {
                    if (mine != null) s.claims.remove(mine.asLong());
                    mine = null;
                    List<BlockPos> list = s.found.get(t.job);
                    if (list != null) {
                        double near = Double.MAX_VALUE;
                        for (BlockPos b : list) {
                            if (t.at != null && b.equals(t.at) || s.claims.containsKey(b.asLong()) || r.passed.contains(b.asLong())) continue;
                            if (t.at != null && b.distSqr(t.at) > 8 * 8 || jobOf(level.getBlockState(b)) != t.job) continue;
                            double dd = b.distSqr(f.blockPosition());
                            if (dd < near) {
                                near = dd;
                                mine = b;
                            }
                        }
                    }
                    leg.claim = mine;
                    leg.claimSince = level.getGameTime();
                }
                if (mine == null) {
                    f.getNavigation().stop();
                    f.getLookControl().setLookAt(lead, 30.0F, 30.0F);
                    f.hobbyNow = "keeping watch while " + lead.displayNameCap() + " digs";
                    return;
                }
                s.claims.put(mine.asLong(), f.getUUID());
                Task own = new Task(t.job, mine, null, leg.claimSince);
                block(level, f, r, leg, own, false);
                if (own.done > 0 || own.at == null) {
                    s.claims.remove(mine.asLong());
                    leg.claim = null;
                }
            }
            case WART -> {
                Scan s = scanOf(r);
                List<BlockPos> list = s.found.get(Job.WART);
                BlockPos mine = null;
                if (list != null) for (BlockPos b : list) {
                    if (t.at != null && b.equals(t.at) || jobOf(level.getBlockState(b)) != Job.WART || s.claims.containsKey(b.asLong())) continue;
                    mine = b;
                    break;
                }
                if (mine != null) {
                    s.claims.put(mine.asLong(), f.getUUID());
                    wart(level, f, r, leg, new Task(Job.WART, mine, null, level.getGameTime()));   // its own reach, not the leader's clock
                    s.claims.remove(mine.asLong());
                }
            }
            case BLAZE -> {
                Blaze b = t.mob != null && level.getEntity(t.mob) instanceof Blaze x && x.isAlive() ? x : nearest(level, f, Blaze.class, 24, LivingEntity::isAlive);
                drinkIfWanted(level, f, r, leg, true);
                if (b != null) shoot(level, f, r, leg, b);
                else if (d > NetherRuns.CLOSE * NetherRuns.CLOSE) makeFor(level, f, r, leg, lead.blockPosition(), 1.0D);
            }
            case BARTER -> {
                // Stood back from the piglin, watching, and taking up what it throws.
                if (d > 6 * 6) makeFor(level, f, r, leg, lead.blockPosition(), 1.0D);
                else f.getNavigation().stop();
                if (t.mob != null && level.getEntity(t.mob) instanceof Piglin p) {
                    f.getLookControl().setLookAt(p, 30.0F, 30.0F);
                    collectNear(level, f, r, leg, p.blockPosition(), 6);
                }
                f.hobbyNow = "watching " + lead.displayNameCap() + " barter with a piglin";
            }
            case HUNT -> {
                if (t.mob != null && level.getEntity(t.mob) instanceof Hoglin h && h.isAlive()) {
                    f.setTarget(h);
                    if (f.distanceToSqr(h) > 9) f.getNavigation().moveTo(h, 1.1D);
                }
            }
            case WAY, LOOK -> {
                if (d > NetherRuns.CLOSE * NetherRuns.CLOSE) makeFor(level, f, r, leg, lead.blockPosition(), 1.1D);
                else f.getNavigation().stop();
                f.hobbyNow = t.job == Job.WAY ? "behind " + lead.displayNameCap() + " on the way to the fortress" : "looking about with " + lead.displayNameCap();
            }
        }
    }

    // ------------------------------------------------------------------ picking up

    /** What lies on the ground near it that is worth taking home: taken (every second). */
    static void collect(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg) {
        long now = level.getGameTime();
        if (now - leg.collect < 20) return;
        leg.collect = now;
        collectNear(level, f, r, leg, f.blockPosition(), 5);
    }

    /** Everything worth taking within so many blocks of a spot (the gold thrown to a piglin left be). */
    static void collectNear(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg, BlockPos at, int reach) {
        for (ItemEntity it : level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(at).inflate(reach),
                e -> e.isAlive() && !e.getItem().isEmpty())) {
            ItemStack s = it.getItem();
            if (junk(s) || s.is(Items.GOLD_INGOT) && it.getOwner() instanceof VillageFolkEntity) continue;
            if (s.is(Items.GOLD_INGOT) && nearest(level, f, Piglin.class, 8, Piglin::isAlive) != null) continue;
            if (f.distanceToSqr(it) > 2.5 * 2.5) {
                if (f.getNavigation().isDone()) f.getNavigation().moveTo(it, 1.0D);
                continue;
            }
            ItemStack lot = s.copy();
            int in = take(level, f, r, leg, lot, it.blockPosition());
            if (in >= s.getCount()) it.discard();
            else if (in > 0) it.setItem(s.copyWithCount(s.getCount() - in));
            if (in > 0) {
                f.swing(InteractionHand.MAIN_HAND);
                if (lot.is(Items.WITHER_SKELETON_SKULL) || lot.is(Items.GHAST_TEAR)) {
                    FolkTalk.speak(f, lot.is(Items.GHAST_TEAR) ? "A ghast's tear! The brewer'll weep." : "A wither skeleton's skull! That's for the museum.");
                    r.event(f.displayNameCap() + " took up " + JobMarket.a(lot.getHoverName().getString().toLowerCase(Locale.ROOT)));
                }
            }
        }
    }

    /** A fallen runner's things taken up by the others, if they can get to them (NetherRuns.fell). */
    static void pickUpAfter(ServerLevel level, NetherRuns.Run r, BlockPos at) {
        PICKUP.put(r.village, at.immutable());
    }

    /** To where a fallen runner's things lie, and taken up: a minute, then on. True while at it. */
    static boolean pickUp(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg) {
        BlockPos at = PICKUP.get(r.village);
        if (at == null) return false;
        if (level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(at).inflate(6), e -> e.isAlive() && !junk(e.getItem())).isEmpty()
                || f.blockPosition().distSqr(at) > 32 * 32) {
            PICKUP.remove(r.village);
            return false;
        }
        if (NetherOutpost.lava(level, at) && !roomInSatchel(f)) {
            PICKUP.remove(r.village);
            return false;
        }
        makeFor(level, f, r, leg, at, 1.0D);
        collectNear(level, f, r, leg, at, 6);
        f.hobbyNow = "taking up what the fallen one carried";
        return true;
    }

    /** Is there nothing more the town wants within the runners' reach of the outpost (and no fortress to make for)? */
    static boolean nothingLeft(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r) {
        Scan s = scanOf(r);
        if (s.looked < LOOKS) return false;
        for (List<BlockPos> l : s.found.values()) if (!l.isEmpty()) return false;
        return !(r.plan != null && r.plan.fortress());
    }

    // ------------------------------------------------------------------ the risks

    /** A potion of fire resistance drunk (on fire, in lava, or before the blazes) when it has none on it. */
    static boolean drinkIfWanted(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg, boolean forBlazes) {
        if (f.hasEffect(MobEffects.FIRE_RESISTANCE)) return false;
        if (!forBlazes && !f.isOnFire() && !f.isInLava()) return false;
        long now = level.getGameTime();
        if (now - leg.drank < 40) return false;
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (!NetherPlan.fireResistance(s)) continue;
            PotionContents pc = s.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            pc.forEachEffect(f::addEffect);
            s.shrink(1);
            if (s.isEmpty()) pack.set(i, ItemStack.EMPTY);
            ItemStack bottle = f.insertItem(new ItemStack(Items.GLASS_BOTTLE));
            if (!bottle.isEmpty()) f.spawnAtLocation(bottle);
            level.playSound(null, f.blockPosition(), SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL, 1.0F, 1.0F);
            leg.drank = now;
            r.potions++;
            f.brain(forBlazes ? "drank a potion of fire resistance before the blazes" : "drank a potion of fire resistance, on fire");
            if (!forBlazes) r.event(f.displayNameCap() + " caught fire and drank fire resistance");
            return true;
        }
        return false;
    }

    /**
     * The risks seen to before anything else, on the far side: on fire or in lava (a potion, and out of it), hurt (a
     * bite to eat), badly hurt (back to the outpost till it is better), the piglins' manners. True if this step went
     * on it.
     */
    static boolean safety(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg) {
        long now = level.getGameTime();
        if (now % 20 < NetherRuns.STEP) piglinManners(level, f, r);
        if (f.isOnFire() || f.isInLava()) {
            drinkIfWanted(level, f, r, leg, false);
            if (f.isInLava()) {
                BlockPos out = outOfLava(level, f.blockPosition());
                if (out != null) {
                    f.getMoveControl().setWantedPosition(out.getX() + 0.5, out.getY(), out.getZ() + 0.5, 1.3D);
                    f.getJumpControl().jump();
                }
                if (now - leg.spoke > 100) {
                    leg.spoke = now;
                    FolkTalk.speak(f, "Lava! Out — out!");
                    r.event(f.displayNameCap() + " fell in the lava, and got out");
                }
                return true;
            }
        }
        // Hurt: a bite to eat.
        if (f.getHealth() < f.getMaxHealth() * 0.7F && now - leg.ate > 100) {
            leg.ate = now;
            f.eatFromPack();
        }
        // Badly hurt: back to the outpost (or the portal) till it is better; the others carry on near it.
        if (leg.retreating) {
            if (f.getHealth() >= f.getMaxHealth() * 0.7F || r.homeward) {
                leg.retreating = false;
                FolkTalk.speak(f, "Better. Back to it.");
                return false;
            }
            BlockPos safe = NetherRuns.safety(f);
            if (safe != null && makeFor(level, f, r, leg, safe, 1.2D)) {
                f.hobbyNow = "falling back to the outpost, hurt";
                return true;
            }
            f.getNavigation().stop();
            f.hobbyNow = "in the outpost, binding its wounds";
            // [kitchen] A bandage from its pack, now it is out of the fight (the kitchen's rules: once in a while, not
            // under attack). The town's tick never reaches a runner on a run, so the run binds its wounds itself.
            Villages.Village home = Villages.get(r.village);
            if (home != null) Kitchen.bind(f, level, home, now);
            if (now - leg.ate > 60) {
                leg.ate = now;
                f.eatFromPack();
            }
            return true;
        }
        if (f.getHealth() < f.getMaxHealth() * 0.35F && NetherRuns.safety(f) != null) {
            leg.retreating = true;
            r.retreats++;
            f.setTarget(null);
            leg.digging = null;
            r.event(f.displayNameCap() + " was hurt, and fell back to the outpost");
            FolkTalk.speak(f, "I'm hurt — back to the outpost! Cover me!");
            return true;
        }
        return false;
    }

    /** The nearest spot out of the lava to step to, or null. */
    @Nullable
    static BlockPos outOfLava(ServerLevel level, BlockPos at) {
        for (int r = 1; r <= 3; r++) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                for (int dy = 1; dy >= -1; dy--) {
                    BlockPos q = at.relative(d, r).above(dy);
                    if (CaveDwellers.standable(level, q) && !NetherOutpost.lava(level, q)) return q;
                }
            }
        }
        return null;
    }

    /**
     * A piglin's manners, kept for the runners as the game keeps them for a player: one without gold on that a piglin
     * sees, the piglin turns on (as it would on a player); one in gold it leaves be. The game's piglins look only at
     * players for this; the runners are held to the same rule.
     */
    static void piglinManners(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r) {
        if (NetherRunners.wearsGold(f)) return;
        for (Piglin p : level.getEntitiesOfClass(Piglin.class, f.getBoundingBox().inflate(12), x -> x.isAlive() && !x.isBaby())) {
            if (p.getBrain().hasMemoryValue(MemoryModuleType.ANGRY_AT) || !p.hasLineOfSight(f)) continue;
            p.getBrain().setMemoryWithExpiry(MemoryModuleType.ANGRY_AT, f.getUUID(), 600L);
            r.event("a piglin turned on " + f.displayNameCap() + ", who had no gold on");
        }
    }

    /**
     * The fight on the far side: what is after it or the team fought (the blazes and the ghasts from range, a wither
     * skeleton or a magma cube with the blade; a piglin only once it has turned on them; a hoglin only for meat, else
     * stepped away from); what it fought tallied when it falls. True while it fights.
     */
    static boolean fight(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg) {
        if (!NetherRuns.inNether(f) || leg.retreating) return false;
        // What it was fighting: fallen?
        if (leg.foe != null) {
            Entity e = level.getEntity(leg.foe);
            if (!(e instanceof LivingEntity le) || !le.isAlive()) {
                if (e instanceof LivingEntity le2 && le2.isDeadOrDying() || e == null) tally(level, f, r, leg, e);
                leg.foe = null;
                if (f.getTarget() != null && !f.getTarget().isAlive()) f.setTarget(null);
            }
        }
        LivingEntity t = f.getTarget();
        if (t != null && t.isAlive() && r.homeward && f.distanceToSqr(t) > HOMEWARD_FIGHT * HOMEWARD_FIGHT) {
            // Turned for home: a blaze or a ghast out at range is not stopped for (a spawner would keep the team there
            // for ever); the fireballs are turned on the way (watchTheSky), and only what comes close is fought.
            f.setTarget(null);
            leg.foe = null;
            t = null;
        }
        if (t != null && t.isAlive()) {
            leg.foe = t.getUUID();
            if (t instanceof Blaze || t instanceof Ghast) {
                if (t instanceof Blaze) drinkIfWanted(level, f, r, leg, true);
                shoot(level, f, r, leg, t);
                return true;
            }
            return f.distanceToSqr(t) < 16 * 16;
        }
        // A ghast in the sky with a line on the team: shot down (not stopped for on the way home).
        Ghast g = r.homeward ? null : nearest(level, f, Ghast.class, 40, x -> x.isAlive() && f.hasLineOfSight(x));
        if (g != null && f.countMatching(s -> s.is(Items.ARROW)) > 0) {
            if (leg.foe == null) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Ghast! Bows up!", "Ghast overhead — shoot it down!"));
            shoot(level, f, r, leg, g);
            return true;
        }
        // Something close and after the team.
        Mob m = nearest(level, f, Mob.class, 10, x -> x.isAlive() && (x instanceof WitherSkeleton || x instanceof MagmaCube || x instanceof Blaze
            || x.getTarget() instanceof VillageFolkEntity vf && r.members.contains(vf.getUUID())));
        if (m != null && NetherRuns.mayTakeOn(f, m)) {
            if (m instanceof Blaze) {
                drinkIfWanted(level, f, r, leg, true);
                shoot(level, f, r, leg, m);
            } else {
                f.setTarget(m);
                leg.foe = m.getUUID();
            }
            return true;
        }
        // A hoglin close by, and nobody hunting it: kept clear of.
        Hoglin h = nearest(level, f, Hoglin.class, 6, x -> x.isAlive() && !x.isBaby());
        if (h != null && (r.task == null || r.task.job != Job.HUNT)) {
            Vec3 away = f.position().subtract(h.position()).normalize().scale(5.0);
            f.getNavigation().moveTo(f.getX() + away.x, f.getY(), f.getZ() + away.z, 1.2D);
            f.hobbyNow = "keeping clear of a hoglin";
            return true;
        }
        return false;
    }

    /** What it fought, fallen: the run's tally. */
    static void tally(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg, @Nullable Entity e) {
        if (e == null) return;
        long key = e.getUUID().getMostSignificantBits() ^ e.getUUID().getLeastSignificantBits() ^ 0x5DEECE66DL;
        if (!r.passed.add(key)) return;                                  // two of the team at the same blaze: one kill
        BlockPos fell = e.blockPosition();                                  // its drops, to be taken up where they land
        for (int k = 0; k < 24 && fell.getY() > level.getMinBuildHeight() && level.getBlockState(fell.below()).isAir(); k++) fell = fell.below();
        PICKUP.put(r.village, fell.immutable());
        r.slain++;
        if (e instanceof Blaze) {
            r.blazes++;
            if (r.blazes == 1) r.event(f.displayNameCap() + " shot down the run's first blaze");
        } else if (e instanceof Ghast) {
            r.ghasts++;
            r.event(f.displayNameCap() + " brought down a ghast");
            FolkTalk.speak(f, "Got it! The ghast's down!");
        } else if (e instanceof Hoglin) {
            r.event("the team brought down a hoglin for its meat");
        }
        f.awardXp(3);
    }

    /**
     * Fireballs (every other tick, in the Nether): a ghast's coming at it and close is turned back, off a raised shield
     * or the flat of the blade, aimed back at the ghast that sent it; a shield is raised against one further out.
     */
    static void watchTheSky(ServerLevel level, VillageFolkEntity f, NetherRuns.Run r, NetherRuns.Leg leg) {
        List<LargeFireball> balls = level.getEntitiesOfClass(LargeFireball.class, f.getBoundingBox().inflate(10), Entity::isAlive);
        long now = level.getGameTime();
        if (balls.isEmpty()) {
            if (leg.shielding >= 0 && now - leg.shielding > 30 && f.isUsingItem() && f.getUsedItemHand() == InteractionHand.OFF_HAND) {
                f.stopUsingItem();
                leg.shielding = -1;
            }
            return;
        }
        for (LargeFireball b : balls) {
            Vec3 to = f.getEyePosition().subtract(b.position());
            double d = to.length();
            if (d < 1e-3) continue;
            if (b.getDeltaMovement().dot(to.normalize()) <= 0.02) continue;                 // not coming at it
            boolean shield = shieldUp(f, leg, now);
            if (d > 4.0 || now - leg.deflect < 10) continue;
            Entity ghast = b.getOwner();
            if (ghast != null) {
                Vec3 at = ghast.getEyePosition().subtract(f.getEyePosition());
                float yaw = (float) (Math.toDegrees(Math.atan2(at.z, at.x)) - 90.0);
                float pitch = (float) -Math.toDegrees(Math.atan2(at.y, Math.sqrt(at.x * at.x + at.z * at.z)));
                f.setYRot(yaw);
                f.setXRot(pitch);
                f.setYHeadRot(yaw);
            }
            if (b.deflect(ProjectileDeflection.AIM_DEFLECT, f, f, false)) {
                leg.deflect = now;
                r.deflected++;
                f.swing(shield ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
                level.playSound(null, f.blockPosition(), shield ? SoundEvents.SHIELD_BLOCK : SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.NEUTRAL, 1.0F, 1.0F);
                if (r.deflected == 1 || f.getRandom().nextInt(3) == 0) {
                    FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Back at you!", "Return to sender!", "Not today, ghast!"));
                }
                if (r.deflected == 1) r.event(f.displayNameCap() + " turned a ghast's fireball back " + (shield ? "off a shield" : "with the blade"));
                NetherRuns.LOG.info("[MCA-NETHER] {} turned a ghast's fireball back ({})", f.displayNameCap(), shield ? "shield" : "blade");
            }
        }
    }

    /** Its shield into the off hand and raised (against a fireball coming), if it has one. */
    static boolean shieldUp(VillageFolkEntity f, NetherRuns.Leg leg, long now) {
        ItemStack off = f.getOffhandItem();
        if (!(off.getItem() instanceof ShieldItem)) {
            var pack = f.getInventoryItems();
            for (int i = 0; i < pack.size(); i++) {
                if (!(pack.get(i).getItem() instanceof ShieldItem)) continue;
                ItemStack was = off;
                f.setItemSlot(EquipmentSlot.OFFHAND, pack.get(i));
                pack.set(i, was);
                off = f.getOffhandItem();
                break;
            }
        }
        if (!(off.getItem() instanceof ShieldItem)) return false;
        if (!f.isUsingItem()) f.startUsingItem(InteractionHand.OFF_HAND);
        leg.shielding = now;
        return true;
    }
}
