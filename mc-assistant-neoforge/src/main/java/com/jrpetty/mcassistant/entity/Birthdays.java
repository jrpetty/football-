package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Birthdays.
 *
 * <p>Folk count their years a year to every five days once they are grown (six to the day as
 * children: VillageFolkEntity.ageYears), so a birthday by their count comes round every fifth day —
 * too often to keep each one. What they keep are the round ones: the day a folk's years pass into a
 * new ten. A child born in the village keeps one in its childhood, the day it comes into double
 * figures (its second day); after that a grown folk keeps one every fifty days, at twenty, thirty,
 * forty and on, as long as it lives. The day comes
 * from when it was born — or, for one who came to the village grown, from the years it came with —
 * and that is saved with it (its BornDay); the last birthday it kept is saved with the world, so
 * nothing is kept twice.
 *
 * <p>On the day it says so, and its friends (those fond enough of it to be its friends, its partner,
 * its parents and its children) each come round with a present: something out of their own packs —
 * a flower, a cookie, a slice of pie, bread, an apple, a rug — the kind it loves first, never the
 * kind it can't abide, never the giver's last rations or the tools of its trade; or, with nothing
 * fit to give, one bought out of their own purse from the stores (the coin into the treasury). The
 * present goes from the one pack to the other — nothing is made out of nothing. It says thank you,
 * it is the happier for the day, and they are the fonder of each other. The village's history notes a
 * child's birthday and an elder's.
 */
public final class Birthdays {

    private Birthdays() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** At most so many come round with a present: the fondest. */
    static final int GIVERS = 3;

    /** Who is keeping a birthday today, and the age it turned. */
    private record Today(long day, int age) {}

