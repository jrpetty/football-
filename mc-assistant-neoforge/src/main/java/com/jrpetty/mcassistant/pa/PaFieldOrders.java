package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.CraftPlanner;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;

/**
 * [pa38] The orders for its rod, throws, mace, cure and bookshelves: "throw snowballs at that", "hook that", "smash
 * that with the mace", "cure that zombie villager", "put bookshelves round the table", and the level and kit each needs
 * ("go fishing" and "catch me 10 fish" plan through PaOrders, checked here).
 */
final class PaFieldOrders {

    private PaFieldOrders() {}

    /** What was pointed at or pinged, if it's a living thing that isn't a player or another assistant. */
    @Nullable
    static LivingEntity pointed(PlayerAssistantEntity a, PaOrders.Look look) {
        if (look.entity() < 0) return null;
        Entity e = a.level().getEntity(look.entity());
        return e instanceof LivingEntity le && le != a && !(le instanceof net.minecraft.world.entity.player.Player)
            && !(le instanceof com.jrpetty.mcassistant.entity.AssistantEntity) ? le : null;
    }

    /** The nearest monster after its player (or near it), when nothing was pointed at. */
    @Nullable
    static LivingEntity nearestThreat(PlayerAssistantEntity a, ServerPlayer p, String kind) {
        LivingEntity best = null;
        double bd = 20 * 20;
        for (Monster m : a.level().getEntitiesOfClass(Monster.class, a.getBoundingBox().inflate(20))) {
            if (!m.isAlive()) continue;
            if (!kind.isEmpty() && !net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).getPath().contains(kind)) continue;
            double d = m.distanceToSqr(p) - (m.getTarget() == p ? 100 : 0);
            if (d < bd) { bd = d; best = m; }
        }
        return best;
    }

    /** The orders answered at once (a throw, a hook, a smash, a cure): the reply, or why not. */
    @Nullable
    static String now(ServerPlayer p, PlayerAssistantEntity a, PaIntent i, PaOrders.Look look) {
        PaField f = a.mind.field;
        switch (i.kind()) {
            case THROW -> {
                String lock = PaOrders.locked(a, "throw");
                if (lock != null) return lock;
                String with = i.item().startsWith("egg") ? "egg" : i.item().startsWith("snow") ? "snowball" : "";
                if (PaThrowGoal.ammo(a, with).isEmpty()) return PaLogiTalk.line(a, "field.throw_none");
                LivingEntity t = pointed(a, look);
                if (t == null) t = nearestThreat(a, p, "");
                if (t == null) return PaLogiTalk.line(a, "field.nothing_there");
                f.throwAt = t.getUUID();
                f.throwWith = with;
                f.throwsLeft = i.amount() > 0 ? Math.min(16, i.amount()) : 5;
                f.throwUntil = a.tickCount + 600;
                return PaLogiTalk.line(a, "field.throw_ok", "item", PaThrows.mob(t));
            }
            case HOOK -> {
                String lock = PaOrders.locked(a, "fish");
                if (lock != null) return lock;
                if (PaRodPull.rodSlot(a) == -2) return PaLogiTalk.line(a, "field.fish_no_rod");
                LivingEntity t = pointed(a, look);
                if (t == null) t = nearestThreat(a, p, "");
                if (t == null) return PaLogiTalk.line(a, "field.nothing_there");
                f.hookOrder = t.getUUID();
                f.hookUntil = a.tickCount + 300;
                return PaLogiTalk.line(a, "field.hook_ok", "item", PaThrows.mob(t));
            }
            case SMASH -> {
                String lock = PaOrders.locked(a, "mace");
                if (lock != null) return lock;
                if (PaMace.maceSlot(a) == -2) return PaLogiTalk.line(a, "field.mace_none");
                if (!PaMace.hasWind(a) && !PaMace.hasBlocks(a)) return PaLogiTalk.line(a, "field.mace_no_lift");
                LivingEntity t = pointed(a, look);
                if (t == null) t = nearestThreat(a, p, "");
                if (t == null) return PaLogiTalk.line(a, "field.nothing_there");
                f.smashOrder = t.getUUID();
                f.smashUntil = a.tickCount + 400;
                a.setTarget(t);
                return PaLogiTalk.line(a, "field.mace_ok", "item", PaThrows.mob(t));
            }
            case CURE -> {
                String lock = PaOrders.locked(a, "cure");
                if (lock != null) return lock;
                LivingEntity t = pointed(a, look);
                if (!(t instanceof ZombieVillager)) {
                    t = null;
                    double bd = 20 * 20;
                    for (ZombieVillager z : a.level().getEntitiesOfClass(ZombieVillager.class, p.getBoundingBox().inflate(20))) {
                        double d = z.distanceToSqr(p);
                        if (z.isAlive() && !z.isConverting() && d < bd) { bd = d; t = z; }
                    }
                }
                if (t == null) return PaLogiTalk.line(a, "field.cure_none");
                ZombieVillager z = (ZombieVillager) t;
                if (z.isConverting()) return PaLogiTalk.line(a, "field.cure_started");
                boolean weak = z.hasEffect(net.minecraft.world.effect.MobEffects.WEAKNESS);
                if (!weak && PaTrade.weakness(a).isEmpty()) return PaLogiTalk.line(a, "field.cure_need", "what", "a splash potion of weakness");
                if (a.countMatching(s -> s.is(Items.GOLDEN_APPLE)) < 1) return PaLogiTalk.line(a, "field.cure_need", "what", "a golden apple");
                f.cureOrder = z.getUUID();
                f.cureUntil = a.tickCount + 1200;
                if (a.getTarget() == z) a.setTarget(null);
                return PaLogiTalk.line(a, "field.cure_ok");
            }
            default -> { return null; }
        }
    }

    /** "Go fishing", "catch me 10 fish": the level and a rod (one made first if it can be), then the fishing. Null when planned. */
    @Nullable
    static String planFish(PlayerAssistantEntity a, PaIntent i, List<PaPlan.Step> out) {
        String lock = PaOrders.locked(a, "fish");
        if (lock != null) return lock;
        boolean rod = a.getMainHandItem().is(Items.FISHING_ROD) || a.countMatching(s -> s.is(Items.FISHING_ROD)) > 0;
        if (!rod) {
            CraftPlanner.Result cr = CraftPlanner.plan(a, "fishing_rod", 1);
            if (!cr.blockers().isEmpty() || cr.jobs().isEmpty()) return PaLogiTalk.line(a, "field.fish_no_rod");
            PaPlan.Step s = PaOrders.step("make a fishing rod first", PaIntent.Kind.CRAFT, 20);
            s.jobs.addAll(cr.jobs());
            s.count = "fishing rod";
            s.target = 1;
            s.make = "fishing_rod";
            out.add(s);
        }
        int n = i.amount() > 0 ? Math.min(32, i.amount()) : 10;
        PaPlan.Step s = PaOrders.step("catch " + n + " fish", PaIntent.Kind.FISH, 25 * n);
        s.jobs.add(Job.fish(n));
        s.count = "anyfish";
        s.target = n;
        out.add(s);
        return null;
    }

    /** "Put bookshelves round the table": the shelves from its pack, set where the table reads them (as well as it knows). */
    @Nullable
    static String planShelves(PlayerAssistantEntity a, List<PaPlan.Step> out) {
        if (a.countMatching(s -> s.is(Items.BOOKSHELF)) == 0) return PaLogiTalk.line(a, "field.shelves_none");
        PaPlan.Step s = PaOrders.step("put bookshelves round the table", PaIntent.Kind.ENCHANT, 30);
        s.jobs.add(Job.enchant("shelves"));
        out.add(s);
        return null;
    }
}
