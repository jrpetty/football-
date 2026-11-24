package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

/**
 * [pa38] A learner's slips, at low level, fading to none by level 8 (the rates are PaKnack's, shown on the Progress tab):
 * a block set one cell off and put right at once with a dry word ("Ignore that. Artistic choice."), a few steps the wrong
 * way on a long walk before it re-paths, and the wrong tool picked up for a new job before it switches. Never harmful: a
 * misplaced block only ever goes into empty air, is a common building block from its own pack, and comes straight back
 * out; it never breaks anything of yours to do it, and never spends anything rare. No two slips within two minutes.
 *
 * <p>The game tests switch the slips off (their timings and counts are exact) unless a test asks for them.
 */
public final class PaSlips {

    private PaSlips() {}

    /** Set by a game test that wants the slips (the rest run without them). */
    public static volatile boolean testing;
    /** A game test's own dice (a fixed seed, so the test sees the same slips every run), or null for the assistant's. */
    public static volatile Random testDice;

    /** Are the slips on? (Off in the game-test server unless a test asks.) */
    static boolean on() {
        return testing || System.getProperty("neoforge.enabledGameTestNamespaces") == null;
    }

    private static Random dice(PlayerAssistantEntity a) {
        Random d = testDice;
        return d != null ? d : new Random(a.getRandom().nextLong());
    }

    private static boolean due(PlayerAssistantEntity a) {
        return on() && a.tickCount - a.mind.field.lastSlip >= PaKnack.SLIP_GAP;
    }

    // ------------------------------------------------------------------ a block set one cell off

    /** A common building block from its own pack (never anything rare, never anything with an inside). */
    static boolean common(BlockState st) {
        if (st.hasBlockEntity() || st.isAir()) return false;
        String p = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
        if (p.contains("ore") || p.contains("diamond") || p.contains("emerald") || p.contains("gold") || p.contains("iron") || p.contains("netherite")
            || p.contains("lapis") || p.contains("amethyst") || p.contains("beacon")) return false;
        return p.matches(".*(cobblestone|stone|planks|log|wood|dirt|glass|sand|gravel|bricks|deepslate|andesite|diorite|granite|tuff|terracotta|wool|mud).*")
            && !p.contains("button") && !p.contains("pressure") && !p.contains("door") && !p.contains("sign");
    }

    /** It has just laid this part of a building: now and then (at a low level) one more of the same goes one cell off. */
    public static void afterPlace(PlayerAssistantEntity a, BlockPos pos, BlockState st) {
        PaField f = a.mind.field;
        if (f.fixAt != null || !due(a) || !common(st)) return;
        Random r = dice(a);
        if (!PaKnack.roll(PaKnack.misplace(a.veteranLevel()), r)) return;
        net.minecraft.world.item.Item item = st.getBlock().asItem();
        if (!(item instanceof BlockItem) || a.countMatching(s -> s.is(item)) < 1) return;
        Direction[] sides = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        for (int k = 0; k < sides.length; k++) {
            Direction d = sides[(r.nextInt(sides.length) + k) % sides.length];
            BlockPos n = pos.relative(d);
            if (!a.level().getBlockState(n).isAir() || !a.inZone(n)) continue;           // only ever empty air, never anyone's build
            if (n.distToCenterSqr(a.getEyePosition()) > a.reach() * a.reach()) continue;
            if (n.equals(a.blockPosition()) || n.equals(a.blockPosition().above())) continue;
            if (a.removeMatching(s -> s.is(item), 1) < 1) return;
            a.level().setBlockAndUpdate(n, st.getBlock().defaultBlockState());
            var snd = st.getSoundType();
            a.level().playSound(null, n, snd.getPlaceSound(), SoundSource.BLOCKS, (snd.getVolume() + 1.0F) / 2.0F, snd.getPitch() * 0.8F);
            f.fixAt = n.immutable();
            f.fixTick = a.tickCount + 20 + r.nextInt(20);
            f.lastSlip = a.tickCount;
            f.misplaced++;
            return;
        }
    }

    /** A misplaced block, put right: out again (back into its pack), with its word. */
    static void fix(PlayerAssistantEntity a) {
        PaField f = a.mind.field;
        if (f.fixAt == null || a.tickCount < f.fixTick) return;
        BlockPos at = f.fixAt;
        f.fixAt = null;
        BlockState st = a.level().getBlockState(at);
        if (st.isAir() || !common(st)) return;                                               // gone already, or something else there now
        a.getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
        a.swing(InteractionHand.MAIN_HAND);
        a.level().destroyBlock(at, false, a);
        ItemStack back = new ItemStack(st.getBlock().asItem());
        ItemStack left = a.insertGiven(back);
        if (!left.isEmpty()) a.spawnAtLocation(left);
        PaTalk.comment(a, 1, PaLogiTalk.line(a, "field.slip_block"), true);
    }

