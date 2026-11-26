package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [featpa] The build itself (PaBuild.Run), as a player builds: walk within arm's reach of the next block, dig out what's
 * in the way (the ground freely; your own blocks only if you said "Clear them", and those go in its pack), the block in
 * its hand, a swing, the block set in its exact state with its own sound, the next. Layer by layer from the ground up.
 * Its pack run dry, it walks to your chest for more. Never a teleport: every step is its own feet on the game's paths.
 */
final class PaBuildGoal extends Goal {

    /** Arm's reach from its eyes to the block's middle. */
    static final double REACH = 4.5;
    /** Ticks without getting nearer before a block is left for later. */
    static final int STUCK = 100;

    private final PlayerAssistantEntity a;
    @Nullable private PaTask t;
    @Nullable private PaBuild.Run run;
    @Nullable private PaBuild.Work cur;
    private int curIdx = -1;
    private int cool, walkAt, pathAt, digLeft, digTotal;
    private double best;
    @Nullable private BlockPos chest;
    private int chestStage;
    private int stepAway;

    PaBuildGoal(PlayerAssistantEntity a) {
        this.a = a;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return a.task != null && a.task.kind == PaTask.Kind.BLUEPRINT && a.getTarget() == null && !a.plan.paused && !a.isSleeping();
    }

    @Override
    public boolean canContinueToUse() {
        return a.task == t && t != null && a.getTarget() == null && !a.plan.paused;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        t = a.task;
        run = PaBuild.run(a);
        if (run == null && t != null) run = PaBuild.restore(a, t);          // a restart: picked up from the task's note
        cur = null;
        curIdx = -1;
        chest = null;
        cool = 0;
        digLeft = 0;
    }

    @Override
    public void stop() {
        a.getNavigation().stop();
        if (cur != null && digLeft > 0) a.level().destroyBlockProgress(a.getId(), cur.pos(), -1);
        digLeft = 0;
    }

    private void finish() {
        if (t != null) {
            t.progress = 100;
            t.result = "";
        }
        a.task = null;
        a.equipBestWeapon();
    }

    @Override
    public void tick() {
        if (t == null) return;
        if (run == null || run.stopped) {
            if (run == null) PaTalk.reply(a, PaLogiTalk.line(a, "kit.bp_lost"));
            finish();
            return;
        }
        t.ticks++;
        if (cool > 0) { cool--; return; }
        ServerLevel level = (ServerLevel) a.level();
        if (chest != null) { fetch(level); return; }
        if (cur == null && !next(level)) { done(level); return; }
        work(level);
        t.progress = run.percent();
        PaPlan.Step step = a.plan.current;
        if (step != null && step.task == t)
            step.label = "build " + run.name + ": layer " + (run.layer + 1) + " of " + run.layers + " (" + (run.placed + run.dug) + "/" + run.total + ")";
    }

    // ------------------------------------------------------------------ what next

    /** The next spot that wants work: along the order, then (once) the ones left for later. */
    private boolean next(ServerLevel level) {
        PaBuild.Run r = run;
        for (int guard = 0; guard < 400; guard++) {
            int i;
            if (!r.retrying) {
                if (r.cursor >= r.order.size()) {
                    if (r.retry.isEmpty()) return false;
                    r.retrying = true;
                    continue;
                }
                i = r.cursor++;
            } else {
                if (r.retry.isEmpty()) return false;
                i = r.retry.remove(0);
            }
            PaBuild.Work w = r.order.get(i);
            if (!PaBuild.wanted(level, w)) continue;
            BlockState now = level.getBlockState(w.pos());
            boolean yours = !now.isAir() && !now.canBeReplaced() && !PaBlueprint.naturalAt(level, w.pos());
            if (yours && !r.clearMine) { r.aroundYours++; continue; }                 // never knocks down what you built, unless told
            if (!w.state().isAir()) {
                PaBlueprint.Cost c = PaBlueprint.costOf(w.state());
                if (c != null && r.missing.containsKey(c.item()) && countOf(c.item()) < c.n()) { r.missing.merge(c.item(), c.n(), Integer::sum); continue; }
            }
            cur = w;
            curIdx = i;
            r.layer = w.layer();
            walkAt = a.tickCount;
            pathAt = -100;
            best = Double.MAX_VALUE;
            digLeft = 0;
            stepAway = 0;
            return true;
        }
        return true;                                                          // a long run of finished spots: more next tick
    }

