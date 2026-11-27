package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

/**
 * [pa38] Fishing as a player fishes, from level 3 (the gear ladder's rung for the rod): it finds open water and a bank
 * to stand on, casts a real hook (PaFishHook) at the water by eye, watches the float, and strikes when it dips. The game
 * sets the waits and the catch (its loot table); its level sets only how well it plays them: a little Lure and Luck of
 * its own (PaKnack, modest), and fewer bites lost (striking at the ripples too soon, or looking away as the float goes
 * under). A cast that lands on the bank is reeled in and tried again. The catch flies to its feet and into its pack.
 * Runs the engine's FISH job for a Player AI Assistant (the engine's own fisher stands aside: AssistantEntity.ownsJob).
 */
final class PaFishGoal extends Goal {

    enum Phase { WALK, AIM, WAIT, COLLECT }

    private final PlayerAssistantEntity a;
    @Nullable private Job job;
    private int gen;
    @Nullable private BlockPos water, stand;
    @Nullable private PaFishHook hook;
    private Phase phase = Phase.WALK;
    private int phaseAt, strikeAt = -1, fish, walkStuck, spots, dryCasts;
    /** Fish actually picked up into its pack this job (a fish reeled in can drift off on the water before it gets there). */
    private int landed;
    private boolean earlyRolled, strikingEarly, biteSeen;
    private double bestDist;
    private final List<ItemEntity> flying = new ArrayList<>();
    private Random rnd = new Random();

    PaFishGoal(PlayerAssistantEntity a) {
        this.a = a;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Job j = a.peekJob();
        return j != null && j.type() == Job.Type.FISH && a.getTarget() == null && !a.level().isClientSide;
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && a.getTarget() == null && a.taskGen() == gen && a.peekJob() == job;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        job = a.peekJob();
        gen = a.taskGen();
        fish = 0;
        landed = 0;
        spots = 0;
        dryCasts = 0;
        flying.clear();
        rnd = new Random(a.getRandom().nextLong());
        int lv = a.veteranLevel();
        if (!rodInHand()) {
            finish(PaLogiTalk.line(a, "field.fish_no_rod"), false);
            return;
        }
        if (!PaLevels.canUse(a, a.getMainHandItem())) {
            finish(PaTalk.line(a, "skill_locked", "need", String.valueOf(PaLevels.gearLevel(a.getMainHandItem())), "level", String.valueOf(lv)), false);
            return;
        }
        if (!findSpot()) {
            finish(PaLogiTalk.line(a, "field.fish_no_water"), false);
            return;
        }
        PaTalk.comment(a, 1, PaLogiTalk.line(a, "field.fish_start", "n", String.valueOf(job.amount())), true);
        to(Phase.WALK);
    }

    @Override
    public void stop() {
        if (hook != null && !hook.isRemoved()) hook.discard();
        hook = null;
        sweepUp(8.0);
        job = null;
        a.getNavigation().stop();
    }

    private void to(Phase p) {
        phase = p;
        phaseAt = a.tickCount;
        if (p == Phase.WALK) { walkStuck = 0; bestDist = Double.MAX_VALUE; }
    }

    private void finish(@Nullable String line, boolean productive) {
        if (line != null && !line.isBlank()) PaTalk.comment(a, 1, line, true);
        if (hook != null && !hook.isRemoved()) hook.discard();
        hook = null;
        sweepUp(8.0);
        a.noteJobOutcome(productive);
        if (job != null && a.peekJob() == job) a.pollJob();
        job = null;
        a.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (job == null) return;
        ItemStack rod = a.getMainHandItem();
        if (!rod.is(Items.FISHING_ROD)) {
            if (!rodInHand()) { finish(PaLogiTalk.line(a, "field.fish_rod_broke", "n", String.valueOf(landed)), landed > 0); return; }
            rod = a.getMainHandItem();
        }
        switch (phase) {
            case WALK -> walk();
            case AIM -> aim();
            case WAIT -> waitForBite(rod);
            case COLLECT -> collect();
        }
    }

    // ------------------------------------------------------------------ to the bank

