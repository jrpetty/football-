package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.item.LeisureItems;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [leisure] The lute (item/LuteItem): the busker's own instrument, and the band's.
 *
 * <p><b>The buskers.</b> A busker goes out with its lute: its own, or one it buys with what its hat has taken (at the
 * shop's price, when it can spare it), or one lent out of the stores for the evening and put back after; only with no
 * lute to be had does it fall back on a note block (Buskers.start). It holds the lute in its hand and plays it, a real
 * tune on the guitar's voice with the harp ringing over it on the strong beats and a low string on the first of each
 * bar, the notes rising off it; it slips less than on a note block. A lute draws a bigger crowd (one more listener
 * stops, and more readily) and a fuller hat (the coin comes the more readily).
 *
 * <p><b>The band.</b> At the tavern and at weddings a musician with a lute plays it in the band, and a lute is lent out
 * of the stores to one with nothing to play when the stores have no note block (Music).
 *
 * <p><b>Made</b> by the shop's workshop, on its book while the town has buskers short of a lute (a lute for each, three
 * at most, and one for the band). A town with no shop yet: a busker makes its own at the bench of an evening, out of
 * the stores' planks, sticks and string, once a day while the town has buskers and no lute to lend; so the buskers play
 * in every town.
 */
public final class Lutes {

    private Lutes() {}

    /** Tunes for the lute, a beat to a note (semitones over F sharp; -1 a rest): a country dance, a lament, a reel, a march. */
    public static final int[][] TUNES = {
        { 12, 16, 19, 16, 17, 21, 19, -1, 16, 17, 19, 21, 19, 17, 16, -1, 12, 16, 19, 24, 21, 19, 17, 16, 14, 16, 17, 14, 12, -1, -1, -1 },
        { 9, 12, 14, 16, 17, 16, 14, -1, 11, 7, 9, 11, 12, 9, 9, -1, 9, 12, 14, 16, 17, 16, 14, -1, 11, 7, 9, 11, 12, 11, 9, -1 },
        Buskers.REEL,
        Music.MARCH };

    /** The most lutes the town keeps for its buskers, and one for the band. */
    static final int MOST = 3;

    /** The day each town's busker last made itself a lute. */
    private static final Map<UUID, Long> MADE_OWN = new ConcurrentHashMap<>();

    /** Players with a lute for the photographs (LeisureStage): who, till when. */
    private static final Map<UUID, Long> STAGED = new ConcurrentHashMap<>();

    static void resetForTests() {
        MADE_OWN.clear();
        STAGED.clear();
    }

    /** For the photographs: this folk plays its lute where it stands, a beat a second, till then. */
    static void stage(VillageFolkEntity f, long until) {
        STAGED.put(f.getUUID(), until);
    }