    private void leaveForLater(boolean unreachable) {
        PaBuild.Run r = run;
        if (!r.retrying && curIdx >= 0) r.retry.add(curIdx);
        else if (unreachable) r.unreachable++;
        cur = null;
    }

    // ------------------------------------------------------------------ the work at one spot

    private void work(ServerLevel level) {
        PaBuild.Work w = cur;
        if (w == null) return;
        BlockPos p = w.pos();
        if (!PaBuild.wanted(level, w)) { cur = null; return; }
        Vec3 mid = Vec3.atCenterOf(p);
        // standing in the block it means to fill: a step aside first
        if (!w.state().isAir() && a.getBoundingBox().intersects(new AABB(p))) {
            if (stepAway++ > 60) { leaveForLater(true); return; }
            Vec3 off = a.position().subtract(mid).multiply(1, 0, 1);
            if (off.lengthSqr() < 0.01) off = new Vec3(1, 0, 0);
            off = off.normalize().scale(2.5);
            if (a.getNavigation().isDone() || a.tickCount % 10 == 0) a.getNavigation().moveTo(a.getX() + off.x, a.getY(), a.getZ() + off.z, 1.0);
            return;
        }
        double d = a.getEyePosition().distanceTo(mid);
        if (d > REACH) {
            if (d < best - 0.3) { best = d; walkAt = a.tickCount; }
            if (a.tickCount - walkAt > STUCK) { a.getNavigation().stop(); leaveForLater(true); return; }
            if (a.getNavigation().isDone() || a.tickCount - pathAt >= 20) {
                pathAt = a.tickCount;
                a.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 1.0);
            }
            return;
        }
        a.getNavigation().stop();
        a.getLookControl().setLookAt(mid.x, mid.y, mid.z);
        BlockState now = level.getBlockState(p);
        boolean dig = !now.isAir() && now.getFluidState().isEmpty() && (w.state().isAir() || !now.canBeReplaced());
        if (dig) { dig(level, w, now); return; }
        if (w.state().isAir()) { cur = null; return; }
        place(level, w);
    }

    private void dig(ServerLevel level, PaBuild.Work w, BlockState now) {
        BlockPos p = w.pos();
        if (digLeft <= 0 && digTotal == 0) {
            a.equipBestTool(now);
            int ticks = now.canBeReplaced() ? 2 : Math.max(3, Math.min(120, a.workTicksFor(now)));
            if (PaBuild.paceForTests > 0) ticks = Math.min(ticks, PaBuild.paceForTests);
            digLeft = digTotal = ticks;
        }
        digLeft--;
        if (digLeft % 5 == 0) a.swing(InteractionHand.MAIN_HAND);
        level.destroyBlockProgress(a.getId(), p, Math.min(9, (int) (10.0 * (digTotal - digLeft) / Math.max(1, digTotal))));
        if (digLeft > 0) return;
        digTotal = 0;
        level.destroyBlockProgress(a.getId(), p, -1);
        BlockEntity be = level.getBlockEntity(p);
        boolean yours = !PaBlueprint.naturalAt(level, p);
        for (ItemStack drop : Block.getDrops(now, level, p, be, a, a.getMainHandItem())) {
            ItemStack left = a.insertItem(drop);
            if (!left.isEmpty()) Block.popResource(level, p, left);
            if (yours) run.keptOfYours += drop.getCount();
        }
        if (be instanceof Container c) {                                         // a chest of yours in the way: its things kept, never lost
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) continue;
                ItemStack left = a.insertItem(s.copy());
                if (!left.isEmpty()) Block.popResource(level, p, left);
                c.setItem(i, ItemStack.EMPTY);
            }
        }
        level.levelEvent(2001, p, Block.getId(now));                             // the break's own puff and sound
        level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        run.dug++;
        a.hunger.exhaust(0.005F);
        if (w.state().isAir()) cur = null;
        cool = 2;
    }

    private void place(ServerLevel level, PaBuild.Work w) {
        BlockPos p = w.pos();
        BlockState want = w.state();
        PaBlueprint.Cost c = PaBlueprint.costOf(want);
        if (c != null && countOf(c.item()) < c.n()) {
            // its pack is out of it: your chest near by, if one has some
            BlockPos from = chestWith(c.item());
            if (from != null) { chest = from; chestStage = 0; walkAt = a.tickCount; best = Double.MAX_VALUE; pathAt = -100; return; }
            run.missing.merge(c.item(), c.n(), Integer::sum);
            cur = null;
            return;
        }
        if (c != null) hold(c.item());
        a.swing(InteractionHand.MAIN_HAND);
        level.setBlock(p, want, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        BlockPos two = PaBuild.partner(p, want);
        if (two != null) {
            BlockState other = want.getBlock() instanceof DoorBlock ? want.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)
                : want.getBlock() instanceof BedBlock ? want.setValue(BedBlock.PART, BedPart.HEAD)
                : want.setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, DoubleBlockHalf.UPPER);
            level.setBlock(two, other, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        net.minecraft.nbt.CompoundTag tag = run.bp.tags.get(w.cell());
        if (tag != null && level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
            sign.loadWithComponents(tag, level.registryAccess());
            sign.setChanged();
            level.sendBlockUpdated(p, want, want, 3);
        }
        var sound = want.getSoundType(level, p, a);
        level.playSound(null, p, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        if (c != null) use(c.item(), c.n());
        run.placed++;
        a.hunger.exhaust(0.01F);
        run.last = BuiltInRegistries.BLOCK.getKey(want.getBlock()).getPath();
        cur = null;
        int pace = PaBuild.paceForTests > 0 ? PaBuild.paceForTests : Math.max(4, a.actionPaceTicks() / 2);
        cool = pace;
    }

    // ------------------------------------------------------------------ its hands and its pack

    private int countOf(Item item) {
        int n = a.getMainHandItem().is(item) ? a.getMainHandItem().getCount() : 0;
        for (ItemStack s : a.getInventoryItems()) if (s.is(item)) n += s.getCount();
        return n;
    }

    /** The block in its hand, as a player holds what they're placing (what was there goes back in the pack). */
    private void hold(Item item) {
        if (a.getMainHandItem().is(item)) return;
        var inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            if (!inv.get(i).is(item)) continue;
            ItemStack was = a.getMainHandItem();
            a.setItemSlot(EquipmentSlot.MAINHAND, inv.get(i));
            inv.set(i, was);
            return;
        }
    }

    private void use(Item item, int n) {
        ItemStack hand = a.getMainHandItem();
        int left = n;
        if (hand.is(item)) {
            int k = Math.min(left, hand.getCount());
            hand.shrink(k);
            left -= k;
            if (hand.isEmpty()) a.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        if (left > 0) a.removeMatching(s -> s.is(item), left);
    }

    @Nullable
    private BlockPos chestWith(Item item) {
        BlockPos bestPos = null;
        double bd = Double.MAX_VALUE;
        for (PaBuild.Chest ch : PaBuild.chests(a, run.anchor)) {
            if (ch.counts().getOrDefault(item, 0) <= 0) continue;
            double dd = ch.pos().distSqr(a.blockPosition());
            if (dd < bd) { bd = dd; bestPos = ch.pos(); }
        }
        return bestPos;
    }

    /** A trip to the chest: walk up, lid open, what the build still wants taken (what it's short of first), lid shut. */
    private void fetch(ServerLevel level) {
        BlockPos p = chest;
        if (p == null) return;
        Vec3 mid = Vec3.atCenterOf(p);
        double d = a.getEyePosition().distanceTo(mid);
        if (chestStage == 0) {
            if (d > 3.5) {
                if (d < best - 0.3) { best = d; walkAt = a.tickCount; }
                if (a.tickCount - walkAt > STUCK * 2) { chest = null; cur = null; return; }
                if (a.getNavigation().isDone() || a.tickCount - pathAt >= 20) { pathAt = a.tickCount; a.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 1.0); }
                return;
            }
            a.getNavigation().stop();
            a.getLookControl().setLookAt(mid.x, mid.y, mid.z);
            PaContainers.lid(a, p, true);
            chestStage = 1;
            cool = 6;
            return;
        }
        if (chestStage == 1) {
            Container c = PaContainers.open(a, p);
            if (c != null) {
                Map<Item, Integer> need = PaBuild.stillNeeded(level, run.bp, run.anchor, run.mirror, run.rot);
                Map<Item, Integer> want = new LinkedHashMap<>();
                if (cur != null) { PaBlueprint.Cost k = PaBlueprint.costOf(cur.state()); if (k != null) want.put(k.item(), need.getOrDefault(k.item(), k.n())); }
                need.forEach(want::putIfAbsent);
                int moves = 0;
                for (Map.Entry<Item, Integer> e : want.entrySet()) {
                    int still = e.getValue() - countOf(e.getKey());
                    while (still > 0 && moves < 12) {
                        Item it = e.getKey();
                        int got = PaContainers.takeOne(a, c, s -> s.is(it), still, 0, c.getContainerSize());
                        if (got <= 0) break;
                        still -= got;
                        moves++;
                    }
                    if (moves >= 12) break;
                }
                a.swing(InteractionHand.MAIN_HAND);
                PaContainers.snapshot(a, p, c);
            }
            chestStage = 2;
            cool = 8;
            return;
        }
        PaContainers.lid(a, p, false);
        chest = null;
        walkAt = a.tickCount;
        best = Double.MAX_VALUE;
    }

    // ------------------------------------------------------------------ the end

    private void done(ServerLevel level) {
        PaBuild.Run r = run;
        r.done = true;
        long mins = Math.max(1, (level.getGameTime() - r.started) / 1200);
        if (!r.missing.isEmpty()) {
            Map<Item, Integer> shortOf = new LinkedHashMap<>();
            Map<Item, Integer> need = PaBuild.stillNeeded(level, r.bp, r.anchor, r.mirror, r.rot);
            need.forEach((k, v) -> { int s = v - countOf(k); if (s > 0) shortOf.put(k, s); });
            r.missing.clear();
            r.missing.putAll(shortOf);
            if (!shortOf.isEmpty()) {
                r.done = false;
                List<PaUnderstand.Choice> ch = new java.util.ArrayList<>(PaBuild.shortChoices());
                ch.add(new PaUnderstand.Choice("Carry on", "#kit bp_resume"));
                PaTalk.reply(a, PaLogiTalk.line(a, "kit.bp_short_end", "item", r.name, "n", String.valueOf(r.placed), "what", PaBuild.words(shortOf, 5)), ch);
                finish();
                return;
            }
        }
        String extra = "";
        if (r.aroundYours > 0) extra = " " + PaLogiTalk.line(a, "kit.bp_around", "n", String.valueOf(r.aroundYours));
        if (r.unreachable > 0) extra += " " + PaLogiTalk.line(a, "kit.bp_unreachable", "n", String.valueOf(r.unreachable));
        if (r.keptOfYours > 0) extra += " " + PaLogiTalk.line(a, "kit.bp_kept", "n", String.valueOf(r.keptOfYours));
        PaTalk.reply(a, (PaLogiTalk.line(a, "kit.bp_done", "item", r.name, "n", String.valueOf(r.placed), "what", mins + (mins == 1 ? " minute" : " minutes")) + extra).trim());
        a.playAnim("cheer");
        PaBuild.endGhost(a, r);
        finish();
    }
}
