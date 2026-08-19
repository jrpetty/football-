package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.WorkZone;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Saved patches. Mark out a good field once, save it, and every future hire can
 * be dropped straight onto it — same ground, same trade, same shift. Putting a
 * second bot on a saved patch is how you crew it up: they share the zone and
 * work it together.
 *
 * Presets are named for you (there is no typing anywhere in this mod) and kept
 * in the player's own persistent data, so they survive a restart and belong to
 * the player rather than to the world.
 */
public final class ZonePresets {

    private ZonePresets() {}

    private static final String KEY = "mc_assistant_presets";
    private static final int MAX = 12;

    public record Preset(String name, AssistantEntity.StationTask job,
                         WorkZone zone, AssistantEntity.Shift shift) {

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("Name", name);
            t.putString("Job", job.name());
            t.putString("Shift", shift.name());
            t.put("Zone", zone.save());
            return t;
        }

        @Nullable
        static Preset load(CompoundTag t) {
            WorkZone zone = WorkZone.load(t.getCompound("Zone"));
            if (zone == null) return null;
            AssistantEntity.StationTask job;
            AssistantEntity.Shift shift;
            try {
                job = AssistantEntity.StationTask.valueOf(t.getString("Job"));
                shift = AssistantEntity.Shift.valueOf(t.getString("Shift"));
            } catch (IllegalArgumentException e) {
                return null;
            }
            return new Preset(t.getString("Name"), job, zone, shift);
        }
    }

    public static List<Preset> list(Player player) {
        List<Preset> out = new ArrayList<>();
        ListTag saved = player.getPersistentData().getList(KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < saved.size(); i++) {
            Preset p = Preset.load(saved.getCompound(i));
            if (p != null) out.add(p);
        }
        return out;
    }

    private static void store(Player player, List<Preset> presets) {
        ListTag list = new ListTag();
        for (Preset p : presets) list.add(p.save());
        player.getPersistentData().put(KEY, list);
    }

    /**
     * Save this specialist's patch as a preset. Named for the trade and the
     * ground it covers, so a list of them reads without anyone typing a word.
     * Saving the same patch twice replaces it rather than piling up duplicates.
     */
    @Nullable
    public static Preset save(Player player, AssistantEntity bot) {
        WorkZone zone = bot.workZone();
        if (zone == null || bot.stationTask() == AssistantEntity.StationTask.NONE) return null;

        // One book. Save Patch used to invent its own name ("Farmer at 120,
        // -340") in the same storage the plot book reads — now it stakes like
        // everything else, keeps the plot's name if the ground is already in
        // the book, and pins the bot's shift onto it.
        Preset staked = stake(player, zone, bot.stationTask());
        List<Preset> presets = list(player);
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).name().equals(staked.name())) {
                staked = new Preset(staked.name(), staked.job(), staked.zone(), bot.shift());
                presets.set(i, staked);
                store(player, presets);
                break;
            }
        }
        bot.assignPlot(staked.zone(), staked.name());
        bot.setPreset(staked.name());
        return staked;
    }

    /** The preset after the one this bot is on — how the button cycles through. */
    @Nullable
    public static Preset next(Player player, @Nullable String current) {
        List<Preset> presets = list(player);
        if (presets.isEmpty()) return null;
        int at = -1;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).name().equals(current)) at = i;
        }
        return presets.get((at + 1) % presets.size());
    }

    /** Put a specialist on a saved patch — its trade, its ground, its shift. */
    public static void applyTo(AssistantEntity bot, Preset preset) {
        // Job before zone: setWorkZone re-posts the station using whatever the
        // current task is, so setting the zone first would post the old trade.
        bot.setStation(preset.zone().center(), preset.job());
        bot.setWorkZone(preset.zone());
        bot.setShift(preset.shift());
        bot.setPreset(preset.name());
    }

    // ------------------------- the plot book ---------------------------------
    // Ground as a first-class thing: staked from the plots menu or the wand,
    // named automatically from the patch-name pool, and crewed by clicking.

    /** The first patch name no plot of this player's is using; "Plot N" when
     *  the pool runs dry. Nothing is ever typed. */
    public static String nextFreeName(Player player) {
        List<Preset> presets = list(player);
        for (String candidate : com.jrpetty.mcassistant.entity.Names.PATCHES) {
            boolean taken = false;
            for (Preset p : presets) {
                if (p.name().equals(candidate)) { taken = true; break; }
            }
            if (!taken) return candidate;
        }
        return "Plot " + (presets.size() + 1);
    }

    @Nullable
    public static Preset byName(Player player, String name) {
        for (Preset p : list(player)) {
            if (p.name().equals(name)) return p;
        }
        return null;
    }

    /**
     * Register a piece of ground as a plot, named for you. A plot re-staked
     * over the same centre keeps its name — widening a field is still the same
     * field — and the job is whatever the caller knows it to be (NONE for bare
     * ground: the workers bring their own trades).
     */
    public static Preset stake(Player player, WorkZone zone, AssistantEntity.StationTask job) {
        List<Preset> presets = list(player);
        Preset kept = null;
        for (Preset p : presets) {
            if (zone.containsColumn(p.zone().center())
                || p.zone().containsColumn(zone.center())) {
                kept = p;
                break;
            }
        }
        // Re-staking the same ground INHERITS what the caller does not know:
        // the name always (it is still the same field), the trade when the new
        // marking is bare (widening a farm with the empty wand must not untype
        // it), and the shift the plot was saved with.
        String name = kept != null ? kept.name() : nextFreeName(player);
        AssistantEntity.StationTask effJob =
            job == AssistantEntity.StationTask.NONE && kept != null ? kept.job() : job;
        AssistantEntity.Shift shift = kept != null ? kept.shift() : AssistantEntity.Shift.ALWAYS;
        Preset preset = new Preset(name, effJob, zone, shift);
        presets.removeIf(p -> p.name().equals(name));
        presets.add(0, preset);
        while (presets.size() > MAX) presets.remove(presets.size() - 1);
        store(player, presets);
        return preset;
    }

    /**
     * Give a plot its trade, or the next one along — the click-cycle every
     * other choice in this mod uses. Everyone already working the plot
     * retrains on the spot: the ground defines the work, that is the point
     * of naming it "North Farm".
     */
    @Nullable
    public static AssistantEntity.StationTask cycleTrade(Player player, String name) {
        List<Preset> presets = list(player);
        for (int i = 0; i < presets.size(); i++) {
            Preset p = presets.get(i);
            if (!p.name().equals(name)) continue;
            AssistantEntity.StationTask[] all = AssistantEntity.StationTask.values();
            AssistantEntity.StationTask next = all[(p.job().ordinal() + 1) % all.length];
            presets.set(i, new Preset(p.name(), next, p.zone(), p.shift()));
            store(player, presets);
            if (next != AssistantEntity.StationTask.NONE) {
                for (AssistantEntity mate : AssistantEntity.allFor(player.getUUID())) {
                    if (mate.isAlive() && p.zone().equals(mate.workZone())) {
                        mate.setStation(p.zone().center(), next);
                        mate.say("Retrained — working \"" + p.name() + "\" as a "
                            + next.label + " now.");
                    }
                }
            }
            return next;
        }
        return null;
    }

    public static boolean forget(Player player, String name) {
        List<Preset> presets = list(player);
        boolean removed = presets.removeIf(p -> p.name().equals(name));
        if (removed) store(player, presets);
        return removed;
    }

    /** Cycle a plot's name to the next unused one in the pool, renaming the
     *  bots working it so their status lines follow. Returns the new name. */
    @Nullable
    public static String rename(Player player, String name) {
        List<Preset> presets = list(player);
        for (int i = 0; i < presets.size(); i++) {
            Preset p = presets.get(i);
            if (!p.name().equals(name)) continue;
            String fresh = nextFreeName(player);
            presets.set(i, new Preset(fresh, p.job(), p.zone(), p.shift()));
            store(player, presets);
            for (AssistantEntity mate : AssistantEntity.allFor(player.getUUID())) {
                if (mate.isAlive() && p.zone().equals(mate.workZone())) {
                    mate.assignPlot(p.zone(), fresh);
                }
            }
            return fresh;
        }
        return null;
    }

    /**
     * Ground only: the bot takes the plot's zone and name but KEEPS its own
     * trade — a plot staked bare is worked by whoever you put on it, as
     * whatever they already are. A plot saved WITH a trade (Save Patch) still
     * hands the trade over, but only to a bot that has none.
     */
    public static void applyGround(AssistantEntity bot, Preset preset) {
        bot.setShift(preset.shift());   // the plot's hours are the plot's
        if (preset.job() != AssistantEntity.StationTask.NONE) {
            // Assigning to "North Farm" means farming it — whatever the bot
            // was before. Bare ground is the other way round: workers keep
            // their own trades and the ground learns from the first of them.
            bot.setStation(preset.zone().center(), preset.job());
        }
        bot.assignPlot(preset.zone(), preset.name());
        bot.setPreset(preset.name());
    }

    /** Cycle the plot's working hours; everyone on it inherits them. */
    @Nullable
    public static AssistantEntity.Shift cycleShift(Player player, String name) {
        List<Preset> presets = list(player);
        for (int i = 0; i < presets.size(); i++) {
            Preset p = presets.get(i);
            if (!p.name().equals(name)) continue;
            AssistantEntity.Shift next = p.shift().next();
            presets.set(i, new Preset(p.name(), p.job(), p.zone(), next));
            store(player, presets);
            for (AssistantEntity mate : AssistantEntity.allFor(player.getUUID())) {
                if (mate.isAlive() && p.zone().equals(mate.workZone())) {
                    mate.setShift(next);
                }
            }
            return next;
        }
        return null;
    }

    /**
     * The plot's condition, 0-100: what is quietly wrong with the ground
     * before it shows in the output. Sampled on a stride so a big plot costs
     * about the same as a small one, and only when the book is opened.
     */
    public static int health(net.minecraft.server.level.ServerLevel level, Preset preset) {
        WorkZone zone = preset.zone();
        net.minecraft.core.BlockPos c = zone.center();
        int rx = Math.max(1, zone.sizeX() / 2), rz = Math.max(1, zone.sizeZ() / 2);
        int stride = Math.max(2, Math.max(rx, rz) / 6);
        int columns = 0, dark = 0, farmland = 0, dry = 0;
        boolean water = false;
        for (int dx = -rx; dx <= rx; dx += stride) {
            for (int dz = -rz; dz <= rz; dz += stride) {
                int x = c.getX() + dx, z = c.getZ() + dz;
                int y = level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                net.minecraft.core.BlockPos top = new net.minecraft.core.BlockPos(x, y, z);
                columns++;
                if (level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, top) == 0) dark++;
                net.minecraft.world.level.block.state.BlockState ground = level.getBlockState(top.below());
                if (ground.is(net.minecraft.world.level.block.Blocks.FARMLAND)) {
                    farmland++;
                    if (ground.getValue(net.minecraft.world.level.block.FarmBlock.MOISTURE) == 0) dry++;
                }
                if (ground.is(net.minecraft.world.level.block.Blocks.WATER)
                    || level.getBlockState(top).is(net.minecraft.world.level.block.Blocks.WATER)) {
                    water = true;
                }
            }
        }
        if (columns == 0) return 100;
        int score = 100;
        score -= dark * 30 / columns;                        // spawnable dark ground
        if (farmland > 0) score -= dry * 40 / farmland;      // a drying field
        if (preset.job() == AssistantEntity.StationTask.FARM && !water) score -= 20;
        if (preset.job() == AssistantEntity.StationTask.RANCH) {
            int animals = level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
                new net.minecraft.world.phys.AABB(zone.min(), zone.max().above(4)),
                a -> a.isAlive() && !a.isBaby()).size();
            if (animals < 2) score -= 40;                    // a pen that cannot breed
        }
        return Math.max(0, Math.min(100, score));
    }

    // ------------------------------ blueprints --------------------------------
    // A working plot, copied whole: its size, trade, hours, and where its
    // chests sit relative to the middle. Stamp it somewhere new and the ground
    // is staked and the chest spots are marked — set up once, repeat forever.

    private static final String BP_KEY = "mc_assistant_blueprint";

    public static boolean copyBlueprint(net.minecraft.server.level.ServerPlayer player, Preset preset) {
        WorkZone zone = preset.zone();
        CompoundTag bp = new CompoundTag();
        bp.putInt("SX", zone.sizeX());
        bp.putInt("SZ", zone.sizeZ());
        bp.putInt("Depth", zone.depth());
        bp.putString("Job", preset.job().name());
        bp.putString("Shift", preset.shift().name());
        ListTag chests = new ListTag();
        for (com.jrpetty.mcassistant.entity.ZoneChests.Found f
                : com.jrpetty.mcassistant.entity.ZoneChests.around(
                    player.serverLevel(), zone.center(), zone.radius() + 2, 6)) {
            if (chests.size() >= 8) break;
            CompoundTag ct = new CompoundTag();
            ct.putInt("X", f.pos().getX() - zone.center().getX());
            ct.putInt("Y", f.pos().getY() - zone.center().getY());
            ct.putInt("Z", f.pos().getZ() - zone.center().getZ());
            chests.add(ct);
        }
        bp.put("Chests", chests);
        player.getPersistentData().put(BP_KEY, bp);
        return true;
    }

    /** Stamp the copied plot where the player stands: staked, named, and its
     *  chest spots marked with light for a few seconds. Null = nothing copied. */
    @Nullable
    public static Preset stampBlueprint(net.minecraft.server.level.ServerPlayer player) {
        CompoundTag bp = player.getPersistentData().getCompound(BP_KEY);
        if (!bp.contains("SX")) return null;
        AssistantEntity.StationTask job;
        AssistantEntity.Shift shift;
        try {
            job = AssistantEntity.StationTask.valueOf(bp.getString("Job"));
            shift = AssistantEntity.Shift.valueOf(bp.getString("Shift"));
        } catch (IllegalArgumentException e) {
            return null;
        }
        net.minecraft.core.BlockPos c = player.blockPosition();
        int sx = Math.max(3, bp.getInt("SX")), sz = Math.max(3, bp.getInt("SZ"));
        WorkZone zone = WorkZone.of(
            c.offset(-(sx / 2), 0, -(sz / 2)),
            c.offset(sx - 1 - sx / 2, 0, sz - 1 - sz / 2),
            bp.getInt("Depth"));
        Preset staked = stake(player, zone, job);
        // Pin the copied shift onto the staked entry.
        List<Preset> presets = list(player);
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).name().equals(staked.name())) {
                staked = new Preset(staked.name(), staked.job(), staked.zone(), shift);
                presets.set(i, staked);
                store(player, presets);
                break;
            }
        }
        // Show the ground and the chest spots: perimeter in wax, chest columns
        // in end-rod light, tall enough to see over a hedge.
        net.minecraft.server.level.ServerLevel level = player.serverLevel();
        net.minecraft.core.BlockPos min = zone.min(), max = zone.max();
        for (int x = min.getX(); x <= max.getX(); x += 2) {
            spark(level, x, c.getY(), min.getZ());
            spark(level, x, c.getY(), max.getZ());
        }
        for (int z = min.getZ(); z <= max.getZ(); z += 2) {
            spark(level, min.getX(), c.getY(), z);
            spark(level, max.getX(), c.getY(), z);
        }
        ListTag chests = bp.getList("Chests", Tag.TAG_COMPOUND);
        for (int i = 0; i < chests.size(); i++) {
            CompoundTag ct = chests.getCompound(i);
            for (int h = 0; h < 6; h++) {
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                    c.getX() + ct.getInt("X") + 0.5, c.getY() + ct.getInt("Y") + 0.3 + h * 0.5,
                    c.getZ() + ct.getInt("Z") + 0.5, 2, 0.05, 0.05, 0.05, 0.0);
            }
        }
        return staked;
    }

    private static void spark(net.minecraft.server.level.ServerLevel level, int x, int y, int z) {
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.WAX_ON,
            x + 0.5, y + 1.2, z + 0.5, 3, 0.1, 0.3, 0.1, 0.0);
    }

    /** How many of this owner's live crew are working that zone right now. */
    public static int workersOn(Player player, Preset preset) {
        int n = 0;
        for (AssistantEntity a : AssistantEntity.allFor(player.getUUID())) {
            if (a.isAlive() && preset.zone().equals(a.workZone())) n++;
        }
        return n;
    }

    /** How many of this owner's live crew are already working that patch. */
    public static int crewOn(AssistantEntity bot, String presetName) {
        if (bot.getOwnerId() == null) return 1;
        int n = 0;
        for (AssistantEntity a : AssistantEntity.allFor(bot.getOwnerId())) {
            if (a.isAlive() && presetName.equals(a.preset())) n++;
        }
        return n;
    }
}