    private void walk() {
        if (stand == null || water == null) { to(Phase.AIM); return; }
        Vec3 c = Vec3.atBottomCenterOf(stand);
        double d = a.position().distanceToSqr(c);
        if (d < 0.7 * 0.7 || (d < 1.6 * 1.6 && a.getNavigation().isDone())) {
            a.getNavigation().stop();
            to(Phase.AIM);
            return;
        }
        if (a.getNavigation().isDone()) a.getNavigation().moveTo(c.x, c.y, c.z, 1.0);
        if (d < bestDist - 0.25) { bestDist = d; walkStuck = 0; }
        else if (++walkStuck > 200) {
            if (++spots > 3 || !findSpot()) { finish(PaLogiTalk.line(a, "field.fish_no_water"), fish > 0); return; }
            to(Phase.WALK);
        }
    }

    // ------------------------------------------------------------------ the cast

    private void aim() {
        if (water == null) return;
        Vec3 eye = a.getEyePosition();
        Vec3 at = Vec3.atBottomCenterOf(water).add(0, 0.9, 0);
        double dx = at.x - eye.x, dz = at.z - eye.z;
        double yaw = PaArchery.yawTo(dx, dz), pitch = PaKnack.hookPitch(Math.hypot(dx, dz), at.y - eye.y);
        int pace = Math.max(PaPace.hand(a), 12);
        PaBow.face(a, yaw, pitch);
        if (a.tickCount - phaseAt < pace) return;                               // a moment to look and settle the rod
        double sway = PaKnack.castSway(a.veteranLevel());
        PaBow.face(a, yaw + rnd.nextGaussian() * sway, pitch + rnd.nextGaussian() * sway);
        ServerLevel sl = (ServerLevel) a.level();
        ItemStack rod = a.getMainHandItem();
        int lv = a.veteranLevel();
        float luck = EnchantmentHelper.getFishingLuckBonus(sl, rod, a) + (float) PaKnack.luck(lv);
        int lure = (int) (EnchantmentHelper.getFishingTimeReduction(sl, rod, a) * 20.0F) + PaKnack.lureTicks(lv);
        a.swing(InteractionHand.MAIN_HAND);
        a.level().playSound(null, a.getX(), a.getY(), a.getZ(), SoundEvents.FISHING_BOBBER_THROW, SoundSource.NEUTRAL, 0.5F,
            0.4F / (a.getRandom().nextFloat() * 0.4F + 0.8F));
        hook = PaFishHook.cast(a, luck, lure);
        a.level().addFreshEntity(hook);
        a.mind.field.casts++;
        strikeAt = -1;
        earlyRolled = false;
        strikingEarly = false;
        biteSeen = false;
        to(Phase.WAIT);
    }

    // ------------------------------------------------------------------ watching the float

    private void waitForBite(ItemStack rod) {
        if (hook == null || hook.isRemoved()) { hook = null; to(Phase.AIM); return; }
        a.getLookControl().setLookAt(hook.getX(), hook.getY(), hook.getZ(), 30.0F, 30.0F);
        int waited = a.tickCount - phaseAt;
        if (!hook.bobbing() && (hook.onGround() || waited > 60)) {           // on the bank, or caught up in something
            a.mind.field.missedCasts++;
            if (++dryCasts % 3 == 1) PaTalk.comment(a, 2, PaLogiTalk.line(a, "field.fish_missed"), true);
            reel(rod);
            if (dryCasts > 6 && !findSpot()) { finish(PaLogiTalk.line(a, "field.fish_no_water"), fish > 0); return; }
            to(dryCasts > 6 ? Phase.WALK : Phase.AIM);
            return;
        }
        int lv = a.veteranLevel();
        if (hook.fishComing() && !earlyRolled) {                              // the ripples: a learner may strike now
            earlyRolled = true;
            if (PaKnack.roll(PaKnack.earlyStrike(lv), rnd)) {
                strikingEarly = true;
                strikeAt = a.tickCount + Math.max(3, PaArchery.row(lv).reactionTicks());
            }
        }
        if (hook.biting() && !biteSeen) {
            biteSeen = true;
            if (!strikingEarly) strikeAt = a.tickCount + PaKnack.strikeTicks(lv, rnd);
        }
        if (strikeAt >= 0 && a.tickCount >= strikeAt) {
            PaFishHook.Reel r = reel(rod);
            onReel(r);
            return;
        }
        if (waited > 1800) { reel(rod); to(Phase.AIM); }                      // nothing for a minute and a half: cast again
    }

