package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Luxury goods: made, bought, and used.
 * <ul>
 * <li><b>Made of real things by the makers.</b> Rugs of the rancher's wool, by the tailor, who dyes the wool
 *     with the meadow's flowers for a household that wants its own colour, and weaves banners of it; glass
 *     of the river's sand by the smelter, cut into panes at the shop's bench; candles of the beekeeper's
 *     comb and the tailor's string; flower pots of fired clay; paintings of sticks and wool; lanterns of the
 *     smith's nuggets; bookshelves of planks and the tailor's books. What a house waits on the stores for
 *     (Decor: the smith's anvil, a blue rug) the makers see to between their own work (craft).</li>
 * <li><b>Sold at the shop.</b> They stand on the shop's shelves (Stockroom keeps them as it keeps all its
 *     wares, by what sells) at the price list's prices, to players at the counters and to the folk.</li>
 * <li><b>Bought with the folk's own savings.</b> A comfortable folk, every second day at most, walks to the
 *     shop for something its home lacks and it can afford — a carpet (in its colour if there is one), a
 *     painting, glass for an open window, a candle, a pot and a flower for it — and a well-off one for a
 *     lantern, a banner in its colour or a bookshelf. It pays at the counter the way every sale is paid
 *     (Cafe.folkShops: its purse, the shop's books, the treasury), so a home fills up as its owner does
 *     well: two things for a comfortable folk, four for a well-off one, seven for the wealthy.</li>
 * <li><b>Carried home and used.</b> It carries the thing home and sets it where it belongs: a carpet on the
 *     floor by its bed, a painting on the wall, the panes in the window, a candle on a table or a sill, the
 *     pot with its flower on the sill. Its candles are lit at dusk and snuffed when the last of the
 *     household goes to bed (candles). A well-furnished home cheers its folk and is worth more (Decor).</li>
 * </ul>
 */
public final class Luxuries {

    private Luxuries() {}

    // ------------------------------------------------------------------ what counts

    /** A luxury for a home: its name, the standing a folk wants before it buys one, how many a house takes, and what counts. */
    public enum Kind {
        RUG("rug", "a carpet", Wealth.Tier.COMFORTABLE, 2, s -> s.is(ItemTags.WOOL_CARPETS)),
        PAINTING("painting", "a painting", Wealth.Tier.COMFORTABLE, 2, s -> s.is(Items.PAINTING)),
        PANE("pane", "glass for the windows", Wealth.Tier.COMFORTABLE, 1, s -> s.is(Items.GLASS_PANE)),
        CANDLE("candle", "a candle", Wealth.Tier.COMFORTABLE, 2, s -> s.is(ItemTags.CANDLES)),
        POT("pot", "a pot of flowers", Wealth.Tier.COMFORTABLE, 2, s -> s.is(Items.FLOWER_POT)),
        LANTERN("lantern", "a lantern", Wealth.Tier.WELL_OFF, 1, s -> s.is(Items.LANTERN)),
        BANNER("banner", "a banner", Wealth.Tier.WELL_OFF, 1, s -> s.is(ItemTags.BANNERS)),
        BOOKSHELF("bookshelf", "a bookshelf", Wealth.Tier.WELL_OFF, 1, s -> s.is(Items.BOOKSHELF));

        public final String key;
        public final String words;
        public final Wealth.Tier from;
        /** How many a house takes (panes: as many as it has windows standing open, four at a go). */
        public final int most;
        public final Predicate<ItemStack> what;

        Kind(String key, String words, Wealth.Tier from, int most, Predicate<ItemStack> what) {
            this.key = key;
            this.words = words;
            this.from = from;
            this.most = most;
            this.what = what;
        }
    }

    /** Which luxury this is, or null. */
    @Nullable
    public static Kind kindOf(ItemStack s) {
        if (s.isEmpty()) return null;
        for (Kind k : Kind.values()) if (k.what.test(s)) return k;
        return null;
    }

    /** A luxury, or what one is made of on its last step (glass, for panes). */
    public static boolean isLuxury(ItemStack s) {
        return kindOf(s) != null;
    }

    // ------------------------------------------------------------------ the folk's side

    /** A folk's errand for its home: when it set off, the day it gave up on a thing it could not find room for, the day it was disappointed. */
    private static final class Errand {
        int setOff = -1;
        int lastLook = -1000;
        int lastWalk = -1000;
        long gaveUp = -1;
        long missed = -1;
        /** Where the thing it is carrying home is to go (looked at again when it gets there). */
        @Nullable Decor.Spot spot;
        /** What it is off to the shop for. */
        @Nullable Kind want;
    }

    private static final Map<UUID, Errand> ERRANDS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        ERRANDS.clear();
        LIT.clear();
    }

    /** Something of its own it is carrying home: the stack (in its pack, or in its house's chest), and where. */
    private record Carried(ItemStack stack, Kind kind, @Nullable Container chest) {}

    /**
     * Its savings, spent on its home (VillageFolkEntity.homeComfort, off work): carrying a luxury home and
     * setting it out, or off to the shop for one. True while it is about it.
     */
    public static boolean forHome(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby()) return false;
        Villages.Village v = Villages.get(village);
        Homes.Home h = v == null ? null : Homes.homeOf(village, f.getUUID());
        Ledger.Building b = h == null ? null : Homes.building(village, h.anchor);
        if (b == null || !level.isLoaded(h.anchor)) return false;
        long day = level.getDayTime() / 24000L;
        Errand e = ERRANDS.computeIfAbsent(f.getUUID(), k -> new Errand());
        // Looked at once a second at most, unless it is about it already.
        if (e.setOff < 0 && f.tickCount - e.lastLook < 20 && f.tickCount >= e.lastLook) return false;
        e.lastLook = f.tickCount;
        BlockPos near = f.bedPos() != null ? f.bedPos() : h.anchor;
        // Carrying one home: home, and set it out.
        Carried c = e.gaveUp == day ? null : carried(level, village, h, f);
        if (c != null) {
            if (e.setOff < 0 || e.spot == null) e.spot = spotFor(level, village, h, b, c.kind(), near);
            if (e.spot == null) {
                e.gaveUp = day;                                      // no room for it today: it keeps it by
                e.setOff = -1;
                return false;
            }
            if (e.setOff < 0) e.setOff = f.tickCount;
            if (f.tickCount - e.setOff > 2400) {
                e.gaveUp = day;
                e.setOff = -1;
                return false;
            }
            if (f.blockPosition().distSqr(e.spot.at()) > 3.0 * 3.0) {
                if (f.getNavigation().isDone() || f.tickCount - e.lastWalk >= 100) {
                    f.walkTo(e.spot.at(), 0.9D);
                    e.lastWalk = f.tickCount;
                }
                f.hobbyNow = "carrying " + c.kind().words + " home";
                return true;
            }
            e.setOff = -1;
            f.getNavigation().stop();
            Decor.Spot spot = spotFor(level, village, h, b, c.kind(), near);      // as it stands now it is here
            if (spot == null || !setOut(level, v, h, b, f, c, spot)) e.gaveUp = day;
            e.spot = null;
            return true;
        }
        // Off to the shop for something for its home.
        BlockPos shop = Villages.builtAt(village, "shop");
        if (shop == null || !Cafe.open(village, "shop")) {
            e.want = null;
            e.setOff = -1;
            return false;
        }
        if (e.want == null) {
            // [econ-prices] Every other day at most; every day while the shop's luxuries are cheap (PriceIndex).
            if (day - f.comfortDay() < (PriceIndex.luxuriesCheap(village) ? 1 : 2)) return false;
            Wealth.Tier tier = Wealth.tier(f);
            if (tier.ordinal() < Wealth.Tier.COMFORTABLE.ordinal() || f.comforts() >= tier.comforts) return false;
            e.want = choose(level, v, h, b, f, tier, day, e);
            if (e.want == null) {
                f.comfortDay(day);
                return false;
            }
        }
        Kind want = e.want;
        if (f.blockPosition().distSqr(shop) > 16.0) {
            if (e.setOff < 0) e.setOff = f.tickCount;
            if (f.tickCount - e.setOff > 1800) {                    // could not get in
                f.comfortDay(day);
                e.setOff = -1;
                e.want = null;
                return false;
            }
            if (f.getNavigation().isDone() || f.tickCount - e.lastWalk >= 100) {
                f.walkTo(shop, 0.9D);
                e.lastWalk = f.tickCount;
            }
            f.hobbyNow = "off to the shop for " + want.words;
            return true;
        }
        e.setOff = -1;
        e.want = null;
        f.comfortDay(day);
        String bought = buy(level, v, f, h, b, want);
        if (bought != null) {
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I've been saving for " + want.words + ".", "Treating my home: " + bought + "!",
                cap(bought) + ". Money well spent."));
            f.brain("bought " + bought + " at the shop for its home");
        }
        return true;
    }

    /** What it is carrying home of its own (or put away in its house's chest), that is a luxury; null if nothing. */
    @Nullable
    private static Carried carried(ServerLevel level, UUID village, Homes.Home h, VillageFolkEntity f) {
        for (ItemStack s : f.getInventoryItems()) {
            Kind k = Homes.isKeepsake(s) && Homes.ownedBy(s, f) ? kindOf(s) : null;
            if (k != null) return new Carried(s, k, null);
        }
        Ledger.Building b = Homes.building(village, h.anchor);
        if (b == null) return null;
        for (BlockPos chest : Decor.room(village, b).chests()) {
            if (!level.isLoaded(chest) || !(level.getBlockEntity(chest) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                Kind k = Homes.isKeepsake(s) && Homes.ownedBy(s, f) ? kindOf(s) : null;
                if (k != null) return new Carried(s, k, c);
            }
        }
        return null;
    }

    /**
     * What its home lacks that it can afford and the shop has, and that there is room for: the things the
     * house has none of first, then the grander. Null if nothing will do today. A first wish the shop has
     * not got goes down in its books as a sale it missed (Stockroom), so it keeps more of it.
     */
    @Nullable
    private static Kind choose(ServerLevel level, Villages.Village v, Homes.Home h, Ledger.Building b, VillageFolkEntity f,
                               Wealth.Tier tier, long day, Errand e) {
        Map<String, Long> book = Decor.book(v.id(), h.anchor);
        book.entrySet().removeIf(x -> !Decor.present(level, x.getKey(), BlockPos.of(x.getValue())));
        BlockPos near = f.bedPos() != null ? f.bedPos() : h.anchor;
        Kind[] all = Kind.values();
        int start = Math.floorMod(f.getUUID().hashCode() + (int) day, all.length);
        Kind best = null;
        int bestRank = Integer.MIN_VALUE;
        boolean first = true;
        for (int i = 0; i < all.length; i++) {
            Kind k = all[(start + i) % all.length];
            if (tier.ordinal() < k.from.ordinal()) continue;
            int have = 0;
            for (String key : book.keySet()) if (key.startsWith("lux." + k.key + ".")) have++;
            if (k != Kind.PANE && have >= k.most) continue;                   // panes: as long as a window stands open
            if (spotFor(level, v.id(), h, b, k, near) == null) continue;
            Predicate<ItemStack> what = forSale(level, v.id(), f, k);
            if (what == null) {
                if (first && e.missed != day) {
                    e.missed = day;
                    Stockroom.missed(level, v.id(), Stockroom.Seller.SHOP, sampleOf(k, f));
                }
                first = false;
                continue;
            }
            first = false;
            ItemStack sample = sample(level, v.id(), what);
            int price = priceOf(level, v.id(), sample) + (k == Kind.POT ? 2 : 0);
            if (f.purse() < price + 3) continue;
            int rank = (have == 0 ? 100 : 0) + price;
            if (rank > bestRank) {
                bestRank = rank;
                best = k;
            }
        }
        return best;
    }

    /** What at the shop will do: a carpet or a banner in its own colour if the shop has one, else any; null if the shop has none. */
    @Nullable
    private static Predicate<ItemStack> forSale(ServerLevel level, UUID village, VillageFolkEntity f, Kind k) {
        Predicate<ItemStack> what = k.what;
        if (k == Kind.RUG || k == Kind.BANNER) {
            Item own = Decor.coloured(Decor.colour(f), k == Kind.RUG ? "_carpet" : "_banner");
            if (Market.stock(level, village, s -> s.is(own)) > 0) what = s -> s.is(own);
        }
        if (k == Kind.POT && flowerIn(f) == null && Market.stock(level, village, Luxuries::pottable) <= 0) return null;
        return Market.stock(level, village, what) > 0 ? what : null;
    }

    private static boolean pottable(ItemStack s) {
        return s.is(ItemTags.SMALL_FLOWERS) && Interiors.potted(s.getItem()) != null;
    }

    /** One of a luxury it would have, to name in the shop's books. */
    private static ItemStack sampleOf(Kind k, VillageFolkEntity f) {
        return switch (k) {
            case RUG -> new ItemStack(Decor.coloured(Decor.colour(f), "_carpet"));
            case PAINTING -> new ItemStack(Items.PAINTING);
            case PANE -> new ItemStack(Items.GLASS_PANE);
            case CANDLE -> new ItemStack(Items.CANDLE);
            case POT -> new ItemStack(Items.FLOWER_POT);
            case LANTERN -> new ItemStack(Items.LANTERN);
            case BANNER -> new ItemStack(Decor.coloured(Decor.colour(f), "_banner"));
            case BOOKSHELF -> new ItemStack(Items.BOOKSHELF);
        };
    }

    private static ItemStack sample(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && what.test(s)) return s.copyWithCount(1);
            }
        }
        return ItemStack.EMPTY;
    }

    /** What the shop asks a folk for one of these, in whole coins: the town's price for the one thing, as Cafe.folkShops
     *  charges it. [econ-prices] It was a whole lot's price (four rugs' for one rug). */
    static int priceOf(ServerLevel level, UUID village, ItemStack sample) {
        if (sample.isEmpty()) return 3;
        return Math.max(1, (int) Math.ceil(Purchases.priceEach(level, village, sample, null) - 1e-6));
    }

    /**
     * At the counter: the luxury bought the way every sale at the shop is made (Cafe.folkShops: out of its
     * purse, into the shop's books and the treasury), and a flower for a pot, and a pane for every open
     * window. Returns what it bought, in words, or null.
     */
    @Nullable
    static String buy(ServerLevel level, Villages.Village v, VillageFolkEntity f, Homes.Home h, Ledger.Building b, Kind kind) {
        Predicate<ItemStack> what = forSale(level, v.id(), f, kind);
        if (what == null) return null;
        int before = f.purse();
        long spent = Purchases.spentToday(f);                  // [econ-prices] to the hundredth: the change stays at the counter
        if (Cafe.folkShops(level, v, f, false, what) == null) return null;
        int n = 1;
        if (kind == Kind.POT && flowerIn(f) == null) Cafe.folkShops(level, v, f, false, Luxuries::pottable);
        if (kind == Kind.PANE) {
            int open = Decor.openWindows(level, Decor.room(v.id(), b)).size();
            for (int i = 1; i < Math.min(open, 4); i++) {
                if (Cafe.folkShops(level, v, f, false, what) == null) break;
                n++;
            }
        }
        int paid = Math.max(0, before - f.purse());
        booked(level, v.id(), n, paid);
        f.persona().remember(level.getDayTime() / 24000L, "I bought " + kind.words + " for my home with my own savings", 2);
        String words = kind == Kind.PANE ? n + (n == 1 ? " pane" : " panes") + " of glass for the windows" : kind.words;
        return words + " for " + String.format(java.util.Locale.ROOT, "%.2f coins", (Purchases.spentToday(f) - spent) / 100.0);
    }

    /** A flower it has with it, for a pot. */
    @Nullable
    private static ItemStack flowerIn(VillageFolkEntity f) {
        for (ItemStack s : f.getInventoryItems()) if (pottable(s)) return s;
        return null;
    }

    // ------------------------------------------------------------------ where it goes, and setting it out

    /** Where a luxury goes in this house now, or null if there is no room for it. */
    @Nullable
    static Decor.Spot spotFor(ServerLevel level, UUID village, Homes.Home h, Ledger.Building b, Kind kind, BlockPos near) {
        Decor.Room room = Decor.room(village, b);
        Set<BlockPos> taken = Decor.reserved(level, village, b);
        for (Long p : Decor.book(village, h.anchor).values()) taken.add(BlockPos.of(p));
        return switch (kind) {
            case RUG -> Decor.rugSpot(level, room, near, taken);
            case PAINTING -> {
                for (Decor.Spot s : Decor.wallSpots(level, room, near, 1, taken)) {
                    if (Painting.create(level, s.at(), s.facing()).isPresent()) yield s;
                }
                yield null;
            }
            case PANE -> {
                List<BlockPos> open = Decor.openWindows(level, room);
                yield open.isEmpty() ? null : new Decor.Spot(open.get(0), Direction.NORTH);
            }
            case CANDLE, POT, LANTERN -> {
                Decor.Spot top = Decor.topSpot(level, room, near, taken);
                yield top != null ? top : Decor.floorSpot(level, room, near, taken);
            }
            case BANNER -> {
                List<Decor.Spot> high = Decor.wallSpots(level, room, near, 2, taken);
                if (!high.isEmpty()) yield high.get(0);
                List<Decor.Spot> low = Decor.wallSpots(level, room, near, 1, taken);
                yield low.isEmpty() ? null : low.get(0);
            }
            case BOOKSHELF -> Decor.floorSpot(level, room, near, taken);
        };
    }

    /** The luxury set out where it goes, written in the house's book; one more comfort of its own. */
    private static boolean setOut(ServerLevel level, Villages.Village v, Homes.Home h, Ledger.Building b, VillageFolkEntity f,
                                  Carried c, Decor.Spot spot) {
        UUID village = v.id();
        Map<String, Long> book = Decor.book(village, h.anchor);
        List<BlockPos> placed = new ArrayList<>();
        ItemStack one = c.stack().copyWithCount(1);
        switch (c.kind()) {
            case PANE -> {
                Decor.Room room = Decor.room(village, b);
                for (BlockPos w : Decor.openWindows(level, room)) {
                    if (c.stack().isEmpty()) break;
                    BlockState pane = Block.updateFromNeighbourShapes(Blocks.GLASS_PANE.defaultBlockState(), level, w);
                    level.setBlock(w, pane, 3);
                    c.stack().shrink(1);
                    placed.add(w);
                }
                if (!placed.isEmpty()) level.playSound(null, placed.get(0), SoundEvents.GLASS_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
            }
            case PAINTING -> {
                Optional<Painting> p = Painting.create(level, spot.at(), spot.facing());
                if (p.isEmpty()) return false;
                level.addFreshEntity(p.get());
                p.get().playPlacementSound();
                c.stack().shrink(1);
                placed.add(spot.at());
            }
            case BANNER -> {
                if (!Decor.hangBanner(level, spot.at(), spot.facing(), one)) return false;
                c.stack().shrink(1);
                placed.add(spot.at());
            }
            case POT -> {
                ItemStack flower = flowerIn(f);
                Block potted = flower == null ? null : Interiors.potted(flower.getItem());
                BlockState st = potted != null ? potted.defaultBlockState() : Blocks.FLOWER_POT.defaultBlockState();
                if (!st.canSurvive(level, spot.at())) return false;
                level.setBlock(spot.at(), st, 3);
                if (potted != null) flower.shrink(1);
                level.playSound(null, spot.at(), st.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8F, 1.0F);
                c.stack().shrink(1);
                placed.add(spot.at());
            }
            default -> {
                Block block = Block.byItem(one.getItem());
                if (block == Blocks.AIR) return false;
                BlockState st = Decor.facing(block.defaultBlockState(), spot.facing());
                if (!st.canSurvive(level, spot.at())) return false;
                level.setBlock(spot.at(), st, 3);
                level.playSound(null, spot.at(), st.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8F, 1.0F);
                c.stack().shrink(1);
                placed.add(spot.at());
            }
        }
        if (placed.isEmpty()) return false;
        if (c.chest() != null) c.chest().setChanged();
        for (BlockPos p : placed) {
            int n = 0;
            while (book.containsKey("lux." + c.kind().key + "." + n)) n++;
            book.put("lux." + c.kind().key + "." + n, p.asLong());
        }
        Decor.save(village, h.anchor, book);
        Decor.remember(level, village, h, b, book);
        f.addComfort();
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        f.getLookControl().setLookAt(spot.at().getX() + 0.5, spot.at().getY() + 0.5, spot.at().getZ() + 0.5);
        long day = level.getDayTime() / 24000L;
        f.persona().remember(day, "I set out " + c.kind().words + " in my home", 2);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There. That's more like home.", "Lovely. Worth every coin.",
            "Now that's a home to be proud of."));
        return true;
    }

    // ------------------------------------------------------------------ candles at dusk

    /** When a house's candles are lit: a little after sunset, each house its own minute. */
    static final long DUSK = 12000L;

    /** What each house's candles were last set to, so they are only touched when that changes (by anchor). */
    private static final Map<Long, Boolean> LIT = new ConcurrentHashMap<>();

    /**
     * The households' candles (their own, and the age's on the shelf: Interiors), lit at dusk while anybody
     * of the household is up, and snuffed when the last of them goes to bed. Every few seconds (Grow.tick).
     */
    public static void candles(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long t = level.getDayTime() % 24000L;
        for (Homes.Home h : Homes.homes(id).values()) {
            if (!level.isLoaded(h.anchor)) continue;
            List<VillageFolkEntity> household = h.members.isEmpty() ? List.of() : Homes.loadedMembers(id, h);
            long bed = -1;
            for (VillageFolkEntity f : household) if (!f.isBaby()) bed = Math.max(bed, f.bedtimeTick());
            long dusk = DUSK + Math.floorMod(h.anchor.hashCode(), 400);
            boolean on = bed > 0 && t >= dusk && t < bed;
            Boolean was = LIT.get(h.anchor.asLong());
            if (was != null && was == on && t % 1200L > 300L) continue;   // as it was (looked at afresh now and then)
            int changed = 0;
            BlockPos first = null;
            for (BlockPos p : candlesIn(level, id, h)) {
                BlockState s = level.getBlockState(p);
                if (!(s.getBlock() instanceof AbstractCandleBlock) || !s.hasProperty(AbstractCandleBlock.LIT)) continue;
                if (s.getValue(AbstractCandleBlock.LIT) == on) continue;
                if (on && s.hasProperty(CandleBlock.WATERLOGGED) && s.getValue(CandleBlock.WATERLOGGED)) continue;
                level.setBlock(p, s.setValue(AbstractCandleBlock.LIT, on), 3);
                if (first == null) first = p;
                changed++;
            }
            LIT.put(h.anchor.asLong(), on);
            if (first == null) continue;
            level.playSound(null, first, on ? SoundEvents.FLINTANDSTEEL_USE : SoundEvents.CANDLE_EXTINGUISH, SoundSource.BLOCKS, 0.6F, 1.0F);
            if (on) {
                for (VillageFolkEntity f : household) {
                    if (f.isBaby() || f.distanceToSqr(first.getX() + 0.5, first.getY(), first.getZ() + 0.5) > 64.0) continue;
                    f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    if (f.getRandom().nextInt(3) == 0) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There — candles lit. That's cosy.",
                        "Let's have a bit of light in here.", "I do like a candle of an evening."));
                    break;
                }
            }
        }
    }

    /** The candles in a house: the household's own (its book), and the ones the age set on its shelf. */
    static List<BlockPos> candlesIn(ServerLevel level, UUID village, Homes.Home h) {
        List<BlockPos> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : Decor.book(village, h.anchor).entrySet()) {
            if (e.getKey().startsWith("lux.candle.")) out.add(BlockPos.of(e.getValue()));
        }
        Ledger.Building b = Homes.building(village, h.anchor);
        int tier = Interiors.tier(Villages.ageOf(village));
        if (b != null && tier >= 3) {
            for (Interiors.Piece p : Interiors.plan(level, village, b, tier)) if (p.kind() == Interiors.Kind.CANDLE) out.add(p.pos());
        }
        return out;
    }

    // ------------------------------------------------------------------ the makers

    /**
     * A maker's turn at what the village's houses wait on the stores for (Decor's wants), each by the trade
     * whose work it is: the tailor dyes wool with flowers for a household's colour and weaves its rug and
     * banner (and a loom); the smith beats out an anvil, a lantern, a compass; the cook builds a smoker;
     * the beekeeper dips candles; the enchanter makes up bookshelves and a lectern; the brewer a stand;
     * the shopkeeper's bench all the rest — by the game's recipes, the whole way from what the stores can
     * spare (Bench). {@code first}: before its own work, a turn in three; else when its own work is done.
     * Returns what it made, or null.
     */
    @Nullable
    public static String craft(ServerLevel level, Villages.Village v, VillageFolkEntity f, boolean first) {
        if (first && Math.floorMod(level.getGameTime() / Crafts.EVERY + f.getUUID().hashCode(), 3L) != 0) return null;
        List<Decor.Want> wants = Decor.wants(v.id());
        if (wants.isEmpty()) return null;
        StationTask t = f.stationTask();
        Bench.Hand hand = null;
        Set<Item> tried = new HashSet<>();
        int start = (int) Math.floorMod(level.getGameTime() / Crafts.EVERY, (long) wants.size());
        for (int i = 0; i < wants.size() && tried.size() < 3; i++) {
            Decor.Want w = wants.get((start + i) % wants.size());
            Item it = w.item();
            if (!makes(t, it) || !tried.add(it)) continue;
            if (Market.stock(level, v.id(), s -> s.is(it)) > 0) continue;          // there already: the house's turn will take it
            if (hand == null) hand = Bench.handOf(level, v, f, VillageFolkEntity.buildingFor(t));
            Bench.Plan p = Bench.plan(level, v, it, 1, hand);
            if (!p.ok()) continue;
            ItemStack out = Bench.make(level, v, p, f, hand);
            if (out.isEmpty()) continue;
            return Bench.words(out.getItem(), out.getCount()) + ", for " + w.forWhat();
        }
        return null;
    }

    /** Whose work a thing is. */
    static boolean makes(StationTask t, Item it) {
        String path = BuiltInRegistries.ITEM.getKey(it).getPath();
        boolean colour = path.endsWith("_carpet") || path.endsWith("_banner");
        return switch (t) {
            case TAILOR -> colour || it == Items.LOOM || it == Items.PAINTING;
            case SMITH -> it == Items.ANVIL || it == Items.LANTERN || it == Items.COMPASS || it == Items.SHEARS;
            case COOK -> it == Items.SMOKER;
            case BEEKEEP -> path.endsWith("candle");
            case ENCHANT -> it == Items.BOOKSHELF || it == Items.LECTERN;
            case BREW -> it == Items.BREWING_STAND;
            case SHOP -> !colour;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ the books

    private static final String BOUGHT = "lux/bought";

    /** So many luxuries bought by the folk for their homes today, for so much coin: "day;n0,...,n6;c0,...,c6", today first. */
    private static void booked(ServerLevel level, UUID village, int n, int coin) {
        long today = level.getDayTime() / 24000L;
        int[][] w = week(village, today);
        w[0][0] += n;
        w[1][0] += coin;
        Ledger.note(village, BOUGHT, today + ";" + csv(w[0]) + ";" + csv(w[1]));
    }

    /** The week's luxuries bought by the folk: {how many, coin, how many today}. */
    public static int[] boughtThisWeek(ServerLevel level, UUID village) {
        int[][] w = week(village, level.getDayTime() / 24000L);
        int n = 0, c = 0;
        for (int i = 0; i < 7; i++) {
            n += w[0][i];
            c += w[1][i];
        }
        return new int[]{ n, c, w[0][0] };
    }

    private static int[][] week(UUID village, long today) {
        int[][] w = new int[2][7];
        String s = Ledger.note(village, BOUGHT);
        if (s == null || s.isEmpty()) return w;
        try {
            String[] p = s.split(";");
            long day = Long.parseLong(p[0]);
            int shift = (int) Math.max(0, Math.min(7, today - day));
            for (int r = 0; r < 2; r++) {
                String[] xs = p[r + 1].split(",");
                for (int i = 0; i < 7 && i < xs.length; i++) if (i + shift < 7) w[r][i + shift] = Integer.parseInt(xs[i].trim());
            }
        } catch (RuntimeException e) {
            return new int[2][7];
        }
        return w;
    }

    private static String csv(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) sb.append(i == 0 ? "" : ",").append(a[i]);
        return sb.toString();
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ tests and the showcase

    /** Tests: at the shop's counter now, buying this for its home. What it bought, or null. */
    @Nullable
    public static String buyForTests(ServerLevel level, VillageFolkEntity f, Kind kind) {
        UUID village = f.ownerId();
        Villages.Village v = Villages.get(village);
        Homes.Home h = v == null ? null : Homes.homeOf(village, f.getUUID());
        Ledger.Building b = h == null ? null : Homes.building(village, h.anchor);
        return b == null ? null : buy(level, v, f, h, b, kind);
    }

    /** Tests: home with what it bought, setting it all out now. How many things it set out. */
    public static int setOutForTests(ServerLevel level, VillageFolkEntity f) {
        UUID village = f.ownerId();
        Villages.Village v = Villages.get(village);
        Homes.Home h = v == null ? null : Homes.homeOf(village, f.getUUID());
        Ledger.Building b = h == null ? null : Homes.building(village, h.anchor);
        if (b == null) return 0;
        int n = 0;
        for (int i = 0; i < 8; i++) {
            Carried c = carried(level, village, h, f);
            if (c == null) break;
            Decor.Spot spot = spotFor(level, village, h, b, c.kind(), f.bedPos() != null ? f.bedPos() : h.anchor);
            if (spot == null || !setOut(level, v, h, b, f, c, spot)) break;
            n++;
        }
        return n;
    }

    /** "glass_pane", "red_carpet": is this a luxury's name in the books (the town's books' filter)? */
    public static boolean luxuryId(String id) {
        String p = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return p.endsWith("_carpet") || p.endsWith("_banner") || p.endsWith("candle") || p.equals("painting") || p.equals("glass_pane")
            || p.equals("flower_pot") || p.equals("lantern") || p.equals("bookshelf") || p.equals("rug") || p.equals("banner")
            || p.toLowerCase(Locale.ROOT).equals("glass");
    }

    /**
     * The showcase (/village decor showcase, for the pictures): a little village raised where it is asked, a
     * smith and a farmer wed and living in a house of the Iron Age, its stores filled with what the house
     * wants (an anvil, the planks for a composter, seed, their colours' rugs and banner, the age's furnishing,
     * the luxuries they bought: paintings, candles, a pot and a flower, a lantern), and the house furnished out
     * of them by the village's own rules. Returns the places to look from ("VIEW name x y z at-x at-y at-z",
     * the feet of the one looking), or why it could not.
     */
    public static List<String> showcase(ServerLevel level, BlockPos at) {
        int gx = at.getX(), gz = at.getZ();
        level.getChunk(gx >> 4, gz >> 4);
        BlockPos heart = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(gx, 0, gz));
        // A level green to stand it on.
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                BlockPos g = heart.offset(dx, -1, dz);
                level.setBlock(g, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int dy = 0; dy < 14; dy++) level.setBlock(g.above(1 + dy), Blocks.AIR.defaultBlockState(), 2);
                for (int dy = 1; dy <= 3; dy++) {
                    BlockState under = level.getBlockState(g.below(dy));
                    if (under.isAir() || !under.getFluidState().isEmpty()) level.setBlock(g.below(dy), Blocks.DIRT.defaultBlockState(), 2);
                }
            }
        }
        VillageFolkEntity smith = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity farmer = smith == null ? null : com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, heart.east(2), 0.0F);
        if (smith == null || farmer == null || smith.ownerId() == null || !smith.ownerId().equals(farmer.ownerId())) {
            return List.of("no village could be raised here");
        }
        UUID id = smith.ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.IRON);
        smith.setJob(StationTask.SMITH);
        farmer.setJob(StationTask.FARM);
        smith.life().partnerWith(farmer.getUUID(), farmer.displayNameCap());
        farmer.life().partnerWith(smith.getUUID(), smith.displayNameCap());
        farmer.earn(150);
        BlockPos house = heart.offset(0, 0, -10);
        Ledger.Building b = new Ledger.Building("house", house, Direction.NORTH);
        com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, "house", house, Direction.NORTH, 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.built(id, "house", house, Direction.NORTH);
        Homes.tickForTests(level, v);
        // The stores: what the house wants, and what its folk bought.
        List<ItemStack> goods = new ArrayList<>(List.of(new ItemStack(Items.ANVIL), new ItemStack(Items.OAK_PLANKS, 64),
            new ItemStack(Items.OAK_PLANKS, 40), new ItemStack(Items.WHEAT_SEEDS, 64), new ItemStack(Items.WHEAT_SEEDS, 24),
            new ItemStack(Items.BARREL, 4), new ItemStack(Items.FLOWER_POT, 4), new ItemStack(Items.POPPY, 6), new ItemStack(Items.BOOKSHELF, 2),
            new ItemStack(Items.LANTERN, 4), new ItemStack(Items.CANDLE, 6), new ItemStack(Items.PAINTING, 3), new ItemStack(Items.WHITE_CARPET, 16),
            new ItemStack(Items.CRAFTING_TABLE)));
        for (VillageFolkEntity f : List.of(smith, farmer)) {
            DyeColor c = Decor.colour(f);
            goods.add(new ItemStack(Decor.coloured(c, "_carpet"), 3));
            goods.add(new ItemStack(Decor.coloured(c, "_banner")));
        }
        for (ItemStack s : goods) Crafts.store(level, v, s);
        TownJobs.instantForTests(true);
        try {
            for (int i = 0; i < 4; i++) Interiors.work(level, v, 60);
        } finally {
            TownJobs.instantForTests(false);
        }
        Decor.workNow(level, v, 60);
        // What they bought for their home, carried in and set out.
        Item[] bought = { Items.PAINTING, Items.PAINTING, Items.CANDLE, Items.CANDLE, Items.FLOWER_POT, Items.POPPY, Items.LANTERN,
            Decor.coloured(Decor.colour(farmer), "_carpet") };
        for (Item it : bought) {
            ItemStack one = Crafts.takeOne(level, v, s -> s.is(it));
            if (one.isEmpty()) continue;
            if (it != Items.POPPY) Homes.keepsake(one, farmer);
            farmer.insertItem(one);
        }
        setOutForTests(level, farmer);
        candles(level, v);
        List<String> out = new ArrayList<>();
        Homes.Home h = Homes.homeOf(id, farmer.getUUID());
        Decor.Room room = Decor.room(id, b);
        BlockPos door = room.door() != null ? room.door() : house.relative(Direction.SOUTH, 3);
        Direction in = b.facing();
        // The house from across the way, a little above the heads of whoever is about.
        BlockPos front = door.relative(in.getOpposite(), 8);
        out.add(view("decor-1-house", front.getX() + 3, front.getY() + 2.0, front.getZ(), house.getX(), house.getY() + 2.5, house.getZ()));
        // By day, from inside: from the back of the room over the chest, under the ceiling (clear of the ceiling's
        // lantern, over the bed head by the wall), looking down the room to the door.
        BlockPos back = door.relative(in, 5);
        out.add(view("decor-2-inside", back.getX(), back.getY() + 1.25, back.getZ(), door.getX(), door.getY() + 0.5, door.getZ()));
        // At dusk, from outside: in front of the window the household's candle stands in, higher than a body's head
        // (never inside a folk come home for supper, nor in a wall or a bed), looking in at it lit on the sill.
        BlockPos candle = null;
        for (Map.Entry<String, Long> e : Decor.book(id, house).entrySet()) {
            if (e.getKey().startsWith("lux.candle.")) { candle = BlockPos.of(e.getValue()); break; }
        }
        BlockPos aim = candle != null ? candle : door;
        BlockPos window = null;
        for (BlockPos w : room.windows()) {
            boolean frontWall = in.getAxis() == Direction.Axis.Z ? w.getZ() == door.getZ() : w.getX() == door.getX();
            if (!frontWall || w.getY() != door.getY() + 1) continue;
            if (window == null || w.distSqr(aim) < window.distSqr(aim)) window = w;
        }
        BlockPos stand = (window != null ? window : door).relative(in.getOpposite(), 4);
        BlockPos lit = candle != null ? candle : window != null ? window : door.above();
        out.add(view("decor-3-dusk", stand.getX(), door.getY() + 0.9, stand.getZ(), lit.getX(), lit.getY() + 0.4, lit.getZ()));
        out.add("HOUSE " + (h == null ? "none" : String.join("; ", Decor.lines(level, v))));
        return out;
    }

    /** "VIEW name x y z at-x at-y at-z": where the one looking stands (its feet, in the middle of the block) and what it looks at. */
    private static String view(String name, int x, double y, int z, int ax, double ay, int az) {
        return String.format(Locale.ROOT, "VIEW %s %.2f %.2f %.2f %.2f %.2f %.2f", name, x + 0.5, y, z + 0.5, ax + 0.5, ay, az + 0.5);
    }
}
