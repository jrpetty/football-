package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [batchB] Midwinter, the fourth day of winter: lanterns along the main avenue, and presents between friends.
 *
 * <ul>
 * <li><b>The lanterns.</b> In the morning a hand at the town's works sets lanterns out along the main avenue
 *     (the one the board looks down), a pair every four blocks from the square's gate, both edges of the
 *     road, twelve at the most: the stores' lanterns, or torches until the town makes lanterns. A lantern
 *     goes where it will stand (on a paved road, or the grass at its edge: a dirt path holds none, as in the
 *     game). The morning after, the hand walks the avenue again and takes them in, one at a time, back into
 *     the stores.</li>
 * <li><b>The presents.</b> Each grown folk with a coin or two to spare picks one to give to: its partner,
 *     else its child or its parent, else the friend it is fondest of. In its own time (its break, the
 *     evening) it goes to the shop and buys something small its friend would like (a flower, a cookie, a
 *     candle, a book: what they love first, never what they cannot abide) out of its own purse, at the
 *     shop's price, the coin into the treasury (Cafe.folkShops); with no shop open, over the stores' counter
 *     as a birthday present is bought (Birthdays). Then it takes it round, and hands it over: "Happy
 *     midwinter!" The present is the friend's own thing now (a keepsake, never banked), they are the fonder
 *     for it, and both remember the day.</li>
 * </ul>
 * The chronicle notes midwinter kept, with the lanterns and the presents, when the lanterns come in.
 */
public final class Midwinter {

    private Midwinter() {}

    /** Lanterns along the avenue at most, and how far apart the pairs stand. */
    static final int LANTERNS = 12, APART = 4;
    /** Folk who go out with a present on midwinter's day, at most. */
    static final int GIVERS = 24;

    /** A present still to be bought and taken round (once bought, it is in the giver's pack: its own, till it is given). */
    static final class Errand {
        final UUID to;
        final long day;
        @Nullable net.minecraft.world.item.Item bought;
        long since = -1;
        int tries;

        Errand(UUID to, long day) {
            this.to = to;
            this.day = day;
        }
    }

    private static final Map<UUID, Errand> ERRANDS = new ConcurrentHashMap<>();
    /** The presents given today, "giver|friend|what" (the tests read them). */
    private static final Map<UUID, List<String>> GIVEN = new ConcurrentHashMap<>();
    /** Spots along the avenue a lantern would not stand on, a village at a time (looked at again the next midwinter). */
    private static final Map<UUID, Set<Long>> NO_ROOM = new ConcurrentHashMap<>();

    public static void resetForTests() {
        ERRANDS.clear();
        GIVEN.clear();
        NO_ROOM.clear();
    }

    /** Once a second (Festivals.tick): on midwinter's day, the lanterns out and the presents planned. */
    static void tick(ServerLevel level, Villages.Village v, Festivals.Town town, long day, long t) {
        UUID id = v.id();
        long d = Festivals.dayThisYear(id, day, Festivals.Feast.MIDWINTER);
        // The morning after, with no lanterns to bring in: midwinter into the chronicle all the same.
        if (day == d + 1 && t >= 1000L && town.giftsDay == d && !Festivals.has(town, "midwinter")) told(level, v, town, d, false);
        if (day != d) return;
        if (t >= 1000L && t < 11500L) Festivals.why(id, Festivals.Feast.MIDWINTER, hang(level, v, town, d, false));
        if (t >= 1000L && town.giftsDay != d) plan(level, v, town, d);
    }

    // ------------------------------------------------------------------ the lanterns

    /** The spots along the main avenue, nearest the square first: both edges, a pair every few blocks. */
    static List<BlockPos> spots(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Direction along = VillageBoards.facingOf(id);
        if (along == null) along = Direction.SOUTH;
        Direction right = along.getClockWise();
        BlockPos c = v.centre();
        List<BlockPos> out = new ArrayList<>();
        for (int k = 0; out.size() < LANTERNS * 2 && k < 10; k++) {
            int out_ = TownPlan.PLAZA + 2 + APART * k;
            for (int side : new int[]{ -1, 1 }) {
                // The road's edge if it will take one, else just off it (hang takes the first that will).
                for (int across : new int[]{ TownPlan.AVENUE, TownPlan.AVENUE + 1 }) {
                    int x = c.getX() + along.getStepX() * out_ + right.getStepX() * across * side;
                    int z = c.getZ() + along.getStepZ() * out_ + right.getStepZ() * across * side;
                    BlockPos at = Festivals.groundAt(level, x, z);
                    if (at == null || Math.abs(at.getY() - c.getY()) > 8) continue;
                    out.add(at);
                }
            }
        }
        return out;
    }

