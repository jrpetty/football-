package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featlife] Stalls in the square: on a Saturday morning a folk with something spare sets up and sells it; on fair day
 * the traders from the neighbouring towns do too (Fairground).
 *
 * <p><b>The stall</b> is the town's trestle, put up for the day and taken down at dusk: a barrel for a counter between two
 * posts, a striped awning over it, on open ground round the square facing its heart, the seller behind it with its wares
 * in its hand. Every block it puts up it takes down again (and after a restart, by the list kept with the town's notes).
 *
 * <p><b>What a folk has spare</b>: what its hobby made in the week (a gardener's cut flowers, an angler's catch, a
 * whittler's wooden toy), and food in its pack beyond what it eats (more than sixteen of a kind). Priced a little under
 * the town's counter, a lot at a time, at least a coin. A buyer pays the seller out of its own purse.
 */
public final class Stalls {

    private Stalls() {}

    /** One stall: its counter, the way it faces (toward its buyers), who keeps it, what is on it and at what price. */
    static final class Stall {
        final UUID seller;
        final String name;
        final BlockPos at;
        final Direction front;
        final List<ItemStack> wares = new ArrayList<>();
        final List<Integer> prices = new ArrayList<>();
        final long day;
        final boolean trader;
        final Block awning;
        int sold, takings, called = -1000;

        Stall(UUID seller, String name, BlockPos at, Direction front, long day, boolean trader, Block awning) {
            this.seller = seller;
            this.name = name;
            this.at = at.immutable();
            this.front = front;
            this.day = day;
            this.trader = trader;
            this.awning = awning;
        }

        BlockPos behind() {
            return at.relative(front.getOpposite());
        }

        BlockPos before() {
            return at.relative(front);
        }

        List<BlockPos> blocks() {
            Direction side = front.getClockWise();
            List<BlockPos> out = new ArrayList<>();
            out.add(at);
            for (int s = -1; s <= 1; s += 2) {
                out.add(at.relative(side, s));
                out.add(at.relative(side, s).above());
            }
            for (int s = -1; s <= 1; s++) out.add(at.relative(side, s).above(2));
            return out;
        }

        boolean empty() {
            return wares.stream().allMatch(ItemStack::isEmpty);
        }
    }

    private static final Map<UUID, List<Stall>> STALLS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> DONE = new ConcurrentHashMap<>();
    private static final Block[] AWNINGS = { Blocks.RED_WOOL, Blocks.YELLOW_WOOL, Blocks.BLUE_WOOL, Blocks.GREEN_WOOL, Blocks.ORANGE_WOOL,
        Blocks.WHITE_WOOL, Blocks.PURPLE_WOOL, Blocks.LIGHT_BLUE_WOOL };

    public static void resetForTests() {
        STALLS.clear();
        DONE.clear();
    }

    static List<Stall> of(UUID village) {
        return STALLS.computeIfAbsent(village, k -> new ArrayList<>());
    }

    /** Is any stall up in the town today? */
    public static boolean any(UUID village) {
        List<Stall> l = STALLS.get(village);
        return l != null && !l.isEmpty();
    }

    public static int count(UUID village) {
        List<Stall> l = STALLS.get(village);
        return l == null ? 0 : l.size();
    }

    /** The stalls up, for the week page and the tests: "Wren: 3 poppies (1c)". */
    public static List<String> lines(UUID village) {
        List<String> out = new ArrayList<>();
        for (Stall s : of(village)) {
            List<String> w = new ArrayList<>();
            for (int i = 0; i < s.wares.size(); i++) if (!s.wares.get(i).isEmpty()) w.add(words(s.wares.get(i)) + " (" + s.prices.get(i) + "c)");
            out.add(s.name + (s.trader ? " (from away)" : "") + ": " + (w.isEmpty() ? "sold out" : String.join(", ", w))
                + (s.takings > 0 ? "; taken " + s.takings + "c" : ""));
        }
        return out;
    }

    @Nullable
    public static BlockPos at(UUID village, UUID seller) {
        for (Stall s : of(village)) if (s.seller.equals(seller)) return s.at;
        return null;
    }

    /** The front of one stall (somewhere a buyer stands), or null. */
    @Nullable
    static BlockPos frontOfOne(UUID village, RandomSource r) {
        List<Stall> l = of(village);
        if (l.isEmpty()) return null;
        return l.get(r.nextInt(l.size())).before();
    }

    // ------------------------------------------------------------------ what a folk has spare

    /** What it could sell today, without taking it: its hobby's week, its pack's extra food. */
    static List<ItemStack> surplus(ServerLevel level, VillageFolkEntity f) {
        List<ItemStack> out = new ArrayList<>();
        if (f.isBaby() || DONE.getOrDefault(f.getUUID(), -1L) == level.getDayTime() / 24000L) return out;
        if (f.persona().rolled()) {
            switch (f.persona().hobby()) {
                case GARDENING -> out.add(new ItemStack(flower(f), 3));
                case FISHING -> out.add(new ItemStack(Items.COD, 2));
                case WHITTLING -> out.add(new ItemStack(McAssistantMod.WOODEN_TOY.get(), 1));
                default -> { }
            }
        }
        if (Quirks.has(f, Quirks.Quirk.GREEN_FINGERS)) out.add(new ItemStack(Items.SWEET_BERRIES, 6));
        for (Item food : new Item[]{ Items.BREAD, Items.APPLE, Items.BAKED_POTATO, Items.CARROT, Items.COOKED_COD }) {
            int n = f.countCarried(s -> s.is(food));
            if (n > 16) out.add(new ItemStack(food, Math.min(8, n - 16)));
        }
        return out;
    }

    static Item flower(VillageFolkEntity f) {
        Item[] all = { Items.POPPY, Items.DANDELION, Items.CORNFLOWER, Items.ALLIUM, Items.OXEYE_DAISY, Items.BLUE_ORCHID, Items.AZURE_BLUET };
        return all[Math.floorMod(f.getUUID().hashCode(), all.length)];
    }

    /** Its wares taken up (the pack's food out of its pack; what its hobby made is its own work). */
    static List<ItemStack> takeUp(ServerLevel level, VillageFolkEntity f) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : surplus(level, f)) {
            if (s.is(Items.BREAD) || s.is(Items.APPLE) || s.is(Items.BAKED_POTATO) || s.is(Items.CARROT) || s.is(Items.COOKED_COD)) {
                Item it = s.getItem();
                int took = f.removeMatching(x -> x.is(it), s.getCount());
                if (took > 0) out.add(new ItemStack(it, took));
            } else out.add(s.copy());
        }
        return out;
    }

    /** A lot's price: a tenth under the town's counter, at least a coin. */
    static int price(ServerLevel level, UUID village, ItemStack lot) {
        double each = Purchases.priceEach(level, village, lot.copyWithCount(1), null);
        return Math.max(1, (int) Math.round(each * lot.getCount() * 0.9));
    }

    static String words(ItemStack s) {
        String n = s.getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
        return s.getCount() == 1 ? (n.matches("^[aeiou].*") ? "an " : "a ") + n : s.getCount() + " " + n;
    }

    // ------------------------------------------------------------------ putting one up, taking it down

    /** Put up a stall for this seller, on open ground round the square: null if there is no room. */
    @Nullable
    static Stall raise(ServerLevel level, Villages.Village v, UUID seller, String name, List<ItemStack> wares, long day, boolean trader) {
        BlockPos c = v.centre();
        List<Stall> l = of(v.id());
        int n = l.size();
        for (int ring : new int[]{ 9, 12, 7, 15 }) {
            for (int k = 0; k < 16; k++) {
                double a = Math.toRadians((k * 22.5) + (n * 37) % 360);
                int x = c.getX() + (int) Math.round(Math.sin(a) * ring), z = c.getZ() + (int) Math.round(Math.cos(a) * ring);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (Math.abs(y - c.getY()) > 2) continue;
                BlockPos at = new BlockPos(x, y, z);
                int dx = c.getX() - x, dz = c.getZ() - z;
                Direction front = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
                boolean near = false;
                for (Stall o : l) if (o.at.distSqr(at) < 36) { near = true; break; }
                if (near) continue;
                Stall s = new Stall(seller, name, at, front, day, trader, AWNINGS[Math.floorMod(seller.hashCode(), AWNINGS.length)]);
                if (!fits(level, s)) continue;
                build(level, s);
                for (ItemStack w : wares) {
                    s.wares.add(w.copy());
                    s.prices.add(price(level, v.id(), w));
                }
                l.add(s);
                remember(v.id());
                return s;
            }
        }
        return null;
    }

    static boolean fits(ServerLevel level, Stall s) {
        for (BlockPos p : s.blocks()) if (!level.getBlockState(p).canBeReplaced()) return false;
        Direction side = s.front.getClockWise();
        for (BlockPos p : new BlockPos[]{ s.at.below(), s.at.relative(side).below(), s.at.relative(side, -1).below() }) {
            if (!level.getBlockState(p).isFaceSturdy(level, p, Direction.UP)) return false;
        }
        for (BlockPos p : new BlockPos[]{ s.behind(), s.behind().above(), s.before(), s.before().above() }) {
            if (!level.getBlockState(p).canBeReplaced()) return false;
        }
        return true;
    }

    static void build(ServerLevel level, Stall s) {
        Direction side = s.front.getClockWise();
        level.setBlock(s.at, Blocks.BARREL.defaultBlockState(), 2);
        for (int k = -1; k <= 1; k += 2) {
            level.setBlock(s.at.relative(side, k), Blocks.SPRUCE_FENCE.defaultBlockState(), 2);
            level.setBlock(s.at.relative(side, k).above(), Blocks.SPRUCE_FENCE.defaultBlockState(), 2);
        }
        for (int k = -1; k <= 1; k++) level.setBlock(s.at.relative(side, k).above(2), (k == 0 ? Blocks.WHITE_WOOL : s.awning).defaultBlockState(), 2);
        level.playSound(null, s.at, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
    }

    /** Down again: every block of it that is still what was put up. */
    static void takeDown(ServerLevel level, BlockPos at, Direction front) {
        Direction side = front.getClockWise();
        List<BlockPos> ps = new ArrayList<>();
        ps.add(at);
        for (int k = -1; k <= 1; k += 2) { ps.add(at.relative(side, k)); ps.add(at.relative(side, k).above()); }
        for (int k = -1; k <= 1; k++) ps.add(at.relative(side, k).above(2));
        if (!level.isLoaded(at)) return;
        for (BlockPos p : ps) {
            BlockState st = level.getBlockState(p);
            if (st.is(Blocks.BARREL) || st.is(Blocks.SPRUCE_FENCE) || st.is(net.minecraft.tags.BlockTags.WOOL)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    /** The town's stalls written down, so a restart still takes them down. */
    static void remember(UUID village) {
        StringBuilder sb = new StringBuilder();
        for (Stall s : of(village)) {
            if (sb.length() > 0) sb.append(';');
            sb.append(s.at.asLong()).append(',').append(s.front.get3DDataValue());
        }
        Ledger.note(village, "life.stalls", sb.toString());
    }

    /** The day over (or a restart with stalls on the list): every stall taken down, the unsold wares back to their sellers. */
    public static void clear(ServerLevel level, UUID village) {
        List<Stall> l = of(village);
        for (Stall s : l) {
            takeDown(level, s.at, s.front);
            if (!s.trader && level.getEntity(s.seller) instanceof VillageFolkEntity f) {
                for (ItemStack w : s.wares) if (!w.isEmpty()) f.insertItem(w.copy());
                f.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
        }
        l.clear();
        String kept = Ledger.note(village, "life.stalls");
        if (kept != null && !kept.isEmpty()) {
            for (String part : kept.split(";")) {
                String[] q = part.split(",");
                try { takeDown(level, BlockPos.of(Long.parseLong(q[0])), Direction.from3DDataValue(Integer.parseInt(q[1]))); } catch (RuntimeException ignored) { }
            }
            Ledger.note(village, "life.stalls", "");
        }
    }

    /** The town's round: at dusk, or on a day with no market, down they come. */
    static void round(ServerLevel level, Villages.Village v) {
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        List<Stall> l = of(v.id());
        boolean stale = false;
        for (Stall s : l) if (s.day != day) { stale = true; break; }
        String kept = Ledger.note(v.id(), "life.stalls");
        if (t >= WeekendLife.TO || stale || l.isEmpty() && kept != null && !kept.isEmpty()) clear(level, v.id());
    }

    // ------------------------------------------------------------------ keeping it

    /** Its stall kept (WeekendLife: SELL): put up, stood behind, its wares called out. False when it cannot or has sold out. */
    static boolean keep(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day) {
        Stall s = null;
        for (Stall x : of(v.id())) if (x.seller.equals(f.getUUID())) { s = x; break; }
        if (s == null) {
            if (DONE.getOrDefault(f.getUUID(), -1L) == day) return false;
            List<ItemStack> wares = takeUp(level, f);
            DONE.put(f.getUUID(), day);
            if (wares.isEmpty()) return false;
            s = raise(level, v, f.getUUID(), f.displayNameCap(), wares, day, false);
            if (s == null) {
                for (ItemStack w : wares) f.insertItem(w);
                return false;
            }
            Villages.tell(v.id(), day, f.displayNameCap() + " set up a stall in the square");
        }
        if (s.empty()) {
            f.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            return false;
        }
        BlockPos stand = s.behind();
        if (f.blockPosition().distSqr(stand) > 2) {
            if (f.getNavigation().isDone() || f.tickCount - s.called > 80) {
                f.walkTo(stand, 0.8D);
                s.called = f.tickCount;
            }
            f.hobbyNow = "on its way to its stall";
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(s.before().getX() + 0.5, s.before().getY() + 1.5, s.before().getZ() + 0.5);
        f.hobbyNow = "at its stall in the square";
        for (ItemStack w : s.wares) if (!w.isEmpty()) { f.setItemInHand(InteractionHand.MAIN_HAND, w.copyWithCount(1)); break; }
        if (f.tickCount - s.called > 400) {
            s.called = f.tickCount;
            for (int i = 0; i < s.wares.size(); i++) {
                if (s.wares.get(i).isEmpty()) continue;
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), FolkTalk.cap(words(s.wares.get(i))) + ", " + s.prices.get(i) + "c.",
                    FolkTalk.cap(words(s.wares.get(i))) + ". Grown, caught or made this week. " + s.prices.get(i) + "c.",
                    "Nothing dearer than " + s.prices.get(i) + "c on here.", "Cheaper than the shop. Not by much.",
                    "Have a look. Looking's free.", "Last of the week's. " + s.prices.get(i) + "c."));
                break;
            }
        }
        return true;
    }

    /** A buyer at the stalls: the nearest stall (not its own) with something it likes at a price it will pay. */
    @Nullable
    static String buy(ServerLevel level, Villages.Village v, VillageFolkEntity buyer, long day) {
        int budget = LifeWeights.budget(buyer);
        if (budget <= 0) return null;
        List<Stall> l = new ArrayList<>(of(v.id()));
        l.sort(java.util.Comparator.comparingDouble(s -> s.at.distSqr(buyer.blockPosition())));
        for (Stall s : l) {
            if (s.seller.equals(buyer.getUUID())) continue;
            net.minecraft.world.entity.Entity e = level.getEntity(s.seller);
            if (!(e instanceof VillageFolkEntity seller) || !seller.isAlive()) continue;
            for (int i = 0; i < s.wares.size(); i++) {
                ItemStack w = s.wares.get(i);
                int p = s.prices.get(i);
                if (w.isEmpty() || p > budget || !buyer.spend(p)) continue;
                seller.earn(p);
                s.sold++;
                s.takings += p;
                ItemStack lot = w.copy();
                s.wares.set(i, ItemStack.EMPTY);
                ItemStack left = buyer.insertItem(lot);
                if (!left.isEmpty()) Block.popResource(level, buyer.blockPosition(), left);
                LifeLog.count(buyer, LifeLog.Stat.SPENT, p);
                LifeLog.count(buyer, LifeLog.Stat.BOUGHT, 1);
                if (!s.trader) {
                    LifeLog.count(seller, LifeLog.Stat.SOLD, p);
                    LifeLog.note(seller, day, "Sold " + words(lot) + " to " + buyer.displayNameCap() + " for " + p + "c at its stall", null);
                }
                Friends.chatted(buyer, seller, 1);
                level.playSound(null, s.at, SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.6F, 1.1F);
                seller.sayLater(FolkTalk.pick(seller.getRandom(), "Pleasure.", "Mind how you carry it.", "Come again.", "That's my dinner paid for.",
                    "You've a good eye.", "I'll tell my neighbours you were in."), 20);
                return words(lot) + " from " + s.name + "'s stall";
            }
        }
        return null;
    }

    /** Tests: a stall for this folk now, with these wares. */
    @Nullable
    public static BlockPos raiseForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f, List<ItemStack> wares) {
        Stall s = raise(level, v, f.getUUID(), f.displayNameCap(), wares, level.getDayTime() / 24000L, false);
        return s == null ? null : s.at;
    }
}
