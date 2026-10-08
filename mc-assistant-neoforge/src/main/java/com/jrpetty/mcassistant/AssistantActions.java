package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.WorkZone;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * Every order a player can give a specialist, in one place.
 *
 * There are two ways in — the container screen you get by right-clicking a bot,
 * and the Orders screen you get by looking at one and pressing the orders key —
 * and both funnel through here, so an order behaves identically whichever route
 * it took and there is only ever one copy of the logic to keep correct.
 *
 * Chat is not, and will not be, one of those routes.
 */
public final class AssistantActions {

    private AssistantActions() {}

    // Shared ids. The container menu's button ids are these same numbers.
    public static final int STOP = 0;
    public static final int FOLLOW = 1;
    public static final int STAY = 2;
    public static final int GUARD = 3;
    public static final int STASH = 4;
    public static final int COME = 5;
    public static final int JOB_PREV = 6;
    public static final int JOB_NEXT = 7;
    public static final int WORK = 8;
    public static final int DEPTH_DOWN = 9;
    public static final int DEPTH_UP = 10;
    public static final int SET_DROPOFF = 11;
    public static final int ZONE_HERE = 12;
    public static final int ZONE_SHRINK = 13;
    public static final int ZONE_GROW = 14;
    public static final int ZONE_SHOW = 15;
    // Orders-screen extras.
    public static final int GO_HOME = 16;
    public static final int OPEN_PACK = 17;
    public static final int REPORT = 18;
    public static final int ROSTER = 19;
    public static final int CYCLE_SHIFT = 20;
    public static final int CLAIM_BED = 21;
    public static final int CYCLE_BRANCH = 22;
    public static final int SAVE_PRESET = 23;
    public static final int USE_PRESET = 24;
    // Depth presets: straight to the strata that matter, not eight blocks at a time.
    public static final int DEPTH_SURFACE = 25;
    public static final int DEPTH_IRON = 26;
    public static final int DEPTH_DIAMOND = 27;
    // The level-30 perk, chosen by click on the record sheet.
    public static final int PERK_SWIFT = 30;
    public static final int PERK_TOUGH = 31;
    public static final int PERK_PORTER = 32;
    // Right-clicking a patch on the map cycles its name through Names.PATCHES.
    public static final int PATCH_NAME_NEXT = 33;
    public static final int ESCORT_NEXT = 34;    // guard: shadow the next crewmate
    public static final int QUIET_TOGGLE = 35;   // routine chatter on/off
    public static final int QUARRY_TOGGLE = 36;  // miner: terraced levels vs one gallery
    public static final int STANCE_NEXT = 37;    // guard: auto / ranged / melee
    public static final int CARRY_NEXT = 38;     // how full the pack gets before banking

    /** Naming. Nothing in this mod is typed, so the order carries an INDEX into
     *  the shared name pool rather than a string: the client can only ask for a
     *  name that already exists in common code, and the wire format is unchanged. */
    public static final int NAME_BASE = 1000;

