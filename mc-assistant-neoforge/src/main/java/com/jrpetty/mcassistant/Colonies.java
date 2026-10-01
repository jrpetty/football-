package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A village that has grown sends people out to found another.
 *
 * <p>Ages end; a village does not have to. Once a settlement is out of the Wood Age and
 * has forty people (villageColonyAt) it sends a founding party — as many as a village
 * the world grows starts with — a couple of hundred blocks out, with the founding
 * stores and food from its own larder, and they found a village of their own that
 * climbs the ages from the beginning. Every two game days at most from any one
 * village, and never past the number of folk the whole world may hold
 * (villageWorldCap), so a long game sees settlements spread across the map without
 * the map ever being more than the machine can run.
 *
 * <p>The ground for the new village is asked for the way the game asks for ground —
 * a short-lived ticket, generated on the world's own threads — and only looked at
 * once it is there, from a later tick: nothing is ever generated on this thread.
 */
public final class Colonies {

    private Colonies() {}

    /** Two game days between founding parties from any one village. */
    public static final long INTERVAL = 48000L;
    /** How far out a colony goes: far enough to be a village of its own. */
    public static final int DISTANCE = 200;
    /** Food each colonist is sent out with, from the mother village's stores. */
    private static final int FOOD_EACH = 6;

    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> WAITED = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> TRIED = new ConcurrentHashMap<>();

