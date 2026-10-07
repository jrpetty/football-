package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [fleet] The fish market on the quay. A stall on the bank by the fleet's quay — three barrels for a counter under a
 * plank awning on posts, a sign with the day's prices, a frame on the counter with the fish of the day, and (from
 * the Stone Age, when the stores can run to it) a smoker — put up by a hand on the town's works out of the stores'
 * barrels, fences and planks.
 *
 * <ul>
 * <li><b>The catch</b> comes up the quay and into its barrels as the boats tie up (Fleet), and the market opens when
 *     the first boat is in. It shuts at dusk.</li>
 * <li><b>The seller</b> is a fisher's husband or wife, from the boats that came in, or the first fisher in if none of
 *     them has one; it stands behind the counter all afternoon and cries the fish.</li>
 * <li><b>The folk buy</b> on their breaks and on their way home: a fish or two for the household, at the town's price
 *     (PriceIndex, by way of Purchases: a tenth off on market day, a tenth off for a Thrifty one), paid out of their
 *     own purses into the treasury. The price falls with a big catch and rises with a small one: half the price for a
 *     catch four times the usual, a quarter over for a poor one. Cheap, they buy more (Purchases.decide).</li>
 * <li><b>You buy</b> by right-clicking the counter: four fish for coin from your pack (sneak to see the price).</li>
 * <li><b>Unsold at dusk</b>, the town's cook smokes what it can in the stall's smoker, on the stores' coal or logs,
 *     and the smoked fish go into the stores; what it cannot (no cook, no smoker, no fuel) goes into the stores raw,
 *     for the café's cook to cook as its menu wants.</li>
 * </ul>
 * The board, the Prices page and the gazette carry the catch and the prices.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class FishMarket {

    private FishMarket() {}

    private static final Logger LOG = LogUtils.getLogger();

    /** The market shuts at the dusk bell. */
    static final long CLOSE_AT = 12000L;
    /** What a boat brings in on a usual day: the catch the prices are reckoned against. */
    static final int USUAL_A_BOAT = 16;
    /** A player's lot at the counter. */
    static final int LOT = 4;
    public static final int LOT_FOR_TESTS = LOT;
    /** The mark on the frame on the counter (not the square's stalls' mark: Market leaves this counter alone). */
    static final String FRAME_TAG = "mca_fishstall";
    /** The fish the market sells. */
    static final Predicate<ItemStack> SOLD = s -> s.is(Items.COD) || s.is(Items.SALMON);

    /** The stall: where the seller stands (on the bank), which way the water lies, and which way the counter runs. */
    public record Stall(BlockPos stand, Direction out, Direction along) {
        public BlockPos counter() { return stand.relative(out.getOpposite()); }
        public List<BlockPos> barrels() { return List.of(counter().relative(along, -1), counter(), counter().relative(along)); }
        public BlockPos customer() { return counter().relative(out.getOpposite()); }
        public BlockPos sign() { return counter().relative(along, 2); }
        public BlockPos smoker() { return counter().relative(along, -2); }
    }

    /** One town's market: its stall, and the day's trade. */
    static final class Mart {
        final UUID village;
        @Nullable Stall stall;
        long looked = -100000L, checked = -100000L, framed = -100000L, cried = -100000L, litAt = -1;
        long day = -1;
        int landed, sold, stored, smoked, boats;
        long cents;
        boolean open, closing, closed;
        @Nullable UUID monger;
        final Map<UUID, Long> shoppers = new ConcurrentHashMap<>();
        final Set<UUID> served = new HashSet<>();
        @Nullable UUID cook;
        long cookSince;

        Mart(UUID village) {
            this.village = village;
        }
    }

    private static final Map<UUID, Mart> MARTS = new ConcurrentHashMap<>();
    /** Folk at the market (its seller, its customers, the cook at the smoker), by folk: what hold() looks up. */
    private static final Map<UUID, UUID> AT = new ConcurrentHashMap<>();

    static void resetForTests() {
        MARTS.clear();
        AT.clear();
    }

    static Mart mart(UUID village) {
        return MARTS.computeIfAbsent(village, id -> {
            Mart m = new Mart(id);
            m.stall = stallNote(id);
            loadDay(m);
            return m;
        });
    }

    // ------------------------------------------------------------------ keeping

    @Nullable
    private static Stall stallNote(UUID village) {
        String s = Ledger.note(village, "fleet.stall");
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(",");
        if (p.length < 5) return null;
        try {
            Direction out = Direction.byName(p[3]), along = Direction.byName(p[4]);
            if (out == null || along == null) return null;
            return new Stall(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])), out, along);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void keepStall(UUID village, @Nullable Stall s) {
        if (s == null) {
            Ledger.forget(village, "fleet.stall");
            return;
        }
        Ledger.note(village, "fleet.stall", s.stand().getX() + "," + s.stand().getY() + "," + s.stand().getZ() + "," + s.out().getName()
            + "," + s.along().getName());
    }

    /** The day's trade, kept with the town: "day|landed|sold|cents|stored|smoked|boats|open|closed". */
    private static void saveDay(Mart m) {
        Ledger.note(m.village, "fleet.market", m.day + "|" + m.landed + "|" + m.sold + "|" + m.cents + "|" + m.stored + "|" + m.smoked
            + "|" + m.boats + "|" + (m.open ? 1 : 0) + "|" + (m.closed ? 1 : 0));
    }

    private static void loadDay(Mart m) {
        String s = Ledger.note(m.village, "fleet.market");
        if (s == null || s.isEmpty()) return;
        String[] p = s.split("\\|");
        if (p.length < 9) return;
        try {
            m.day = Long.parseLong(p[0]);
            m.landed = Integer.parseInt(p[1]);
            m.sold = Integer.parseInt(p[2]);
            m.cents = Long.parseLong(p[3]);
            m.stored = Integer.parseInt(p[4]);
            m.smoked = Integer.parseInt(p[5]);
            m.boats = Integer.parseInt(p[6]);
            m.open = p[7].equals("1");
            m.closed = p[8].equals("1");
        } catch (NumberFormatException e) {
            m.day = -1;
        }
    }

    /** A new day at the market: yesterday's trade into the log (the last fortnight), and the counter cleared. */
    private static void roll(ServerLevel level, Mart m) {
        long day = level.getDayTime() / 24000L;
        if (m.day == day) return;
        // Yesterday's market never put away (the world stopped, or the town was out of sight at dusk): its fish into the
        // stores now, before today's go into the barrels.
        Villages.Village v = Villages.get(m.village);
        if (v != null && (m.open || m.closing) && m.stall != null && level.isLoaded(m.stall.stand())) putAway(level, v, m, null);
        if (m.day >= 0 && (m.landed > 0 || m.sold > 0)) {
            String s = Ledger.note(m.village, "fleet.market.log");
            List<String> lines = new ArrayList<>();
            if (s != null && !s.isEmpty()) lines.addAll(List.of(s.split("\n")));
            lines.add(m.day + "|" + m.landed + "|" + m.sold + "|" + m.cents + "|" + m.stored + "|" + m.smoked);
            while (lines.size() > 14) lines.remove(0);
            Ledger.note(m.village, "fleet.market.log", String.join("\n", lines));
        }
        m.day = day;
        m.landed = m.sold = m.stored = m.smoked = m.boats = 0;
        m.cents = 0;
        m.open = m.closing = m.closed = false;
        m.monger = null;
        m.cook = null;
        m.served.clear();
        m.shoppers.clear();
        saveDay(m);
    }

    /** The fleet went out today with so many boats: the usual catch the prices are reckoned against. */
    static void boatsOut(ServerLevel level, UUID village, long day, int boats) {
        Mart m = mart(village);
        roll(level, m);
        if (m.day != day) return;
        m.boats = boats;
        saveDay(m);
    }

    // ------------------------------------------------------------------ the stall

    /** Somewhere on the bank by the quay's foot for the stall: three to eight blocks along the shore from the quay, on
     *  flat dry ground with room over it, its customers' side away from the water. */
    @Nullable
    static Stall site(ServerLevel level, Waterfront.Dock q) {
        Direction in = q.out().getOpposite(), side = q.out().getClockWise();
        BlockPos bank = q.start().relative(in);
        for (int n : new int[]{ 3, -3, 4, -4, 5, -5, 6, -6, 7, -7, 8, -8 }) {
            Direction a = n > 0 ? side : side.getOpposite();
            for (int back = 0; back <= 3; back++) {
                for (int dy = 1; dy >= 0; dy--) {
                    BlockPos stand = bank.relative(a, Math.abs(n)).relative(in, back).above(dy);
                    Stall s = new Stall(stand, q.out(), side);
                    if (fits(level, s)) return s;
                }
            }
        }
        return null;
    }

    static boolean fits(ServerLevel level, Stall s) {
        List<BlockPos> feet = new ArrayList<>(s.barrels());
        feet.add(s.stand());
        feet.add(s.customer());
        feet.add(s.sign());
        feet.add(s.smoker());
        feet.add(s.stand().relative(s.along()));
        feet.add(s.stand().relative(s.along(), -1));
        for (BlockPos p : feet) {
            BlockState below = level.getBlockState(p.below());
            if (!below.isFaceSturdy(level, p.below(), Direction.UP) || !level.getFluidState(p.below()).isEmpty()) return false;
            if (below.getBlock() instanceof net.minecraft.world.level.block.FarmBlock) return false;
            if (!open(level, p) || !open(level, p.above())) return false;
        }
        for (int a = -1; a <= 1; a++) {
            if (!open(level, s.stand().relative(s.along(), a).above(2)) || !open(level, s.counter().relative(s.along(), a).above(2))) return false;
        }
        return true;
    }

    private static boolean open(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        return level.getFluidState(p).isEmpty() && (st.isAir() || st.canBeReplaced());
    }

    /**
     * Put the stall up: the counter's three barrels, the awning's two posts and its six slabs, the sign; out of the
     * stores (a barrel, a fence, a sign put by, else the planks for them: seven a barrel, two a fence or a sign, three
     * for the slabs), all or none, unless {@code free}. Then the smoker, if the stores run to it. True if it stands.
     */
    static boolean build(ServerLevel level, @Nullable Villages.Village v, Stall s, boolean free) {
        if (!free && (v == null || !affordable(level, v))) return false;          // nobody called out for what can't be paid
        if (!free && (v == null || !TownJobs.atWork(level, v, "fishmarket", s.stand(), "putting up the fish market on the quay",
                AssistantEntity.StationTask.FISH))) return false;
        if (!free && !pay(level, v)) return false;
        for (BlockPos p : List.of(s.stand(), s.customer(), s.sign(), s.smoker())) clearPlant(level, p);
        BlockState barrel = Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP);
        for (BlockPos p : s.barrels()) {
            clearPlant(level, p);
            level.setBlock(p, barrel, 3);
            if (level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity be) {
                be.applyComponents(net.minecraft.core.component.DataComponentMap.builder()
                    .set(DataComponents.CUSTOM_NAME, Component.literal("Fish Market")).build(), net.minecraft.core.component.DataComponentPatch.EMPTY);
                be.setChanged();
            }
        }
        // The awning's posts stand on the counter's end barrels, so the seller's side is open along the bank: a stall on
        // a bank one block wide is walked into from the quay's foot, not from the water.
        BlockState post = Blocks.SPRUCE_FENCE.defaultBlockState();
        for (int a : new int[]{ -1, 1 }) level.setBlock(s.counter().relative(s.along(), a).above(), post, 3);
        BlockState slab = Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        for (int a = -1; a <= 1; a++) {
            level.setBlock(s.stand().relative(s.along(), a).above(2), slab, 3);
            level.setBlock(s.counter().relative(s.along(), a).above(2), slab, 3);
        }
        int rot = Math.floorMod(Math.round(s.out().getOpposite().toYRot() / 22.5F), 16);   // its face to the customers
        level.setBlock(s.sign(), Blocks.SPRUCE_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rot), 3);
        if (v != null) smoker(level, v, s, free);
        return true;
    }

    private static void clearPlant(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        if (!st.isAir() && st.canBeReplaced() && level.getFluidState(p).isEmpty()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
    }

    /** Can the stores run to the stall (pay, without taking anything)? */
    private static boolean affordable(ServerLevel level, Villages.Village v) {
        int barrels = Math.min(3, Crafts.stock(level, v, s -> s.is(Items.BARREL))), fences = Math.min(2, Crafts.stock(level, v, s -> s.is(ItemTags.WOODEN_FENCES))),
            signs = Math.min(1, Crafts.stock(level, v, s -> s.is(ItemTags.SIGNS)));
        int planks = (3 - barrels) * 7 + (2 - fences) * 2 + (1 - signs) * 2 + 3;
        return Crafts.stock(level, v, s -> s.is(ItemTags.PLANKS)) + 4 * Crafts.stock(level, v, s -> s.is(ItemTags.LOGS)) >= planks;
    }

    private static boolean pay(ServerLevel level, Villages.Village v) {
        Predicate<ItemStack> barrel = s -> s.is(Items.BARREL), fence = s -> s.is(ItemTags.WOODEN_FENCES), sign = s -> s.is(ItemTags.SIGNS);
        int barrels = Math.min(3, Crafts.stock(level, v, barrel)), fences = Math.min(2, Crafts.stock(level, v, fence)),
            signs = Math.min(1, Crafts.stock(level, v, sign));
        int planks = (3 - barrels) * 7 + (2 - fences) * 2 + (1 - signs) * 2 + 3;
        if (Crafts.stock(level, v, s -> s.is(ItemTags.PLANKS)) + 4 * Crafts.stock(level, v, s -> s.is(ItemTags.LOGS)) < planks) return false;
        List<ItemStack> spent = new ArrayList<>();
        boolean ok = Crafts.take(level, v, barrel, barrels);
        if (ok && barrels > 0) spent.add(new ItemStack(Items.BARREL, barrels));
        ok = ok && Crafts.take(level, v, fence, fences);
        if (ok && fences > 0) spent.add(new ItemStack(Items.SPRUCE_FENCE, fences));
        ok = ok && Crafts.take(level, v, sign, signs);
        if (ok && signs > 0) spent.add(new ItemStack(Items.SPRUCE_SIGN, signs));
        ok = ok && Crafts.usePlanks(level, v, planks);
        if (ok) return true;
        for (ItemStack st : spent) Crafts.giveBack(level, v, st.getItem(), st.getCount());
        return false;
    }

    /** The stall's smoker, from the Stone Age: one put by, else a furnace and four logs, else eight cobblestone and four
     *  logs (the game's recipes). Set beside the counter. */
    private static boolean smoker(ServerLevel level, Villages.Village v, Stall s, boolean free) {
        if (level.getBlockState(s.smoker()).is(Blocks.SMOKER)) return true;
        if (!open(level, s.smoker())) return false;
        if (!free) {
            if (Villages.ageOf(v.id()).ordinal() < Villages.Age.STONE.ordinal()) return false;
            boolean paid = Crafts.take(level, v, st -> st.is(Items.SMOKER), 1);
            if (!paid && Crafts.stock(level, v, st -> st.is(ItemTags.LOGS)) >= 4) {
                if (Crafts.take(level, v, st -> st.is(Items.FURNACE), 1)) {
                    paid = Crafts.take(level, v, st -> st.is(ItemTags.LOGS), 4);
                    if (!paid) Crafts.giveBack(level, v, Items.FURNACE, 1);
                } else if (Crafts.stock(level, v, st -> st.is(Items.COBBLESTONE)) >= 8 && Crafts.take(level, v, st -> st.is(Items.COBBLESTONE), 8)) {
                    paid = Crafts.take(level, v, st -> st.is(ItemTags.LOGS), 4);
                    if (!paid) Crafts.giveBack(level, v, Items.COBBLESTONE, 8);
                }
            }
            if (!paid) return false;
        }
        Direction face = s.out().getOpposite();
        level.setBlock(s.smoker(), Blocks.SMOKER.defaultBlockState().setValue(SmokerBlock.FACING, face), 3);
        return true;
    }

    /** Is the stall still standing (its counter's barrels)? */
    static boolean stands(ServerLevel level, Stall s) {
        for (BlockPos p : s.barrels()) if (!level.getBlockState(p).is(Blocks.BARREL)) return false;
        return true;
    }

    static List<Container> barrels(ServerLevel level, Mart m) {
        List<Container> out = new ArrayList<>();
        if (m.stall == null) return out;
        for (BlockPos p : m.stall.barrels()) if (level.isLoaded(p) && level.getBlockEntity(p) instanceof Container c) out.add(c);
        return out;
    }

    /** How many of what matches the market's barrels hold. */
    static int inBarrels(ServerLevel level, Mart m, Predicate<ItemStack> what) {
        int n = 0;
        for (Container c : barrels(level, m)) {
            for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        }
        return n;
    }

    /** Take so many of a thing out of the barrels. Returns how many came out. */
    static int takeFromBarrels(ServerLevel level, Mart m, Item it, int n) {
        int got = 0;
        for (Container c : barrels(level, m)) {
            for (int i = 0; i < c.getContainerSize() && got < n; i++) {
                ItemStack s = c.getItem(i);
                if (!s.is(it)) continue;
                int k = Math.min(n - got, s.getCount());
                s.shrink(k);
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                got += k;
            }
            c.setChanged();
        }
        return got;
    }

    /** The fish on the counter: whichever the barrels hold most of (cod, else salmon), or nothing. */
    @Nullable
    static Item fishOfTheDay(ServerLevel level, Mart m) {
        int cod = inBarrels(level, m, s -> s.is(Items.COD)), salmon = inBarrels(level, m, s -> s.is(Items.SALMON));
        if (cod <= 0 && salmon <= 0) return null;
        return cod >= salmon ? Items.COD : Items.SALMON;
    }

    // ------------------------------------------------------------------ the market's round

    /** Once a second for a town with a fleet (Fleet.tick): the stall put up, opened and shut, its customers sent, its
     *  sign and its counter kept. */
    static void tick(ServerLevel level, Villages.Village v, Waterfront.Dock q) {
        Mart m = mart(v.id());
        roll(level, m);
        long now = level.getGameTime();
        long tod = level.getDayTime() % 24000L;
        if (m.stall == null) {
            if (now - m.looked < 1200L && now >= m.looked) return;
            m.looked = now;
            Stall s = site(level, q);
            if (s == null || !build(level, v, s, false)) return;
            m.stall = s;
            keepStall(v.id(), s);
            Villages.tell(v.id(), level.getDayTime() / 24000L, "a fish market was put up on the quay, by the fishing fleet's berths");
            LOG.info("[MCA-FLEET] {}: the fish market stands at {}", Villages.name(v.id()), s.stand().toShortString());
            return;
        }
        if (!level.isLoaded(m.stall.stand())) return;
        if (now - m.checked >= 600L || now < m.checked) {
            m.checked = now;
            if (!stands(level, m.stall)) {
                LOG.info("[MCA-FLEET] {}: the fish market's counter is gone; it will be put up again", Villages.name(v.id()));
                m.stall = null;
                keepStall(v.id(), null);
                return;
            }
            if (!level.getBlockState(m.stall.smoker()).is(Blocks.SMOKER) && tod < 11000L) smoker(level, v, m.stall, false);
        }
        if (m.litAt >= 0 && now - m.litAt > 600L) {
            BlockState sm = level.getBlockState(m.stall.smoker());
            if (sm.is(Blocks.SMOKER) && sm.getValue(SmokerBlock.LIT)) level.setBlock(m.stall.smoker(), sm.setValue(SmokerBlock.LIT, false), 3);
            m.litAt = -1;
        }
        if (m.open && (tod >= CLOSE_AT || tod < 1000L)) shut(level, v, m);
        if (m.closing) closing(level, v, m, now, tod);
        if (now - m.framed >= 200L || now < m.framed) {
            m.framed = now;
            dress(level, v, m);
        }
        if (m.open) sendShoppers(level, v, m, now, tod);
    }

    /** The counter's frame and the sign: the fish of the day and its price, or shut. */
    static void dress(ServerLevel level, Villages.Village v, Mart m) {
        if (m.stall == null) return;
        Item fish = m.open ? fishOfTheDay(level, m) : null;
        frame(level, m.stall.counter().above(), fish == null ? ItemStack.EMPTY : new ItemStack(fish));
        if (level.getBlockEntity(m.stall.sign()) instanceof SignBlockEntity sign) {
            String[] lines;
            if (m.open && fish != null) {
                lines = new String[]{ "Fish Market", "Cod " + money(priceEach(level, v, new ItemStack(Items.COD), null)),
                    "Salmon " + money(priceEach(level, v, new ItemStack(Items.SALMON), null)), m.landed + " landed today" };
            } else {
                lines = new String[]{ "Fish Market", "", m.closed ? "Sold out / shut" : "Opens when the", m.closed ? "till tomorrow" : "boats come in" };
            }
            TownLife.write(sign, lines);
        }
    }

    /** One of the day's fish in the counter's frame, laid flat and fixed, so a passer-by can look but not help
     *  themselves (as the square's stalls show their goods: TownLife.frameOn). */
    private static void frame(ServerLevel level, BlockPos at, ItemStack want) {
        ItemFrame frame = null;
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, new AABB(at))) {
            if (!f.getTags().contains(FRAME_TAG)) continue;
            if (frame == null) frame = f;
            else f.discard();
        }
        if (frame == null) {
            if (want.isEmpty() || !level.getBlockState(at).isAir()) return;
            frame = new ItemFrame(level, at, Direction.UP);
            CompoundTag t = frame.saveWithoutId(new CompoundTag());
            t.putBoolean("Fixed", true);
            t.putBoolean("Invisible", true);
            frame.load(t);
            frame.addTag(FRAME_TAG);
            level.addFreshEntity(frame);
        }
        ItemStack now = frame.getItem();
        if (want.isEmpty()) {
            if (!now.isEmpty()) frame.setItem(ItemStack.EMPTY, false);
            return;
        }
        if (!ItemStack.isSameItemSameComponents(now, want)) frame.setItem(want.copyWithCount(1), false);
    }

    /** Where a hand from the boats brings its catch: behind the counter, or the foot of the quay with no stall yet. */
    static BlockPos landingSpot(UUID village, Waterfront.Dock q) {
        Mart m = mart(village);
        return m.stall != null ? m.stall.stand() : q.start().relative(q.out().getOpposite()).above();
    }

    /**
     * A hand from the boats lands its catch: its cod and salmon into the market's barrels (into the stores with no
     * stall, or when the barrels are full), the rest of what came up (a pufferfish, a tropical fish, the junk, a
     * treasure) into the stores; all of it the fisher's work in the town's books (Economy.produced). The market opens
     * with the first boat in. Returns the fish landed into the market.
     */
    static int land(ServerLevel level, Villages.Village v, VillageFolkEntity f, Map<Item, Integer> haul) {
        Mart m = mart(v.id());
        roll(level, m);
        List<Container> barrels = m.stall != null && stands(level, m.stall) ? barrels(level, m) : List.of();
        Map<Item, Integer> rest = new HashMap<>(haul);
        int toMarket = 0;
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (s.isEmpty() || Homes.isKeepsake(s) || Fleet.isNet(s) || s.is(Items.FISHING_ROD)) continue;
            boolean fish = Economy.RAW_FISH.test(s);
            int n = fish ? s.getCount() : Math.min(s.getCount(), rest.getOrDefault(s.getItem(), 0));
            if (n <= 0) continue;
            ItemStack lot = s.copyWithCount(n);
            Economy.produced(f, lot.copy());
            if (SOLD.test(lot) && !barrels.isEmpty()) {
                ItemStack left = com.jrpetty.mcassistant.entity.Stacking.insert(barrels, lot.copy());
                toMarket += n - left.getCount();
                if (!left.isEmpty()) Crafts.store(level, v, left);
            } else {
                Crafts.store(level, v, lot);
            }
            if (!fish) rest.merge(s.getItem(), -n, Integer::sum);
            s.shrink(n);
            if (s.isEmpty()) pack.set(i, ItemStack.EMPTY);
        }
        for (Container c : barrels) c.setChanged();
        long tod = level.getDayTime() % 24000L;
        m.landed += toMarket;
        if (toMarket > 0 && !m.open && !m.closed && tod < CLOSE_AT) openUp(level, v, m, f);
        dress(level, v, m);
        saveDay(m);
        LOG.info("[MCA-FLEET] {} landed {} fish into the market at {} (landed today {})", f.displayNameCap(), toMarket,
            Villages.name(v.id()), m.landed);
        return toMarket;
    }

    /** The first boat in: the market opens, its seller behind the counter — the fisher's husband or wife if it has one
     *  free, else the fisher itself. */
    private static void openUp(ServerLevel level, Villages.Village v, Mart m, VillageFolkEntity lander) {
        m.open = true;
        VillageFolkEntity seller = null;
        UUID partner = lander.life().partner();
        if (partner != null && level.getEntity(partner) instanceof VillageFolkEntity p && Fleet.fit(p) && v.id().equals(p.ownerId())
                && !Fleet.out(p) && p.stationTask() != AssistantEntity.StationTask.GUARD && !p.isSleeping()) {
            seller = p;
        }
        if (seller == null) seller = lander;
        m.monger = seller.getUUID();
        AT.put(seller.getUUID(), v.id());
        if (seller.peekJob() != null) seller.clearQueue();
        seller.brain("selling the catch at the fish market");
        FolkTalk.speak(seller, seller == lander ? "Fresh off the boat! Who'll buy?" : "The boats are in! Fresh fish on the quay!");
    }

    /** At the dusk bell: the market shuts; what is left is the cook's to smoke, or the stores'. */
    private static void shut(ServerLevel level, Villages.Village v, Mart m) {
        m.open = false;
        m.closing = true;
        if (m.monger != null) AT.remove(m.monger);
        for (UUID s : m.shoppers.keySet()) AT.remove(s);
        m.shoppers.clear();
        m.monger = null;
        int left = inBarrels(level, m, SOLD);
        VillageFolkEntity cook = null;
        if (left > 0 && m.stall != null && level.getBlockState(m.stall.smoker()).is(Blocks.SMOKER)) {
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (a instanceof VillageFolkEntity f && f.stationTask() == AssistantEntity.StationTask.COOK && Fleet.fit(f) && !f.isSleeping()
                        && f.blockPosition().distSqr(m.stall.stand()) < 96 * 96) { cook = f; break; }
            }
        }
        if (cook != null) {
            m.cook = cook.getUUID();
            m.cookSince = level.getGameTime();
            AT.put(cook.getUUID(), v.id());
            if (cook.peekJob() != null) cook.clearQueue();
            cook.brain("off to the quay to smoke the unsold fish");
        }
        dress(level, v, m);
    }

    /** While shutting: the cook on its way to the smoker; got there (or not come in a minute), it is put away. */
    private static void closing(ServerLevel level, Villages.Village v, Mart m, long now, long tod) {
        if (m.cook != null && now - m.cookSince < 1200L && level.getEntity(m.cook) instanceof VillageFolkEntity c && c.isAlive()) return;
        putAway(level, v, m, null);
    }

    /**
     * The unsold catch put away: smoked by the cook if it is here, as much as the stores' fuel will do (a piece of coal or
     * charcoal smokes eight, two logs three, as a smoker burns them), into the stores as its work; the rest into the
     * stores raw. The day's figures into the chronicle.
     */
    static void putAway(ServerLevel level, Villages.Village v, Mart m, @Nullable VillageFolkEntity cook) {
        if (m.cook != null) AT.remove(m.cook);
        m.cook = null;
        m.closing = false;
        m.closed = true;
        int cod = takeFromBarrels(level, m, Items.COD, Integer.MAX_VALUE), salmon = takeFromBarrels(level, m, Items.SALMON, Integer.MAX_VALUE);
        int smoked = 0;
        if (cook != null && cod + salmon > 0 && m.stall != null && level.getBlockState(m.stall.smoker()).is(Blocks.SMOKER)) {
            int want = cod + salmon;
            int coal = Math.min((want + 7) / 8, Crafts.stock(level, v, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL)));
            if (coal > 0 && Crafts.take(level, v, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), coal)) smoked = Math.min(want, coal * 8);
            if (smoked < want) {
                int logs = Math.min((want - smoked + 2) / 3 * 2, Crafts.stock(level, v, s -> s.is(ItemTags.LOGS)));
                logs -= logs % 2;
                if (logs > 0 && Crafts.take(level, v, s -> s.is(ItemTags.LOGS), logs)) smoked = Math.min(want, smoked + logs / 2 * 3);
            }
            int smokedCod = Math.min(cod, smoked), smokedSalmon = Math.min(salmon, smoked - smokedCod);
            cod -= smokedCod;
            salmon -= smokedSalmon;
            if (smokedCod > 0) {
                ItemStack s = new ItemStack(Items.COOKED_COD, smokedCod);
                Economy.produced(cook, s.copy());
                Crafts.giveBack(level, v, Items.COOKED_COD, smokedCod);
            }
            if (smokedSalmon > 0) {
                ItemStack s = new ItemStack(Items.COOKED_SALMON, smokedSalmon);
                Economy.produced(cook, s.copy());
                Crafts.giveBack(level, v, Items.COOKED_SALMON, smokedSalmon);
            }
            if (smoked > 0) {
                BlockState sm = level.getBlockState(m.stall.smoker());
                level.setBlock(m.stall.smoker(), sm.setValue(SmokerBlock.LIT, true), 3);
                m.litAt = level.getGameTime();
                level.playSound(null, m.stall.smoker(), SoundEvents.SMOKER_SMOKE, SoundSource.BLOCKS, 1.0F, 1.0F);
                level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, m.stall.smoker().getX() + 0.5, m.stall.smoker().getY() + 1.1,
                    m.stall.smoker().getZ() + 0.5, 4, 0.1, 0.1, 0.1, 0.01);
                cook.swing(InteractionHand.MAIN_HAND);
                cook.note(AssistantEntity.Deed.THINGS_MADE, 1);
                FolkTalk.speak(cook, smoked + " fish in the smoker. They'll keep a good while now.");
            }
        }
        if (cod > 0) Crafts.giveBack(level, v, Items.COD, cod);
        if (salmon > 0) Crafts.giveBack(level, v, Items.SALMON, salmon);
        Kitchen.glut(level, v, glut(m), cod + salmon);           // [kitchen] a glut's unsold fish: the cook's pies
        m.smoked += smoked;
        m.stored += cod + salmon;
        saveDay(m);
        dress(level, v, m);
        if (m.landed > 0) {
            Villages.tell(v.id(), level.getDayTime() / 24000L, "the fish market on the quay sold " + m.sold + " of " + m.landed + " fish"
                + (smoked > 0 ? "; " + smoked + " smoked by the cook" : "") + (cod + salmon > 0 ? "; " + (cod + salmon) + " to the stores" : ""));
        }
        LOG.info("[MCA-FLEET] {}: the fish market shut: sold {}, smoked {}, stored {}", Villages.name(v.id()), m.sold, smoked, cod + salmon);
    }

    // ------------------------------------------------------------------ the price

    /** How the day's catch sets the price: the usual catch for the boats out over what was landed, its square root, half
     *  to a quarter over: a catch four times the usual at half the price, a poor one a quarter dearer. */
    static double glut(Mart m) {
        double usual = USUAL_A_BOAT * Math.max(1, m.boats);
        double landed = Math.max(1, m.landed);
        return Math.max(0.5, Math.min(1.25, Math.sqrt(usual / landed)));
    }

    /** What one of this fish costs at the market today: the town's price (Purchases.priceEach: the market day's and a
     *  Thrifty buyer's tenths off), by the catch. */
    public static double priceEach(ServerLevel level, Villages.Village v, ItemStack one, @Nullable VillageFolkEntity buyer) {
        double base = Purchases.priceEach(level, v.id(), one.copyWithCount(1), buyer);
        return Math.max(0.01, base * glut(mart(v.id())));
    }

    private static String money(double coins) {
        return String.format(Locale.ROOT, "%.2fc", coins);
    }

    // ------------------------------------------------------------------ the folk at the market

    /** Is this folk at the fish market just now (its seller, a customer, the cook)? */
    public static boolean busy(VillageFolkEntity f) {
        return AT.containsKey(f.getUUID());
    }

    /**
     * Every few ticks for the market's seller and its customers (Auctions.hold calls this): the seller behind the counter
     * crying the fish; a customer to the counter and served; the cook to the smoker. True while that is its business.
     */
    static boolean hold(VillageFolkEntity f, ServerLevel level) {
        UUID village = AT.get(f.getUUID());
        if (village == null) return false;
        Mart m = MARTS.get(village);
        Villages.Village v = Villages.get(village);
        long tod = level.getDayTime() % 24000L;
        if (m == null || m.stall == null || v == null || !village.equals(f.ownerId()) || f.isSleeping()
                || (tod >= CLOSE_AT + 1500L || tod < 1000L)) {                 // never kept from its bed by a market left open
            AT.remove(f.getUUID());
            return false;
        }
        long now = level.getGameTime();
        if (f.getUUID().equals(m.monger)) {
            if (!m.open) {
                AT.remove(f.getUUID());
                return false;
            }
            BlockPos at = m.stall.stand();
            if (f.blockPosition().distSqr(at) > 1.5 * 1.5) {
                if (f.getNavigation().isDone() || now % 60L == 0L) f.walkTo(at, 0.9D);
            } else {
                f.getNavigation().stop();
                BlockPos c = m.stall.customer();
                f.getLookControl().setLookAt(c.getX() + 0.5, c.getY() + 1.5, c.getZ() + 0.5);
                if (now - m.cried > 500L && f.getRandom().nextInt(3) == 0) {
                    m.cried = now;
                    Item fish = fishOfTheDay(level, m);
                    FolkTalk.speak(f, fish == null ? "All sold! Back tomorrow with the boats."
                        : FolkTalk.pick(f.getRandom(), "Fresh " + (fish == Items.COD ? "cod" : "salmon") + "! Caught this morning!",
                            "Fish! Fresh fish! " + money(priceEach(level, v, new ItemStack(fish), null)) + " apiece!",
                            m.landed > USUAL_A_BOAT * Math.max(1, m.boats) * 2 ? "A big catch today — cheap as you like!" : "Lovely fish, straight off the boats!"));
                }
            }
            return true;
        }
        if (f.getUUID().equals(m.cook)) {
            BlockPos at = m.stall.smoker().relative(m.stall.out().getOpposite());
            if (f.blockPosition().distSqr(at) > 2.5 * 2.5 && now - m.cookSince < 1200L) {
                if (f.getNavigation().isDone() || now % 60L == 0L) f.walkTo(at, 1.0D);
                return true;
            }
            f.getNavigation().stop();
            putAway(level, v, m, f);
            return false;
        }
        Long since = m.shoppers.get(f.getUUID());
        if (since == null || !m.open || now - since > 800L) {
            m.shoppers.remove(f.getUUID());
            AT.remove(f.getUUID());
            return false;
        }
        BlockPos at = m.stall.customer();
        if (f.blockPosition().distSqr(at) > 2.0 * 2.0) {
            if (f.getNavigation().isDone() || (now - since) % 60L == 0L) f.walkTo(at, 0.9D);
            return true;
        }
        f.getNavigation().stop();
        m.shoppers.remove(f.getUUID());
        AT.remove(f.getUUID());
        serve(level, v, f);
        return false;
    }

    /** While the market is open, now and then a folk off work (or on its break) goes down to buy a fish: the cheaper the
     *  catch, the more of them; the ones that love a bit of fish most of all. Two at a time at most. */
    private static void sendShoppers(ServerLevel level, Villages.Village v, Mart m, long now, long tod) {
        if (tod > CLOSE_AT - 300L || m.shoppers.size() >= 2 || m.stall == null || fishOfTheDay(level, m) == null) return;
        double cheap = glut(m) < 0.8 ? 2.0 : 1.0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (m.shoppers.size() >= 2) break;
            if (!(a instanceof VillageFolkEntity f) || !Fleet.fit(f) || f.isSleeping() || m.served.contains(f.getUUID())) continue;
            if (busy(f) || Fleet.out(f) || Auctions.busy(f) || f.purse() < 1) continue;
            if (!(f.offWorkNow() || f.stationTask() == AssistantEntity.StationTask.NONE)) continue;
            if (f.blockPosition().distSqr(m.stall.stand()) > 80 * 80) continue;
            boolean loves = f.persona().food().contains("cod") || f.persona().food().contains("salmon");
            double chance = (loves ? 3.0 : 1.0) * cheap / 30.0;
            if (f.getRandom().nextDouble() >= chance) continue;
            m.served.add(f.getUUID());
            m.shoppers.put(f.getUUID(), now);
            AT.put(f.getUUID(), v.id());
            f.brain("off to the fish market for a fish or two");
        }
    }

    /**
     * A folk at the counter buys: the fish it loves or the one the barrels hold most of, a fish for itself and one for
     * its household, at today's price, paid out of its own purse into the treasury (Purchases: it buys more when the
     * catch makes it cheap). Returns what it bought, or null.
     */
    @Nullable
    static String serve(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        Mart m = mart(v.id());
        if (!m.open || m.stall == null) return null;
        Item fish = fishOfTheDay(level, m);
        if (fish == null) return null;
        if (f.persona().food().contains("salmon") && inBarrels(level, m, s -> s.is(Items.SALMON)) > 0) fish = Items.SALMON;
        else if (f.persona().food().contains("cod") && inBarrels(level, m, s -> s.is(Items.COD)) > 0) fish = Items.COD;
        ItemStack one = new ItemStack(fish);
        Homes.Home home = f.ownerId() == null ? null : Homes.homeOf(v.id(), f.getUUID());
        int want = home != null && Homes.loadedMembers(v.id(), home).size() > 1 ? 2 : 1;
        double each = priceEach(level, v, one, f);
        int k = Math.min(inBarrels(level, m, s -> s.is(Items.COD) || s.is(Items.SALMON)), Purchases.decide(level, f, one, each, Purchases.Need.FOOD, want));
        while (k > 0 && !Purchases.canPay(f, each * k)) k--;
        if (k <= 0) {
            FolkTalk.speak(f, "Not today — my purse won't run to it.");
            return null;
        }
        int got = takeFromBarrels(level, m, fish, k);
        if (got <= 0) return null;
        int paid = Purchases.charge(level, f, v.id(), each * got, false, one, got);
        if (paid < 0) {
            // It could not pay after all: the fish back on the counter.
            ItemStack back = com.jrpetty.mcassistant.entity.Stacking.insert(barrels(level, m), new ItemStack(fish, got));
            if (!back.isEmpty()) Crafts.store(level, v, back);
            return null;
        }
        PriceIndex.bought(v.id(), one, got);
        m.sold += got;
        m.cents += Math.round(each * got * 100);
        saveDay(m);
        ItemStack bought = new ItemStack(fish, got);
        ItemStack left = f.insertItem(bought);
        if (!left.isEmpty()) net.minecraft.world.level.block.Block.popResource(level, f.blockPosition(), left);
        f.swing(InteractionHand.MAIN_HAND);
        String what = got + " " + (fish == Items.COD ? "cod" : "salmon") + " at " + money(each);
        if (m.monger != null && level.getEntity(m.monger) instanceof VillageFolkEntity seller && seller.distanceToSqr(f) < 64) {
            FolkTalk.speak(seller, FolkTalk.pick(f.getRandom(), "There you are — " + what + ". Enjoy it!", "Mind the bones! Thank you kindly.",
                "Best fish in " + Villages.name(v.id()) + ", that."));
        }
        dress(level, v, m);
        return what;
    }

    /** Tests: this folk buys at the counter now, as if it had walked down to it. */
    @Nullable
    public static String serveForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        return serve(level, v, f);
    }

    // ------------------------------------------------------------------ players at the counter

    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        if (!level.getBlockState(pos).is(Blocks.BARREL)) return;
        Mart m = null;
        for (Mart x : MARTS.values()) {
            if (x.stall != null && x.stall.barrels().contains(pos)) { m = x; break; }
        }
        if (m == null) return;
        // The market's barrels are its stock, not a chest to rummage in.
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getHand() != InteractionHand.MAIN_HAND) return;
        Villages.Village v = Villages.get(m.village);
        if (v == null) return;
        e.getEntity().displayClientMessage(Component.literal(playerBuys(level, v, e.getEntity())), true);
    }

    /** A player at the counter: a lot of four of the fish of the day for coin from their pack. Sneaking, the price. */
    public static String playerBuys(ServerLevel level, Villages.Village v, Player p) {
        UUID id = v.id();
        Mart m = mart(id);
        roll(level, m);
        if (Standing.of(id, p.getUUID(), level.getGameTime()).title() == Standing.Title.OUTCAST) return "Nobody here will sell to you.";
        if (Laws.banished(id, p.getUUID(), level.getDayTime() / 24000L)) return "You're banished from " + Villages.name(id) + ". Nobody will serve you.";
        if (!m.open) {
            return m.closed ? "The fish market's shut for the day. The boats go out again at dawn."
                : "The fish market opens when the boats come in" + (Fleet.today(level, id).contains("out at sea") ? " — they're out at sea now." : ".");
        }
        Item fish = fishOfTheDay(level, m);
        if (fish == null) return "All sold! Back tomorrow with the boats.";
        int lot = Math.min(LOT, inBarrels(level, m, s -> s.is(fish)));
        ItemStack one = new ItemStack(fish);
        double each = priceEach(level, v, one, null);
        int price = Math.max(1, (int) Math.round(each * lot));
        String name = lot + " " + (fish == Items.COD ? "cod" : "salmon");
        if (p.isShiftKeyDown()) return name + ": " + price + (price == 1 ? " coin" : " coins") + " (" + money(each) + " apiece). Right-click to buy.";
        int coins = Market.coinsHeld(p);
        if (coins < price) return name + " for " + price + (price == 1 ? " coin" : " coins") + ". You have " + coins + ".";
        int got = takeFromBarrels(level, m, fish, lot);
        if (got <= 0) return "All sold!";
        Market.payOut(p, price);
        Ledger.addCoins(id, price);
        Economy.sold(id, price);
        PriceIndex.bought(id, one, got);
        m.sold += got;
        m.cents += price * 100L;
        saveDay(m);
        ItemStack bought = new ItemStack(fish, got);
        if (!p.getInventory().add(bought)) p.drop(bought, false);
        level.playSound(null, p.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.8F, 1.0F);
        if (m.monger != null && level.getEntity(m.monger) instanceof VillageFolkEntity seller) {
            FolkTalk.speak(seller, FolkTalk.pick(seller.getRandom(), "Thank you! Fry it with a bit of butter.", "Come again tomorrow!",
                "Caught this very morning, that."));
        }
        dress(level, v, m);
        return "Bought " + got + " " + (fish == Items.COD ? "cod" : "salmon") + " for " + price + (price == 1 ? " coin." : " coins.");
    }

    // ------------------------------------------------------------------ what it says

    /** The board's line: open, with the catch and the prices; or shut. */
    @Nullable
    public static String boardLine(ServerLevel level, UUID village) {
        if (!Fleet.has(village)) return null;
        Mart m = mart(village);
        roll(level, m);
        Villages.Village v = Villages.get(village);
        if (m.stall == null || v == null) return null;
        if (m.open) {
            double g = glut(m);
            return "Fish market on the quay: open, " + m.landed + " landed today — cod " + money(priceEach(level, v, new ItemStack(Items.COD), null))
                + ", salmon " + money(priceEach(level, v, new ItemStack(Items.SALMON), null))
                + (g <= 0.7 ? " (a big catch: cheap)" : g >= 1.15 ? " (a poor catch: dear)" : "") + ".";
        }
        if (m.closed) return "Fish market: sold " + m.sold + " of " + m.landed + " today" + (m.smoked > 0 ? ", " + m.smoked + " smoked" : "") + ".";
        return "Fish market: shut till the boats come in.";
    }

    /** The Prices page's line about the fish market. */
    public static String pricesLine(ServerLevel level, UUID village) {
        if (!Fleet.has(village)) return "";
        Mart m = mart(village);
        roll(level, m);
        Villages.Village v = Villages.get(village);
        if (m.stall == null || v == null) return "";
        double g = glut(m);
        String cod = money(priceEach(level, v, new ItemStack(Items.COD), null)), salmon = money(priceEach(level, v, new ItemStack(Items.SALMON), null));
        if (m.landed <= 0) return "The fish market on the quay: no catch landed yet today; cod would be " + cod + ", salmon " + salmon;
        return "The fish market on the quay: " + m.landed + " fish landed today (" + Math.round(g * 100) + "% of the town's price by the catch): cod "
            + cod + ", salmon " + salmon + "; " + m.sold + " sold" + (m.open ? ", open" : ", shut");
    }

    /** The gazette's line about the market, for a day. */
    @Nullable
    static String gazette(UUID village, long day) {
        String s = Ledger.note(village, "fleet.market.log");
        if (s == null || s.isEmpty()) return null;
        for (String line : s.split("\n")) {
            String[] p = line.split("\\|");
            if (p.length < 6 || !p[0].equals(Long.toString(day))) continue;
            return "The fish market sold " + p[2] + " of " + p[1] + " fish for " + money(Long.parseLong(p[3]) / 100.0)
                + (Integer.parseInt(p[5]) > 0 ? "; the cook smoked " + p[5] : "") + ".";
        }
        return null;
    }

    /** For the town's books. */
    static CompoundTag report(ServerLevel level, UUID village) {
        CompoundTag out = new CompoundTag();
        Mart m = mart(village);
        roll(level, m);
        Villages.Village v = Villages.get(village);
        out.putBoolean("stall", m.stall != null);
        out.putBoolean("open", m.open);
        out.putBoolean("closed", m.closed);
        out.putInt("landed", m.landed);
        out.putInt("sold", m.sold);
        out.putInt("smoked", m.smoked);
        out.putInt("stored", m.stored);
        out.putInt("takings100", (int) m.cents);
        out.putInt("glut100", (int) Math.round(glut(m) * 100));
        if (v != null && m.stall != null) {
            out.putInt("cod100", (int) Math.round(priceEach(level, v, new ItemStack(Items.COD), null) * 100));
            out.putInt("salmon100", (int) Math.round(priceEach(level, v, new ItemStack(Items.SALMON), null) * 100));
            out.putInt("inBarrels", inBarrels(level, m, SOLD));
            if (m.monger != null && level.getEntity(m.monger) instanceof VillageFolkEntity f) out.putString("seller", f.displayNameCap());
        }
        ListTag days = new ListTag();
        String s = Ledger.note(village, "fleet.market.log");
        if (s != null && !s.isEmpty()) {
            String[] lines = s.split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String[] p = lines[i].split("\\|");
                if (p.length < 6) continue;
                try {
                    days.add(StringTag.valueOf("Day " + (Long.parseLong(p[0]) + 1) + ": " + p[1] + " landed, " + p[2] + " sold for "
                        + money(Long.parseLong(p[3]) / 100.0) + (Integer.parseInt(p[5]) > 0 ? ", " + p[5] + " smoked" : "")));
                } catch (NumberFormatException ignored) {
                    // an unreadable day: left out
                }
            }
        }
        out.put("days", days);
        return out;
    }

    /** /village fleet's lines about the market. */
    static List<String> status(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        Mart m = mart(village);
        roll(level, m);
        if (m.stall == null) {
            out.add("No fish market yet: it goes up on the bank by the quay when the stores run to three barrels, two fences, a sign and the planks.");
            return out;
        }
        out.add("Fish market at " + m.stall.stand().toShortString() + ": " + (m.open ? "open" : m.closed ? "shut for the day" : "waiting for the boats")
            + "; landed " + m.landed + ", sold " + m.sold + " for " + money(m.cents / 100.0) + ", in the barrels " + inBarrels(level, m, SOLD)
            + (m.smoked > 0 ? ", smoked " + m.smoked : "") + "; the catch makes it " + Math.round(glut(m) * 100) + "% of the town's price");
        String line = pricesLine(level, village);
        if (!line.isEmpty()) out.add(line);
        return out;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the stall put up by this quay now, for nothing. */
    public static boolean buildForTests(ServerLevel level, Villages.Village v, Waterfront.Dock q) {
        Mart m = mart(v.id());
        Stall s = site(level, q);
        if (s == null || !build(level, v, s, true)) return false;
        m.stall = s;
        keepStall(v.id(), s);
        return true;
    }

    /** Tests: the stall, or null. */
    @Nullable
    public static Stall stallForTests(UUID village) {
        return mart(village).stall;
    }

    /** Tests: the cod and salmon in the market's barrels. */
    public static int inBarrelsForTests(ServerLevel level, UUID village) {
        return inBarrels(level, mart(village), SOLD);
    }

    /** Tests: so many fish landed now, out of thin air — no: out of this folk's pack, as a boat would land them. */
    public static int landForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        return land(level, v, f, Map.of());
    }

    /** Tests: the day's figures, "landed sold open closed boats". */
    public static String dayForTests(ServerLevel level, UUID village) {
        Mart m = mart(village);
        roll(level, m);
        return m.landed + " " + m.sold + " " + m.open + " " + m.closed + " " + m.boats + " glut " + String.format(Locale.ROOT, "%.2f", glut(m));
    }

    /** Tests: the market shut now, and the unsold catch put away by this cook (or none). */
    public static void shutForTests(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity cook) {
        Mart m = mart(v.id());
        if (m.open) shut(level, v, m);
        putAway(level, v, m, cook);
    }

    /** Tests: a new day at the market (the figures cleared). */
    public static void newDayForTests(Villages.Village v, long day, int boats) {
        Mart m = mart(v.id());
        m.day = day;
        m.landed = m.sold = m.stored = m.smoked = 0;
        m.cents = 0;
        m.open = m.closing = m.closed = false;
        m.served.clear();
        m.boats = boats;
        saveDay(m);
    }

    // ------------------------------------------------------------------ the stage

    /**
     * The pictures' stage (/village fleet stage): a bay cut out of the ground twenty blocks along from where it is run
     * (east), two deep, forty by thirty; a quay run out into it from its west bank, the fish market on the bank by it,
     * the fleet's boats put in (for nothing, a stage's), and the town's fishers sent out in them now. Returns the views
     * ("VIEW name x y z lookx looky lookz").
     */
    static List<String> stage(ServerLevel level, BlockPos at) {
        List<String> out = new ArrayList<>();
        Villages.Village v = Villages.nearest(level, at, Villages.VILLAGE_RANGE * 4);
        if (v == null) {
            out.add("no village");
            return out;
        }
        int x0 = at.getX() + 20, z0 = at.getZ() - 15;
        int g = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0, at.getZ());
        int y = g - 1;
        for (int x = x0; x < x0 + 40; x++) {
            for (int z = z0; z < z0 + 30; z++) {
                for (int dy = 1; dy <= 6; dy++) level.setBlock(new BlockPos(x, y + dy, z), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, y, z), Blocks.WATER.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, y - 1, z), Blocks.WATER.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, y - 2, z), Blocks.SAND.defaultBlockState(), 2);
            }
        }
        // The west bank: level ground for the stall, a strip eight wide.
        for (int x = x0 - 8; x < x0; x++) {
            for (int z = z0; z < z0 + 30; z++) {
                level.setBlock(new BlockPos(x, y, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int dy = 1; dy <= 5; dy++) level.setBlock(new BlockPos(x, y + dy, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        Waterfront.Dock d = new Waterfront.Dock(new BlockPos(x0, y, z0 + 15), Direction.EAST, 6);
        Waterfront.build(level, d);
        Fleet.quayForTests(v, d);
        Mart m = mart(v.id());
        Stall s = site(level, d);
        if (s != null && build(level, v, s, true)) {
            m.stall = s;
            keepStall(v.id(), s);
            smoker(level, v, s, true);
        }
        int boats = Fleet.boatsForTests(level, v);
        String sailed = Fleet.sailForTests(level, v);
        out.add("FLEET stage: quay at " + d.start().toShortString() + ", stall " + (s == null ? "none" : s.stand().toShortString()) + ", "
            + boats + " boats; " + sailed);
        BlockPos mid = new BlockPos(x0 + 20, y, z0 + 15);
        out.add("VIEW fleet-1-bay " + (x0 - 6) + " " + (y + 12) + " " + (z0 + 2) + " " + mid.getX() + " " + y + " " + mid.getZ());
        if (s != null) {
            BlockPos c = s.customer();
            out.add("VIEW fleet-2-market " + (c.getX() - 4) + " " + (c.getY() + 3) + " " + (c.getZ() + (s.along() == Direction.NORTH ? 4 : -4))
                + " " + s.counter().getX() + " " + s.counter().getY() + " " + s.counter().getZ());
        }
        out.add("VIEW fleet-3-quay " + (x0 + 2) + " " + (y + 5) + " " + (z0 + 21) + " " + (x0 + 6) + " " + (y + 1) + " " + (z0 + 15));
        return out;
    }
}
