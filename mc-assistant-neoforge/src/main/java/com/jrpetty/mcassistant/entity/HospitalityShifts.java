package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.Shifts.Shift;
import com.jrpetty.mcassistant.entity.Shifts.Task;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * [roles-c] The baker's hand's paid shift (ShiftRole.BAKE), worked the way the cook's is (ShiftTasks): the bake the stores
 * are shortest of (Bakery.next: bread, cookies, a pie, a cake) shows on the player's card; a right-click on the bakery's
 * oven puts it in, its makings out of the town's stores by the game's own recipes (Bench); a second right-click once it
 * is READY takes it out into the stores, counted with the day's bakes. Too soon and it is not done; too late and it is
 * burnt, the batch thrown out and the task unpaid. With the stores out of the makings meanwhile, the ticket goes back,
 * no fault of the player's.
 *
 * <p>Cost: nothing without a shift on; a task's bake is planned once, when one is wanted.
 */
public final class HospitalityShifts {

    private HospitalityShifts() {}

    /** A batch's time in the oven at a new hand (ticks); less for a practised one. */
    static final long BAKE_TICKS = 300L;
    /** How long after it is ready a batch can be taken out before it burns (ticks), and how much longer each level. */
    static final long BURN_WINDOW = 160L, BURN_PER_LEVEL = 20L;

    /** The bakery's oven, where a baker's hand works, and whether anything is short there now. Null with no bakery. */
    @Nullable
    static ShiftTasks.Post post(ServerLevel level, Villages.Village v) {
        Ledger.Building b = Bakery.bakery(v.id());
        if (b == null) return null;
        boolean ready = Bakery.next(level, v, null, Windmill.mill(v.id()) != null) != null;
        return new ShiftTasks.Post(Bakery.oven(b), null, ready, "Nothing's short at the bakery just now, or the stores are out of its makings.");
    }