    public static void reset() {
        LAST.clear();
        PENDING.clear();
        WAITED.clear();
        TRIED.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 200 != 37) return;
        Guard.run("colonies", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) tick(level);
        });
    }

    /** How many founders a colony is sent out with. */
    public static int party() {
        return Math.max(2, AssistantConfig.villageMinFolk());
    }

    static void tick(ServerLevel level) {
        if (!AssistantConfig.villageColonies()) return;
        long now = level.getGameTime();
        int world = 0;
        for (Villages.Village v : Villages.every()) world += Villages.headcount(v.id());
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            UUID id = v.id();
            BlockPos pending = PENDING.get(id);
            if (pending != null) {
                if (settle(level, v, pending, now)) world += party();
                continue;
            }
            // A village first seen now (a new one, or the world just loaded) waits a whole
            // interval before its first party, so a restart is not a reason to send one.
            long last = LAST.computeIfAbsent(id, k -> now);
            if (now - last < INTERVAL) continue;
            if (!ready(level, id, world)) continue;
            BlockPos target = spot(level, v);
            if (target == null) {
                LAST.put(id, now - INTERVAL + 6000L);            // look again in five minutes
                continue;
            }
            PENDING.put(id, target);
            WAITED.put(id, 0);
            ask(level, target);
        }
    }

    /** Is this village able to spare a founding party right now? */
    static boolean ready(ServerLevel level, UUID id, int world) {
        if (Villages.ageOf(id).ordinal() < Villages.Age.STONE.ordinal()) return false;
        if (Villages.headcount(id) < AssistantConfig.villageColonyAt()) return false;
        if (world + party() > AssistantConfig.villageWorldCap()) return false;
        // Out of what is put by, over and above a day's meals for the people staying.
        Villages.Village v = Villages.get(id);
        if (v == null) return false;
        int food = Villages.stock(level, v.centre(), Villages.Task.FOOD, Villages.storesRadius(id));
        return food >= Villages.larderForBirth(id) + party() * FOOD_EACH;
    }

    /** Somewhere a couple of hundred blocks out with no village near it yet, or null. */
    static BlockPos spot(ServerLevel level, Villages.Village v) {
        int tried = TRIED.getOrDefault(v.id(), 0);
        for (int i = 0; i < 8; i++) {
            double angle = (tried + i) * 2.399963229728653 + (v.id().getLeastSignificantBits() & 7);
            int x = v.centre().getX() + (int) Math.round(Math.cos(angle) * DISTANCE);
            int z = v.centre().getZ() + (int) Math.round(Math.sin(angle) * DISTANCE);
            BlockPos at = new BlockPos(x, v.centre().getY(), z);
            if (Villages.nearest(level, at, Villages.VILLAGE_RANGE * 2) != null) continue;
            TRIED.put(v.id(), tried + i + 1);
            return at;
        }
        TRIED.put(v.id(), tried + 8);
        return null;
    }

    /** Ask for the ground there the way the game does: generated off this thread. */
    private static void ask(ServerLevel level, BlockPos at) {
        level.getChunkSource().addRegionTicket(TicketType.PORTAL, new ChunkPos(at), 2, at);
    }

    /** The ground has (or has not yet) arrived: found the colony, or keep waiting. */
    private static boolean settle(ServerLevel level, Villages.Village mother, BlockPos at, long now) {
        UUID id = mother.id();
        if (!level.hasChunk(at.getX() >> 4, at.getZ() >> 4)
                || !level.hasChunk((at.getX() + 16) >> 4, (at.getZ() + 16) >> 4)
                || !level.hasChunk((at.getX() - 16) >> 4, (at.getZ() - 16) >> 4)) {
            int waited = WAITED.merge(id, 1, Integer::sum);
            if (waited > 30) {                                   // ten minutes: try elsewhere later
                PENDING.remove(id);
                LAST.put(id, now - INTERVAL + 6000L);
            } else {
                ask(level, at);                                  // the ticket lasts fifteen seconds
            }
            return false;
        }
        PENDING.remove(id);
        BlockPos ground = VillageSpawner.groundAt(level, at.getX(), at.getZ());
        if (ground == null || !VillageSpawner.liveable(level, ground)
                || Villages.nearest(level, ground, Villages.VILLAGE_RANGE * 2) != null) {
            LAST.put(id, now - INTERVAL + 1200L);                // somewhere else, in a minute
            return false;
        }
        return found(level, mother, ground, now);
    }

    /** Send the party: the mother village pays the food, and a new village stands up. */
    public static boolean found(ServerLevel level, Villages.Village mother, BlockPos ground, long now) {
        UUID id = mother.id();
        int food = party() * FOOD_EACH;
        if (takeFood(level, mother, food) < food) {
            LAST.put(id, now - INTERVAL + 6000L);
            return false;
        }
        int stood = VillageFolkSpawnerBlock.raiseParty(level, ground, 0.0F, party());
        LAST.put(id, now);
        if (stood == 0) return false;
        Villages.noteColony(id);
        long day = level.getDayTime() / 24000L;
        Villages.Village colony = Villages.nearest(level, ground, 40);
        String colonyName = colony == null || colony.id().equals(id) ? "a new village" : Villages.name(colony.id());
        Villages.tell(id, day, "settlers left to found " + colonyName);
        if (colony != null && !colony.id().equals(id)) {
            Villages.tell(colony.id(), day, "settlers from " + Villages.name(id) + " founded " + colonyName);
            // Mother and daughter: a road between them, and caravans along it (Roads, Caravans).
            com.jrpetty.mcassistant.village.Ledger.link(id, colony.id());
        }
        // Like coming of age, a new village is a thing worth being told about.
        net.minecraft.network.chat.Component line = net.minecraft.network.chat.Component.literal(
            Villages.name(id) + " (" + mother.centre().getX() + ", " + mother.centre().getZ()
                + ") has founded " + colonyName + " at " + ground.getX() + ", " + ground.getZ() + ".")
            .withStyle(net.minecraft.ChatFormatting.GOLD);
        for (net.minecraft.server.level.ServerPlayer p : level.players()) p.sendSystemMessage(line);
        com.mojang.logging.LogUtils.getLogger().info("[MCA-COLONY] {} folk from {} founded a village at {}",
            stood, mother.centre(), ground);
        return true;
    }

    /** Take this much food out of the village's stores. Returns how much was taken. */
    private static int takeFood(ServerLevel level, Villages.Village v, int want) {
        int taken = 0;
        boolean before = ZoneChests.askAs(true);
        try {
            for (ZoneChests.Found f : ZoneChests.around(level, v.centre(), Villages.storesRadius(v.id()), 64)) {
                if (taken >= want) break;
                if (!f.stillThere() || !ZoneChests.isStashable(f)) continue;
                net.minecraft.world.Container c = f.container();
                for (int i = 0; i < c.getContainerSize() && taken < want; i++) {
                    net.minecraft.world.item.ItemStack st = c.getItem(i);
                    if (st.isEmpty() || st.get(net.minecraft.core.component.DataComponents.FOOD) == null) continue;
                    int n = Math.min(st.getCount(), want - taken);
                    st.shrink(n);
                    if (st.isEmpty()) c.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                    taken += n;
                }
                c.setChanged();
            }
        } finally {
            ZoneChests.askAs(before);
        }
        return taken;
    }
}
