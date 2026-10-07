package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Watch dogs. [batchG] The watch keeps dogs: one for every two guards at most.
 * <ul>
 * <li><b>Taming.</b> When the watch is short of a dog and there is a wild wolf about the town, a guard takes
 *     bones (or raw meat) out of the stores, walks out to it and holds them out one at a time, as a player
 *     tames one: a chance in three a bone. What it does not use goes back to the stores. The dog is named,
 *     and the chronicle takes it down.</li>
 * <li><b>On the rounds.</b> By day the dog is at its guard's heels on its beat (Patrols), and with the bell
 *     ringing it is at its side whatever the hour.</li>
 * <li><b>Monsters.</b> It growls at a monster that comes near the town, barks at it, and goes for it: it
 *     helps fight. Never a creeper: a dog knows better.</li>
 * <li><b>At night</b> (or while its guard sleeps) it goes home and lies down by its guard's bed, in the
 *     barracks or the house.</li>
 * </ul>
 * A dog is the watch's: if its guard leaves the watch or dies, another guard without a dog takes it on. A dog
 * not seen for three days is given up for lost. The guard's card names its dog; the books list the watch's dogs.
 */
public final class WatchDogs {

    private WatchDogs() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** How far from the heart (and from the guard's home) a wolf may be for a guard to go for it. */
    static final int REACH = 64;
    /** How near the dog a monster must be for it to go for it. */
    static final int SCENT = 12;

    static final String[] NAMES = { "Rex", "Bran", "Shep", "Fly", "Brindle", "Scout", "Jess", "Nell", "Tam", "Gelert", "Bess", "Rook" };

    /** A dog of the watch: the wolf, its guard, its name, the day it was tamed. */
    record Dog(UUID wolf, UUID guard, String name, long since) {}

    /** A guard out taming one. */
    static final class Errand {
        final UUID village, wolf;
        final long start;
        int carried, offered;
        long lastOffer;

        Errand(UUID village, UUID wolf, long start) {
            this.village = village;
            this.wolf = wolf;
            this.start = start;
        }
    }

    private static final Map<UUID, List<Dog>> DOGS = new ConcurrentHashMap<>();
    private static final Map<UUID, Errand> TAMING = new ConcurrentHashMap<>();
    /** The day each dog was last seen about. */
    private static final Map<UUID, Long> SEEN = new ConcurrentHashMap<>();
    /** When each dog last barked. */
    private static final Map<UUID, Long> BARKED = new ConcurrentHashMap<>();

    static void resetForTests() {
        DOGS.clear();
        TAMING.clear();
        SEEN.clear();
        BARKED.clear();
    }

    private static final Predicate<ItemStack> BONE = s -> s.is(Items.BONE);
    private static final Predicate<ItemStack> MEAT = s -> s.is(Items.BEEF) || s.is(Items.PORKCHOP) || s.is(Items.MUTTON)
        || s.is(Items.CHICKEN) || s.is(Items.RABBIT);
    private static final Predicate<ItemStack> TREAT = BONE.or(MEAT);

    // ------------------------------------------------------------------ the book of dogs

    static List<Dog> dogs(UUID village) {
        return DOGS.computeIfAbsent(village, k -> {
            List<Dog> out = new ArrayList<>();
            String s = Ledger.note(k, "dogs");
            if (s != null && !s.isEmpty()) {
                for (String part : s.split(";")) {
                    String[] q = part.split("\\|");
                    if (q.length < 4) continue;
                    try {
                        out.add(new Dog(UUID.fromString(q[0]), UUID.fromString(q[1]), q[2], Long.parseLong(q[3])));
                    } catch (IllegalArgumentException ignored) { }
                }
            }
            return new java.util.concurrent.CopyOnWriteArrayList<>(out);
        });
    }

    private static void save(UUID village) {
        StringBuilder sb = new StringBuilder();
        for (Dog d : dogs(village)) {
            if (sb.length() > 0) sb.append(';');
            sb.append(d.wolf()).append('|').append(d.guard()).append('|').append(d.name().replace("|", "")).append('|').append(d.since());
        }
        if (sb.length() == 0) Ledger.forget(village, "dogs");
        else Ledger.note(village, "dogs", sb.toString());
    }

    @Nullable
    static Dog dogOf(UUID village, UUID guard) {
        for (Dog d : dogs(village)) if (d.guard().equals(guard)) return d;
        return null;
    }

    // ------------------------------------------------------------------ the town's round

    /** The watch's dogs looked over (Visitors.tick): lost ones given up, orphans given a guard, and one tamed if short. */
    static void tick(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        List<VillageFolkEntity> watch = Patrols.watch(id);
        List<Dog> dogs = dogs(id);
        boolean changed = false;
        for (Dog d : List.copyOf(dogs)) {
            net.minecraft.world.entity.Entity e = level.getEntity(d.wolf());
            if (e instanceof Wolf w && !w.isAlive()) {
                dogs.remove(d);
                changed = true;
                Villages.tell(id, day, d.name() + ", the watch's dog, died");
                continue;
            }
            if (e instanceof Wolf) SEEN.put(d.wolf(), day);
            Long seen = SEEN.putIfAbsent(d.wolf(), day);
            if (seen != null && day - seen > 3) {
                dogs.remove(d);
                changed = true;
                Villages.tell(id, day, d.name() + ", the watch's dog, never came home");
                continue;
            }
            // Its guard gone from the watch: another without a dog takes it on.
            boolean kept = false;
            for (VillageFolkEntity g : watch) if (g.getUUID().equals(d.guard())) kept = true;
            if (!kept) {
                for (VillageFolkEntity g : watch) {
                    if (dogOf(id, g.getUUID()) != null) continue;
                    dogs.remove(d);
                    dogs.add(new Dog(d.wolf(), g.getUUID(), d.name(), d.since()));
                    if (e instanceof Wolf w) w.setOwnerUUID(g.getUUID());
                    changed = true;
                    break;
                }
            }
        }
        if (changed) save(id);
        long t = level.getDayTime() % 24000L;
        if (dogs.size() >= watch.size() / 2 || t < 1000L || t > 10000L || Raids.underAlarm(id)) return;
        for (Errand e : TAMING.values()) if (e.village.equals(id)) return;   // one at a time
        start(level, v, watch, false);
    }

    /** A guard without a dog sets out to tame the nearest wild wolf about the town, with bones from the stores. */
    @Nullable
    static String start(ServerLevel level, Villages.Village v, List<VillageFolkEntity> watch, boolean now) {
        UUID id = v.id();
        Wolf wolf = wildWolf(level, v);
        if (wolf == null) return "no wild wolf about the town";
        VillageFolkEntity guard = null;
        for (VillageFolkEntity g : watch) {
            if (dogOf(id, g.getUUID()) != null || TAMING.containsKey(g.getUUID())) continue;
            if (!now && (g.isSleeping() || Patrols.escorting(g))) continue;
            if (guard == null || g.distanceToSqr(wolf) < guard.distanceToSqr(wolf)) guard = g;
        }
        if (guard == null) return "no guard free to tame one";
        if (guard.isSleeping()) guard.stopSleeping();
        int carried = 0;
        for (int i = 0; i < 6; i++) {
            ItemStack got = takeOne(level, v);
            if (got.isEmpty()) break;
            ItemStack left = guard.insertGiven(got);
            if (!left.isEmpty()) {
                Crafts.store(level, v, left);
                break;
            }
            carried++;
        }
        if (carried == 0) return "no bones or meat in the stores to tame one with";
        Errand e = new Errand(id, wolf.getUUID(), level.getGameTime());
        e.carried = carried;
        TAMING.put(guard.getUUID(), e);
        guard.clearQueue();
        FolkTalk.speak(guard, FolkTalk.pick(guard.getRandom(), "There's a wolf out there. A few bones, and the watch has a dog.",
            "The watch could do with a dog. Let's see if that one will come."));
        LOG.info("[MCA-DOGS] {} of {} sets out to tame a wolf for the watch with {} bones and meat", guard.displayNameCap(),
            Villages.name(id), carried);
        return guard.displayNameCap() + " sets out to tame a wolf with " + carried + " bones and meat";
    }

    private static ItemStack takeOne(ServerLevel level, Villages.Village v) {
        if (Crafts.take(level, v, BONE, 1)) return new ItemStack(Items.BONE);
        for (net.minecraft.world.item.Item meat : new net.minecraft.world.item.Item[]{ Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.RABBIT }) {
            if (Crafts.take(level, v, s -> s.is(meat), 1)) return new ItemStack(meat);
        }
        return ItemStack.EMPTY;
    }

    /** The nearest wild wolf about the town, not anybody's. */
    @Nullable
    static Wolf wildWolf(ServerLevel level, Villages.Village v) {
        Wolf best = null;
        double bd = Double.MAX_VALUE;
        for (Wolf w : level.getEntitiesOfClass(Wolf.class, new AABB(v.centre()).inflate(REACH, 24, REACH),
                x -> x.isAlive() && !x.isTame() && x.getOwnerUUID() == null && !x.isBaby())) {
            boolean sought = false;
            for (Errand e : TAMING.values()) if (e.wolf.equals(w.getUUID())) sought = true;
            if (sought) continue;
            double d = w.distanceToSqr(v.centre().getX(), v.centre().getY(), v.centre().getZ());
            if (d < bd) { bd = d; best = w; }
        }
        return best;
    }

    // ------------------------------------------------------------------ the taming

    /** Is this guard out taming a dog? */
    static boolean busy(VillageFolkEntity f) {
        return !TAMING.isEmpty() && TAMING.containsKey(f.getUUID());
    }

    /** The guard's taming, every tick (Visitors.drive): out to the wolf, a bone held out every second and a half. */
    static boolean drive(VillageFolkEntity g, ServerLevel level) {
        Errand e = TAMING.get(g.getUUID());
        if (e == null) return false;
        Villages.Village v = Villages.get(e.village);
        long t = level.getDayTime() % 24000L;
        Wolf wolf = level.getEntity(e.wolf) instanceof Wolf w && w.isAlive() && !w.isTame() ? w : null;
        if (v == null || wolf == null || Raids.underAlarm(e.village) || t > 12000L || level.getGameTime() - e.start > 3600L
                || g.getTarget() != null) {
            drop(level, g, e, v, wolf == null ? "the wolf is gone" : "it gave up for today");
            return false;
        }
        if (g.distanceToSqr(wolf) > 2.4 * 2.4) {
            if (g.getNavigation().isDone() || g.tickCount % 40 == 0) g.getNavigation().moveTo(wolf, 0.8D);
            return true;
        }
        g.getNavigation().stop();
        g.getLookControl().setLookAt(wolf, 30.0F, 30.0F);
        wolf.getNavigation().stop();
        wolf.getLookControl().setLookAt(g, 30.0F, 30.0F);
        long now = level.getGameTime();
        if (now - e.lastOffer < 30L) return true;
        e.lastOffer = now;
        boolean bone = g.removeMatching(BONE, 1) == 1;
        if (!bone && g.removeMatching(MEAT, 1) != 1) {
            drop(level, g, e, v, "nothing left to offer");
            return false;
        }
        e.offered++;
        g.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        // As a player tames one: a chance in three a bone; meat is a poorer bribe.
        if (g.getRandom().nextInt(bone ? 3 : 5) == 0) {
            tamed(level, v, g, wolf, level.getDayTime() / 24000L);
            drop(level, g, e, v, null);
            return false;
        }
        level.broadcastEntityEvent(wolf, (byte) 6);
        if (e.offered >= e.carried) {
            FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "That's the last bone, and it still won't come.", "Wild yet. Another day."));
            drop(level, g, e, v, "it would not take to them");
            return false;
        }
        return true;
    }

    /** The errand over: what it did not use back to the stores. */
    private static void drop(ServerLevel level, VillageFolkEntity g, Errand e, @Nullable Villages.Village v, @Nullable String why) {
        TAMING.remove(g.getUUID());
        if (v == null) return;
        int left = e.carried - e.offered;
        for (int i = 0; i < left; i++) {
            ItemStack back = ItemStack.EMPTY;
            for (ItemStack s : g.getInventoryItems()) {
                if (!s.isEmpty() && TREAT.test(s)) { back = s.split(1); break; }
            }
            if (back.isEmpty()) break;
            Crafts.store(level, v, back);
        }
        if (why != null) LOG.info("[MCA-DOGS] {} gave up taming a wolf: {}", g.displayNameCap(), why);
    }

    /** Tamed: the watch's now, named, and down in the chronicle. */
    static Dog tamed(ServerLevel level, Villages.Village v, VillageFolkEntity g, Wolf wolf, long day) {
        wolf.setTame(true, true);
        wolf.setOwnerUUID(g.getUUID());
        wolf.setOrderedToSit(false);
        wolf.setInSittingPose(false);
        wolf.setPersistenceRequired();
        wolf.setTarget(null);
        level.broadcastEntityEvent(wolf, (byte) 7);
        String name = NAMES[Math.floorMod(wolf.getUUID().hashCode(), NAMES.length)];
        wolf.setCustomName(Component.literal(name));
        Dog d = new Dog(wolf.getUUID(), g.getUUID(), name, day);
        dogs(v.id()).add(d);
        save(v.id());
        SEEN.put(wolf.getUUID(), day);
        FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Good dog! You're the watch's now. We'll call you " + name + ".",
            name + "! That's your name. Come on — we've a beat to walk."));
        g.persona().remember(day, "tamed " + name + " to walk the rounds with me", 5);
        Villages.tell(v.id(), day, g.displayNameCap() + " of the watch tamed a wolf, " + name + ", to walk the rounds with");
        LOG.info("[MCA-DOGS] {} tamed a wolf for the watch of {}: {}", g.displayNameCap(), Villages.name(v.id()), name);
        return d;
    }

    // ------------------------------------------------------------------ on the rounds

    /**
     * A guard's dog seen to, every half second from the guard's own tick (Visitors.drive): at its heels by
     * day (and with the bell ringing), at home by its bed at night or while it sleeps; and a monster near the
     * town growled at, barked at, and gone for.
     */
    static void lead(VillageFolkEntity g, ServerLevel level) {
        UUID id = g.ownerId();
        if (id == null) return;
        Dog d = dogOf(id, g.getUUID());
        if (d == null || !(level.getEntity(d.wolf()) instanceof Wolf w) || !w.isAlive()) return;
        long dt = level.getDayTime(), t = dt % 24000L;
        SEEN.put(d.wolf(), dt / 24000L);
        boolean alarm = Raids.underAlarm(id);
        boolean night = t >= 13000L && t < 23000L;
        if (guard(level, id, w)) return;                          // a monster near: that first
        if (!alarm && (night || g.isSleeping())) {
            BlockPos home = home(g);
            if (home == null) home = g.blockPosition();
            double far = w.distanceToSqr(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
            if (far > 2.5 * 2.5) {
                w.setOrderedToSit(false);
                w.setInSittingPose(false);
                if (far > 48.0 * 48.0) w.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
                else if (w.getNavigation().isDone() || w.tickCount % 60 < 10) w.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.0D);
            } else {
                w.getNavigation().stop();
                w.setOrderedToSit(true);
                w.setInSittingPose(true);
            }
            return;
        }
        w.setOrderedToSit(false);
        w.setInSittingPose(false);
        double d2 = w.distanceToSqr(g);
        if (d2 > 24.0 * 24.0) {
            w.getNavigation().stop();
            w.teleportTo(g.getX(), g.getY(), g.getZ());
        } else if (d2 > 4.0 * 4.0) {
            w.getNavigation().moveTo(g, 1.15D);
        } else if (w.getTarget() == null) {
            w.getNavigation().stop();
            w.getLookControl().setLookAt(g, 10.0F, w.getMaxHeadXRot());
        }
    }

    /** Where it sleeps: by its guard's bed, else at its guard's home. */
    @Nullable
    private static BlockPos home(VillageFolkEntity g) {
        if (g.bedPos() != null) return g.bedPos();
        return Homes.homeOf(g);
    }

    /**
     * A monster near the dog and near the town: growled at, barked at, and gone for (the wolf's own fighting
     * does the rest). Never a creeper. True if it has one to see to.
     */
    static boolean guard(ServerLevel level, UUID village, Wolf w) {
        LivingEntity now = w.getTarget();
        if (now != null && now.isAlive()) return true;
        if (now != null) w.setTarget(null);
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        int reach = Villages.townReach(village) + Patrols.ABROAD;
        Mob near = null;
        double bd = SCENT * SCENT;
        for (Mob m : level.getEntitiesOfClass(Mob.class, w.getBoundingBox().inflate(SCENT, 6, SCENT),
                m -> Patrols.hostile(m) && !(m instanceof Creeper))) {
            if (Math.max(Math.abs(m.getX() - v.centre().getX()), Math.abs(m.getZ() - v.centre().getZ())) > reach) continue;
            double d = m.distanceToSqr(w);
            if (d < bd) { bd = d; near = m; }
        }
        if (near == null) return false;
        long t = level.getGameTime();
        if (t - BARKED.getOrDefault(w.getUUID(), -1000L) > 40L) {
            BARKED.put(w.getUUID(), t);
            level.playSound(null, w.blockPosition(), bd > 6.0 * 6.0 ? SoundEvents.WOLF_GROWL : SoundEvents.WOLF_AMBIENT,
                SoundSource.NEUTRAL, 1.2F, 0.9F + w.getRandom().nextFloat() * 0.2F);
        }
        w.setOrderedToSit(false);
        w.setInSittingPose(false);
        w.setTarget(near);
        return true;
    }

    // ------------------------------------------------------------------ what is shown

    /** A guard's card: its dog, and where the dog is now. */
    static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || f.stationTask() != AssistantEntity.StationTask.GUARD) return "";
        if (busy(f)) return "out taming a wolf for the watch";
        Dog d = dogOf(id, f.getUUID());
        if (d == null) return "";
        String where = "";
        if (f.level() instanceof ServerLevel level && level.getEntity(d.wolf()) instanceof Wolf w) {
            where = w.getTarget() != null ? ", after a monster" : w.isInSittingPose() ? ", asleep at home" : ", at its heels";
        }
        return d.name() + ", the watch's dog, tamed on day " + (d.since() + 1) + where;
    }

    /** The books: the watch's dogs. */
    static String bookLine(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        for (Dog d : dogs(village)) {
            String guard = level.getEntity(d.guard()) instanceof VillageFolkEntity g ? g.displayNameCap() : "its guard";
            out.add(d.name() + " (with " + guard + ")");
        }
        return out.isEmpty() ? "" : "The watch's dogs: " + String.join(", ", out) + ".";
    }

    /** /village visitors: where each dog is ("DOG name x y z with guard"), for scripts. */
    static List<String> lines(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        for (Dog d : dogs(v.id())) {
            if (!(level.getEntity(d.wolf()) instanceof Wolf w)) continue;
            BlockPos p = w.blockPosition();
            out.add("DOG " + d.name() + " " + p.getX() + " " + p.getY() + " " + p.getZ() + " with "
                + (level.getEntity(d.guard()) instanceof VillageFolkEntity g ? g.displayNameCap().replace(' ', '_') : "?"));
        }
        return out;
    }

    /** /village visitors dog, and the tests: a guard tames the nearest wild wolf now (out of the same stores; certain). */
    static String tameNowForTests(ServerLevel level, Villages.Village v) {
        List<VillageFolkEntity> watch = Patrols.watch(v.id());
        if (watch.isEmpty()) return "no watch";
        if (dogs(v.id()).size() >= Math.max(1, watch.size() / 2)) return "the watch has its dogs (" + dogs(v.id()).size() + ")";
        String started = start(level, v, watch, true);
        for (Map.Entry<UUID, Errand> e : TAMING.entrySet()) {
            if (!e.getValue().village.equals(v.id())) continue;
            if (!(level.getEntity(e.getKey()) instanceof VillageFolkEntity g) || !(level.getEntity(e.getValue().wolf) instanceof Wolf wolf)) continue;
            g.moveTo(wolf.getX() + 1.0, wolf.getY(), wolf.getZ(), g.getYRot(), 0.0F);
            if (g.removeMatching(TREAT, 1) == 1) e.getValue().offered++;
            Dog d = tamed(level, v, g, wolf, level.getDayTime() / 24000L);
            drop(level, g, e.getValue(), v, null);
            return started + "; tamed " + d.name();
        }
        return started;
    }

    /** Tests: where a guard's dog sleeps. */
    public static BlockPos homeForTests(VillageFolkEntity guard) {
        BlockPos h = home(guard);
        return h == null ? guard.blockPosition() : h;
    }

    /** Tests: the guard leads its dog now (one look). */
    public static void leadForTests(ServerLevel level, VillageFolkEntity guard) {
        lead(guard, level);
    }

    /** Tests: the watch's dogs as "name:wolf-uuid:guard-uuid". */
    public static List<String> dogsForTests(UUID village) {
        List<String> out = new ArrayList<>();
        for (Dog d : dogs(village)) out.add(d.name() + ":" + d.wolf() + ":" + d.guard());
        return out;
    }

    /** Tests: tame one now (see tameNowForTests). */
    public static String tameForTests(ServerLevel level, Villages.Village v) {
        return tameNowForTests(level, v);
    }
}