    private PaFishHook.Reel reel(ItemStack rod) {
        PaFishHook h = hook;
        hook = null;
        if (h == null || h.isRemoved()) return new PaFishHook.Reel(0, List.of(), false, true);
        List<ItemEntity> before = a.level().getEntitiesOfClass(ItemEntity.class, h.getBoundingBox().inflate(1.0));
        PaFishHook.Reel r = h.reel(rod);
        for (ItemEntity ie : a.level().getEntitiesOfClass(ItemEntity.class, h.getBoundingBox().inflate(1.0))) if (!before.contains(ie)) flying.add(ie);
        if (r.damage() > 0) rod.hurtAndBreak(r.damage(), a, EquipmentSlot.MAINHAND);
        a.swing(InteractionHand.MAIN_HAND);
        a.level().playSound(null, a.getX(), a.getY(), a.getZ(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.NEUTRAL, 1.0F,
            0.4F / (a.getRandom().nextFloat() * 0.4F + 0.8F));
        return r;
    }

    private void onReel(PaFishHook.Reel r) {
        PaField f = a.mind.field;
        if (r.empty()) {
            if (strikingEarly) {
                f.early++;
                PaTalk.comment(a, 2, PaLogiTalk.line(a, "field.fish_early"), true);
            } else if (biteSeen) {
                f.lost++;
                PaTalk.comment(a, 2, PaLogiTalk.line(a, "field.fish_lost"), true);
            }
            to(Phase.AIM);
            return;
        }
        int got = 0;
        for (ItemStack s : r.caught()) {
            if (s.is(ItemTags.FISHES)) got += s.getCount();
            else if (treasure(s)) { f.treasure++; PaTalk.comment(a, 1, PaLogiTalk.line(a, "field.fish_treasure", "item", s.getHoverName().getString().toLowerCase(java.util.Locale.ROOT)), true); }
            else { f.junk++; PaTalk.comment(a, 3, PaLogiTalk.line(a, "field.fish_junk", "item", s.getHoverName().getString().toLowerCase(java.util.Locale.ROOT)), true); }
        }
        f.catches++;
        fish += got;
        if (got > 0) a.note(AssistantEntity.Deed.FISH_CAUGHT, got);
        a.awardXp(1 + a.getRandom().nextInt(6));                              // the game's orb for a catch, into its own bank
        a.earn("fish");
        to(Phase.COLLECT);
    }

    /** The game's treasure: books, bows and rods (enchanted), a saddle, a name tag, a nautilus shell, a lily pad is junk. */
    static boolean treasure(ItemStack s) {
        return s.is(Items.ENCHANTED_BOOK) || s.is(Items.NAME_TAG) || s.is(Items.SADDLE) || s.is(Items.NAUTILUS_SHELL)
            || (s.is(Items.BOW) || s.is(Items.FISHING_ROD)) && s.isEnchanted();
    }

    // ------------------------------------------------------------------ the catch into its pack

    private void collect() {
        int age = a.tickCount - phaseAt;
        sweepUp(age > 30 ? 6.0 : 1.8);
        if (!flying.isEmpty() && age < 60) return;
        flying.clear();
        // Done when the fish are in its pack, not when they were reeled in: one that drifted off on the water is fished again.
        if (job != null && landed >= job.amount()) {
            finish(PaLogiTalk.line(a, "field.fish_done", "n", String.valueOf(landed)), true);
            return;
        }
        if (PaContainers.packRoom(a, new ItemStack(Items.COD)) <= 0) {
            finish(PaLogiTalk.line(a, "field.fish_full", "n", String.valueOf(landed)), landed > 0);
            return;
        }
        to(Phase.AIM);
    }

    /** What it reeled in, into its pack once it's near enough (as a player picks up their catch). */
    private void sweepUp(double reach) {
        for (java.util.Iterator<ItemEntity> it = flying.iterator(); it.hasNext(); ) {
            ItemEntity ie = it.next();
            if (ie.isRemoved()) { it.remove(); continue; }
            if (ie.distanceToSqr(a) > reach * reach) continue;
            ItemStack s = ie.getItem();
            int n = s.getCount();
            ItemStack left = a.insertGiven(s.copy());
            int took = n - left.getCount();
            if (took > 0) {
                if (s.is(ItemTags.FISHES)) landed += took;
                a.take(ie, took);
                a.level().playSound(null, a.getX(), a.getY(), a.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F,
                    ((a.getRandom().nextFloat() - a.getRandom().nextFloat()) * 0.7F + 1.0F) * 2.0F);
            }
            if (left.isEmpty()) { ie.discard(); it.remove(); }
            else ie.setItem(left);
        }
    }

    // ------------------------------------------------------------------ the rod and the spot

    private boolean rodInHand() {
        if (a.getMainHandItem().is(Items.FISHING_ROD)) return true;
        var inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).is(Items.FISHING_ROD)) {
                ItemStack old = a.getMainHandItem();
                a.setItemSlot(EquipmentSlot.MAINHAND, inv.get(i));
                inv.set(i, old);
                return true;
            }
        }
        return false;
    }

    /** Open water near it (or near its player), and a bank to cast from: a 5x5 of water scores best (the treasure needs it). */
    private boolean findSpot() {
        Level l = a.level();
        BlockPos from = a.blockPosition();
        Spot best = null;
        List<BlockPos> surfaces = new ArrayList<>();
        for (int dx = -14; dx <= 14; dx++) for (int dz = -14; dz <= 14; dz++) {
            for (int dy = 4; dy >= -6; dy--) {
                BlockPos p = from.offset(dx, dy, dz);
                if (!l.getFluidState(p).is(FluidTags.WATER) || !l.getFluidState(p).isSource()) continue;
                BlockState up = l.getBlockState(p.above());
                if (up.isAir() || up.is(net.minecraft.world.level.block.Blocks.LILY_PAD)) surfaces.add(p.immutable());
                break;
            }
        }
        surfaces.sort((x, y) -> Double.compare(x.distSqr(from), y.distSqr(from)));
        int tried = 0;
        for (BlockPos w : surfaces) {
            if (tried++ > 160) break;
            if (water != null && w.equals(water) && spots > 0) continue;
            int open = 0;
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                if (l.getFluidState(w.offset(x, 0, z)).is(FluidTags.WATER)) open++;
                if (l.getFluidState(w.offset(x, -1, z)).is(FluidTags.WATER)) open++;
            }
            if (open < 12) continue;
            for (int r = 2; r <= 6; r++) for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
                if (Math.max(Math.abs(x), Math.abs(z)) != r) continue;
                for (int y = 1; y <= 3; y++) {
                    BlockPos s = w.offset(x, y, z);
                    if (!standable(l, s)) continue;
                    double h = Math.hypot(x, z);
                    if (h < 2.5 || h > 7.5) continue;
                    double score = open * 1.5 - Math.sqrt(s.distSqr(from)) * 0.4 - Math.abs(h - 4.5) - y * 0.5;
                    if (best == null || score > best.score) best = new Spot(w, s, score);
                }
            }
        }
        if (best == null) return false;
        water = best.water;
        stand = best.stand;
        return true;
    }

    private record Spot(BlockPos water, BlockPos stand, double score) {}

    private static boolean standable(Level l, BlockPos feet) {
        BlockPos below = feet.below();
        return l.getBlockState(below).isFaceSturdy(l, below, Direction.UP) && l.getFluidState(below).isEmpty()
            && l.getBlockState(feet).getCollisionShape(l, feet).isEmpty() && l.getFluidState(feet).isEmpty()
            && l.getBlockState(feet.above()).getCollisionShape(l, feet.above()).isEmpty();
    }
}
