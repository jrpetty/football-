package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [player15] The keeper of a player's shop (PlayerShops): a folk of the town taken on to mind the counter while the
 * player is away.
 * <ul>
 * <li><b>Taking one on.</b> The shop's screen lists who could be had: folk between trades first, then hands a trade has
 *     more of than it needs, then any shop hand; each with the wage it asks (its going day's wage) and why it could come.
 *     One click takes it on.</li>
 * <li><b>Its day.</b> In its hours (the shop's, or mornings or afternoons as the player sets) it stands behind the counter,
 *     looks after whoever is at it, greets each customer who comes in, and fills the shelves from the stockroom's chests
 *     whenever a ware runs low. Its own trade waits while it is at the shop.</li>
 * <li><b>Its wage</b> comes out of the till every morning into its purse. A till that cannot pay it runs up what it is
 *     owed; owed more than two days, it gives notice and goes.</li>
 * </ul>
 */
public final class ShopKeepers {

    private ShopKeepers() {}

    /** Every keeper: which town and whose shop. */
    private static final Map<UUID, UUID[]> KEEPERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LAST_RESTOCK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> GREETED = new ConcurrentHashMap<>();

    static void resetForTests() {
        KEEPERS.clear();
        LAST_RESTOCK.clear();
        GREETED.clear();
    }

    /** One who could be taken on: the folk, its wage, and why it is free. */
    public record Candidate(VillageFolkEntity folk, int wage, String why) {}

    public static List<Candidate> candidates(ServerLevel level, UUID village, int most) {
        List<Candidate> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive() || f.isHired() || f.isShowcase()) continue;
            if (KEEPERS.containsKey(f.getUUID()) || TownJobs.busy(f) || Orders.elderOf(village) == f) continue;
            AssistantEntity.StationTask t = f.stationTask();
            String why;
            if (t == AssistantEntity.StationTask.NONE) why = "between trades";
            else if (t == AssistantEntity.StationTask.SHOP) why = "a shop hand already";
            else if (Villages.overStaffed(village, t)) why = "the " + t.title.toLowerCase(java.util.Locale.ROOT) + "s have hands to spare";
            else continue;
            out.add(new Candidate(f, Math.max(1, Wealth.wage(f)), why));
        }
        out.sort(Comparator.comparingInt((Candidate c) -> c.why().startsWith("between") ? 0 : c.why().startsWith("a shop") ? 1 : 2)
            .thenComparingInt(Candidate::wage));
        return out.size() > most ? out.subList(0, most) : out;
    }

    /** Take this folk on as the shop's keeper. Returns what to say ("!" refused). */
    static String hire(ServerLevel level, PlayerShops.Shop s, VillageFolkEntity f) {
        if (s.keeper != null) return "!" + s.keeperName + " keeps the shop already.";
        if (f.isHired() || KEEPERS.containsKey(f.getUUID())) return "!" + f.displayNameCap() + " can't be had just now.";
        s.keeper = f.getUUID();
        s.keeperName = f.displayNameCap();
        s.wage = Math.max(1, Wealth.wage(f));
        s.owed = 0;
        s.keeperSince = PlayerShops.today(level);
        s.kOpens = s.opens;
        s.kCloses = s.closes;
        KEEPERS.put(f.getUUID(), new UUID[]{ s.village, s.owner });
        PlayerShops.save(s);
        f.brain("taken on to keep " + s.name);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Keep " + s.name + "? Gladly. I'll mind the counter.", "Taken on at " + s.name + ". Steady wages!"));
        Villages.tell(s.village, PlayerShops.today(level), f.displayNameCap() + " was taken on to keep " + s.name);
        return f.displayNameCap() + " will keep " + s.name + " for " + PlayerLife.coins(s.wage) + " a day out of its till.";
    }

    /** Let the keeper go (the owner's word, the shop closed, or unpaid). */
    static void letGo(ServerLevel level, PlayerShops.Shop s, String why) {
        if (s.keeper == null) return;
        VillageFolkEntity f = Homes.loaded(s.village, s.keeper);
        KEEPERS.remove(s.keeper);
        if (f != null) {
            f.brain("left " + s.name + ": " + why);
            f.hobbyNow = null;
        }
        Villages.tell(s.village, PlayerShops.today(level), s.keeperName + " left " + s.name + " (" + why + ")");
        s.keeper = null;
        s.keeperName = "";
        s.owed = 0;
        PlayerShops.save(s);
    }

    /** Every morning: the wage out of the till; unpaid two days and more, it goes. */
    static void payday(ServerLevel level, Villages.Village v, PlayerShops.Shop s, long day) {
        if (s.keeper == null) return;
        VillageFolkEntity f = Homes.loaded(s.village, s.keeper);
        if (f == null && Villages.folkOf(s.village).stream().noneMatch(a -> a.getUUID().equals(s.keeper))) {
            letGo(level, s, "it is gone from the town");
            return;
        }
        int due = s.wage + s.owed;
        int paid = Math.min(due, s.till);
        if (paid > 0 && f != null) {
            s.till -= paid;
            f.earn(paid);
            PlayerFinance.internal(s.owner, s.village, -paid * 100L, PlayerFinance.Cat.HANDS, s.keeperName + " keeping " + s.name + ", out of its till");   // [join21]
            s.owed = due - paid;
        } else if (f != null) {
            s.owed = due;
        }
        if (s.owed > 2 * s.wage) {
            PlayerShops.tellOwner(level, s.owner, s.keeperName + " has gone from " + s.name + ": its wages were owed " + PlayerLife.coins(s.owed) + ".");
            letGo(level, s, "unpaid");
            return;
        }
        if (paid > 0 && s.tell) PlayerShops.tellOwner(level, s.owner, s.keeperName + " was paid " + PlayerLife.coins(paid) + " out of " + s.name + "'s till.");
    }

    /** Every few seconds: who keeps what, read from the shops. */
    static void tick(ServerLevel level, Villages.Village v) {
        for (PlayerShops.Shop s : PlayerShops.all(v.id())) {
            if (s.keeper != null) KEEPERS.put(s.keeper, new UUID[]{ s.village, s.owner });
        }
    }

    @Nullable
    static PlayerShops.Shop shopOf(VillageFolkEntity f) {
        UUID[] k = KEEPERS.get(f.getUUID());
        if (k == null) return null;
        PlayerShops.Shop s = PlayerShops.of(k[0], k[1]);
        if (s == null || !f.getUUID().equals(s.keeper)) {
            KEEPERS.remove(f.getUUID());
            return null;
        }
        return s;
    }

    /** The keeper, if it is behind the counter now. */
    @Nullable
    static VillageFolkEntity behindCounter(ServerLevel level, PlayerShops.Shop s) {
        if (s.keeper == null) return null;
        VillageFolkEntity f = Homes.loaded(s.village, s.keeper);
        if (f == null) return null;
        BlockPos c = PlayerShops.survey(level, s).counter();
        return c != null && f.blockPosition().distSqr(c) <= 9 ? f : null;
    }

    /** Every tick for a keeper in its hours: to its place behind the counter, and the work there. True while at it. */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        PlayerShops.Shop s = shopOf(f);
        if (s == null || f.isSleeping() || !level.isLoaded(s.anchor)) return false;
        if (!PlayerShops.inHours(level, s.kOpens, s.kCloses) || PlayerShops.state(level, s) == PlayerShops.State.INCOMPLETE) return false;
        ShopNeeds.Survey sv = PlayerShops.survey(level, s);
        BlockPos counter = sv.counter();
        if (counter == null) return false;
        BlockPos place = behind(level, sv, counter);
        if (!ShopCustomers.closeTo(f, place, 0.9)) {
            if (f.getNavigation().isDone() || f.tickCount % 60 == 0) f.walkTo(place, 0.8);
            f.hobbyNow = "on the way to keep " + s.name;
            return true;
        }
        f.getNavigation().stop();
        f.hobbyNow = "keeping " + s.name;
        Direction front = facing(level, counter);
        BlockPos look = counter.relative(front, 2);
        f.getLookControl().setLookAt(look.getX() + 0.5, look.getY() + 1.4, look.getZ() + 0.5);
        if (f.tickCount % 20 == 0) greet(level, f, s, sv);
        int last = LAST_RESTOCK.getOrDefault(f.getUUID(), -10000);
        if (f.tickCount - last >= 300 || f.tickCount < last) {
            LAST_RESTOCK.put(f.getUUID(), f.tickCount);
            if (restock(level, s) > 0) f.swing(InteractionHand.MAIN_HAND);
        }
        return true;
    }

    static Direction facing(ServerLevel level, BlockPos counter) {
        var st = level.getBlockState(counter);
        return st.hasProperty(com.jrpetty.mcassistant.block.ShopCounterBlock.FACING) ? st.getValue(com.jrpetty.mcassistant.block.ShopCounterBlock.FACING) : Direction.NORTH;
    }

    /** Behind the counter: the side away from the customers, else beside it. */
    static BlockPos behind(ServerLevel level, ShopNeeds.Survey sv, BlockPos counter) {
        BlockPos b = counter.relative(facing(level, counter).getOpposite());
        if (sv.room.contains(b) && sv.room.contains(b.above())) return b;
        BlockPos side = ShopCustomers.standBeside(level, sv, counter);
        return side == null ? counter.relative(facing(level, counter)) : side;
    }

    /** A word for each customer who comes in, once a visit; a shop that greets its customers is better spoken of. */
    private static void greet(ServerLevel level, VillageFolkEntity f, PlayerShops.Shop s, ShopNeeds.Survey sv) {
        for (VillageFolkEntity c : level.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(10))) {
            if (c == f || !ShopCustomers.underWay(c) || !sv.room.contains(c.blockPosition())) continue;
            long now = level.getGameTime();
            Long was = GREETED.get(c.getUUID());
            if (was != null && now - was < 2400) continue;
            GREETED.put(c.getUUID(), now);
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Welcome to " + s.name + "!", "Good day, " + firstName(c.displayNameCap()) + ". Have a look round.",
                "Morning! Anything I can find you?"));
            s.greeted++;
            s.rep = Math.min(1.0, s.rep + 0.003);
            return;
        }
    }

    /** The shelves filled from the stockroom: a ware the shelves are short of (under two lots), a stack of it. Returns how many moved. */
    static int restock(ServerLevel level, PlayerShops.Shop s) {
        List<Container> back = PlayerShops.stockroom(level, s), shelves = PlayerShops.shelves(level, s);
        if (back.isEmpty() || shelves.isEmpty()) return 0;
        Map<String, Integer> onShelf = new LinkedHashMap<>();
        PlayerShops.wares(level, s, onShelf, false);
        for (Container b : back) {
            for (int i = 0; i < b.getContainerSize(); i++) {
                ItemStack x = b.getItem(i);
                if (x.isEmpty() || Market.isCoin(x)) continue;
                String k = PlayerStalls.key(x);
                if (onShelf.getOrDefault(k, 0) >= 2 * PlayerStalls.lot(x)) continue;
                ItemStack moving = x.copy();
                int before = moving.getCount();
                for (Container sh : shelves) {
                    moving = Stacking.insert(sh, moving);
                    sh.setChanged();
                    if (moving.isEmpty()) break;
                }
                int moved = before - moving.getCount();
                if (moved <= 0) continue;
                x.shrink(moved);
                if (x.isEmpty()) b.setItem(i, ItemStack.EMPTY);
                b.setChanged();
                s.soldOut.remove(k);
                PlayerShops.save(s);
                return moved;
            }
        }
        return 0;
    }

    static String firstName(String n) {
        int sp = n.indexOf(' ');
        return sp > 0 ? n.substring(0, sp) : n;
    }

    /** Tests: is this folk the keeper of a shop, and at its post now? */
    public static boolean atPostForTests(ServerLevel level, VillageFolkEntity f) {
        return hold(f, level);
    }

    public static String hireForTests(ServerLevel level, PlayerShops.Shop s, VillageFolkEntity f) {
        return hire(level, s, f);
    }

    public static int restockForTests(ServerLevel level, PlayerShops.Shop s) {
        return restock(level, s);
    }

    public static void paydayForTests(ServerLevel level, PlayerShops.Shop s) {
        payday(level, Villages.get(s.village), s, PlayerShops.today(level));
    }
}
