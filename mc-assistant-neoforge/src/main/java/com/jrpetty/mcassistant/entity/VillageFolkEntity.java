package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Village Folk — the same hands as an assistant, with nobody telling them what
 * to do.
 *
 * <p>An assistant waits to be hired, pointed at ground and given a trade. Folk
 * do all three for themselves: they find the settlement they belong to (or
 * found one), take up whichever trade their village is short of, walk out and
 * claim ground suited to that trade, and then work it. Nothing here needs a
 * player, a wand, or a menu.
 *
 * <p>Everything BELOW that decision — how to farm, how to sink a shaft, how to
 * feed a furnace bank, the trade ladders, the claim book that stops two hands
 * reaching for the same wheat — is the assistant's, inherited whole. This class
 * is only the part that decides what to want and when to want it.
 *
 * <p>They are deliberately unhurried. The agenda is consulted once every five
 * seconds and does exactly one thing; the settlement puts up one building every
 * eight minutes at most. A village should look like it grew, not like it was
 * printed.
 */
public class VillageFolkEntity extends AssistantEntity {

    @Nullable private BlockPos villageCentre;
    private int agendaTick = -1000;
    private int searchFailTick = -100000;

    public VillageFolkEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return AssistantEntity.createAttributes();
    }

    /**
     * Folk never speak. Not a word, ever — not a greeting, not a complaint,
     * not the day's tally, not "my pack is full". A settlement is meant to be
     * something you come across and watch, and ten of them narrating their
     * every decision would fill the chat with noise nobody asked for. What
     * they are doing is legible from what they are DOING.
     */
    @Override
    protected boolean speaksInChat() { return false; }

    /** Nobody hired them, so nobody owes them a wage or a charge. They eat
     *  like anyone else — a village that cannot feed itself has failed at the
     *  one thing a village is for — but a settlement does not run on redstone
     *  and does not answer to a payroll. */
    @Override
    public boolean needsCharge() { return false; }

    /** Look all you like — you just cannot give them orders. */
    @Override
    public boolean openToAnyone() { return true; }

    @Override
    protected boolean drawsWages() { return false; }

    /**
     * When the last of a settlement dies, its chunks stop being held open.
     * The force-load ticket is taken under the VILLAGE's id, and no entity's
     * own clean-up could ever release it — so a dead village would have kept
     * eighty-one chunks ticking for the life of the world, and the id needed
     * to free them died with the last folk.
     */
    @Override
    public void remove(net.minecraft.world.entity.Entity.RemovalReason reason) {
        UUID village = ownerId();
        BlockPos centre = villageCentre;
        super.remove(reason);
        if (!reason.shouldDestroy() || village == null || centre == null) return;
        Villages.recordDeath(village);      // unloading is not dying
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return;
        // Somebody still lives here — and "here" means the roll, not the room.
        // The last LOADED folk dying while ninety-nine are asleep in unloaded
        // chunks would otherwise have wiped the settlement's age, its
        // buildings and its roll, and dropped the chunks it keeps awake.
        if (!Villages.folkOf(village).isEmpty()) return;
        if (Villages.recordedPopulation(village) > 0) return;
        com.jrpetty.mcassistant.ChunkLoad.setLoaded(
            server, village, centre,
            com.jrpetty.mcassistant.VillageSpawner.MAX_LOADED_RADIUS, false);
        Villages.forget(village);
    }

    /** Only a building that actually went up counts as built. */
    @Override
    public void noteBuilt(String structure) {
        UUID village = ownerId();
        if (village != null) Villages.noteProject(village, structure, level().getGameTime());
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide) return;
        if (tickCount - agendaTick < 100) return;   // folk think slowly, on purpose
        agendaTick = tickCount;
        agenda();
    }

    /**
     * One rung a visit, in the order a person would care about them: belong
     * somewhere, have a trade, have ground to work it on — and only once all
     * that is settled, look up and see what the village still needs building.
     */
    private void agenda() {
        if (ownerId() == null) { settle(); return; }
        if (workZone() == null) { takeUpATrade(); return; }
        mindTheRoute();                                // a carrier's round is chosen, not clicked
        if (peekJob() != null) return;                 // already busy
        if (resting()) return;                         // off the clock for a bit
        if (movedOnFromSpentGround()) return;          // this patch is finished
        if (changedTrade()) return;                    // the village lost a trade
        if (raisedAChild()) return;                    // the village grew

        if (workedOut() && lendAHand()) return;        // my trade has nothing: help
        considerVillageWork();
    }

    /**
     * The heart of a working village: a pair of hands with nothing to do in
     * its own trade does not stand there. A smelter with no ore does not wait
     * for ore to appear — it goes and fetches some, or carries what the
     * village has to where the village needs it, or cuts timber for the next
     * building. The village's plan says what is short; this turns that into
     * a job the folk can actually do.
     */
    private boolean lendAHand() {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return false;
        UUID village = ownerId();
        if (village == null) return false;

        // First, be a courier. Anything in the pack that somebody else's trade
        // wants is a delivery, and delivering beats fetching because the goods
        // already exist. The deposit run routes it to whoever needs it.
        if (countItems() > 0 && Supply.routeFor(this) != null) {
            sayRoutine("Nothing to do in my own line — running this where it's wanted.");
            enqueue(Job.deposit());
            return true;
        }

        if (tickCount - lastHelpTick < 1200) return false;   // one attempt a minute, at most
        for (Villages.Need need : Villages.needs(server, village)) {
            if (takeOn(server, need)) { lastHelpTick = tickCount; return true; }
        }
        return false;
    }

    private int lastHelpTick = -100000;

    /** Can this hand do anything about that particular want? */
    private boolean takeOn(net.minecraft.server.level.ServerLevel server, Villages.Need need) {
        switch (need.task()) {
            case COAL -> {
                enqueue(Job.gather(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.COAL,
                    Math.min(32, Math.max(8, need.amount()))));
                enqueue(Job.deposit());
                return true;
            }
            case DIAMOND -> {
                // The deep is a miner's business; everyone else helps by
                // keeping the stores moving. A mine floor set 24 blocks under
                // the hillside it was staked on sits a hundred blocks above the
                // nearest diamond, though, so the shaft has to go down before
                // the seam can be found at all — which is why no settlement
                // could ever leave the Diamond Age.
                if (stationTask() == StationTask.MINE) return deepenShaft();
                if (countItems() > 0) { enqueue(Job.deposit()); return true; }
                return false;
            }
            case OBSIDIAN -> {
                // Nothing under diamond drops obsidian, so this is a job for
                // one miner in the whole village: the one that has been given
                // the pickaxe. It is down there already; the lava is what it
                // has been walking round for weeks.
                if (stationTask() != StationTask.MINE) {
                    if (countItems() > 0) { enqueue(Job.deposit()); return true; }
                    return false;
                }
                if (countCarried(st -> st.is(net.minecraft.world.item.Items.DIAMOND_PICKAXE)) == 0) {
                    return deepenShaft();
                }
                enqueue(Job.gather(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.OBSIDIAN,
                    Math.min(16, Math.max(4, need.amount()))));
                enqueue(Job.deposit());
                return true;
            }
            case LOGS -> {
                enqueue(Job.gather(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.LOGS,
                    Math.min(48, Math.max(16, need.amount()))));
                enqueue(Job.deposit());
                return true;
            }
            case STONE -> {
                enqueue(Job.gather(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.STONE,
                    Math.min(64, Math.max(16, need.amount()))));
                enqueue(Job.deposit());
                return true;
            }
            case IRON -> {
                // No withdraw-and-redeposit "courier" here: a deposit with no
                // route picks the NEAREST chest, which is the one the ore was
                // just taken out of, so the run moved the ore in a circle. The
                // carry-what-is-wanted path above is the real courier.
                enqueue(Job.gather(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.IRON,
                    Math.min(32, Math.max(8, need.amount()))));
                enqueue(Job.deposit());
                return true;
            }
            case FOOD -> {
                // Get what has been grown into the stores first — it exists
                // already, which beats anything that has to be made.
                if (countItems() > 0) {
                    enqueue(Job.deposit());
                    return true;
                }
                // Then hunt. A field takes days; a herd on the doorstep is
                // meat this afternoon, and a hungry village cannot wait for
                // wheat. Farmers stay on the field — the crop is the long
                // answer and somebody has to be planting it.
                if (stationTask() != StationTask.FARM && adultAnimalsNearby(24) >= 2) {
                    enqueue(Job.hunt(null, 3));
                    enqueue(Job.deposit());
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }


    // ------------------------------ belonging --------------------------------

    /** Join the settlement nearest to where we woke up, or found one here. */
    private void settle() {
        Villages.Village v = Villages.nearest(level(), blockPosition());
        if (v == null) {
            v = Villages.found(level(), blockPosition());
            say("There's good ground here. This'll do for a village.");
        }
        this.villageCentre = v.centre();
        adoptVillage(v.id());
        setHome(v.centre());
        setAutonomous(true);
    }

    // ------------------------------ a trade ----------------------------------

    /**
     * Take up whatever the village is short of, then go and find ground for
     * it. The trade is chosen first and the ground second, because a farmer
     * needs water and a miner needs stone — what you are decides where you go.
     */
    private void takeUpATrade() {
        // Looking for ground is expensive and the answer rarely changes from
        // one second to the next. Once a minute is plenty, and it stops every
        // folk in a village re-running a quarter-million block reads on the
        // same tick for ever.
        // Staggered by entity id: a hundred folk all failing to find ground on
        // the same tick, once a minute, is a hundred searches in one tick.
        if (tickCount - searchFailTick < 1200 + (getId() % 12) * 100) { roam(); return; }
        searchFailTick = tickCount;

        // Claim the trade BEFORE going to look for ground. The village works
        // out what it is short of from what its folk ARE, so a folk that has
        // decided but not yet settled used to be invisible — and every folk
        // in the village would pick the same trade, look for the same ground,
        // and fail together, for ever.
        if (stationTask() == StationTask.NONE) {
            setStation(blockPosition(), Villages.needed(ownerId()));
        }
        StationTask trade = stationTask();
        BlockPos site = findSite(trade, radiusFor(trade));
        if (site == null) {
            // This trade has nowhere to work HERE. Rather than stand in a
            // field looking for water that does not exist, try the next thing
            // the village wants — a settlement in a desert should end up
            // quarrying and cutting, not waiting for a farm it cannot have.
            triedTrades++;
            if (triedTrades >= 3) {
                triedTrades = 0;
                StationTask fallback = nextTradeAfter(trade);
                if (fallback != trade) setStation(blockPosition(), fallback);
            }
            roam();
            return;
        }
        triedTrades = 0;
        WorkZone zone = WorkZone.around(site, radiusFor(trade), depthFor(trade, site));
        setStation(site, trade);
        assignPlot(zone, patchNameFor(trade));
        setAutonomous(true);
    }

    private int triedTrades;

    /** The next trade worth trying when this one has nowhere to work. Ordered
     *  by how little ground it is fussy about: stone and timber are almost
     *  everywhere, a farm needs water, a forge needs nothing at all. */
    private StationTask nextTradeAfter(StationTask trade) {
        StationTask[] order = { StationTask.WOOD, StationTask.MINE,
                                StationTask.FARM, StationTask.SMELT };
        for (int i = 0; i < order.length; i++) {
            if (order[i] == trade) return order[(i + 1) % order.length];
        }
        return StationTask.WOOD;
    }

    private static int radiusFor(StationTask trade) {
        return switch (trade) {
            case FARM -> 8;      // a field you can actually keep watered
            case WOOD -> 14;     // woodland is worked wide
            case MINE -> 8;
            default -> 6;        // the smelter works at its furnaces
        };
    }

    private int depthFor(StationTask trade, BlockPos site) {
        return trade == StationTask.MINE
            ? Math.max(level().getMinBuildHeight() + 8, site.getY() - 24)
            : WorkZone.DEFAULT_DEPTH;
    }

    private String patchNameFor(StationTask trade) {
        String base = switch (trade) {
            case FARM -> "Home Fields";
            case WOOD -> "East Wood";
            case MINE -> "The Pit";
            case SMELT -> "The Forge";
            default -> "The Commons";
        };
        // Two farms in one village should not share a name.
        int same = 0;
        for (AssistantEntity mate : Villages.folkOf(ownerId())) {
            if (mate != this && mate.stationTask() == trade && mate.workZone() != null) same++;
        }
        return same == 0 ? base : base + " " + (same + 1);
    }

    // ------------------------------ finding ground ---------------------------

    /**
     * Ground that suits the trade, searched outward from the village so a
     * settlement stays a settlement rather than scattering. Coarse on purpose:
     * this runs at most once every five seconds and only while a folk is
     * looking for work.
     */
    @Nullable
    private BlockPos findSite(StationTask trade, int radius) {
        BlockPos heart = villageCentre != null ? villageCentre : blockPosition();
        // Everybody searching outward from the same point finds the same
        // ground. Each folk starts its look in its own direction instead, so a
        // village fans out around its centre rather than piling into one
        // corner of it — which is precisely how two miners ended up trying to
        // dig the same hill.
        double angle = ((getId() + searchBearing) % 8) * (Math.PI / 4.0);
        // ...and it starts that look FURTHER OUT the bigger the village is.
        // Ten plots fit around one hillside; twenty do not, and the ground is
        // claimed whole and never shared — so past ten folk every search that
        // starts at the same place finds nothing but its neighbours' fields,
        // gives up, and the newcomer stands in the square for ever with no
        // trade. Widening the SCAN instead would have squared its cost, and
        // that scan already reads eight hundred blocks per candidate; moving
        // where it starts costs nothing at all.
        int reach = com.jrpetty.mcassistant.village.VillageMath
            .searchReach(Villages.headcount(ownerId()));
        BlockPos from = heart.offset(
            (int) Math.round(Math.cos(angle) * reach), 0,
            (int) Math.round(Math.sin(angle) * reach));
        return switch (trade) {
            // One scan distance for every trade. The ring a village keeps
            // awake and the range its stores are read over are both derived
            // from how far a plot can end up, and neither can be reasoned
            // about while the woodcutter quietly reaches a third further than
            // everybody else.
            case FARM -> scan(from, SCAN, 6, radius, this::farmable);
            case WOOD -> scan(from, SCAN, 6, radius, this::woodland);
            case MINE -> scan(from, SCAN, 6, radius, this::diggable);
            // A pen goes where the animals already are and a jetty goes on
            // water — both were staking the village square, where a rancher
            // found nothing to breed and a fisher nothing to cast into, and
            // both trades were a silent no-op for the life of the settlement.
            case RANCH -> scan(from, SCAN, 6, radius, this::pasture);
            case FISH -> scan(from, SCAN, 6, radius, this::fishable);
            // The indoor trades belong in the village rather than out in a
            // field — but not all three in the same square. Each takes its own
            // corner of the middle, on its own bearing, so the forge, the
            // storeroom and the carrier's post are neighbours instead of one
            // pile.
            default -> {
                BlockPos near = surfaceAt(
                    heart.getX() + (int) Math.round(Math.cos(angle) * 6),
                    heart.getZ() + (int) Math.round(Math.sin(angle) * 6));
                yield near != null && !taken(near, radius) ? near
                    : surfaceAt(heart.getX(), heart.getZ());
            }
        };
    }

    /** A spiral-ish coarse scan on the surface, nearest ring first. */
    @Nullable
    private BlockPos scan(BlockPos from, int radius, int stride, int plotRadius,
                          java.util.function.Predicate<BlockPos> good) {
        // Fetched ONCE, not once per candidate. The overlap test runs for
        // every square of every ring, and in a town of a hundred that was
        // building a hundred-element list a few hundred times per search.
        neighbours = Villages.folkOf(ownerId());
        try {
            return scanRings(from, radius, stride, plotRadius, good);
        } finally {
            neighbours = null;
        }
    }

    @Nullable private java.util.List<AssistantEntity> neighbours;

    @Nullable
    private BlockPos scanRings(BlockPos from, int radius, int stride, int plotRadius,
                               java.util.function.Predicate<BlockPos> good) {
        for (int r = stride; r <= radius; r += stride) {
            for (int dx = -r; dx <= r; dx += stride) {
                for (int dz = -r; dz <= r; dz += stride) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // ring only
                    BlockPos p = surfaceAt(from.getX() + dx, from.getZ() + dz);
                    if (p == null || taken(p, plotRadius)) continue;
                    if (good.test(p)) return p;
                }
            }
        }
        return null;
    }

    @Nullable
    private BlockPos surfaceAt(int x, int z) {
        if (!level().isLoaded(new BlockPos(x, level().getSeaLevel(), z))) return null;
        int y = level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level().getMinBuildHeight() + 1) return null;
        return new BlockPos(x, y, z);
    }

    /**
     * Would a plot HERE tread on anybody else's? Testing the centre alone was
     * not enough — two plots can overlap heavily without either centre being
     * inside the other, which is how a lumberjack ended up felling the trees
     * around a farmer's field. The whole prospective footprint is tested, with
     * a couple of blocks of elbow room on top.
     */
    private boolean taken(BlockPos pos, int plotRadius) {
        WorkZone mine = WorkZone.around(pos, plotRadius + 2, WorkZone.DEFAULT_DEPTH);
        // Ground we are deliberately leaving counts as somebody else's.
        if (avoidHere != null && mine.overlaps(avoidHere)) return true;
        java.util.List<AssistantEntity> crew =
            neighbours != null ? neighbours : Villages.folkOf(ownerId());
        for (AssistantEntity mate : crew) {
            if (mate == this) continue;
            WorkZone theirs = mate.workZone();
            if (theirs != null && mine.overlaps(theirs)) return true;
        }
        return false;
    }

    /** Water to hand and soft ground around it: a field, in other words. */
    private boolean farmable(BlockPos pos) {
        boolean water = false;
        int soil = 0;
        // Reads every block in an 13x5x13 box, and did so to the last block
        // even once the answer was settled. A hundred folk each testing a few
        // hundred candidates a minute made that the most expensive thing in
        // the mod by a wide margin. Stop as soon as it is known.
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-6, -2, -6), pos.offset(6, 2, 6))) {
            BlockState st = level().getBlockState(p);
            if (st.is(Blocks.WATER)) water = true;
            else if (st.is(BlockTags.DIRT)) soil++;
            if (water && soil >= 20) return true;
        }
        return false;
    }

    /** Livestock on the hoof: a herd worth putting a fence round. */
    private boolean pasture(BlockPos pos) {
        return level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
            new net.minecraft.world.phys.AABB(
                pos.getX() - 8, pos.getY() - 5, pos.getZ() - 8,
                pos.getX() + 8, pos.getY() + 5, pos.getZ() + 8),
            a -> a.isAlive() && !a.isBaby()).size() >= 2;
    }

    /** Open water, and enough of it to be worth a rod. */
    private boolean fishable(BlockPos pos) {
        int water = 0;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-6, -3, -6), pos.offset(6, 1, 6))) {
            if (level().getBlockState(p).is(Blocks.WATER) && ++water >= 12) return true;
        }
        return false;
    }

    /** Standing timber, and enough of it to be worth walking to. */
    private boolean woodland(BlockPos pos) {
        int logs = 0;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-6, -2, -6), pos.offset(6, 6, 6))) {
            if (level().getBlockState(p).is(BlockTags.LOGS)) logs++;
            if (logs >= 12) return true;
        }
        return false;
    }

    /** Stone under the boots, and not the middle of somebody's field. */
    private boolean diggable(BlockPos pos) {
        int stone = 0;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-3, -6, -3), pos.offset(3, -1, 3))) {
            if (level().getBlockState(p).is(BlockTags.BASE_STONE_OVERWORLD)) stone++;
            if (stone >= 24) return true;
        }
        return false;
    }

    // ------------------------------ the carrier's round ---------------------

    private int routeTick = -100000;

    /**
     * A hauler's round, chosen rather than clicked. There is no wand and no
     * player in a settlement, and the freight checklist will not let a carrier
     * move so much as a loaf until both ends of a route stand — so a village's
     * carrier would have been a permanent no-op.
     *
     * <p>The round a carrier would pick for itself: load wherever the goods
     * are actually piling up — the field chest, the woodpile, the mine head —
     * and unload at the storehouse in the middle. Re-read every couple of
     * minutes, because the fullest chest in a working village is a different
     * chest by the afternoon.
     */
    private void mindTheRoute() {
        if (stationTask() != StationTask.HAUL) return;
        if (tickCount - routeTick < 2400) return;
        routeTick = tickCount;
        BlockPos heart = villageCentre;
        if (heart == null) return;

        // The depot is whatever chest sits closest to the middle. A tight look:
        // the storehouse is at the heart by definition.
        BlockPos depot = null;
        double depotDist = Double.MAX_VALUE;
        for (ZoneChests.Found f : ZoneChests.around(level(), heart, 32, 10)) {
            if (!ZoneChests.isStashable(f)) continue;      // a furnace is not a depot
            double d = f.pos().distSqr(heart);
            if (d < depotDist) { depotDist = d; depot = f.pos(); }
        }
        // The load is the fullest chest near THIS carrier's own post, not the
        // fullest in the settlement. A town of a hundred has several carriers
        // and reaches two hundred blocks out; one shared answer would have put
        // every one of them on the same chest and left three quarters of the
        // place uncollected — and a scan of the whole town, per carrier, every
        // two minutes, is a bill nobody wants to pay either. Each has its own
        // post on its own bearing, so a sector each falls out of it.
        BlockPos post = stationPos() != null ? stationPos() : blockPosition();
        BlockPos load = null;
        int fullest = 0;
        for (ZoneChests.Found f : ZoneChests.around(level(), post, 64, 12)) {
            if (!ZoneChests.isStashable(f)) continue;
            if (f.pos().equals(depot)) continue;           // never haul the depot to itself
            int held = stockIn(f);
            if (held > fullest) { fullest = held; load = f.pos(); }
        }
        // One chest in the whole village is a village with nothing to carry
        // between; leave the route alone and let the hand lend itself out.
        if (depot != null && load != null) setHaulRoute(load, depot);
    }

    /** How much is actually sitting in this chest. */
    private int stockIn(ZoneChests.Found f) {
        if (!(f.blockEntity() instanceof net.minecraft.world.Container box)) return 0;
        int n = 0;
        for (int i = 0; i < box.getContainerSize(); i++) n += box.getItem(i).getCount();
        return n;
    }

    /**
     * Sink the same shaft properly. A village mine is staked on the surface
     * and floored 24 blocks under it, which is the right depth for stone, coal
     * and iron and nowhere near deep enough for anything else. An experienced
     * miner whose village is asking for diamonds re-marks its own plot down to
     * the bedrock and keeps digging — same ground, same claim, a real shaft.
     */
    private boolean deepenShaft() {
        WorkZone zone = workZone();
        if (zone == null || veteranLevel() < 20) return false;
        int floor = level().getMinBuildHeight() + 8;
        if (zone.depth() <= floor + 4) return false;          // already down there
        assignPlot(WorkZone.around(zone.center(), zone.radius(), floor),
            patchNameFor(StationTask.MINE));
        setAutonomous(true);
        return true;
    }

    /** A short stroll while looking for somewhere to settle to work. */
    private void roam() {
        if (!getNavigation().isDone()) return;
        BlockPos from = villageCentre != null ? villageCentre : blockPosition();
        int x = from.getX() + getRandom().nextInt(49) - 24;
        int z = from.getZ() + getRandom().nextInt(49) - 24;
        BlockPos to = surfaceAt(x, z);
        if (to != null) walkTo(to, 1.0D);
    }

    // ------------------------------ moving on, and knocking off --------------

    /**
     * A worked-out patch is not a patch. A miner whose seam is exhausted, or a
     * woodcutter left standing in stumps, does not walk the same empty ground
     * for ever — it lets the claim go and finds fresh ground. This is the only
     * reason a village can keep growing past its first hillside.
     *
     * <p>Only for the trades whose ground genuinely runs out. A field does not
     * run out; it comes round again.
     */
    private boolean movedOnFromSpentGround() {
        StationTask trade = stationTask();
        if (trade != StationTask.MINE && trade != StationTask.WOOD) return false;
        if (!workedOut()) { spentSince = 0; return false; }
        if (spentSince == 0) { spentSince = tickCount; return false; }
        // Three solid minutes of finding nothing: long enough that a slow
        // patch is not abandoned, short enough that nobody stands in a
        // clearing all afternoon.
        if (tickCount - spentSince < 3600) return false;
        spentSince = 0;
        // Look somewhere ELSE. findSite is deterministic and a mined-out patch
        // still looks like perfectly good stone from the surface, so searching
        // the same way returns the same spent ground every time. Turning the
        // bearing and standing further off is what actually moves them on.
        avoidHere = workZone();
        searchBearing++;
        BlockPos site = findSite(trade, radiusFor(trade));
        avoidHere = null;
        if (site == null) return false;
        WorkZone zone = WorkZone.around(site, radiusFor(trade), depthFor(trade, site));
        setStation(site, trade);
        assignPlot(zone, patchNameFor(trade));
        setAutonomous(true);
        return true;
    }

    private int breedTick = -100000;

    /**
     * How a settlement grows its own people.
     *
     * <p>Everything else about these villages scales with headcount — the
     * watch at eleven, the carrier at twelve, the storekeeper at thirteen, the
     * pen at fourteen — and none of it could ever happen, because a village
     * was founded with eight to twelve folk and had no way on earth to reach
     * thirteen. A settlement could only ever shrink. Every loss was permanent
     * and every specialist trade was a rung nobody would ever stand on.
     *
     * <p>The bar is deliberately low, because a village that has to jump
     * through hoops to grow is a village that does not grow: BE FED, and BE IN
     * WORK. Two folk who are both eating and both doing a job have a chance of
     * raising a child between them, and it costs them the food it takes — so a
     * settlement that cannot feed itself stops growing on its own, without
     * anybody having to write a rule about it. That is the whole check.
     */
    private boolean raisedAChild() {
        if (!com.jrpetty.mcassistant.AssistantConfig.villageBreeding()) return false;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return false;
        UUID village = ownerId();
        if (village == null || villageCentre == null) return false;
        if (tickCount - breedTick < 6000) return false;          // five minutes apiece
        breedTick = tickCount;

        if (stationTask() == StationTask.NONE) return false;      // in work
        if (countFood() < 2) return false;                        // and fed
        if (Villages.headcount(village)
            >= com.jrpetty.mcassistant.AssistantConfig.villageGrowthCap()) {
            return false;
        }
        // Somebody to raise it with, near enough to count as living together,
        // in the same trade-less sense: fed, in work, and not this one.
        VillageFolkEntity partner = null;
        for (AssistantEntity mate : Villages.folkOf(village)) {
            if (mate == this || !(mate instanceof VillageFolkEntity other)) continue;
            if (other.stationTask() == StationTask.NONE) continue;
            if (other.countFood() < 2) continue;
            if (other.tickCount - other.breedTick < 6000) continue;
            if (other.distanceToSqr(this) > 12.0 * 12.0) continue;
            partner = other;
            break;
        }
        if (partner == null) return false;
        // A chance, not a certainty. Two fed hands in work who happen to be
        // stood together, once every five minutes, one time in three: a
        // settlement fills out over a few in-game days rather than doubling
        // overnight.
        if (getRandom().nextInt(3) != 0) return false;

        // It costs what it costs. Both parents put the food in, which is what
        // makes a hungry village stop growing by itself.
        if (removeMatching(s -> s.get(net.minecraft.core.component.DataComponents.FOOD) != null, 2) < 2) {
            return false;
        }
        partner.removeMatching(
            s -> s.get(net.minecraft.core.component.DataComponents.FOOD) != null, 2);
        partner.breedTick = partner.tickCount;

        VillageFolkEntity child = com.jrpetty.mcassistant.McAssistantMod.VILLAGE_FOLK.get()
            .create(server);
        if (child == null) return false;
        child.moveTo(getX(), getY(), getZ(), getYRot(), 0.0F);
        child.rename(freeName(village));
        // Less than its parents spent on it — see childKit. A village that
        // could breed its way to a full larder would never have to farm.
        com.jrpetty.mcassistant.VillageSpawner.childKit(child);
        // BORN INTO THIS VILLAGE, not left to go and look for one. A newborn
        // settles by finding the nearest settlement within ninety-six blocks,
        // and it is born wherever its parents were STANDING — which is out on
        // their plot, which as a village grows is most of ninety-six blocks
        // from the heart already. A child born a step too far would have
        // FOUNDED A RIVAL VILLAGE on top of its own parents, split the
        // headcount, and set both halves back to the Wood Age. It is given the
        // village it was born into, and its agenda picks up from there.
        child.villageCentre = villageCentre;
        child.adoptVillage(village);
        child.setHome(villageCentre);
        child.setAutonomous(true);
        server.addFreshEntity(child);
        Villages.recordBirth(village);
        // The settlement is bigger than it was, so it keeps more ground awake.
        // Re-taken at the new radius, which is a superset of the old one, so
        // nothing is dropped and nothing is doubled.
        com.jrpetty.mcassistant.ChunkLoad.setLoaded(server, village, villageCentre,
            com.jrpetty.mcassistant.VillageSpawner.loadedRadiusFor(
                Villages.headcount(village)), true);
        // Choosing whichever trade the village is now short of, and finding
        // ground for it, happen on the child's own agenda a moment from now —
        // exactly as they did for its parents.
        return true;
    }

    /** A name nobody in this village is using yet. */
    private String freeName(UUID village) {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            used.add(a.getAssistantName().toLowerCase());
        }
        for (String candidate : Names.POOL) {
            if (!used.contains(candidate.toLowerCase())) return candidate;
        }
        return "folk_" + (used.size() + 1);
    }

    private int tradeCheckTick = -100000;

    /**
     * A trade is not for life. A settlement decides its shape when its people
     * arrive and then never revisits it — so the day its only smelter falls
     * down a hole, the forge goes cold for good and the village sits at the
     * age that forge was meant to carry it through. There are no newcomers to
     * fill the gap: the ten who founded the place are the ten it has.
     *
     * <p>The rule is deliberately narrow. A hand only re-badges when its OWN
     * trade has more people in it than the village's shape calls for AND some
     * other trade has nobody left at all — so a spare farmer picks up the
     * forge, and a village never strips a working trade to staff another. It
     * finds the ground before it gives up the old plot, so a folk can never
     * end up between trades with nowhere to stand.
     */
    private boolean changedTrade() {
        UUID village = ownerId();
        if (village == null) return false;
        if (tickCount - tradeCheckTick < 6000) return false;      // once every five minutes
        tradeCheckTick = tickCount;
        StationTask mine = stationTask();
        StationTask vacancy = Villages.vacancy(village);
        if (vacancy == null || vacancy == mine) return false;
        if (!Villages.overStaffed(village, mine)) return false;

        avoidHere = workZone();          // do not simply re-stake my own field
        BlockPos site = findSite(vacancy, radiusFor(vacancy));
        avoidHere = null;
        if (site == null) return false;
        setStation(site, vacancy);
        assignPlot(WorkZone.around(site, radiusFor(vacancy), depthFor(vacancy, site)),
            patchNameFor(vacancy));
        setAutonomous(true);
        return true;
    }

    /** How far a ground search reaches out from where it starts. */
    private static final int SCAN = com.jrpetty.mcassistant.village.VillageMath.SCAN;

    private int spentSince;
    private int searchBearing;
    @Nullable private WorkZone avoidHere;

    /**
     * Nobody works every waking hour. After a long stretch a folk knocks off
     * for a minute or two — wanders back toward the middle of the village and
     * stands about — before going back to it. It costs a little output and
     * buys the thing that makes a settlement look inhabited rather than
     * operated: people who are sometimes just there.
     */
    /**
     * Off the clock, and the work brain is told so. The agenda only PLANS
     * work; the station brain inherited from the assistant is what actually
     * swings the hoe, and it runs from the tick regardless — so until this
     * was wired through, a folk on a break carried on working and the break
     * was a thing you could only see in the code.
     */
    @Override
    protected boolean onBreak() {
        return tickCount < restUntil;
    }

    private boolean resting() {
        if (tickCount < restUntil) {
            if (getNavigation().isDone() && villageCentre != null
                && villageCentre.distSqr(blockPosition()) > 64.0) {
                walkTo(villageCentre, 0.9D);
            }
            return true;
        }
        if (tickCount - lastRest < 12000) return false;   // ten minutes on the job
        lastRest = tickCount;
        restUntil = tickCount + 1200 + getRandom().nextInt(1200);
        return true;
    }

    private int restUntil;
    private int lastRest;

    // ------------------------------ the village's work -----------------------

    /**
     * Once a folk has its own ground in hand, it starts noticing what the
     * settlement itself is missing. One project at a time, one folk at a time,
     * and a long wait between them — this is the part that must never feel
     * like a construction crew descending on a field.
     */
    private void considerVillageWork() {
        UUID village = ownerId();
        if (village == null || villageCentre == null) return;
        if (peekJob() != null || !getNavigation().isDone()) return;
        long now = level().getGameTime();
        if (!Villages.projectDue(village, now)) return;
        String project = Villages.nextProject(village);
        if (project == null) return;
        // Only a folk standing near the village heart takes the job on — the
        // buildings go up where people live, not wherever the volunteer was.
        if (villageCentre.distSqr(blockPosition()) > 32.0 * 32.0) return;
        // Only the ATTEMPT is recorded here, which is what paces the projects.
        // Whether it actually went up is reported by the build itself — this
        // used to mark the storehouse "built" the moment somebody set off to
        // build it, so a village that could not find the timber ticked the job
        // off its list anyway and never built it at all.
        Villages.noteAttempt(village, now);
        // Load up FIRST. The builder places real items out of its own pack —
        // no cheating — and nothing was putting them there, so every volunteer
        // walked to the site empty-handed, read out a list of what it still
        // needed and gave up on the spot. No village ever built anything, which
        // means no village ever left the Wood Age either.
        if (!stockedFor(project)) return;
        enqueue(Job.build(project));
    }

    /** Everything a shell is made of, and the fixtures that go inside it. */
    private static boolean buildStock(net.minecraft.world.item.ItemStack s) {
        return com.jrpetty.mcassistant.entity.goal.BuildGoal.isBuildingBlock(s)
            || s.is(net.minecraft.world.item.Items.CHEST)
            || s.is(net.minecraft.world.item.Items.FURNACE)
            || s.is(net.minecraft.world.item.Items.CRAFTING_TABLE)
            || s.is(net.minecraft.world.item.Items.LADDER)
            || s.is(net.minecraft.world.item.Items.TORCH)
            || s.is(net.minecraft.world.item.Items.GLASS);
    }

    /** Chests, furnaces and benches this blueprint blocks on, in that order. */
    private static int[] fixturesFor(String project) {
        return switch (project) {
            case "storage" -> new int[]{ 4, 0, 0 };
            case "smeltery" -> new int[]{ 2, 3, 1 };
            case "workshop", "house" -> new int[]{ 1, 1, 1 };
            default -> new int[]{ 0, 0, 0 };
        };
    }

    /** Roughly what the shell alone costs, so a volunteer does not set off
     *  with a handful of planks for a building that wants a hundred. */
    private static int blocksFor(String project) {
        return switch (project) {
            case "house" -> 120;
            case "wall", "platform", "pen" -> 40;
            default -> 90;
        };
    }

    /**
     * Fill the pack from the village stores, and make up whatever fixture the
     * blueprint is short of. Returns false when the settlement genuinely does
     * not have the materials yet — the attempt is still recorded, so the next
     * try is paced rather than hammered, and the gather plan will have moved
     * timber and stone into the stores by then.
     */
    private boolean stockedFor(String project) {
        BlockPos heart = villageCentre;
        if (heart == null) return false;
        drawFrom(heart, VillageFolkEntity::buildStock, 384, 48);

        int[] want = fixturesFor(project);
        int chests = countCarried(s -> s.is(net.minecraft.world.item.Items.CHEST));
        int furnaces = countCarried(s -> s.is(net.minecraft.world.item.Items.FURNACE));
        int benches = countCarried(s -> s.is(net.minecraft.world.item.Items.CRAFTING_TABLE));
        // One craft a visit: the planner queues real jobs, and a build that
        // needs three furnaces gets them over three visits rather than fighting
        // over one pack of cobble.
        if (chests < want[0] && craftNow("chest", want[0] - chests)) return false;
        if (furnaces < want[1] && craftNow("furnace", want[1] - furnaces)) return false;
        if (benches < want[2] && craftNow("crafting_table", want[2] - benches)) return false;
        if (chests < want[0] || furnaces < want[1] || benches < want[2]) return false;

        return countCarried(com.jrpetty.mcassistant.entity.goal.BuildGoal::isBuildingBlock)
            >= blocksFor(project);
    }

    // ------------------------------ persistence ------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (villageCentre != null) tag.putLong("VillageCentre", villageCentre.asLong());
        UUID village = ownerId();
        if (village != null) {
            // The settlement's own progress rides on its people. The register
            // is memory-only, so without this a village that had reached the
            // Iron Age came back from a restart as a camp and set about
            // building the storehouse it already had.
            tag.putString("VillageAge", Villages.ageOf(village).name());
            net.minecraft.nbt.ListTag built = new net.minecraft.nbt.ListTag();
            for (String s : Villages.builtList(village)) {
                built.add(net.minecraft.nbt.StringTag.valueOf(s));
            }
            tag.put("VillageBuilt", built);
            // The roll rides on its people, the same as the age and the
            // buildings do. Without it a restart forgets how many live here
            // and the place sizes its larder for whoever happens to be loaded.
            tag.putInt("VillagePop", Villages.recordedPopulation(village));
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("VillageCentre")) {
            this.villageCentre = BlockPos.of(tag.getLong("VillageCentre"));
            // The register lives in memory only; the first folk to load puts
            // its settlement back on the map for the rest — age, buildings
            // and all.
            UUID id = ownerId();
            if (id != null) {
                Villages.Age age = Villages.Age.WOOD;
                try {
                    if (tag.contains("VillageAge")) age = Villages.Age.valueOf(tag.getString("VillageAge"));
                } catch (IllegalArgumentException ignored) { }
                java.util.List<String> built = new java.util.ArrayList<>();
                for (net.minecraft.nbt.Tag t : tag.getList("VillageBuilt", net.minecraft.nbt.Tag.TAG_STRING)) {
                    built.add(t.getAsString());
                }
                Villages.restore(level(), id, villageCentre, age, built,
                    tag.getInt("VillagePop"));
            }
        }
    }
}