    /** Lantern or torch: what the stores hold. */
    @Nullable
    private static Block light(ServerLevel level, UUID village) {
        if (Market.stock(level, village, s -> s.is(Items.LANTERN)) > 0) return Blocks.LANTERN;
        if (Market.stock(level, village, s -> s.is(Items.TORCH)) > 0) return Blocks.TORCH;
        return null;
    }

    /**
     * One more lantern set out along the avenue, by a hand at the town's works (or at once). Null when one went
     * up or all are up; else why not.
     */
    @Nullable
    static String hang(ServerLevel level, Villages.Village v, Festivals.Town town, long d, boolean now) {
        UUID id = v.id();
        int up = 0;
        Set<Long> taken = new HashSet<>();
        for (Festivals.Placed p : town.placed) {
            if (!p.feast().equals("midwinter")) continue;
            up++;
            taken.add(p.pos().asLong());
        }
        if (up >= LANTERNS) return null;
        Block light = light(level, id);
        if (light == null) return up > 0 ? null : "no lanterns or torches in the stores";
        Set<Long> bad = NO_ROOM.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
        BlockPos at = null;
        for (BlockPos p : spots(level, v)) {
            if (bad.contains(p.asLong())) continue;
            boolean beside = false;                                    // one to a spot: not the road's edge and just off it both
            for (long q : taken) if (BlockPos.of(q).distManhattan(p) <= 1) beside = true;
            if (beside) continue;
            if (!Festivals.open(level, p) || !light.defaultBlockState().canSurvive(level, p)) {
                bad.add(p.asLong());
                continue;
            }
            at = p;
            break;
        }
        if (at == null) return up > 0 ? null : "no room along the avenue";
        if (!now && !TownJobs.atWork(level, v, "festival", at, "setting out the midwinter lanterns")) return "waiting for a hand";
        Predicate<ItemStack> what = light == Blocks.LANTERN ? s -> s.is(Items.LANTERN) : s -> s.is(Items.TORCH);
        ItemStack one = Crafts.takeOne(level, v, what);
        if (one.isEmpty()) return "no lanterns or torches in the stores";
        BlockState st = light.defaultBlockState();
        Festivals.put(level, town, at, st, "midwinter", d, one.copyWithCount(1));
        Festivals.kept(id, Festivals.Feast.MIDWINTER, d, level.getDayTime() / 24000L);
        Festivals.dirty();
        return null;
    }

    /** Every lantern out at once (the tests and /village festival midwinter now). Null when any are up, else why none. */
    @Nullable
    static String hangAll(ServerLevel level, Villages.Village v, long d, boolean now) {
        Festivals.Town town = Festivals.town(v.id());
        String why = null;
        for (int i = 0; i < LANTERNS; i++) {
            int before = town.placed.size();
            why = hang(level, v, town, d, now);
            if (town.placed.size() == before) break;
        }
        if (now && town.giftsDay != d) plan(level, v, town, d);
        return Festivals.has(town, "midwinter") ? null : why;
    }

    /** Midwinter into the chronicle (once): its lanterns, if any were out, and its presents. */
    static void told(ServerLevel level, Villages.Village v, Festivals.Town town, long d, boolean lanterns) {
        if (town.midwinterTold == d) return;
        town.midwinterTold = d;
        Festivals.dirty();
        int gifts = town.giftsDay == d ? town.gifts : 0;
        if (!lanterns && gifts == 0) return;
        String presents = gifts + (gifts == 1 ? " present" : " presents") + " given between friends";
        Villages.tell(v.id(), d, "midwinter was kept: " + (lanterns ? "lanterns along the avenue" + (gifts > 0 ? ", and " + presents : "")
            : presents));
    }

    // ------------------------------------------------------------------ the presents