    /** The staged players' tune (Pastimes.round, every second). */
    static void staged(ServerLevel level, Villages.Village v) {
        if (STAGED.isEmpty()) return;
        long now = level.getGameTime();
        for (Map.Entry<UUID, Long> e : new java.util.ArrayList<>(STAGED.entrySet())) {
            if (!(level.getEntity(e.getKey()) instanceof VillageFolkEntity f) || !v.id().equals(f.ownerId())) continue;
            if (now >= e.getValue() || !f.isAlive()) {
                STAGED.remove(e.getKey());
                continue;
            }
            inHand(f);
            f.getNavigation().stop();
            int beat = (int) (now / 20L);
            strum(level, f, TUNES[0], beat, 90, 1.0F);
            if (beat % 2 == 0) f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
    }

    public static boolean isLute(ItemStack s) {
        return !s.isEmpty() && s.is(LeisureItems.LUTE.get()) && !Leisure.isProp(s);
    }

    // ------------------------------------------------------------------ the music

    /**
     * A beat of a tune on the lute, where its player stands: the note on the guitar's voice, the harp a third or a fifth
     * over it on the strong beats, a low string on the first of the bar; the notes rising off it. A hand of {@code skill}
     * (nought to a hundred) slips a semitone now and then, with a puff of smoke for it. Whether a note sounded.
     */
    public static boolean strum(ServerLevel level, Entity who, int[] tune, int beat, int skill, float volume) {
        int note = tune[Math.floorMod(beat, tune.length)];
        if (note < 0) return false;
        boolean slip = who.getRandom().nextInt(100) >= 80 + skill * 2 / 10;
        if (slip) note += who.getRandom().nextBoolean() ? 1 : -1;
        note = Math.max(0, Math.min(24, note));
        double x = who.getX(), y = who.getY() + 1.0, z = who.getZ();
        level.playSound(null, x, y, z, SoundEvents.NOTE_BLOCK_GUITAR.value(), SoundSource.RECORDS, volume, pitch(note));
        if (beat % 2 == 0) {
            int over = Math.min(24, note + (beat % 4 == 0 ? 7 : 4));
            level.playSound(null, x, y, z, SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.RECORDS, volume * 0.55F, pitch(over));
        }
        if (beat % 8 == 0 && note >= 12) {
            level.playSound(null, x, y, z, SoundEvents.NOTE_BLOCK_GUITAR.value(), SoundSource.RECORDS, volume * 0.7F, pitch(note - 12));
        }
        level.sendParticles(ParticleTypes.NOTE, x, who.getY() + who.getBbHeight() + 0.3, z, 0, note / 24.0, 0.0, 0.0, 1.0);
        if (slip) level.sendParticles(ParticleTypes.SMOKE, x, who.getY() + who.getBbHeight() + 0.1, z, 2, 0.1, 0.1, 0.1, 0.01);
        return true;
    }

    static float pitch(int note) {
        return (float) Math.pow(2.0, (note - 12) / 12.0);
    }

    // ------------------------------------------------------------------ the busker's lute (Buskers)

    /**
     * Out to play with a lute: its own, one bought with its savings (it keeps a few coins by), or one lent out of the
     * stores for the evening. Whether it has one now; {@code lent} says it is the stores' (and goes back).
     */
    static boolean ready(ServerLevel level, Villages.Village v, VillageFolkEntity f, boolean[] lent) {
        lent[0] = false;
        if (f.countCarried(Lutes::isLute) > 0) return true;
        UUID id = v.id();
        if (Market.stock(level, id, Lutes::isLute) + ShopStock.held(level, id, Lutes::isLute) <= 0) return false;
        double price = Purchases.priceEach(level, id, new ItemStack(LeisureItems.LUTE.get()), f);
        if (Purchases.open(id) && f.purse() >= Math.ceil(price) + 3) {
            int before = f.purse();
            if (Purchases.get(level, f, Lutes::isLute, 1, Purchases.Need.TREAT) > 0) {
                for (ItemStack s : f.getInventoryItems()) if (isLute(s) && !Homes.isKeepsake(s)) { Homes.keepsake(s, f); break; }
                long day = level.getDayTime() / 24000L;
                int paid = Math.max(0, before - f.purse());
                f.persona().remember(day, "I bought a lute of my own" + (paid > 0 ? " with " + paid + " coins out of my hat" : ""), 4);
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "A lute of my own! Listen to that tone.", "Saved every copper for this lute, I did."));
                Pastimes.LOG.info("[MCA-LEISURE] {} of {} bought a lute for {} coins", f.displayNameCap(), Villages.name(id), paid);
                return true;
            }
        }
        ItemStack one = Crafts.takeOne(level, v, Lutes::isLute);
        if (one.isEmpty()) return false;
        ItemStack left = f.insertItem(one);
        if (!left.isEmpty()) {
            Crafts.store(level, v, left);
            return false;
        }
        lent[0] = true;
        return true;
    }

    /** The lute into its hand to play (out of its pack; what it held goes into the pack). */
    static void inHand(VillageFolkEntity f) {
        if (isLute(f.getMainHandItem())) return;
        List<ItemStack> pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            if (!isLute(pack.get(i))) continue;
            ItemStack was = f.getMainHandItem();
            f.setItemSlot(EquipmentSlot.MAINHAND, pack.get(i));
            pack.set(i, was);
            return;
        }
    }

    /** Done playing: the lute out of its hand into its pack, and a lent one back into the stores. */
    static void putAway(ServerLevel level, @Nullable Villages.Village v, VillageFolkEntity f, boolean lent) {
        if (isLute(f.getMainHandItem())) {
            ItemStack l = f.getMainHandItem();
            f.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            ItemStack left = f.insertItem(l);
            if (!left.isEmpty() && v != null) Crafts.store(level, v, left);
        }
        if (lent && v != null && f.isAlive()) {
            for (ItemStack s : f.getInventoryItems()) {
                if (!isLute(s) || Homes.isKeepsake(s)) continue;
                ItemStack back = s.copyWithCount(1);
                s.shrink(1);
                Crafts.store(level, v, back);
                break;
            }
        }
    }

    // ------------------------------------------------------------------ made

    /** The lutes the town wants kept: one for each busker without its own (three at most), and one for the band. */
    static int wanted(ServerLevel level, Villages.Village v) {
        int buskers = 0, without = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !Music.musician(f)) continue;
            buskers++;
            if (f.countCarried(Lutes::isLute) == 0) without++;
        }
        if (buskers == 0) return 0;
        int n = Math.min(MOST, Math.max(1, without));
        if (buskers >= 2 && Tavern.of(v.id()) != null) n++;
        return n;
    }

    /**
     * A busker's own lute, in a town with nobody to make one (no shop): once a day, of an evening, a busker off work and
     * with no lute to play makes one at the bench out of the stores (Bench: the planks, the sticks, the string).
     */
    static void tick(ServerLevel level, Villages.Village v, long day, long t) {
        if (t < 12000L || t >= 14000L) return;
        UUID id = v.id();
        if (MADE_OWN.getOrDefault(id, -1L) >= day) return;
        if (Pastimes.madeHere(id, LeisureItems.LUTE.get())) return;
        if (Market.stock(level, id, Lutes::isLute) > 0) return;
        Item lute = LeisureItems.LUTE.get();
        if (!Tiers.allows(level, Villages.ageOf(id), lute)) return;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || !Music.musician(f) || !f.offWorkNow() || f.isSleeping()) continue;
            if (f.countCarried(Lutes::isLute) > 0) continue;
            MADE_OWN.put(id, day);
            ItemStack made = makeOwn(level, v, f);
            if (!made.isEmpty()) return;
        }
    }

    /** A busker makes itself a lute at the bench out of the stores, and keeps it. Empty if the stores cannot run to it. */
    static ItemStack makeOwn(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        Item lute = LeisureItems.LUTE.get();
        Bench.Hand hand = Bench.handOf(level, v, f, null);
        Bench.Plan p = Bench.plan(level, v, lute, 1, hand);
        if (!p.ok()) {
            Pastimes.shortOf(v.id(), lute, p.chain());
            return ItemStack.EMPTY;
        }
        ItemStack out = Bench.make(level, v, p, f, hand);
        if (out.isEmpty()) return out;
        // Out of the stores again into its own hands: it is the busker's now.
        ItemStack mine = Crafts.takeOne(level, v, Lutes::isLute);
        if (!mine.isEmpty()) {
            Homes.keepsake(mine, f);
            ItemStack left = f.insertGiven(mine);
            if (!left.isEmpty()) Crafts.store(level, v, left);
        }
        long day = level.getDayTime() / 24000L;
        f.persona().remember(day, "I made myself a lute", 4);
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Nobody here makes lutes, so I made my own. Listen!", "Three strings and a good neck — my own lute!"));
        Pastimes.made(v.id(), f.displayNameCap() + " (a busker) made itself a lute");
        Pastimes.LOG.info("[MCA-LEISURE] {} of {} made itself a lute at the bench", f.displayNameCap(), Villages.name(v.id()));
        return out;
    }

    @Nullable
    static String card(VillageFolkEntity f) {
        for (ItemStack s : f.getInventoryItems()) if (isLute(s) && Homes.isKeepsake(s)) return "plays a lute of its own";
        if (isLute(f.getMainHandItem()) && Homes.isKeepsake(f.getMainHandItem())) return "plays a lute of its own";
        return null;
    }

    static String status(ServerLevel level, Villages.Village v) {
        int own = 0, buskers = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !Music.musician(f)) continue;
            buskers++;
            if (card(f) != null) own++;
        }
        return buskers + (buskers == 1 ? " busker" : " buskers") + ", " + own + " with a lute of its own; " + Market.stock(level, v.id(), Lutes::isLute)
            + " in the stores to lend";
    }

    // ------------------------------------------------------------------ tests

    /** Tests: a busker in a town with no shop makes its own lute now. */
    public static ItemStack makeOwnForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = f.ownerId() == null ? null : Villages.get(f.ownerId());
        return v == null ? ItemStack.EMPTY : makeOwn(level, v, f);
    }

    /** Tests: the lute into its hand, as a busker at its pitch takes it up (Buskers.busk). Whether it holds one now. */
    public static boolean inHandForTests(VillageFolkEntity f) {
        inHand(f);
        return isLute(f.getMainHandItem());
    }

    /** Tests: the town's evening look for a busker to make its own lute, now. */
    public static void tickForTests(ServerLevel level, Villages.Village v, long day) {
        MADE_OWN.remove(v.id());
        tick(level, v, day, 12500L);
    }
}
