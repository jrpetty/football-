package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.PortalShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Nether Age, lived: the village lights its gateway (flint and steel: a flint and an iron
 * out of the stores) and, every two days or so, sends a party through — a guard and two miners
 * — who come back half a day later with what the Nether has: blaze rods for the brewer's fire,
 * nether wart, soul sand, quartz, glowstone, gold. Sometimes somebody comes back hurt; very
 * rarely somebody doesn't come back. So the brewer's first kit is the last the village is
 * given: after that, the Nether keeps it.
 *
 * <p>The party walks to the gateway and waits by it while they are "through"; the Nether
 * itself is told, not walked — a village's folk on the far side of a portal would be out of
 * reach of everything that keeps them working.
 */
public final class Nether {

    private Nether() {}

    /** A party through the gateway: who, and when they come back. */
    record Party(List<UUID> folk, long back) {}

    private static final Map<UUID, Party> OUT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        OUT.clear();
        LOOKED.clear();
    }

    /** Is this folk away through the gateway (it waits by it, and works at nothing else)? */
    public static boolean away(VillageFolkEntity f) {
        UUID village = f.ownerId();
        Party p = village == null ? null : OUT.get(village);
        return p != null && p.folk().contains(f.getUUID());
    }

    /** The gateway's look round, once a minute (Land's village tick). */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        if (now - LOOKED.getOrDefault(id, -100000L) < 1200L) return;
        LOOKED.put(id, now);
        if (Villages.ageOf(id) != Villages.Age.NETHER || !Villages.hasBuilt(id, "gateway")) return;
        BlockPos gate = Villages.builtAt(id, "gateway");
        if (gate == null || !Land.areaLoaded(level, gate, 10)) return;
        if (!lit(level, gate) && !light(level, v, gate)) return;
        Party out = OUT.get(id);
        long day = level.getDayTime() / 24000L;
        if (out != null) {
            if (now >= out.back()) comeBack(level, v, out, day);
            else for (UUID u : out.folk()) {
                if (level.getEntity(u) instanceof VillageFolkEntity f && f.distanceToSqr(gate.getX() + 0.5, gate.getY(), gate.getZ() + 0.5) > 25.0) {
                    f.getNavigation().moveTo(gate.getX() + 0.5, gate.getY() + 1, gate.getZ() + 0.5, 1.0D);
                }
            }
            return;
        }
        // A party every other day, by daylight, when there are hands to send.
        String last = Ledger.note(id, "nether.last");
        long lastDay = -10;
        try { if (last != null) lastDay = Long.parseLong(last); } catch (NumberFormatException ignored) { }
        long t = level.getDayTime() % 24000L;
        if (day - lastDay < 2 || t < 1000L || t > 6000L || Raids.underAlarm(id)) return;
        List<UUID> party = new ArrayList<>();
        VillageFolkEntity guard = null;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby()) continue;
            if (guard == null && f.stationTask() == AssistantEntity.StationTask.GUARD) { guard = f; party.add(f.getUUID()); }
        }
        // [individual] One who dreams of the Nether volunteers first; one afraid of it never goes (Dreams, Fears).
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (party.size() >= 3) break;
            if (a instanceof VillageFolkEntity f && Dreams.volunteersForNether(f) && !party.contains(f.getUUID())) party.add(f.getUUID());
        }
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (party.size() >= 3) break;
            if (a instanceof VillageFolkEntity f && !f.isBaby() && f.stationTask() == AssistantEntity.StationTask.MINE
                    && !Fears.staysThisSide(f) && !party.contains(f.getUUID())) party.add(f.getUUID());
        }
        if (party.size() < 2) return;
        // Not without provisions: four of food a head out of the stores, and a torch or two.
        java.util.function.Predicate<ItemStack> food = s -> s.get(net.minecraft.core.component.DataComponents.FOOD) != null
            && !s.is(Items.ROTTEN_FLESH) && !s.is(Items.SPIDER_EYE);
        if (Market.stock(level, id, food) < 4 * party.size() || !Crafts.take(level, v, food, 4 * party.size())) return;
        Crafts.take(level, v, s -> s.is(Items.TORCH), 4);
        Ledger.note(id, "nether.last", Long.toString(day));
        OUT.put(id, new Party(party, now + 6000L));
        List<String> names = new ArrayList<>();
        for (UUID u : party) if (level.getEntity(u) instanceof VillageFolkEntity f) {
            names.add(f.displayNameCap());
            f.clearQueue();
            f.brain("through the gateway into the Nether");
            f.getNavigation().moveTo(gate.getX() + 0.5, gate.getY() + 1, gate.getZ() + 0.5, 1.0D);
        }
        Villages.tell(id, day, String.join(", ", names) + " went through the gateway into the Nether");
    }

    /** The party home, and what it brought. */
    private static void comeBack(ServerLevel level, Villages.Village v, Party party, long day) {
        UUID id = v.id();
        OUT.remove(id);
        net.minecraft.util.RandomSource r = level.getRandom();
        int size = party.folk().size();
        // What they could bring back is what they had the means to get: blaze rods only with a
        // blade or a bow to take them, quartz and glowstone only with a pick to dig them; and the
        // tools come back the worse for it.
        boolean armed = false, picks = false;
        for (UUID u : party.folk()) {
            if (!(level.getEntity(u) instanceof VillageFolkEntity f)) continue;
            if (f.countMatching(s -> s.getItem() instanceof net.minecraft.world.item.SwordItem || s.getItem() instanceof net.minecraft.world.item.BowItem) > 0) armed = true;
            if (f.countMatching(s -> s.getItem() instanceof net.minecraft.world.item.PickaxeItem) > 0) picks = true;
            for (int i = 0; i < 12; i++) f.damageHeldTool();
            f.individual().beenNether = true;                   // [individual] been through and back: a dream, a friend's courage
        }
        List<ItemStack> haul = new ArrayList<>();
        if (armed) haul.add(new ItemStack(Items.BLAZE_ROD, 1 + r.nextInt(2 + size)));
        haul.add(new ItemStack(Items.NETHER_WART, 2 + r.nextInt(6)));
        haul.add(new ItemStack(Items.SOUL_SAND, 2 + r.nextInt(4)));
        if (picks) haul.add(new ItemStack(Items.QUARTZ, 4 + r.nextInt(12)));
        if (picks) haul.add(new ItemStack(Items.GLOWSTONE_DUST, 2 + r.nextInt(6)));
        if (picks) haul.add(new ItemStack(Items.GOLD_NUGGET, 4 + r.nextInt(12)));
        if (armed && r.nextInt(4) == 0) haul.add(new ItemStack(Items.MAGMA_CREAM, 1 + r.nextInt(2)));
        if (armed && r.nextInt(5) == 0) haul.add(new ItemStack(Items.GHAST_TEAR));
        List<String> what = new ArrayList<>();
        for (ItemStack s : haul) {
            what.add(s.getCount() + " " + s.getHoverName().getString().toLowerCase());
            Crafts.store(level, v, s);
        }
        // The Nether is not kind: now and then somebody comes back hurt, and very rarely not at all.
        String fate = "";
        for (UUID u : party.folk()) {
            if (!(level.getEntity(u) instanceof VillageFolkEntity f)) continue;
            f.brain("back from the Nether");
            int roll = r.nextInt(100);
            if (roll < 3 && f.stationTask() != AssistantEntity.StationTask.GUARD) {
                fate = "; " + f.displayNameCap() + " never came back";
                f.hurt(level.damageSources().inFire(), Float.MAX_VALUE);
            } else if (roll < 25) {
                f.setHealth(Math.max(2.0F, f.getHealth() * 0.4F));
                f.setRemainingFireTicks(40);
            }
        }
        Villages.tell(id, day, "the party came back through the gateway with " + String.join(", ", what) + fate);
    }

    /** Is there a lit portal in the gateway? */
    static boolean lit(ServerLevel level, BlockPos gate) {
        for (BlockPos p : BlockPos.betweenClosed(gate.offset(-6, -1, -6), gate.offset(6, 7, 6))) {
            if (level.getBlockState(p).is(Blocks.NETHER_PORTAL)) return true;
        }
        return false;
    }

    /** Light the gateway with flint and steel (a flint and an iron out of the stores). */
    static boolean light(ServerLevel level, Villages.Village v, BlockPos gate) {
        Optional<PortalShape> shape = Optional.empty();
        for (BlockPos p : BlockPos.betweenClosed(gate.offset(-6, -1, -6), gate.offset(6, 6, 6))) {
            if (!level.getBlockState(p).isAir() || !level.getBlockState(p.below()).is(Blocks.OBSIDIAN)) continue;
            for (Direction.Axis axis : new Direction.Axis[]{ Direction.Axis.X, Direction.Axis.Z }) {
                shape = PortalShape.findEmptyPortalShape(level, p.immutable(), axis);
                if (shape.isPresent()) break;
            }
            if (shape.isPresent()) break;
        }
        if (shape.isEmpty()) return false;
        boolean steel = Crafts.take(level, v, s -> s.is(Items.FLINT_AND_STEEL), 1);
        if (!steel && !(Crafts.stock(level, v, s -> s.is(Items.FLINT)) >= 1 && Crafts.stock(level, v, s -> s.is(Items.IRON_INGOT)) >= 1
                && Crafts.take(level, v, s -> s.is(Items.FLINT), 1) && Crafts.take(level, v, s -> s.is(Items.IRON_INGOT), 1))) return false;
        shape.get().createPortalBlocks();
        Villages.tell(v.id(), level.getDayTime() / 24000L, "the gateway was lit, and the Nether lay open");
        return true;
    }
}