    /** Who gives to whom this midwinter: each grown folk with coin to spare, to the one nearest its heart. */
    static void plan(ServerLevel level, Villages.Village v, Festivals.Town town, long d) {
        town.giftsDay = d;
        town.gifts = 0;
        Festivals.dirty();
        GIVEN.remove(v.id());
        NO_ROOM.remove(v.id());                                         // the avenue looked at afresh this midwinter
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && !f.isShowcase() && !f.isHired()) folk.add(f);
        }
        int planned = 0;
        for (VillageFolkEntity f : folk) {
            if (planned >= GIVERS) break;
            if (f.isBaby() || f.purse() < 2) continue;
            VillageFolkEntity to = dearest(f, folk);
            if (to == null) continue;
            ERRANDS.put(f.getUUID(), new Errand(to.getUUID(), d));
            planned++;
        }
    }

    /** Its partner; else its child or its parent; else the friend it is fondest of (a friend by its own feeling). */
    @Nullable
    static VillageFolkEntity dearest(VillageFolkEntity f, List<VillageFolkEntity> folk) {
        UUID partner = f.life().partner();
        VillageFolkEntity kin = null, friend = null;
        int warmest = Social.FRIEND - 1;
        for (VillageFolkEntity o : folk) {
            if (o == f) continue;
            if (o.getUUID().equals(partner)) return o;
            if (kin == null && (o.parentIds().contains(f.getUUID()) || f.parentIds().contains(o.getUUID()))) kin = o;
            int w = f.life().affinity(o.getUUID());
            if (w > warmest) { warmest = w; friend = o; }
        }
        return kin != null ? kin : friend;
    }

    /** Is it on its way with a midwinter present (its own evening waits)? */
    static boolean busy(VillageFolkEntity f) {
        Errand e = ERRANDS.get(f.getUUID());
        return e != null && e.since >= 0;
    }

    /**
     * A giver's part, from its own tick (Festivals.hold): in its own time, to the shop for the present, then
     * round to its friend with it. True while it is about it.
     */
    static boolean hold(VillageFolkEntity f, ServerLevel level) {
        Errand e = ERRANDS.get(f.getUUID());
        if (e == null) return false;
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L, now = level.getGameTime();
        VillageFolkEntity them = level.getEntity(e.to) instanceof VillageFolkEntity o && o.isAlive() ? o : null;
        Villages.Village v = Villages.get(f.ownerId());
        if (day != e.day || them == null || v == null) {
            ERRANDS.remove(f.getUUID());                     // the day is gone, or so are they: a present bought is its own, kept
            return false;
        }
        if (e.since < 0) {
            // Only in its own time, and not after bedtime.
            if (f.isSleeping() || !f.offWorkNow() || f.peekJob() != null || Assemblies.attending(f) || TownJobs.busy(f)) return false;
            if (t >= f.bedtimeTick() || them.isSleeping() || f.distanceToSqr(them) > 96.0 * 96.0) return false;
            e.since = now;
            f.getNavigation().stop();
        }
        if (now - e.since > 2400L || them.isSleeping()) {
            e.since = -1;                                    // could not get there: later in the day
            if (++e.tries >= 3) ERRANDS.remove(f.getUUID());
            return false;
        }
        f.lastLeisureTick = f.tickCount;
        if (e.bought == null) {
            BlockPos shop = Villages.builtAt(v.id(), "shop");
            boolean open = shop != null && Cafe.open(v.id(), "shop");
            BlockPos counter = open ? shop : v.centre();
            if (f.blockPosition().distSqr(counter) > 16.0) {
                if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(counter, 0.95D);
                f.hobbyNow = "off to " + (open ? "the shop" : "the stores") + " for a midwinter present for " + them.displayNameCap();
                return true;
            }
            f.getNavigation().stop();
            ItemStack got = buy(level, v, f, them, open);
            if (got.isEmpty()) {
                ERRANDS.remove(f.getUUID());
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Nothing there I could run to. A hug will have to do this year.",
                    "Hm. Nothing fit for " + them.displayNameCap() + ". I'll wish them well instead."));
                return true;
            }
            // Its own till it is given (a keepsake in its pack: never banked, and kept through a restart).
            e.bought = got.getItem();
            Homes.keepsake(got, f);
            ItemStack left = f.insertItem(got.copy());
            if (!left.isEmpty()) {
                ERRANDS.remove(f.getUUID());
                Crafts.store(level, v, left);
                return true;
            }
            f.brain("bought " + Birthdays.a(got) + " for " + them.displayNameCap() + " for midwinter");
            return true;
        }
        if (f.distanceToSqr(them) > 2.6 * 2.6) {
            if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(them.blockPosition(), 0.95D);
            f.hobbyNow = "taking " + them.displayNameCap() + " a midwinter present";
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(them, 30.0F, 30.0F);
        ItemStack gift = fromPack(f, e.bought);
        if (!gift.isEmpty()) handOver(level, v, f, them, gift);
        ERRANDS.remove(f.getUUID());
        return true;
    }

    /**
     * The present bought out of the giver's own purse: at the shop's counter (the shop's sale, its books, the
     * coin into the treasury) or, with no shop open, over the stores' counter. What its friend loves first,
     * never what they cannot abide. Carried in hand; empty if there was nothing it could run to.
     */
    static ItemStack buy(ServerLevel level, Villages.Village v, VillageFolkEntity giver, VillageFolkEntity to, boolean shop) {
        if (!shop) return Birthdays.buy(level, giver, to);
        Persona.Gift loves = to.persona().loves(), hates = to.persona().hates();
        List<Predicate<ItemStack>> tries = new ArrayList<>();
        tries.add(s -> Birthdays.present(s) && Birthdays.Gifts.kindOf(s) == loves && !Homes.isKeepsake(s) && s.get(net.minecraft.core.component.DataComponents.FOOD) == null);
        tries.add(s -> Birthdays.present(s) && Birthdays.Gifts.kindOf(s) != hates && !Homes.isKeepsake(s));
        for (Predicate<ItemStack> want : tries) {
            if (Cafe.folkShops(level, v, giver, false, want) == null) continue;
            // Out of its pack into its hand: the newest thing of the kind it owns.
            var pack = giver.getInventoryItems();
            for (int i = pack.size() - 1; i >= 0; i--) {
                ItemStack s = pack.get(i);
                if (s.isEmpty() || !Homes.isKeepsake(s) || !Homes.ownedBy(s, giver) || !Birthdays.present(s)) continue;
                ItemStack one = s.split(1);
                if (s.isEmpty()) pack.set(i, ItemStack.EMPTY);
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    /** The present it bought, out of its pack (its own keepsake of that kind), or nothing if it is gone. */
    static ItemStack fromPack(VillageFolkEntity f, @Nullable net.minecraft.world.item.Item what) {
        var pack = f.getInventoryItems();
        for (int i = pack.size() - 1; i >= 0; i--) {
            ItemStack s = pack.get(i);
            if (s.isEmpty() || what != null && !s.is(what) || !Homes.isKeepsake(s) || !Homes.ownedBy(s, f)) continue;
            ItemStack one = s.split(1);
            if (s.isEmpty()) pack.set(i, ItemStack.EMPTY);
            return one;
        }
        return ItemStack.EMPTY;
    }

    /** The present handed over: the friend's own now, a thank-you, and both the fonder for it. */
    static void handOver(ServerLevel level, Villages.Village v, VillageFolkEntity giver, VillageFolkEntity to, ItemStack gift) {
        long day = level.getDayTime() / 24000L;
        RandomSource r = giver.getRandom();
        String what = Birthdays.a(gift);
        String name = to.displayNameCap();
        // Its own now: a keepsake of the friend's; something to eat is just food (the giver's mark off it, too).
        if (gift.get(net.minecraft.core.component.DataComponents.FOOD) == null) Homes.keepsake(gift, to);
        else gift.remove(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        ItemStack left = to.insertGiven(gift.copy());
        if (!left.isEmpty()) {
            ItemStack back = giver.insertItem(left);
            if (!back.isEmpty()) Crafts.store(level, v, back);
        }
        to.life().feel(giver.getUUID(), giver.displayNameCap(), 5);
        giver.life().feel(to.getUUID(), name, 3);
        to.persona().gotAGift(day, giver.displayNameCap());
        to.persona().remember(day, giver.displayNameCap() + " gave me " + what + " for midwinter", 4);
        giver.persona().remember(day, "I gave " + name + " " + what + " for midwinter", 2);
        giver.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        level.sendParticles(ParticleTypes.HEART, to.getX(), to.getEyeY() + 0.4, to.getZ(), 2, 0.3, 0.2, 0.3, 0.0);
        level.sendParticles(ParticleTypes.SNOWFLAKE, to.getX(), to.getEyeY() + 0.6, to.getZ(), 8, 0.4, 0.3, 0.4, 0.01);
        FolkTalk.speak(giver, FolkTalk.pick(r, "Happy midwinter, " + name + "! This is for you: " + what + ".",
            "For you, " + name + " — happy midwinter!", "Merry midwinter, " + name + ". I got you " + what + "."));
        to.sayLater(FolkTalk.pick(r, "Oh, " + giver.displayNameCap() + ", you shouldn't have! Happy midwinter!",
            "Thank you! Happy midwinter to you too.", "For me? How lovely — thank you!"), 40);
        Festivals.Town town = Festivals.town(v.id());
        town.gifts++;
        Festivals.kept(v.id(), Festivals.Feast.MIDWINTER, Festivals.dayThisYear(v.id(), day, Festivals.Feast.MIDWINTER), day);
        GIVEN.computeIfAbsent(v.id(), k -> new ArrayList<>()).add(giver.displayNameCap() + "|" + name + "|" + what);
    }

    // ------------------------------------------------------------------ for the tests

    /** Who is to give to whom (giver to friend), as planned. */
    public static Map<UUID, UUID> plannedForTests(UUID village) {
        Map<UUID, UUID> out = new java.util.HashMap<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            Errand e = ERRANDS.get(a.getUUID());
            if (e != null) out.put(a.getUUID(), e.to);
        }
        return out;
    }

    /** The presents given today: "giver|friend|what". */
    public static List<String> givenForTests(UUID village) {
        List<String> g = GIVEN.get(village);
        return g == null ? List.of() : new ArrayList<>(g);
    }

    /** The presents planned now (whatever the day), for this midwinter. */
    public static void planForTests(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        plan(level, v, Festivals.town(v.id()), Festivals.dayThisYear(v.id(), day, Festivals.Feast.MIDWINTER));
    }
}
