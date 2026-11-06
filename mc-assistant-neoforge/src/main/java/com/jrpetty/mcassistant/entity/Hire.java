package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hiring a folk for an adventure. For a few coins a day a folk who knows you leaves its work
 * and goes with you: it keeps up wherever you go (by night as by day), fights whatever comes
 * at you, and picks up what falls. When the time you paid for runs out, or you send it home,
 * it hands over what it carried for you and goes home with a story: the monsters it saw off
 * and the lands it saw, told in the village's history (and so at the tavern of an evening).
 */
public final class Hire {

    private Hire() {}

    /** A day of a folk's time. */
    public static final int PRICE = 4;
    static final long DAY = 24000L;

    /** "Come adventuring with me?" — the answer, and the bargain if it is yes. */
    public static String ask(VillageFolkEntity f, Player p) {
        if (!(f.level() instanceof ServerLevel level)) return "";
        if (f.isBaby()) return "I'm too little for adventures! Ask my mum.";
        UUID village = f.ownerId();
        long day = level.getDayTime() / DAY;
        String you = p.getName().getString();
        if (f.isHired() && !p.getUUID().equals(f.hiredBy())) return "I'm spoken for already — I'm off adventuring with " + f.hiredName + ".";
        if (village != null && (Laws.banished(village, p.getUUID(), day)
                || Standing.of(village, p.getUUID(), level.getGameTime()).title() == Standing.Title.OUTCAST)) {
            return "Go anywhere with you? Not for all the coin in the world.";
        }
        if (f.persona().affinity(p.getUUID()) < 10) return "Hire me? I hardly know you. Talk to me a while first.";
        if (Raids.underAlarm(village)) return "Not with the bell ringing! Ask me when it's quiet.";
        if (!f.isHired() && f.persona().mood() < 25) return "I'm not up to it. Ask me another day.";
        int coins = Market.coinsHeld(p);
        if (coins < PRICE) {
            return "I'll go with you for " + PRICE + " coins a day — I'll fight, I'll carry, and I'll want a story out of it. You've "
                + coins + ".";
        }
        Market.payOut(p, PRICE);
        f.earn(PRICE);
        boolean more = f.isHired();
        long until = (more ? Math.max(f.hiredUntil, level.getGameTime()) : level.getGameTime()) + DAY;
        if (more) {
            f.hire(p, until);
            return "Another day? Done. Lead on, " + you + ".";
        }
        // Something to fight with, out of the stores, if it has nothing (before it sets out, so
        // the blade is not counted as something it carried for you).
        if (f.countCarried(s -> s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES)) == 0 && village != null && f.villageCentre() != null) {
            f.drawFrom(f.villageCentre(), s -> s.is(ItemTags.SWORDS), 1, Villages.storesRadius(village));
        }
        f.hire(p, until);
        f.persona().remember(day, you + " hired me for an adventure", 4);
        Social.Life life = f.life();
        if (life.has(Social.Trait.CURIOUS)) return "An adventure? I've been waiting all my life for somebody to ask! Lead the way.";
        if (life.has(Social.Trait.GRUMPY)) return "Fine. Four coins a day, and I'm not carrying anything heavy. Well — not much.";
        if (life.has(Social.Trait.SHY)) return "All right… I'll come. I'll stay close.";
        return FolkTalk.pick(f.getRandom(), "You've hired yourself a companion! Where are we going?",
            "Done! I'll fight, I'll carry, and you'll have a story to tell. Lead on.");
    }

    /** Once a second while it is hired: keep up, look about, stand up for its employer. */
    static void tick(VillageFolkEntity f, ServerLevel level) {
        if (level.getGameTime() >= f.hiredUntil) {
            home(f, level, true);
            return;
        }
        Player p = f.companionPlayer();
        if (p == null || p.level() != level) return;
        // Keeps up: a long way behind (a boat, a cliff, a run), it catches up.
        if (f.distanceToSqr(p) > 24.0 * 24.0 && p.onGround()) {
            f.teleportTo(p.getX(), p.getY(), p.getZ());
            f.getNavigation().stop();
        }
        // What falls and lies about (the spoils of a fight, a dropped stack), it carries.
        for (net.minecraft.world.entity.item.ItemEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                f.getBoundingBox().inflate(4.0), e -> e.isAlive() && !e.hasPickUpDelay() && e.getAge() > 60)) {
            ItemStack left = f.insertItem(e.getItem().copy());
            if (left.isEmpty()) e.discard();
            else e.setItem(left);
        }
        // The lands it sees.
        level.getBiome(f.blockPosition()).unwrapKey().ifPresent(k -> {
            String b = k.location().getPath().replace('_', ' ');
            if (f.hiredSaw.size() < 12) f.hiredSaw.add(b);
        });
        // Whatever goes for its employer, or what its employer goes for, it goes for too.
        if (f.getTarget() == null || !f.getTarget().isAlive()) {
            LivingEntity threat = p.getLastHurtByMob();
            if (threat == null || !threat.isAlive() || !(threat instanceof Enemy)) {
                LivingEntity hit = p.getLastHurtMob();
                threat = hit != null && hit.isAlive() && hit instanceof Enemy ? hit : null;
            }
            if (threat != null && threat != f && f.distanceToSqr(threat) < 20.0 * 20.0) f.setTarget(threat);
        }
    }

    /** Back home: its share handed over, the story told. */
    public static void home(VillageFolkEntity f, ServerLevel level, boolean timeUp) {
        UUID boss = f.hiredBy();
        if (boss != null) home(f, level, timeUp, level.getPlayerByUUID(boss));
    }

    /** Back home, its employer (if it is to hand) given what it carried. */
    public static void home(VillageFolkEntity f, ServerLevel level, boolean timeUp, @javax.annotation.Nullable Player p) {
        UUID boss = f.hiredBy();
        if (boss == null) return;
        String bossName = f.hiredName;
        long day = level.getDayTime() / DAY;
        long days = Math.max(1L, Math.round((level.getGameTime() - f.hiredSince) / (double) DAY));
        // What it carried for its employer: everything it has more of than when it set out.
        List<ItemStack> share = new ArrayList<>();
        Map<Item, Integer> had = new java.util.HashMap<>(f.hiredPack);
        for (ItemStack st : f.getInventoryItems()) {
            if (st.isEmpty()) continue;
            int keep = had.getOrDefault(st.getItem(), 0);
            if (st.getCount() <= keep) {
                had.put(st.getItem(), keep - st.getCount());
                continue;
            }
            had.put(st.getItem(), 0);
            share.add(st.split(st.getCount() - keep));
        }
        int handed = 0;
        UUID village = f.ownerId();
        for (ItemStack st : share) {
            handed += st.getCount();
            if (p != null && p.level() == level && f.distanceToSqr(p) < 32.0 * 32.0) {
                if (!p.getInventory().add(st)) p.drop(st, false);
            } else if (village != null) {
                ItemStack left = Market.intoStores(level, village, st);
                if (!left.isEmpty()) f.spawnAtLocation(left);
            } else {
                f.spawnAtLocation(st);
            }
        }
        // The story.
        int kills = f.hiredKills;
        List<String> saw = new ArrayList<>(f.hiredSaw);
        String lands = saw.isEmpty() ? "" : saw.size() == 1 ? "the " + saw.get(0)
            : "the " + String.join(", the ", saw.subList(0, Math.min(3, saw.size()) - 1)) + " and the " + saw.get(Math.min(3, saw.size()) - 1);
        String trip = (days == 1 ? "a day's" : days + " days'") + " adventuring with " + bossName;
        String line = f.displayNameCap() + " came home from " + trip
            + (kills > 0 ? ", " + kills + (kills == 1 ? " monster" : " monsters") + " the worse for it" : "")
            + (lands.isEmpty() ? "" : ", having seen " + lands);
        if (village != null) Villages.tell(village, day, line);
        f.persona().remember(day, "I went adventuring with " + bossName + (lands.isEmpty() ? "" : " and saw " + lands), 8);
        f.persona().feelFor(boss, bossName, 6 + Math.min(10, kills));
        f.awardXp(40 + 15 * kills);
        f.unhire();
        f.getNavigation().stop();
        String said = (timeUp ? "That's my time up — " : "Home it is — ") + (handed > 0 ? "here's what I carried for you. " : "")
            + "I'll be telling this one at the tavern for weeks!";
        FolkTalk.speak(f, said);
        if (p != null && handed > 0 && f.distanceToSqr(p) >= 32.0 * 32.0) {
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal(f.displayNameCap()
                + " went home; what it carried for you is in the village's stores."));
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getSource().getEntity() instanceof VillageFolkEntity f && f.isHired() && event.getEntity() instanceof Enemy) {
            f.hiredKills++;
        }
    }
}