    /** Run an order. Returns false for an unknown id. */
    public static boolean apply(AssistantEntity a, Player player, int action) {
        if (!a.isOwner(player)) return false;
        if (action >= NAME_BASE) {
            int index = action - NAME_BASE;
            if (!com.jrpetty.mcassistant.entity.Names.validIndex(index)) return false;
            String picked = com.jrpetty.mcassistant.entity.Names.at(index);
            if (com.jrpetty.mcassistant.entity.Names.inUse(player.getUUID(), picked)) {
                a.say("One of us is already called " + picked + " — pick another.");
                return true;
            }
            String was = a.displayNameCap();
            a.rename(picked);
            a.say("I was " + was + "; I'm " + a.displayNameCap() + " now.");
            return true;
        }
        switch (action) {
            case STOP -> a.requestStop();
            // Calling a specialist over (or parking it) must take it OFF the job,
            // or the work brain re-queues the same task seconds later and it
            // walks straight back to its patch.
            case FOLLOW -> {
                a.clearQueue();
                a.setAutonomous(false);
                a.setMode(AssistantEntity.Mode.FOLLOW);
                a.say("Following you — press Work to send me back to the job.");
            }
            case STAY -> {
                a.clearQueue();
                a.setAutonomous(false);
                a.setMode(AssistantEntity.Mode.STAY);
                a.say("Holding here — press Work to send me back to the job.");
            }
            case GUARD -> {
                a.clearQueue();
                a.setAutonomous(false);
                a.setMode(AssistantEntity.Mode.GUARD);
                a.say("Guarding you.");
            }
            case STASH -> a.requestDeposit();
            case COME -> {
                a.clearQueue();
                a.setAutonomous(false);
                a.setMode(AssistantEntity.Mode.FOLLOW);
                a.getNavigation().moveTo(player, 1.25D);
                a.say("Coming.");
            }
            case JOB_PREV -> cycleJob(a, -1);
            case JOB_NEXT -> cycleJob(a, 1);
            case WORK -> toggleWork(a);
            case DEPTH_DOWN -> adjustDepth(a, -8);
            case DEPTH_UP -> adjustDepth(a, 8);
            case DEPTH_SURFACE -> presetDepth(a, Integer.MIN_VALUE);
            case DEPTH_IRON -> presetDepth(a, 16);
            case DEPTH_DIAMOND -> presetDepth(a, -54);
            case PATCH_NAME_NEXT -> a.cyclePatchName();
            case ESCORT_NEXT -> a.cycleEscort();
            case QUIET_TOGGLE -> a.toggleQuiet();
            case QUARRY_TOGGLE -> a.toggleQuarry();
            case STANCE_NEXT -> a.cycleStance();
            case CARRY_NEXT -> a.cycleCarry();
            case PERK_SWIFT -> a.choosePerk(AssistantEntity.Perk.SWIFT);
            case PERK_TOUGH -> a.choosePerk(AssistantEntity.Perk.TOUGH);
            case PERK_PORTER -> a.choosePerk(AssistantEntity.Perk.PORTER);
            case SET_DROPOFF -> {
                a.setHome(player.blockPosition());
                a.say("Drop-off set here — I'll bring every load to this spot.");
            }
            case ZONE_HERE -> {
                WorkZone old = a.workZone();
                WorkZone here = WorkZone.around(player.blockPosition(),
                    old != null ? old.radius() : 12,
                    old != null ? old.depth() : WorkZone.DEFAULT_DEPTH);
                // Every way of marking ground feeds the one plot book.
                ZonePresets.Preset staked = ZonePresets.stake(player, here, a.stationTask());
                a.assignPlot(here, staked.name());
                a.showZoneTo(player);
                a.say("\"" + staked.name() + "\" is my patch now — "
                    + a.workZone().describe() + ". I'll stay inside it.");
                player.displayClientMessage(net.minecraft.network.chat.Component.literal("Press ")
                    .append(net.minecraft.network.chat.Component.keybind("key.mc_assistant.plots"))
                    .append(net.minecraft.network.chat.Component.literal(
                        " to crew \"" + staked.name() + "\" with more hands.")), true);
            }
            case ZONE_SHRINK -> resizeZone(a, player, -4);
            case ZONE_GROW -> resizeZone(a, player, 4);
            case ZONE_SHOW -> {
                if (a.workZone() == null) {
                    a.say("No patch yet — stand where you want the middle and press Set Area.");
                } else {
                    a.showZoneTo(player);
                    a.say("That's my patch — " + a.workZone().describe() + ".");
                }
            }
            case GO_HOME -> {
                a.clearQueue();
                a.setAutonomous(false);
                a.enqueue(com.jrpetty.mcassistant.entity.Job.goHome());
                a.say("Heading home.");
            }
            case OPEN_PACK -> {
                if (player instanceof ServerPlayer sp) a.openManagementScreen(sp);
            }
            case CYCLE_SHIFT -> {
                a.setShift(a.shift().next());
                a.say("I'll work " + a.shift().label + " from now on.");
            }
            case CLAIM_BED -> {
                if (a.claimBedNear(player.blockPosition())) {
                    a.say("That's my bed — I'll sleep there when I'm off duty.");
                } else {
                    a.say("No bed nearby — put one down and press it again.");
                }
            }
            case CYCLE_BRANCH -> a.cycleBranch();
            case SAVE_PRESET -> {
                com.jrpetty.mcassistant.ZonePresets.Preset saved =
                    com.jrpetty.mcassistant.ZonePresets.save(player, a);
                if (saved == null) {
                    a.say("Give me a job and a patch first — then I can save it as a preset.");
                } else {
                    a.say("Saved this patch as \"" + saved.name()
                        + "\". Put anyone else on it with Use Patch.");
                }
            }
            case USE_PRESET -> {
                com.jrpetty.mcassistant.ZonePresets.Preset next =
                    com.jrpetty.mcassistant.ZonePresets.next(player, a.preset());
                if (next == null) {
                    a.say("No saved patches yet — set one up and press Save Patch.");
                } else {
                    com.jrpetty.mcassistant.ZonePresets.applyTo(a, next);
                    int crew = com.jrpetty.mcassistant.ZonePresets.crewOn(a, next.name());
                    a.say("On \"" + next.name() + "\" now"
                        + (crew > 1 ? " — working it with " + (crew - 1)
                            + (crew == 2 ? " other." : " others.") : "."));
                }
            }
            case REPORT -> report(a);
            case ROSTER -> {
                if (player instanceof ServerPlayer sp) roster(sp);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Step through the specialisations; starts work at once when it can. */
    private static void cycleJob(AssistantEntity a, int step) {
        AssistantEntity.StationTask next =
            AssistantEntity.StationTask.byOrdinal(a.stationTask().ordinal() + step);
        a.setJob(next);
        if (next == AssistantEntity.StationTask.NONE) {
            a.setAutonomous(false);
            a.say("Off the roster — pick me a job when you're ready.");
            return;
        }
        List<String> gaps = a.missingEssentials();
        if (gaps.isEmpty()) {
            a.setMode(AssistantEntity.Mode.FOLLOW); // clear any parking mode
            a.setAutonomous(true);
            a.say("I'm your " + next.title.toLowerCase() + " — working this patch now.");
        } else {
            a.say("I'm your " + next.title.toLowerCase() + ", but I need "
                + String.join(", ", gaps) + " — put them in my pack and I'll start.");
        }
    }

    /** Work / pause, explaining anything that's blocking it. */
    private static void toggleWork(AssistantEntity a) {
        if (a.stationTask() == AssistantEntity.StationTask.NONE) {
            a.say("Pick what I should specialise in first — the arrows either side.");
            return;
        }
        if (a.isAutonomous()) {
            a.setAutonomous(false);
            a.clearQueue();
            a.say("Taking a break — press Work again to put me back on it.");
            return;
        }
        List<String> gaps = a.missingEssentials();
        if (!gaps.isEmpty()) {
            a.say("Not yet — I need " + String.join(", ", gaps) + ".");
            return;
        }
        a.clearQueue();
        a.setMode(AssistantEntity.Mode.FOLLOW);
        a.setAutonomous(true);
        a.say("On it — working my patch as your " + a.stationTask().title.toLowerCase() + ".");
    }

    private static void resizeZone(AssistantEntity a, Player player, int delta) {
        WorkZone zone = a.workZone();
        if (zone == null) {
            a.say("No patch yet — stand in the middle of the ground you want and press Set Area.");
            return;
        }
        int r = Math.max(4, Math.min(com.jrpetty.mcassistant.AssistantConfig.maxZoneRadius(),
            zone.radius() + delta));
        WorkZone resized = WorkZone.around(zone.center(), r, zone.depth());
        // The book's footprint follows the resize (same centre keeps the name).
        ZonePresets.Preset staked = ZonePresets.stake(player, resized, a.stationTask());
        a.assignPlot(resized, staked.name());
        a.showZoneTo(player);
        a.say("Patch is now " + a.workZone().describe() + ".");
    }

    /** Jump the dig floor straight to a stratum. MIN_VALUE means "the marked
     *  ground itself" — a surface strip rather than a shaft. */
    private static void presetDepth(AssistantEntity a, int y) {
        WorkZone zone = a.workZone();
        if (zone == null) {
            a.say("Set my area first, then I'll know where to dig.");
            return;
        }
        int target = y == Integer.MIN_VALUE ? zone.min().getY() : y;
        a.setWorkZone(zone.withDepth(target));
        a.say(y == Integer.MIN_VALUE
            ? "Staying at surface level — I'll work the ground as it's marked."
            : "I'll dig down to Y" + target
              + (y == 16 ? " — iron country." : " — diamond country."));
    }

    private static void adjustDepth(AssistantEntity a, int delta) {
        WorkZone zone = a.workZone();
        if (zone == null) {
            a.say("Set my area first, then I'll know where to dig.");
            return;
        }
        a.setWorkZone(zone.withDepth(zone.depth() + delta));
        a.say("I'll dig down to Y" + a.workZone().depth() + ".");
    }

    /** "How's it going?" — job, patch, status and today's output in one line. */
    private static void report(AssistantEntity a) {
        StringBuilder s = new StringBuilder();
        s.append(a.stationTask() == AssistantEntity.StationTask.NONE
            ? "No job yet." : "I'm on " + a.stationTask().label + ".");
        if (a.workZone() != null) s.append(" Patch: ").append(a.workZone().describe()).append('.');
        s.append(' ').append(a.clientStatus()).append('.');
        s.append(" On duty ").append(a.shift().label).append('.');
        if (a.veteranLevel() >= 1) s.append(" Level ").append(a.veteranLevel()).append('.');
        String made = a.productionSummary(4);
        if (made != null) s.append(" Today: ").append(made).append('.');
        java.util.List<String> career = a.workRecord(3);
        if (!career.isEmpty()) s.append(" All told: ").append(String.join(", ", career)).append('.');
        a.say(s.toString());
    }

    /** The whole crew, same readout as the Job Board. */
    public static void roster(ServerPlayer player) {
        List<AssistantEntity> crew = AssistantEntity.allFor(player.getUUID());
        if (crew.isEmpty()) {
            player.sendSystemMessage(Component.literal("You have no crew yet."));
            return;
        }
        player.sendSystemMessage(Component.literal("— Crew (" + crew.size() + ") —")
            .withStyle(net.minecraft.ChatFormatting.GOLD));
        for (AssistantEntity a : crew) {
            String status = a.clientStatus();
            boolean blocked = status.startsWith("Needs") || status.startsWith("Out of");
            player.sendSystemMessage(Component.literal(
                (blocked ? "⚠ " : "• ") + a.displayNameCap() + " — " + a.stationTask().title
                    + " · " + status).withStyle(blocked ? net.minecraft.ChatFormatting.RED
                    : status.startsWith("Working") ? net.minecraft.ChatFormatting.GREEN
                    : net.minecraft.ChatFormatting.YELLOW));
        }
    }
}
