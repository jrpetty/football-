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
