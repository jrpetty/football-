package com.jrpetty.mcassistant.admin;

import com.jrpetty.mcassistant.entity.AdminTools;
import com.jrpetty.mcassistant.entity.AdminTools.Done;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * [admin] A town's setup kept as a named preset (age, population, the watch and its kit, the stores' main goods) in
 * {@code <world>/mcassistant/admin-presets/<name>.txt}, a plain "key=value" file an admin can edit, and applied to
 * another town: its age set, folk added to the headcount, the watch raised and armed, the stores topped up to the
 * preset's counts. Only ever adds: a town bigger or richer than the preset keeps what it has.
 */
public final class AdminPresets {

    private AdminPresets() {}

    private static final String DIR = "admin-presets";
    /** The stores' goods a preset keeps: the commonest so many. */
    private static final int GOODS = 30;

    private static String clean(String name) {
        return name.replaceAll("[^A-Za-z0-9_-]", "");
    }

    public static Done save(MinecraftServer server, ServerLevel level, Villages.Village v, String name) {
        String n = clean(name);
        if (n.isEmpty()) return Done.no("Give the preset a name (letters, digits, - and _).");
        List<String> lines = new ArrayList<>();
        lines.add("# preset of " + Villages.name(v.id()) + ", day " + level.getDayTime() / 24000L);
        lines.add("age=" + Villages.ageOf(v.id()).name().toLowerCase());
        lines.add("folk=" + Villages.headcount(v.id()));
        List<VillageFolkEntity> watch = AdminTools.guards(v.id());
        lines.add("guards=" + watch.size());
        lines.add("kit=" + tierOf(watch));
        Map<Item, Integer> held = held(level, v);
        held.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(GOODS)
            .forEach(e -> lines.add("store." + BuiltInRegistries.ITEM.getKey(e.getKey()).getPath() + "=" + e.getValue()));
        try {
            Files.write(AdminLog.folder(server, DIR).resolve(n + ".txt"), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Done.no("Could not write the preset: " + e.getMessage());
        }
        return Done.said("PRESET " + n + " saved from " + Villages.name(v.id()) + ": " + lines.get(1) + ", " + lines.get(2) + ", " + lines.get(3) + ", "
            + lines.get(4) + ", " + Math.min(GOODS, held.size()) + " goods");
    }

    /** The armour the watch wears, as a tier word. */
    private static String tierOf(List<VillageFolkEntity> watch) {
        for (VillageFolkEntity f : watch) {
            String id = BuiltInRegistries.ITEM.getKey(f.getItemBySlot(EquipmentSlot.CHEST).getItem()).getPath();
            if (id.startsWith("netherite")) return "netherite";
            if (id.startsWith("diamond")) return "diamond";
            if (id.startsWith("iron")) return "iron";
            if (id.startsWith("chainmail")) return "chain";
            if (id.startsWith("leather")) return "leather";
        }
        return "none";
    }

    /** Every item the store chests hold, counted. */
    static Map<Item, Integer> held(ServerLevel level, Villages.Village v) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty()) out.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        return out;
    }

    /** Bring a town up to a preset: the age, then the stores, then (as jobs) the folk and the watch. */
    public static Done apply(AdminRunner.Ctx c, Villages.Village v, String name) {
        Map<String, String> p = read(c.server(), clean(name));
        if (p == null) return Done.no("No preset called " + name + ". Presets: " + String.join(", ", names(c.server())));
        ServerLevel level = AdminActions.levelOf(c, v);
        List<String> said = new ArrayList<>();
        Villages.Age age = AdminTools.age(p.getOrDefault("age", ""));
        if (age != null && age != Villages.ageOf(v.id())) said.add(AdminTools.setAge(level, v, age).text());
        Map<Item, Integer> held = held(level, v), want = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (!e.getKey().startsWith("store.")) continue;
            ResourceLocation id = ResourceLocation.tryParse("minecraft:" + e.getKey().substring(6));
            Item it = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            int n = num(e.getValue());
            if (it != null && n > held.getOrDefault(it, 0)) want.put(it, n - held.getOrDefault(it, 0));
        }
        if (!want.isEmpty()) said.add(AdminTools.fill(level, v, want) + " goods into the stores");
        int folk = num(p.getOrDefault("folk", "0")) - Villages.headcount(v.id());
        if (folk > 0) said.add(AdminActions.addFolk(c, v, folk, AdminActions.Mix.SPREAD, null).text());
        int guards = num(p.getOrDefault("guards", "0")) - AdminTools.guards(v.id()).size();
        String kit = p.getOrDefault("kit", "none");
        if (guards > 0 && !kit.equals("none")) {
            said.add(AdminActions.raiseArmy(c, v, guards, kit).text());
        }
        return Done.ok("Preset " + name + " on " + Villages.name(v.id()) + ": " + (said.isEmpty() ? "it has all of it already" : String.join("; ", said)),
            "the town was brought up to the preset " + name);
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @javax.annotation.Nullable
    static Map<String, String> read(MinecraftServer server, String name) {
        try {
            Path f = AdminLog.folder(server, DIR).resolve(name + ".txt");
            if (!Files.exists(f)) return null;
            Map<String, String> out = new LinkedHashMap<>();
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String t = line.trim();
                int eq = t.indexOf('=');
                if (t.startsWith("#") || eq <= 0) continue;
                out.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
            }
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    public static List<String> names(MinecraftServer server) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(AdminLog.folder(server, DIR))) {
            s.map(x -> x.getFileName().toString()).filter(n -> n.endsWith(".txt")).sorted().forEach(n -> out.add(n.substring(0, n.length() - 4)));
        } catch (IOException ignored) {
            // no folder: no presets
        }
        return out;
    }
}