    private static final Map<UUID, Today> TODAY = new ConcurrentHashMap<>();
    /** Presents still to be taken round: giver to the folk whose birthday it is. */
    private static final Map<UUID, Deque<UUID>> OWES = new ConcurrentHashMap<>();
    /** A giver on its way: to whom, since when. */
    private record Visit(UUID to, long since) {}
    private static final Map<UUID, Visit> VISITS = new ConcurrentHashMap<>();
    /** What each folk has been given on its birthday (for its card, and the tests). */
    private static final Map<UUID, List<String>> GOT = new ConcurrentHashMap<>();
    /** The day each village last looked for birthdays. */
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        TODAY.clear();
        OWES.clear();
        VISITS.clear();
        GOT.clear();
        LOOKED.clear();
    }

    // ------------------------------------------------------------------ the years

    /** Its age on a day, by the folk's own count (as VillageFolkEntity.ageYears has it today). */
    public static int ageOn(long born, long day) {
        long days = Math.max(0, day - born);
        return days < VillageFolkEntity.GROW_DAYS ? VillageFolkEntity.childYears(days) : VillageFolkEntity.grownYears(days);
    }

    /** When it was born (worked out the first time anybody asks, for one who came grown). */
    static long born(VillageFolkEntity f) {
        if (f.bornDay() == VillageFolkEntity.UNKNOWN) f.ageYears();
        return f.bornDay();
    }

    /** Is today its birthday: the day its years pass into a new ten? Not the day it was born, or came. */
    public static boolean isBirthday(VillageFolkEntity f, long day) {
        long born = born(f);
        if (day - born < 1) return false;
        if (f.life().parents().isEmpty() && f.persona().since() == day) return false;     // it only came today
        return ageOn(born, day) / 10 > ageOn(born, day - 1) / 10;
    }

    /** The next day its years pass into a new ten (today, if today is the day), and the age it will be. */
    public static long[] next(VillageFolkEntity f, long day) {
        long born = born(f);
        // [ageing] Ten years ahead at the most (fifty days, at a year in five), and a few days over.
        for (long d = Math.max(day, born + 1); d <= day + 10L * VillageFolkEntity.DAYS_A_YEAR + 10; d++) {
            if (ageOn(born, d) / 10 > ageOn(born, d - 1) / 10) return new long[]{ d, ageOn(born, d) };
        }
        return new long[]{ -1, 0 };
    }

    /** Is it keeping its birthday today? */
    public static boolean today(VillageFolkEntity f) {
        Today t = TODAY.get(f.getUUID());
        return t != null && t.day() == f.level().getDayTime() / 24000L;
    }

    /** What it has been given this birthday. */
    public static List<String> presents(VillageFolkEntity f) {
        List<String> got = GOT.get(f.getUUID());
        return got == null ? List.of() : new ArrayList<>(got);
    }

    /** A notable birthday, for the history: a child's, or an elder's. */
    static boolean notable(VillageFolkEntity f, int age) {
        return f.isBaby() || age < 18 || age >= VillageFolkEntity.OLD_AT;
    }

    /** "into double figures", or the age in words. */
    static String turned(int age) {
        return age < 18 ? TownCalendar.inWords(age) + " — into double figures" : TownCalendar.inWords(age);
    }

    // ------------------------------------------------------------------ the day itself

    /** Once a morning, after the town is up: whose birthday is it? (TownCalendar's look round, every few seconds.) */
    static void tick(ServerLevel level, Villages.Village v) {
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        if (t < 200L || t > 11000L) return;                  // the town is up (the dawn bell has rung, or the sun has)
        if (LOOKED.getOrDefault(v.id(), Long.MIN_VALUE) == day) return;
        LOOKED.put(v.id(), day);
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isShowcase() || f.isHired()) continue;
            if (!isBirthday(f, day) || TownCalendar.birthdayKept(f.getUUID()) == day) continue;
            celebrate(level, v, f, day);
        }
    }

    /** It is this folk's birthday: it says so, it remembers it, and its friends set off with presents. */
    public static void celebrate(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day) {
        int age = f.ageYears();
        TODAY.put(f.getUUID(), new Today(day, age));
        GOT.remove(f.getUUID());
        TownCalendar.birthdayKept(f.getUUID(), day);
        RandomSource r = f.getRandom();
        f.persona().remember(day, "my birthday: " + TownCalendar.inWords(age) + " today", 5);
        if (!f.isSleeping()) {
            FolkTalk.speak(f, age < 18 ? FolkTalk.pick(r, "It's my birthday! I'm " + TownCalendar.inWords(age) + " — double figures!",
                    "Guess what? It's my birthday!")
                : FolkTalk.pick(r, "It's my birthday — " + TownCalendar.inWords(age) + " today!", TownCalendar.inWords(age).substring(0, 1).toUpperCase()
                    + TownCalendar.inWords(age).substring(1) + " today. Where do the years go?", "Another ten years. Birthday today!"));
        }
        if (notable(f, age)) {
            Villages.tell(v.id(), day, f.displayNameCap() + " turned " + turned(age) + (age >= VillageFolkEntity.OLD_AT ? ", and the town wished them many more" : ""));
        }
        // Who comes round: its friends, its partner, its family, the fondest first.
        List<VillageFolkEntity> givers = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity o) || o == f || !o.isAlive() || o.isShowcase() || o.isHired()) continue;
            if (close(o, f)) givers.add(o);
        }
        givers.sort((p, q) -> Integer.compare(q.life().affinity(f.getUUID()), p.life().affinity(f.getUUID())));
        for (int i = 0; i < givers.size() && i < GIVERS; i++) {
            OWES.computeIfAbsent(givers.get(i).getUUID(), k -> new ArrayDeque<>()).addLast(f.getUUID());
        }
        LOG.info("[MCA-BIRTHDAY] {} of {} turned {}; {} coming round", f.displayNameCap(), Villages.name(v.id()), age,
            givers.subList(0, Math.min(GIVERS, givers.size())).stream().map(VillageFolkEntity::displayNameCap).toList());
    }

    /** Close enough to come round with a present: a friend (by its own feeling), its partner, a parent or a child. */
    static boolean close(VillageFolkEntity giver, VillageFolkEntity to) {
        if (to.getUUID().equals(giver.life().partner())) return true;
        if (giver.life().affinity(to.getUUID()) >= Social.FRIEND) return true;
        if (to.parentIds().contains(giver.getUUID()) || giver.parentIds().contains(to.getUUID())) return true;
        String parents = to.life().parents();
        return !parents.isEmpty() && parents.contains(giver.displayNameCap());
    }

    // ------------------------------------------------------------------ going round with a present

    /** Is this folk on its way round with a present (its own work waits)? */
    static boolean busy(VillageFolkEntity f) {
        return VISITS.containsKey(f.getUUID());
    }

    /** Has it a present still to take round today (the tests)? */
    public static boolean owes(VillageFolkEntity f) {
        java.util.Deque<UUID> owed = OWES.get(f.getUUID());
        return owed != null && !owed.isEmpty();
    }

    /** Is it on its way round with a present now (the tests)? */
    public static boolean visiting(VillageFolkEntity f) {
        return busy(f);
    }

    /**
     * A friend's part, from its own tick: off work (its break, the evening, the day of rest), round to
     * whoever's birthday it is, and the present handed over. True while it is on its way.
     */
    static boolean hold(VillageFolkEntity f, ServerLevel level) {
        Deque<UUID> owed = OWES.get(f.getUUID());
        if (owed == null || owed.isEmpty()) {
            VISITS.remove(f.getUUID());
            return false;
        }
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L, now = level.getGameTime();
        UUID to = owed.peekFirst();
        Today their = TODAY.get(to);
        VillageFolkEntity them = level.getEntity(to) instanceof VillageFolkEntity o && o.isAlive() ? o : null;
        if (their == null || their.day() != day || them == null || f.isBaby()) {
            owed.pollFirst();                                   // the day is gone, or so are they
            VISITS.remove(f.getUUID());
            return false;
        }
        Visit visit = VISITS.get(f.getUUID());
        if (visit == null) {
            // Only in its own time, and not after bedtime.
            if (f.isSleeping() || !f.offWorkNow() || f.peekJob() != null || Assemblies.attending(f) || TownJobs.busy(f)) return false;
            if (t >= f.bedtimeTick() || them.isSleeping() || f.distanceToSqr(them) > 96.0 * 96.0) return false;
            visit = new Visit(to, now);
            VISITS.put(f.getUUID(), visit);
            f.getNavigation().stop();
        }
        if (now - visit.since() > 1200L || them.isSleeping()) {
            // Could not get to them: try again later in the day.
            VISITS.remove(f.getUUID());
            owed.pollFirst();
            owed.addLast(to);
            return false;
        }
        f.hobbyNow = "taking " + them.displayNameCap() + " a birthday present";
        f.lastLeisureTick = f.tickCount;
        if (f.distanceToSqr(them) > 2.6 * 2.6) {
            if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(them.blockPosition(), 0.95D);
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(them, 30.0F, 30.0F);
        give(level, f, them);
        owed.pollFirst();
        VISITS.remove(f.getUUID());
        return true;
    }

    /**
     * The present itself, from {@code giver} to {@code to}: out of the giver's own pack, else bought from
     * the stores out of its own purse. Returns what was given ("a cookie"), or null if it had nothing to give
     * (it wishes them a happy birthday all the same).
     */
    @Nullable
    public static String give(ServerLevel level, VillageFolkEntity giver, VillageFolkEntity to) {
        long day = level.getDayTime() / 24000L;
        RandomSource r = giver.getRandom();
        String name = to.displayNameCap();
        ItemStack gift = fromPack(giver, to);
        boolean bought = false;
        if (gift.isEmpty()) {
            gift = buy(level, giver, to);
            bought = !gift.isEmpty();
        }
        to.life().feel(giver.getUUID(), giver.displayNameCap(), gift.isEmpty() ? 3 : 6);
        giver.life().feel(to.getUUID(), name, 3);
        level.sendParticles(ParticleTypes.HEART, to.getX(), to.getEyeY() + 0.4, to.getZ(), 3, 0.3, 0.2, 0.3, 0.0);
        giver.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (gift.isEmpty()) {
            FolkTalk.speak(giver, FolkTalk.pick(r, "Happy birthday, " + name + "! I've nothing to give but my good wishes.",
                "Many happy returns, " + name + "!"));
            to.sayLater(FolkTalk.pick(r, "Thank you, " + giver.displayNameCap() + ". That's present enough.", "Bless you!"), 40);
            return null;
        }
        String what = a(gift);
        boolean loved = Gifts.kindOf(gift) == to.persona().loves();
        // Its own, kept: a keepsake is never banked in the stores (Homes); food may be eaten as any ration.
        if (gift.get(net.minecraft.core.component.DataComponents.FOOD) == null) Homes.keepsake(gift, to);
        ItemStack left = to.insertGiven(gift.copy());
        if (!left.isEmpty()) {
            ItemStack back = giver.insertItem(left);             // no room in its pack: the giver keeps it
            if (!back.isEmpty()) {
                Villages.Village v = Villages.get(to.ownerId());
                if (v != null) Crafts.store(level, v, back);
            }
        }
        to.persona().gotAGift(day, giver.displayNameCap());
        to.persona().remember(day, giver.displayNameCap() + " gave me " + what + " for my birthday", 4);
        GOT.computeIfAbsent(to.getUUID(), k -> new ArrayList<>()).add(what + " from " + giver.displayNameCap());
        FolkTalk.speak(giver, FolkTalk.pick(r, "Happy birthday, " + name + "! " + (bought ? "I got you " : "Here — ") + what + ".",
            "Many happy returns, " + name + ". This is for you: " + what + ".", "For you, " + name + ". Happy birthday!"));
        to.sayLater(loved ? FolkTalk.pick(r, cap(what) + "! My favourite — thank you, " + giver.displayNameCap() + "!",
                "Oh, " + giver.displayNameCap() + ", you remembered what I love!")
            : FolkTalk.pick(r, "Thank you, " + giver.displayNameCap() + "! " + cap(what) + " — how kind.", "For me? Thank you!",
                "You shouldn't have! Thank you."), 40);
        LOG.info("[MCA-BIRTHDAY] {} gave {} {}{}", giver.displayNameCap(), name, what,
            bought ? " (bought from the stores)" : " (from its own pack)");
        return what;
    }

    /** "a cookie", "an apple". */
    static String a(ItemStack s) {
        String n = s.getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
        return ("aeiou".indexOf(n.isEmpty() ? 'x' : n.charAt(0)) >= 0 ? "an " : "a ") + n;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** One of the small things a friend gives. */
    static boolean present(ItemStack s) {
        return s.is(ItemTags.SMALL_FLOWERS) || s.is(Items.COOKIE) || s.is(Items.CAKE) || s.is(Items.PUMPKIN_PIE) || s.is(Items.BREAD)
            || s.is(Items.APPLE) || s.is(ItemTags.WOOL_CARPETS) || s.is(Items.HONEY_BOTTLE) || s.is(Items.SWEET_BERRIES)
            || s.is(Items.BOOK) || s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON) || s.is(Items.GOLD_NUGGET)
            || s.is(Items.AMETHYST_SHARD) || s.is(Items.CANDLE) || s.is(Items.FLOWER_POT);
    }

    /** Something fit to give out of its own pack: the kind they love first; never the kind they hate, its last rations, its kit. */
    private static ItemStack fromPack(VillageFolkEntity giver, VillageFolkEntity to) {
        Persona.Gift loves = to.persona().loves(), hates = to.persona().hates();
        int food = giver.countFood();
        int bestSlot = -1, bestScore = -1;
        var pack = giver.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (s.isEmpty() || !present(s) || Homes.isKeepsake(s)) continue;
            Persona.Gift kind = Gifts.kindOf(s);
            if (kind == hates) continue;
            boolean isFood = s.get(net.minecraft.core.component.DataComponents.FOOD) != null;
            if (isFood && food <= 3) continue;                   // its own rations come first
            int score = (kind == loves ? 10 : 1) + (isFood ? 0 : 2);
            if (score > bestScore) { bestScore = score; bestSlot = i; }
        }
        if (bestSlot < 0) return ItemStack.EMPTY;
        ItemStack one = pack.get(bestSlot).split(1);
        if (pack.get(bestSlot).isEmpty()) pack.set(bestSlot, ItemStack.EMPTY);
        return one;
    }

    /** Bought from the stores out of its own purse: the kind they love first, at the market's price, into the treasury. */
    private static ItemStack buy(ServerLevel level, VillageFolkEntity giver, VillageFolkEntity to) {
        Villages.Village v = Villages.get(giver.ownerId());
        if (v == null || giver.purse() < 1) return ItemStack.EMPTY;
        Persona.Gift loves = to.persona().loves(), hates = to.persona().hates();
        List<Predicate<ItemStack>> tries = new ArrayList<>();
        tries.add(s -> present(s) && Gifts.kindOf(s) == loves && !Homes.isKeepsake(s));
        tries.add(s -> present(s) && Gifts.kindOf(s) != hates && !Homes.isKeepsake(s));
        for (Predicate<ItemStack> want : tries) {
            if (Market.stock(level, v.id(), want) <= 0) continue;
            ItemStack got = Crafts.takeOne(level, v, want);
            if (got.isEmpty()) continue;
            int price = Purchases.coinPrice(level, v.id(), got, giver);     // [econ-prices] the town's price for the one
            if (!giver.spend(price)) {
                Crafts.store(level, v, got);                    // too dear: back it goes
                return ItemStack.EMPTY;
            }
            Ledger.addCoins(v.id(), price);
            Economy.spentInTown(v.id(), price);                 // [econ-prices] it was left out of the town's books
            PriceIndex.bought(v.id(), got, 1);
            Stockroom.sold(level, v.id(), Stockroom.Seller.STORES, got, 1, price);
            return got;
        }
        return ItemStack.EMPTY;
    }

    /** What kind of present a thing is (what a folk loves or hates: Persona.Gift). */
    static final class Gifts {
        private Gifts() {}

        @Nullable
        static Persona.Gift kindOf(ItemStack s) {
            if (s.is(ItemTags.SMALL_FLOWERS) || s.is(Items.FLOWER_POT)) return Persona.Gift.FLOWERS;
            if (s.is(Items.COOKIE) || s.is(Items.CAKE) || s.is(Items.PUMPKIN_PIE) || s.is(Items.HONEY_BOTTLE) || s.is(Items.SWEET_BERRIES)) return Persona.Gift.SWEETS;
            if (s.is(Items.BOOK)) return Persona.Gift.BOOKS;
            if (s.is(ItemTags.WOOL_CARPETS) || s.is(ItemTags.WOOL)) return Persona.Gift.WOOL;
            if (s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON)) return Persona.Gift.FISH;
            if (s.is(Items.GOLD_NUGGET)) return Persona.Gift.GOLD;
            if (s.is(Items.AMETHYST_SHARD)) return Persona.Gift.GEMS;
            return null;
        }
    }

    // ------------------------------------------------------------------ how it feels, and what the player sees

    /** On its birthday a folk is the happier for it (VillageFolkEntity.refreshMood). */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        Today t = TODAY.get(f.getUUID());
        if (t == null || t.day() != day) return m;
        // Six better for the day; and on its birthday the birthday is the first thing it speaks of (a card shows
        // the first three reasons, the weightiest first: at six it fell behind a good night's sleep).
        why.add(new Object[]{ "birthday", 12 });
        return m + 6;
    }

    /** How it puts it, asked how it is. */
    public static String moodWords(VillageFolkEntity f) {
        Today t = TODAY.get(f.getUUID());
        if (t == null) return "";
        List<String> got = GOT.get(f.getUUID());
        return "It's my birthday — " + TownCalendar.inWords(t.age()) + " today!"
            + (got == null || got.isEmpty() ? "" : " " + got.get(0).substring(0, 1).toUpperCase() + got.get(0).substring(1) + ", too.");
    }

    /** Its card: its birthday, its age, and the next. */
    public static String cardLine(VillageFolkEntity f) {
        if (f.ownerId() == null || f.isShowcase()) return "";
        long day = f.level().getDayTime() / 24000L;
        long born = born(f);
        int age = f.ageYears();
        StringBuilder sb = new StringBuilder();
        boolean here = !f.life().parents().isEmpty();
        sb.append(here ? "Born on day " + (born + 1) : "Came to the village grown (born, by its years, on day " + (born + 1) + ")");
        sb.append("; ").append(age).append(age == 1 ? " year old" : " years old");
        if (today(f)) {
            List<String> got = presents(f);
            sb.append(". Its birthday is today").append(got.isEmpty() ? "" : " — given " + String.join(", ", got));
        } else {
            long[] n = next(f, day);
            if (n[0] >= 0 && n[1] <= 100) sb.append(". Next birthday: ").append(turned((int) n[1])).append(" on day ").append(n[0] + 1)
                .append(n[0] - day == 1 ? " (tomorrow)" : " (in " + (n[0] - day) + " days)");
        }
        return sb.append('.').toString();
    }

    /** Whose birthday falls in the coming week (today first): "Wren (twelve — into double figures) today". */
    public static List<String> thisWeek(ServerLevel level, UUID village, long day) {
        List<long[]> found = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isShowcase() || f.isHired()) continue;
            long[] n = next(f, day);
            if (n[0] < 0 || n[0] - day > 6 || n[1] > 100) continue;
            found.add(new long[]{ n[0], n[1], names.size() });
            names.add(f.displayNameCap());
        }
        found.sort((p, q) -> Long.compare(p[0], q[0]));
        List<String> out = new ArrayList<>();
        for (long[] n : found) {
            long in = n[0] - day;
            out.add(names.get((int) n[2]) + " (" + turned((int) n[1]) + ") " + (in == 0 ? "today" : in == 1 ? "tomorrow" : "in " + in + " days"));
            if (out.size() >= 6) break;
        }
        return out;
    }
}
