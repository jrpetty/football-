package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [soak40] A sleeping town is on the map again after a restart. The towns' register lives in memory, and its folk carry
 * it: the first of a town's folk to load puts the town back (Villages.restore). A town asleep when the server stopped
 * has every one of its folk in chunks nobody holds, so after a restart nobody put it back: it was gone from every list
 * (the soak's colony Hartthorpe, "3 villages in the world" where there were four), never stepped and never woken (its
 * level is reckoned only for the towns on the register), till something chanced to load one of its folk. So a town
 * going to sleep writes where it is and how far it has got into its books, which are saved with the world, and the
 * first tick after a world opens puts every sleeping town back from them; it is forgotten again when it wakes.
 */
public final class TownRoll {

    private TownRoll() {}

    private static final Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The ledger's note: "v1;dimension;centre;age;roll;built,built,...". */
    static final String NOTE = "lod.where";

    /** The town goes to sleep: where it is and how far it has got, into its books. */
    static void keep(Villages.Village v) {
        Ledger.note(v.id(), NOTE, String.join(";", "v1", v.dim().location().toString(), Long.toString(v.centre().asLong()),
            Villages.ageOf(v.id()).name(), Integer.toString(Villages.headcount(v.id())), String.join(",", Villages.builtList(v.id()))));
    }

    /** The town is awake (its folk loaded carry it again). */
    static void forget(UUID village) {
        Ledger.forget(village, NOTE);
    }

    /** A world opened: every town asleep in its books and not on the register put back on it. Returns how many. */
    static int restore(MinecraftServer server) {
        int n = 0;
        List<String> names = new ArrayList<>();
        for (UUID id : Ledger.notedTowns(NOTE)) {
            if (Villages.get(id) != null) continue;
            String s = Ledger.note(id, NOTE);
            if (s == null || !s.startsWith("v1;")) continue;
            try {
                String[] p = s.split(";", -1);
                ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(p[1])));
                if (level == null) continue;
                BlockPos centre = BlockPos.of(Long.parseLong(p[2]));
                Villages.Age age = Villages.Age.valueOf(p[3]);
                int roll = Integer.parseInt(p[4]);
                List<String> built = new ArrayList<>();
                for (String b : p[5].split(",")) if (!b.isEmpty()) built.add(b);
                Villages.restore(level, id, centre, age, built, roll);
                names.add(Villages.name(id) + " (" + roll + ")");
                n++;
            } catch (RuntimeException e) {
                LOG.warn("[MCA-LOD] {}: where it sleeps could not be read ({})", id, e.toString());
            }
        }
        if (n > 0) LOG.info("[MCA-LOD] {} sleeping town(s) put back on the map from their books: {}", n, String.join(", ", names));
        return n;
    }

    /** Tests: the restore run now, as the first tick after a world opens runs it. */
    public static int restoreForTests(MinecraftServer server) {
        return restore(server);
    }
}