    /** The bakery's first oven (a furnace of its drawing), or where the baker stands before them if there is none. */
    static BlockPos ovenBlock(ServerLevel level, Ledger.Building b) {
        for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
            if (p.part() == BuildGoal.Part.FURNACE && !level.getBlockState(p.pos()).isAir()) return p.pos();
        }
        return Bakery.oven(b);
    }

    /** The next batch for the shift: what the stores are shortest of, as the bench would make it now. */
    @Nullable
    static Task task(ServerLevel level, ServerPlayer p, Villages.Village v, long now) {
        Ledger.Building b = Bakery.bakery(v.id());
        if (b == null) return null;
        boolean milled = Windmill.mill(v.id()) != null;
        Object[] next = Bakery.next(level, v, null, milled);
        if (next == null) return null;
        Bakery.Bake bake = (Bakery.Bake) next[0];
        int n = bake.batch() * (milled && bake.what() != Items.CAKE ? 2 : 1);
        BlockPos oven = ovenBlock(level, b);
        Task t = new Task("baked", "Knead a batch of " + Bench.words(bake.what(), n) + " and put it in the oven: right-click the bakery's oven.",
            oven, now, walk(p.blockPosition(), oven) + BAKE_TICKS + ShiftTasks.HANDLING);
        t.item = bake.what();
        t.count = n;
        t.marks.add(oven);
        return t;
    }

    private static long walk(BlockPos a, BlockPos b) {
        return (long) (Math.sqrt(a.distSqr(b)) / ShiftTasks.WALK * 20);
    }

    /** How long a batch takes at this hand's level (quicker when the dining's quick clock is on, for the tests). */
    static long bakeTicks(int lv) {
        long ticks = (long) (BAKE_TICKS * Math.max(0.5, 1.1 - 0.1 * lv));
        return Dining.QUICK ? Math.max(10, ticks / 8) : ticks;
    }

    /** A right-click at work: the batch in (its makings out of the stores), or out into the stores. True if it was the task's. */
    static boolean use(ServerLevel level, ServerPlayer p, Shift s, Task t, BlockPos at) {
        if (t.target == null || t.item == null || Math.abs(at.getX() - t.target.getX()) > 1 || Math.abs(at.getY() - t.target.getY()) > 1
            || Math.abs(at.getZ() - t.target.getZ()) > 1) return false;
        Villages.Village v = Villages.get(s.village);
        Ledger.Building b = Bakery.bakery(s.village);
        if (v == null || b == null) { s.task = null; return false; }
        long now = level.getGameTime();
        String what = Bench.words(t.item, t.count);
        if (t.leg == 0) {
            Bench.Plan plan = Bench.plan(level, v, t.item, t.count, Bench.handOf(level, v, null, TownLook.BAKERY));
            if (!plan.ok() || plan.made <= 0 || !Bench.take(level, v, plan, null)) {
                s.task = null;
                p.sendSystemMessage(Component.literal("The stores are out of the makings for " + what + ": the ticket goes back. Not your fault.")
                    .withStyle(ChatFormatting.YELLOW));
                return true;
            }
            int lv = Shifts.level(p, s.role);
            t.count = plan.made;
            t.leg = 1;
            t.readyAt = now + bakeTicks(lv);
            t.burnAt = t.readyAt + BURN_WINDOW + BURN_PER_LEVEL * lv;
            t.text = "Baking " + what + ". Right-click the oven again when it's READY, before it burns.";
            level.playSound(null, t.target, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 0.7F, 1.0F);
            return true;
        }
        if (now < t.readyAt) {
            p.displayClientMessage(Component.literal("Not done yet: " + Shifts.secs(t.readyAt - now) + " more.").withStyle(ChatFormatting.YELLOW), true);
            return true;
        }
        if (now > t.burnAt) {
            burnt(level, p, s, t, what);
            return true;
        }
        Crafts.giveBack(level, v, t.item, t.count);
        Bakery.booked(level, v, b, new ItemStack(t.item, t.count));
        long late = now - t.readyAt, window = t.burnAt - t.readyAt;
        int lost = late > window * 2 / 3 ? 1 : 0;                             // caught at the last moment: a little dark
        Shifts.done(level, p, s, t, lost, 1, "baking " + what, null);
        return true;
    }

    private static void burnt(ServerLevel level, ServerPlayer p, Shift s, Task t, String what) {
        if (t.target != null) {
            level.sendParticles(ParticleTypes.LARGE_SMOKE, t.target.getX() + 0.5, t.target.getY() + 1.1, t.target.getZ() + 0.5, 10, 0.2, 0.2, 0.2, 0.01);
        }
        Shifts.failed(level, p, s, "Burnt! The batch of " + what + " is thrown out. Unpaid.");
    }

    /** Every second of a batch in the oven: smoke while it bakes, a sparkle when it is ready; left far too long, burnt. */
    static void watch(ServerLevel level, ServerPlayer p, Shift s, Task t) {
        if (t.leg != 1 || t.target == null || t.item == null) return;
        long now = level.getGameTime();
        level.sendParticles(now >= t.readyAt ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.SMOKE,
            t.target.getX() + 0.5, t.target.getY() + 1.1, t.target.getZ() + 0.5, 3, 0.2, 0.1, 0.2, 0.01);
        if (now > t.burnAt + BURN_WINDOW) burnt(level, p, s, t, Bench.words(t.item, t.count).toLowerCase(Locale.ROOT));
    }

    /** Tests: the batch in the oven ready now ({@code burnt}: left past burning). */
    public static void readyForTests(Player p, boolean burnt) {
        Shift s = Shifts.ON.get(p.getUUID());
        if (s != null && s.task != null && s.task.leg == 1) {
            long now = p.level().getGameTime();
            s.task.readyAt = burnt ? now - 2 * BURN_WINDOW : now;
            s.task.burnAt = burnt ? now - 1 : now + BURN_WINDOW;
        }
    }

    /** Tests: where the task wants the player's right-click, or null. */
    @Nullable
    public static BlockPos targetForTests(Player p) {
        Shift s = Shifts.ON.get(p.getUUID());
        return s == null || s.task == null ? null : s.task.target;
    }
}
