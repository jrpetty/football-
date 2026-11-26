package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featcivic] The town's politics on its rounds (VillageFolkEntity, the town's ROUNDS beat): the morning's look at the
 * unrest (Dissent), a new leader's policy (Policy), the posters left from the last election (Polls) and the government's
 * flag; and through the day the candidates' rallies, a protest or a rising when one is due, a ceremony waiting for the
 * square to be free. And on the server's own beat, the count at the ballot box, ballot by ballot (Polls.countTick).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Politics {

    private Politics() {}

    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> MORNING = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LOOKED.clear();
        MORNING.clear();
        Polls.resetForTests();
        Dissent.resetForTests();
    }

    public static void tick(ServerLevel level, Villages.Village v) {
        if (Identity.neutral() && !Polls.visualForTests) return;
        UUID id = v.id();
        if (Identity.known(id) == null) return;
        long now = level.getGameTime();
        Long last = LOOKED.get(id);
        if (last != null && now - last < 100L && now >= last) return;
        LOOKED.put(id, now);
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        if (t >= 1000L && MORNING.getOrDefault(id, -1L) != day) {
            MORNING.put(id, day);
            com.jrpetty.mcassistant.Guard.run("the unrest", () -> Dissent.daily(level, v, day));
            com.jrpetty.mcassistant.Guard.run("the policy", () -> Policy.look(level, v, day));
            com.jrpetty.mcassistant.Guard.run("the posters", () -> Polls.morning(level, v, day));
            com.jrpetty.mcassistant.Guard.run("the notices", () -> Regimes.notices(level, v));
            com.jrpetty.mcassistant.Guard.run("the flag", () -> {
                String flag = Ledger.note(id, "gov.flag");
                if (flag == null || flag.isEmpty()) Regimes.raiseFlag(level, v, Government.of(id), false);
            });
        }
        com.jrpetty.mcassistant.Guard.run("the rallies", () -> Polls.campaign(level, v, day, t));
        com.jrpetty.mcassistant.Guard.run("the protests", () -> Dissent.tick(level, v, day, t));
        com.jrpetty.mcassistant.Guard.run("a ceremony", () -> Ceremonies.begin(level, id));
    }

    /** The count, ballot by ballot, on the server's own beat (every other tick: the count reads one every eight). */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if ((event.getServer().getTickCount() & 1) != 0 || Polls.countingTowns().isEmpty()) return;
        com.jrpetty.mcassistant.Guard.run("the count", () -> {
            for (UUID id : Polls.countingTowns()) {
                Villages.Village v = Villages.get(id);
                if (v == null) continue;
                ServerLevel level = event.getServer().getLevel(v.dim());
                if (level != null) Polls.countTick(level, v);
            }
        });
    }
}