    // ------------------------------------------------------------------ the wrong tool first

    /**
     * Before the engine picks the best tool for a block: true when it has just picked up the wrong one for a new job
     * (and keeps it for a swing), so the engine leaves it be for now.
     */
    public static boolean wrongTool(PlayerAssistantEntity a, BlockState st) {
        PaField f = a.mind.field;
        if (f.toolFor != null) {
            if (a.tickCount < f.toolSwitchAt) return true;                                   // a swing or two with it
            f.toolFor = null;                                                                // and now the right one, with a word
            PaTalk.comment(a, 2, PaLogiTalk.line(a, "field.slip_tool"), true);
            return false;
        }
        boolean newJob = st.getBlock() != f.lastToolBlock || a.tickCount - f.lastToolJob > 600;
        f.lastToolBlock = st.getBlock();
        f.lastToolJob = a.tickCount;
        if (!newJob || !due(a)) return false;
        Random r = dice(a);
        if (!PaKnack.roll(PaKnack.wrongTool(a.veteranLevel()), r)) return false;
        float best = a.getMainHandItem().getDestroySpeed(st);
        var inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) best = Math.max(best, inv.get(i).getDestroySpeed(st));
        int pick = -1;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !s.isDamageableItem() || PaIdleTake.valuable(s) || s.getDestroySpeed(st) >= best * 0.6F) continue;
            String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            if (!path.matches("(wooden|stone)_(pickaxe|axe|shovel|hoe)|shears")) continue;   // a cheap tool, never a good one worn down
            pick = i;
            break;
        }
        if (pick < 0) return false;
        ItemStack held = a.getMainHandItem();
        a.setItemSlot(EquipmentSlot.MAINHAND, inv.get(pick));
        inv.set(pick, held);
        f.toolFor = st;
        f.toolSwitchAt = a.tickCount + 12 + r.nextInt(8);
        f.lastSlip = a.tickCount;
        f.wrongTools++;
        return true;
    }

    // ------------------------------------------------------------------ the wrong turn

    private static final Map<PlayerAssistantEntity, Path> SEEN = new WeakHashMap<>();

    /** Every tick: a new long walk now and then starts the wrong way; after a few steps it stops, turns, and re-paths. */
    static void walk(PlayerAssistantEntity a) {
        PaField f = a.mind.field;
        PathNavigation nav = a.getNavigation();
        Path p = nav.getPath();
        if (f.detour != null) {
            boolean there = a.position().distanceToSqr(f.detour) < 2.0 * 2.0;
            if (p == null || !p.getTarget().closerToCenterThan(f.detour, 2.5) && SEEN.get(a) != p) { f.detour = null; f.meant = null; return; }   // someone else changed the plan
            if (there || a.tickCount - f.detourAt > 50) {
                Vec3 m = f.meant;
                f.detour = null;
                f.meant = null;
                if (m != null) {
                    nav.moveTo(m.x, m.y, m.z, f.meantSpeed);
                    SEEN.put(a, nav.getPath());
                    PaTalk.comment(a, 2, PaLogiTalk.line(a, "field.slip_turn"), true);
                }
            }
            return;
        }
        if (p == null || p == SEEN.get(a)) return;
        SEEN.put(a, p);
        if (p.isDone() || a.getTarget() != null || a.mind.come != null || a.plan.current == null || !due(a)) return;
        BlockPos goal = p.getTarget();
        if (goal.distToCenterSqr(a.position()) < 20 * 20) return;
        Random r = dice(a);
        if (!PaKnack.roll(PaKnack.wrongTurn(a.veteranLevel()), r)) return;
        Vec3 dir = Vec3.atCenterOf(goal).subtract(a.position()).multiply(1, 0, 1).normalize();
        double ang = Math.toRadians((r.nextBoolean() ? 1 : -1) * (100 + r.nextInt(50)));
        Vec3 off = new Vec3(dir.x * Math.cos(ang) - dir.z * Math.sin(ang), 0, dir.x * Math.sin(ang) + dir.z * Math.cos(ang)).scale(7);
        BlockPos d = a.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(a.position().add(off)));
        if (Math.abs(d.getY() - a.getY()) > 3) return;
        f.meant = Vec3.atBottomCenterOf(goal);
        f.meantSpeed = 1.0;
        f.detour = Vec3.atBottomCenterOf(d);
        f.detourAt = a.tickCount;
        if (!nav.moveTo(f.detour.x, f.detour.y, f.detour.z, 1.0)) { f.detour = null; f.meant = null; return; }
        SEEN.put(a, nav.getPath());
        f.lastSlip = a.tickCount;
        f.wrongTurns++;
    }
}
