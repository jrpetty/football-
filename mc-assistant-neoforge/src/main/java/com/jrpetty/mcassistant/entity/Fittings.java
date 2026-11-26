package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.FittingBlock;
import com.jrpetty.mcassistant.block.FittingBlockEntity;
import com.jrpetty.mcassistant.block.TillBlockEntity;
import com.jrpetty.mcassistant.item.TillItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [till] Shop fittings at work.
 * <ul>
 * <li><b>Stock on show.</b> A shelf, a case, a crate, a larder or a rack holds five lots, drawn on it, and they are its
 *     business's stock: a player's shop sells off them (ShopNeeds counts them), the town's shop too (Store.containers).
 *     Its owner stocks them by hand (right-click); its staff fill them from the stock chests, walking over to do it.</li>
 * <li><b>Browsing.</b> A customer clicks a ware on a shelf and the checkout opens with it in the basket (Tills.browse). The
 *     price board shows today's prices; the basket stand opens the checkout empty.</li>
 * <li><b>Fit-out.</b> More fittings, kept full, bring more customers: up to a third more footfall (a player's shop's visits a
 *     day, a firm's name with buyers: Sellers.reputation). The Core says so ("Shop fit-out: 4 shelves, 1 case, price board:
 *     +12% footfall").</li>
 * <li><b>Folk-owned businesses</b> furnish themselves over time, by their coin and their owner's nature (furnish).</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Fittings {

    private Fittings() {}

    /** The most footfall a fit-out brings, in the hundred. */
    static final int MOST = 30;
    static final String HINT = "tillFittingHint";

    record Known(UUID village, String key, FittingBlock.Kind kind) {}

    private static final Map<BlockPos, Known> KNOWN = new ConcurrentHashMap<>();

    public static void known(FittingBlockEntity be) {
        if (be.village() != null) KNOWN.put(be.getBlockPos().immutable(), new Known(be.village(), be.key(), be.kind()));
    }

    public static void forget(BlockPos pos) {
        KNOWN.remove(pos);
    }

    public static void resetForTests() {
        KNOWN.clear();
    }

    /** The fittings of a business. */
    static List<BlockPos> of(UUID village, String key) {
        List<BlockPos> out = new ArrayList<>();
        for (Map.Entry<BlockPos, Known> e : KNOWN.entrySet()) if (e.getValue().village().equals(village) && e.getValue().key().equals(key)) out.add(e.getKey());
        return out;
    }

    /** The town shop's and café's fittings that hold stock (part of the shop's own stock: Store.containers). */
    static List<BlockPos> townShelves(UUID village) {
        List<BlockPos> out = new ArrayList<>();
        for (Map.Entry<BlockPos, Known> e : KNOWN.entrySet()) {
            Known k = e.getValue();
            if (k.village().equals(village) && k.key().equals("town:shop") && k.kind().holds) out.add(e.getKey());
        }
        return out;
    }

    // ------------------------------------------------------------------ setting one

    public static void placed(ServerLevel level, BlockPos pos, ServerPlayer p) {
        if (!(level.getBlockEntity(pos) instanceof FittingBlockEntity be)) return;
        String[] b = TillShops.resolve(level, pos);
        if (b == null) {
            p.displayClientMessage(Component.literal("A " + be.kind().word + ". Set it in a shop or a business of yours and it counts to its fit-out.")
                .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        UUID village = UUID.fromString(b[0]);
        TillShops.Source src = TillShops.of(level, village, b[1]);
        if (src == null) return;
        if (!b[1].startsWith("town:") && !src.ownedBy(p.getUUID())) {
            p.displayClientMessage(Component.literal("Not your business: the " + be.kind().word + " is just furniture here.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        be.bind(village, b[1], p.getUUID());
        known(be);
        p.displayClientMessage(Component.literal("The " + be.kind().word + " is part of " + src.name(level) + ". " + words(village, b[1])).withStyle(ChatFormatting.GREEN), true);
    }

    /** Right-click: its owner stocks it; anybody else browses it (the checkout, with the ware they clicked). */
    public static void use(ServerPlayer p, BlockPos pos, Vec3 hit) {
        ServerLevel level = p.serverLevel();
        if (!(level.getBlockEntity(pos) instanceof FittingBlockEntity be)) return;
        if (!be.bound()) {
            String[] b = TillShops.resolve(level, pos);
            if (b != null && b[1].startsWith("town:")) { be.bind(UUID.fromString(b[0]), b[1], null); known(be); }
        }
        TillShops.Source src = be.bound() ? TillShops.of(level, be.village(), be.key()) : null;
        boolean mine = src == null ? (be.owner() == null || be.owner().equals(p.getUUID())) : src.ownedBy(p.getUUID());
        if (be.holdsStock() && mine && !p.isShiftKeyDown()) {
            p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new HopperMenu(id, inv, be),
                Component.literal(be.getBlockState().getBlock().getName().getString() + (src != null ? ": " + src.name(level) : ""))));
            return;
        }
        if (src == null) {
            p.displayClientMessage(Component.literal("Just furniture: it isn't any business's.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        CompoundTagHint.once(p, HINT, "Click a ware on a shelf and it goes in your basket. The till does the rest.");
        ItemStack ware = be.holdsStock() ? wareAt(be, hit) : ItemStack.EMPTY;
        if (be.holdsStock() && ware.isEmpty()) {
            p.displayClientMessage(Component.literal("Empty. Nobody's got round to filling it.").withStyle(ChatFormatting.GRAY), true);
        }
        Tills.browse(p, be.village(), be.key(), pos, ware);
    }

    /** The ware nearest where the fitting was clicked (its slots run left to right as a customer faces it). */
    static ItemStack wareAt(FittingBlockEntity be, Vec3 hit) {
        Direction face = be.getBlockState().getValue(FittingBlock.FACING);
        BlockPos pos = be.getBlockPos();
        double lx = hit.x - pos.getX(), lz = hit.z - pos.getZ();
        // across, 0 at the customer's left
        double across = switch (face) {
            case NORTH -> 1 - lx;
            case SOUTH -> lx;
            case EAST -> 1 - lz;
            default -> lz;
        };
        double up = hit.y - pos.getY();
        List<Integer> order = new ArrayList<>();
        if (be.kind() == FittingBlock.Kind.SHELF && up > 0.5) { order.add(across < 0.5 ? 3 : 4); }
        else order.add(Math.max(0, Math.min(2, (int) (across * 3))));
        for (int i = 0; i < FittingBlockEntity.SLOTS; i++) if (!order.contains(i)) order.add(i);
        for (int i : order) if (!be.getItem(i).isEmpty()) return be.getItem(i).copyWithCount(1);
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ the fit-out

    /** A business's fit-out: so many of each fitting, how full they are, and the footfall it brings. */
    record FitOut(Map<FittingBlock.Kind, Integer> counts, float fullness, int percent) {}

    static FitOut fitOut(ServerLevel level, UUID village, String key) {
        Map<FittingBlock.Kind, Integer> counts = new EnumMap<>(FittingBlock.Kind.class);
        float full = 0;
        int holding = 0;
        for (BlockPos p : of(village, key)) {
            Known k = KNOWN.get(p);
            if (k == null) continue;
            counts.merge(k.kind(), 1, Integer::sum);
            if (k.kind().holds && level.isLoaded(p) && level.getBlockEntity(p) instanceof FittingBlockEntity be) {
                full += be.fullness();
                holding++;
            }
        }
        float fullness = holding == 0 ? 1F : full / holding;
        return new FitOut(counts, fullness, percent(counts, fullness));
    }

    /** The footfall in the hundred: three for each shelf, crate, larder and rack, four for a case, five for the first board
     *  and three for the first basket stand; scaled by how full they are kept (empty shelves count for less than none). */
    static int percent(Map<FittingBlock.Kind, Integer> counts, float fullness) {
        int raw = 0;
        for (Map.Entry<FittingBlock.Kind, Integer> e : counts.entrySet()) {
            int n = e.getValue();
            raw += switch (e.getKey()) {
                case CASE -> 4 * n;
                case BOARD -> 5 * Math.min(1, n);
                case BASKETS -> 3 * Math.min(1, n);
                default -> 3 * n;
            };
        }
        double tidy = 0.4 + 0.6 * Math.max(0, Math.min(1, fullness));
        return (int) Math.min(MOST, Math.round(raw * tidy));
    }

    /** "Shop fit-out: 4 shelves, 1 case, price board: +12% footfall", or "" with none. */
    static String words(UUID village, String key) {
        Map<FittingBlock.Kind, Integer> counts = new EnumMap<>(FittingBlock.Kind.class);
        for (BlockPos p : of(village, key)) {
            Known k = KNOWN.get(p);
            if (k != null) counts.merge(k.kind(), 1, Integer::sum);
        }
        if (counts.isEmpty()) return "";
        return words(counts, PCT.getOrDefault(village + "/" + key, percent(counts, 1F)));
    }

    static String words(Map<FittingBlock.Kind, Integer> counts, int pct) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<FittingBlock.Kind, Integer> e : counts.entrySet()) {
            int n = e.getValue();
            boolean single = e.getKey() == FittingBlock.Kind.BOARD || e.getKey() == FittingBlock.Kind.BASKETS;
            parts.add(single && n == 1 ? e.getKey().word : n + " " + (n == 1 ? e.getKey().word : e.getKey().words));
        }
        return "Shop fit-out: " + String.join(", ", parts) + ": +" + pct + "% footfall";
    }

    /** The last reckoned footfall of each business, in the hundred (kept by the tick, read by the customers). */
    private static final Map<String, Integer> PCT = new ConcurrentHashMap<>();

    /** How much more custom the fit-out brings: 1.0 with none, up to 1.3. */
    public static double footfall(UUID village, String key) {
        Integer p = PCT.get(village + "/" + key);
        return 1.0 + (p == null ? 0 : p) / 100.0;
    }

    /** Tests: reckon a business's fit-out now. */
    public static int footfallPercentForTests(ServerLevel level, UUID village, String key) {
        FitOut f = fitOut(level, village, key);
        PCT.put(village + "/" + key, f.percent());
        return f.percent();
    }

    // ------------------------------------------------------------------ the staff fill the shelves

    /** The business's staff member nearest this fitting (its keeper, its assistant, one of its hands), or null. */
    @Nullable
    static VillageFolkEntity staffFor(ServerLevel level, TillShops.Source src, BlockPos near) {
        if (src instanceof TillShops.Town t) return ServicePoints.keeper(level, t.v.id(), t.role, ServicePoints.nearest(level, t.v.id(), t.role, near));
        if (src instanceof TillShops.Shop s && s.s.keeper != null && level.getEntity(s.s.keeper) instanceof VillageFolkEntity f && f.isAlive()) return f;
        if (src instanceof TillShops.Firm fm) {
            VillageFolkEntity best = null;
            double bd = Double.MAX_VALUE;
            for (VillageFolkEntity f : Enterprise.hands(fm.v.id(), fm.b)) {
                double d = f.distanceToSqr(near.getX() + 0.5, near.getY(), near.getZ() + 0.5);
                if (f.isAlive() && d < bd) { bd = d; best = f; }
            }
            return best;
        }
        return null;
    }

    /**
     * A staff member at a fitting fills it from the stock chests: what is on it already first, else the most plentiful
     * ware, a stack at a time into its empty slots. It must be standing at it (the tick walks it over). How many moved.
     */
    public static int restock(ServerLevel level, FittingBlockEntity be, VillageFolkEntity f, TillShops.Source src) {
        if (!be.holdsStock() || be.fullness() >= 1F) return 0;
        BlockPos pos = be.getBlockPos();
        if (f.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5) > 3.0 * 3.0) return 0;
        List<Container> from = new ArrayList<>();
        for (Container c : src.stockChests(level)) if (!(c instanceof FittingBlockEntity)) from.add(c);
        if (from.isEmpty()) return 0;
        ItemStack want = ItemStack.EMPTY;
        for (ItemStack s : be.shown()) if (!s.isEmpty()) { want = s; break; }
        if (want.isEmpty()) {
            int most = 0;
            for (Container c : from) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack s = c.getItem(i);
                    if (s.isEmpty() || Market.isCoin(s) || !suits(be.kind(), s)) continue;
                    if (s.getCount() > most) { most = s.getCount(); want = s; }
                }
            }
        }
        if (want.isEmpty()) return 0;
        ItemStack like = want.copyWithCount(1);
        int moved = 0;
        for (int slot = 0; slot < FittingBlockEntity.SLOTS; slot++) {
            ItemStack in = be.getItem(slot);
            if (!in.isEmpty()) continue;
            int room = like.getMaxStackSize();
            int got = 0;
            for (Container c : from) {
                if (got >= room) break;
                ItemStack g = ShopCustomers.take(c, like, room - got);
                got += g.getCount();
            }
            if (got <= 0) break;
            be.setItem(slot, like.copyWithCount(got));
            moved += got;
            if (moved >= like.getMaxStackSize() * 2) break;
        }
        if (moved > 0) {
            f.swing(InteractionHand.MAIN_HAND);
            f.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.5F, 1.1F);
        }
        return moved;
    }

    /** What goes on what: food in the larder, clothes on the rack, anything on a shelf, a case or a crate. */
    static boolean suits(FittingBlock.Kind kind, ItemStack s) {
        return switch (kind) {
            case LARDER -> s.get(net.minecraft.core.component.DataComponents.FOOD) != null;
            case RACK -> s.getItem() instanceof net.minecraft.world.item.ArmorItem || s.getItem() instanceof com.jrpetty.mcassistant.item.GarmentItem;
            case CRATE -> s.get(net.minecraft.core.component.DataComponents.FOOD) != null || s.is(net.minecraft.tags.ItemTags.FLOWERS)
                || s.is(net.minecraft.world.item.Items.WHEAT) || s.is(net.minecraft.world.item.Items.SUGAR_CANE);
            default -> true;
        };
    }

    // ------------------------------------------------------------------ a kiosk nobody watches

    /** Now and then a dishonest folk at an unwatched kiosk helps itself to one more, off the business's shelves: a theft the
     *  town may see and the watch may solve, like any other (Mischief). Light: one in a dozen of the least honest. */
    static void maybeLift(ServerLevel level, TillBlockEntity kiosk, VillageFolkEntity f) {
        if (kiosk.village() == null || Mischief.honesty(f) >= 45 || level.getRandom().nextInt(12) != 0) return;
        Villages.Village v = Villages.get(kiosk.village());
        if (v == null) return;
        for (BlockPos p : of(kiosk.village(), kiosk.key())) {
            if (p.distSqr(kiosk.getBlockPos()) > 16 * 16 || !(level.getBlockEntity(p) instanceof FittingBlockEntity be) || be.isEmpty()) continue;
            com.jrpetty.mcassistant.Guard.run("kiosk theft", () -> Mischief.commit(level, v, f, Crime.Kind.STORES, null, p, null, null));
            return;
        }
    }

    // ------------------------------------------------------------------ folk businesses furnish themselves

    /**
     * Once a day: a business of the town's folk with coin to spare buys a fitting for itself, up to what its owner's nature
     * and purse allow (two to start, more for the well-off and the hard-working): a shelf, then a price board, then a crate
     * or a case, set on a free spot of floor by its till. Nothing from nothing: it pays the joiners' price out of its till.
     */
    static void furnish(ServerLevel level, Villages.Village v) {
        for (Business b : Enterprise.open(v.id())) {
            if (b.owners.stream().anyMatch(s -> s.holder() == Business.Holder.PLAYER)) continue;        // a player's own: theirs to furnish
            String key = "firm:" + b.id;
            List<BlockPos> tills = Tills.tillsOf(v.id(), key, b.at == null ? v.centre() : b.at);
            BlockPos at = !tills.isEmpty() ? tills.get(0) : BusinessCore.siteOf(level, v, b);
            if (at == null || !level.isLoaded(at)) continue;
            int have = of(v.id(), key).size();
            long purse = b.till;
            int cap = 2 + (purse > 20000 ? 2 : purse > 6000 ? 1 : 0);
            for (VillageFolkEntity h : Enterprise.hands(v.id(), b)) { if (h.life().has(Social.Trait.HARDWORKING)) { cap++; break; } }
            if (have >= cap) continue;
            long price = 400L * (have + 1);
            if (purse < price * 3) continue;
            FittingBlock.Kind kind = have == 0 ? FittingBlock.Kind.SHELF : have == 1 ? FittingBlock.Kind.BOARD
                : Enterprise.kindOf(v.id(), b) != null && Enterprise.kindOf(v.id(), b).work() == AssistantEntity.StationTask.COOK ? FittingBlock.Kind.LARDER
                : have % 2 == 0 ? FittingBlock.Kind.CRATE : FittingBlock.Kind.CASE;
            BlockPos spot = CoreSites.spotNear(level, at);
            if (spot == null || !TownBlocks.mayPlace(level, v, spot, TownBlocks.Why.RENOVATE)) continue;
            BlockState st = blockOf(kind).defaultBlockState().setValue(FittingBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(level.getRandom()));
            level.setBlock(spot, st, 3);
            if (level.getBlockEntity(spot) instanceof FittingBlockEntity be) {
                be.bind(v.id(), key, null);
                known(be);
                b.till -= price;
                Ledger.addCoins(v.id(), (int) (price / 100));
                Accounts.dirty();
                Villages.tell(v.id(), level.getDayTime() / 24000L, b.name + " bought a " + kind.word + " for its shop floor");
            }
        }
    }

    static net.minecraft.world.level.block.Block blockOf(FittingBlock.Kind kind) {
        return switch (kind) {
            case SHELF -> TillItems.DISPLAY_SHELF.get();
            case CASE -> TillItems.DISPLAY_CASE.get();
            case CRATE -> TillItems.PRODUCE_CRATE.get();
            case BOARD -> TillItems.PRICE_BOARD.get();
            case BASKETS -> TillItems.BASKET_STAND.get();
            case LARDER -> TillItems.LARDER.get();
            case RACK -> TillItems.CLOTHES_RACK.get();
        };
    }

    // ------------------------------------------------------------------ the tick: boards, restocking, footfall

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % 100 != 31 || KNOWN.isEmpty()) return;
        ServerLevel level = event.getServer().overworld();
        com.jrpetty.mcassistant.Guard.run("shop fittings", () -> tick(level));
    }

    static void tick(ServerLevel level) {
        Map<String, Boolean> seen = new java.util.HashMap<>();
        for (Map.Entry<BlockPos, Known> e : KNOWN.entrySet()) {
            BlockPos pos = e.getKey();
            Known k = e.getValue();
            String biz = k.village() + "/" + k.key();
            if (!level.isLoaded(pos)) continue;
            if (!(level.getBlockEntity(pos) instanceof FittingBlockEntity be)) { KNOWN.remove(pos); continue; }
            TillShops.Source src = TillShops.of(level, k.village(), k.key());
            if (src == null) continue;
            if (seen.putIfAbsent(biz, Boolean.TRUE) == null) PCT.put(biz, fitOut(level, k.village(), k.key()).percent());
            if (k.kind() == FittingBlock.Kind.BOARD) be.setBoard(board(level, src));
            if (!k.kind().holds || be.fullness() > 0.6F) continue;
            VillageFolkEntity f = staffFor(level, src, pos);
            if (f == null || f.isSleeping()) continue;
            double d = f.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            if (d <= 3.0 * 3.0) restock(level, be, f, src);
            else if (d <= 16 * 16 && f.getNavigation().isDone() && hasStock(level, src)) f.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.6);
        }
    }

    private static boolean hasStock(ServerLevel level, TillShops.Source src) {
        for (Container c : src.stockChests(level)) if (!(c instanceof FittingBlockEntity) && !c.isEmpty()) return true;
        return false;
    }

    /** Today's prices for the board: its business's first few wares in stock. */
    static List<String> board(ServerLevel level, TillShops.Source src) {
        List<String> out = new ArrayList<>();
        out.add(src.name(level));
        for (TillShops.Ware w : src.wares(level, null)) {
            if (w.stock() <= 0) continue;
            String n = Goods.name(w.sample());
            if (n.length() > 14) n = n.substring(0, 13) + ".";
            out.add(n + "|" + w.priceWords());
            if (out.size() >= 6) break;
        }
        return out;
    }

    /** Tests: the board's lines now. */
    public static List<String> boardForTests(ServerLevel level, FittingBlockEntity be) {
        TillShops.Source src = TillShops.of(level, be.village(), be.key());
        return src == null ? List.of() : board(level, src);
    }

    /** Tests: a fitting bound to this business. */
    public static void bindForTests(ServerLevel level, BlockPos pos, UUID village, String key) {
        if (level.getBlockEntity(pos) instanceof FittingBlockEntity be) {
            be.bind(village, key, null);
            known(be);
        }
    }

    /** Tests: a staff member at the fitting fills it. */
    public static int restockForTests(ServerLevel level, BlockPos pos, VillageFolkEntity f) {
        if (!(level.getBlockEntity(pos) instanceof FittingBlockEntity be) || !be.bound()) return 0;
        TillShops.Source src = TillShops.of(level, be.village(), be.key());
        return src == null ? 0 : restock(level, be, f, src);
    }

    /** A first-time word, once per player. */
    static final class CompoundTagHint {
        static void once(ServerPlayer p, String key, String text) {
            if (p.getPersistentData().getBoolean(key)) return;
            p.getPersistentData().putBoolean(key, true);
            p.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GOLD));
        }
    }
}
