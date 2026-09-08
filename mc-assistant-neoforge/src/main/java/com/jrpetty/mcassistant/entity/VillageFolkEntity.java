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
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return;
        if (!Villages.folkOf(village).isEmpty()) return;    // somebody still lives here
        com.jrpetty.mcassistant.ChunkLoad.setLoaded(
            server, village, centre, com.jrpetty.mcassistant.VillageSpawner.LOADED_RADIUS, false);
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
        if (peekJob() != null) return;                 // already busy
        if (resting()) return;                         // off the clock for a bit
        if (movedOnFromSpentGround()) return;          // this patch is finished
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
            case DIAMOND, OBSIDIAN -> {
                // Both live in the deep, and both are a miner's business.
                // Everyone else helps by keeping the stores moving instead.
                if (stationTask() == StationTask.MINE) return false;
                if (countItems() > 0) { enqueue(Job.deposit()); return true; }
                return false;
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
        if (tickCount - searchFailTick < 1200) { roam(); return; }
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
        WorkZone zone = WorkZone.around(site, radiusFor(trade), depthFor(trade));
        setStation(site, trade);
        assignPlot(zone, patchNameFor(trade));
        setAutonomous(true);
        say("I'll take up " + trade.label + " — this ground will do for it.");
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

    private int depthFor(StationTask trade) {
        return trade == StationTask.MINE
            ? Math.max(level().getMinBuildHeight() + 8, blockPosition().getY() - 24)
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
        BlockPos from = heart.offset(
            (int) Math.round(Math.cos(angle) * 12), 0, (int) Math.round(Math.sin(angle) * 12));
        return switch (trade) {
            case FARM -> scan(from, 48, 6, radius, this::farmable);
            case WOOD -> scan(from, 64, 6, radius, this::woodland);
            case MINE -> scan(from, 48, 6, radius, this::diggable);
            // The forge belongs in the village, not out in a field.
            default -> level().getBlockState(heart).isAir() ? heart : surfaceAt(heart.getX(), heart.getZ());
        };
    }

    /** A spiral-ish coarse scan on the surface, nearest ring first. */
    @Nullable
    private BlockPos scan(BlockPos from, int radius, int stride, int plotRadius,
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
        for (AssistantEntity mate : Villages.folkOf(ownerId())) {
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
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-6, -2, -6), pos.offset(6, 2, 6))) {
            BlockState st = level().getBlockState(p);
            if (st.is(Blocks.WATER)) water = true;
            else if (st.is(BlockTags.DIRT)) soil++;
        }
        return water && soil >= 20;
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
        WorkZone zone = WorkZone.around(site, radiusFor(trade), depthFor(trade));
        setStation(site, trade);
        assignPlot(zone, patchNameFor(trade));
        setAutonomous(true);
        return true;
    }

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
        enqueue(Job.build(project));
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
                Villages.restore(level(), id, villageCentre, age, built);
            }
        }
    }
}
