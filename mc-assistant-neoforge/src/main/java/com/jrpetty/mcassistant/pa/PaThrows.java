package com.jrpetty.mcassistant.pa;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * [pa38] What became of its throws: a hit (the mob was hurt by it after the throw), the mob turning on it (its player
 * left alone: "over here"), and a mob at a ledge knocked over the edge. Said once each, not every throw.
 */
final class PaThrows {

    private PaThrows() {}

    /** One throw in the air: at whom, thrown when, from what height the mob stood, at a ledge or not, and what's been said. */
    static final class Watch {
        final Projectile p;
        final LivingEntity t;
        final int at;
        final double y;
        final boolean ledge;
        boolean hit, said;

        Watch(Projectile p, LivingEntity t, int at, boolean ledge) {
            this.p = p;
            this.t = t;
            this.at = at;
            this.y = t.getY();
            this.ledge = ledge;
        }
    }

    private static final Map<PlayerAssistantEntity, List<Watch>> OUT = new WeakHashMap<>();

    static void watch(PlayerAssistantEntity a, Projectile p, LivingEntity t, boolean ledge) {
        OUT.computeIfAbsent(a, k -> new ArrayList<>()).add(new Watch(p, t, a.tickCount, ledge));
    }

    static int inAir(PlayerAssistantEntity a) {
        List<Watch> l = OUT.get(a);
        return l == null ? 0 : l.size();
    }

    static void tick(PlayerAssistantEntity a) {
        List<Watch> l = OUT.get(a);
        if (l == null || l.isEmpty()) return;
        PaField f = a.mind.field;
        for (java.util.Iterator<Watch> it = l.iterator(); it.hasNext(); ) {
            Watch w = it.next();
            int age = a.tickCount - w.at;
            if (!w.hit && w.t.getLastHurtByMob() == a && w.t.getLastHurtByMobTimestamp() >= w.at) {
                w.hit = true;
                f.throwHits++;
            }
            if (w.hit && !w.said) {
                if (w.ledge && w.t.getY() < w.y - 2.5) {
                    w.said = true;
                    PaTalk.comment(a, 1, PaLogiTalk.line(a, "field.throw_ledge", "item", mob(w.t)), true);
                } else if (!w.ledge && w.t instanceof net.minecraft.world.entity.Mob m && m.getTarget() == a) {
                    w.said = true;
                    f.aggroPulls++;
                    PaTalk.comment(a, 1, PaLogiTalk.line(a, "field.throw_aggro", "item", mob(w.t)), true);
                }
            }
            if (age > 80 || !w.t.isAlive()) it.remove();
        }
    }

    static String mob(LivingEntity e) {
        return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().replace('_', ' ');
    }
}
