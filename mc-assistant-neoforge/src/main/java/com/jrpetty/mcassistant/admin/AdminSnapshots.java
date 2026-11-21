package com.jrpetty.mcassistant.admin;

import com.jrpetty.mcassistant.entity.AdminTools;
import com.jrpetty.mcassistant.entity.AdminTools.Done;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * [admin] A quick copy of a town's SAVED DATA before a big change, and putting it back: its age, its treasury and every
 * note the town keeps in the world's books (research, elections, wars, the leader's plan, prices...). Not blocks, not
 * folk and not their belongings: for those, back up the world (/save-all and a copy of the save). Kept as
 * {@code <world>/mcassistant/admin-snapshots/<town>-<n>.dat}.
 */
public final class AdminSnapshots {

    private AdminSnapshots() {}

    private static final String DIR = "admin-snapshots";

    private static String slug(Villages.Village v) {
        return Villages.name(v.id()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    /** Take a snapshot; its name in the result. */
    public static Done take(MinecraftServer server, Villages.Village v) {
        try {
            Path dir = AdminLog.folder(server, DIR);
            int n = 1;
            while (Files.exists(dir.resolve(slug(v) + "-" + n + ".dat"))) n++;
            CompoundTag t = new CompoundTag();
            t.putUUID("id", v.id());
            t.putString("name", Villages.name(v.id()));
            t.putString("age", Villages.ageOf(v.id()).name());
            t.putInt("coins", Ledger.coins(v.id()));
            t.putLong("day", server.overworld().getDayTime() / 24000L);
            CompoundTag notes = new CompoundTag();
            for (Map.Entry<String, String> e : Ledger.notes(v.id()).entrySet()) notes.putString(e.getKey(), e.getValue());
            t.put("notes", notes);
            String name = slug(v) + "-" + n;
            NbtIo.writeCompressed(t, dir.resolve(name + ".dat"));
            return Done.said("SNAPSHOT " + name + " of " + Villages.name(v.id()) + ": age, treasury and " + notes.size()
                + " notes (saved data only, not blocks or folk); /village admin restore " + name);
        } catch (IOException e) {
            return Done.no("Could not write the snapshot: " + e.getMessage());
        }
    }

    /** Put a snapshot's saved data back on its town (the town must be the one it was taken of). */
    public static Done restore(MinecraftServer server, ServerLevel level, Villages.Village v, String name) {
        try {
            Path f = AdminLog.folder(server, DIR).resolve(name.replaceAll("[^A-Za-z0-9_-]", "") + ".dat");
            if (!Files.exists(f)) return Done.no("No snapshot called " + name + ". Snapshots: " + String.join(", ", names(server)));
            CompoundTag t = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap());
            if (!t.hasUUID("id") || !t.getUUID("id").equals(v.id())) {
                return Done.no(name + " is a snapshot of " + t.getString("name") + ", not of " + Villages.name(v.id()) + " (use town:" + t.getString("name") + ").");
            }
            Villages.Age age = AdminTools.age(t.getString("age"));
            if (age != null) Villages.decreeAge(level, v.id(), age);
            Ledger.takeCoins(v.id(), Integer.MAX_VALUE);
            Ledger.addCoins(v.id(), t.getInt("coins"));
            CompoundTag notes = t.getCompound("notes");
            for (String k : new ArrayList<>(Ledger.notes(v.id()).keySet())) if (!notes.contains(k)) Ledger.forget(v.id(), k);
            for (String k : notes.getAllKeys()) Ledger.note(v.id(), k, notes.getString(k));
            return Done.ok("Restored " + Villages.name(v.id()) + " from " + name + " (day " + t.getLong("day") + "): " + (age == null ? "?" : age.label)
                + ", " + t.getInt("coins") + " coin, " + notes.size() + " notes. Blocks and folk were not touched.", "the town's books were restored from " + name);
        } catch (IOException e) {
            return Done.no("Could not read the snapshot: " + e.getMessage());
        }
    }

    public static List<String> names(MinecraftServer server) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(AdminLog.folder(server, DIR))) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".dat")).sorted().forEach(n -> out.add(n.substring(0, n.length() - 4)));
        } catch (IOException ignored) {
            // no folder: no snapshots
        }
        return out;
    }
}
