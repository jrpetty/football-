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

        String name = bot.stationTask().title + " at "
            + zone.center().getX() + ", " + zone.center().getZ();
        Preset preset = new Preset(name, bot.stationTask(), zone, bot.shift());

        List<Preset> presets = list(player);
        presets.removeIf(p -> p.name().equals(name));
        presets.add(0, preset);
        while (presets.size() > MAX) presets.remove(presets.size() - 1);
        store(player, presets);
        bot.setPreset(name);
        return preset;
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
        String keep = null;
        for (Preset p : presets) {
            if (zone.containsColumn(p.zone().center())
                || p.zone().containsColumn(zone.center())) {
                keep = p.name();
                break;
            }
        }
        String name = keep != null ? keep : nextFreeName(player);
        Preset preset = new Preset(name, job, zone, AssistantEntity.Shift.ALWAYS);
        presets.removeIf(p -> p.name().equals(name));
        presets.add(0, preset);
        while (presets.size() > MAX) presets.remove(presets.size() - 1);
        store(player, presets);
        return preset;
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
        if (preset.job() != AssistantEntity.StationTask.NONE
            && bot.stationTask() == AssistantEntity.StationTask.NONE) {
            bot.setStation(preset.zone().center(), preset.job());
        }
        bot.assignPlot(preset.zone(), preset.name());
        bot.setPreset(preset.name());
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
