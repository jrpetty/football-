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
    private int lastSpeechTick = -100000;

    public VillageFolkEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return AssistantEntity.createAttributes();
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
        if (leadIfLeader()) return;                    // the plan, said out loud
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

        Villages.Need need = Villages.nextNeed(server, village);
        if (need == null) return false;
        switch (need.task()) {
            case LOGS -> {
                say("The village is short of timber. I'll cut some.");
                enqueue(Job.gather(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.LOGS,
                    Math.min(48, Math.max(16, need.amount()))));
                enqueue(Job.deposit());
                return true;
            }
            case STONE -> {
                say("The village wants stone. I'll fetch some.");
                enqueue(Job.gather(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.STONE,
                    Math.min(64, Math.max(16, need.amount()))));
                enqueue(Job.deposit());
                return true;
            }
            case IRON -> {
                // The courier run the smelter actually needs: ore out of
                // whichever chest it is sitting in, and into the forge's.
                if (stationTask() != StationTask.SMELT
                    && countStocked(st -> st.is(net.minecraft.world.item.Items.RAW_IRON)) > 0) {
                    say("Taking this ore over to the forge.");
                    enqueue(Job.withdraw("raw iron", 32));
                    enqueue(Job.deposit());
                    return true;
                }
                say("We could do with iron. I'll go and dig.");
                enqueue(Job.gather(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.IRON,
                    Math.min(32, Math.max(8, need.amount()))));
                enqueue(Job.deposit());
                return true;
            }
            case FOOD -> {
                // Nobody but a farmer grows food, so everyone else helps by
                // making sure what HAS been grown reaches the stores.
                if (countItems() > 0) {
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

    /** The leader keeps the plan and says it out loud now and then, so the
     *  village's goal is something you can hear rather than infer. */
    private boolean leadIfLeader() {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return false;
        UUID village = ownerId();
        if (village == null) return false;
        if (Villages.leader(village) != this) return false;
        if (tickCount - lastSpeechTick < 2400) return false;
        lastSpeechTick = tickCount;
        Villages.Need need = Villages.nextNeed(server, village);
        if (need == null) return false;
        say("We're " + Villages.stage(village).label + ". What we want next is "
            + need.what() + ".");
        return false;   // saying it is not a job — carry on afterwards
    }

    // ------------------------------ belonging --------------------------------

    /** Join the settlement nearest to where we woke up, or found one here. */
    private void settle() {
        Villages.Village v = Villages.nearest(level(), blockPosition());
        if (v == null) {
            v = Villages.found(blockPosition());
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
        StationTask trade = stationTask() != StationTask.NONE
            ? stationTask() : Villages.needed(ownerId());
        BlockPos site = findSite(trade, radiusFor(trade));
        if (site == null) {
            // Nothing suitable in sight. Don't thrash: wander a little and
            // look again in a while. A folk that cannot find water yet is not
            // a folk that should stand still for ever.
            if (tickCount - searchFailTick > 1200) {
                searchFailTick = tickCount;
                say("Looking for somewhere to work as " + trade.label + ".");
            }
            roam();
            return;
        }
        WorkZone zone = WorkZone.around(site, radiusFor(trade), depthFor(trade));
        setStation(site, trade);
        assignPlot(zone, patchNameFor(trade));
        setAutonomous(true);
        say("I'll take up " + trade.label + " — this ground will do for it.");
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
        double angle = (getId() % 8) * (Math.PI / 4.0);
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
        Villages.noteProject(village, project, now);
        say("The village could do with " + article(project) + project + ". I'll see to it.");
        enqueue(Job.build(project));
    }

    private static String article(String word) {
        return "aeiou".indexOf(word.charAt(0)) >= 0 ? "an " : "a ";
    }

    // ------------------------------ persistence ------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (villageCentre != null) tag.putLong("VillageCentre", villageCentre.asLong());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("VillageCentre")) {
            this.villageCentre = BlockPos.of(tag.getLong("VillageCentre"));
            // The register lives in memory only; the first folk to load puts
            // its settlement back on the map for the rest.
            UUID id = ownerId();
            if (id != null && Villages.get(id) == null) {
                Villages.register(new Villages.Village(id, villageCentre));
            }
        }
    }
}
