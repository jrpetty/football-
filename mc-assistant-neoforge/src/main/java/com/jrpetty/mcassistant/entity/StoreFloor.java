package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The shop floor: the counters, what is on them, and the assistants behind them who serve the customers face to
 * face.
 * <ul>
 * <li><b>The counters</b> of the store and of the little shop (its branch) are set out from the shop's own stock,
 *     one thing to a counter in a frame, the food and the dearest first, then (while the stockroom has not got
 *     enough to fill them) what the crafts have put in the stores for the shop, as the counters always showed.
 *     Each has its price tag in front, at the shop's own price (ShopStock.price).</li>
 * <li><b>The assistants</b> stand behind the counters in working hours, one to a counter, the store's counters
 *     nearest its door first. A folk who buys at the shop comes to a counter and the assistant there (or the
 *     nearest one at work) hands it over and takes the coin; a word on each side. With no assistant at work the
 *     keeper serves between pieces of work, as it always did.</li>
 * <li><b>Players</b> buy at a counter by right-clicking it (crouch for the price), or by right-clicking the
 *     assistant behind it with village coin in hand: a lot of whatever is on that counter, at the shop's price,
 *     the coin to the treasury.</li>
 * </ul>
 */
public final class StoreFloor {

    private StoreFloor() {}

    /** The tag on a frame on one of the shop's counters (a player's right-click buys there: Market). */
    public static final String FRAME_TAG = "mca_store";

    /** A counter, the place behind it where its assistant stands, the side it faces the customers on, and its
     *  building. */
    record Post(Ledger.Building building, BlockPos counter, @Nullable BlockPos stand, Direction front) {}

    private static final Map<UUID, Integer> SERVED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SERVED_DAY = new ConcurrentHashMap<>();
    private static final Map<UUID, String> DOING = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> WALKING = new ConcurrentHashMap<>();
    /** Who served the last sale in each village (folkBuys reads it). */
    private static final Map<UUID, UUID> LAST_SERVER = new ConcurrentHashMap<>();

    public static void resetForTests() {
        SERVED.clear();
        SERVED_DAY.clear();
        DOING.clear();
        WALKING.clear();
        LAST_SERVER.clear();
    }

    // ------------------------------------------------------------------ the counters

    /** The side of a counter that faces the door (where its price tag goes and its customers stand). */
    static Direction front(BlockPos counter, @Nullable BlockPos door) {
        if (door == null) return Direction.NORTH;
        int dx = door.getX() - counter.getX(), dz = door.getZ() - counter.getZ();
        return Math.abs(dz) >= Math.abs(dx) ? (dz >= 0 ? Direction.SOUTH : Direction.NORTH) : (dx >= 0 ? Direction.EAST : Direction.WEST);
    }

    /** Somebody can stand here: room for the body and a floor under it. */
    static boolean standable(ServerLevel level, BlockPos p) {
        if (!level.isLoaded(p)) return false;
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty()
            && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
            && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP);
    }

    /** Every counter of the shop's premises (the store's first, nearest its door first), with its post. */
    static List<Post> posts(ServerLevel level, UUID village) {
        List<Post> out = new ArrayList<>();
        for (Ledger.Building b : Store.premises(village)) {
            if (!level.isLoaded(b.anchor())) continue;
            Store.Layout l = Store.layout(b);
            for (BlockPos c : l.counters()) {
                if (!level.getBlockState(c).is(Blocks.BARREL)) continue;          // not built yet
                Direction f = front(c, l.door());
                BlockPos stand = null;
                for (BlockPos p : new BlockPos[]{ c.relative(f.getOpposite()), c.relative(f.getClockWise()), c.relative(f.getCounterClockWise()) }) {
                    if (standable(level, p)) { stand = p; break; }
                }
                out.add(new Post(b, c, stand, f));
            }
        }
        return out;
    }

    /** The counter this assistant keeps (the i-th assistant the i-th counter that has room behind it), or null. */
    @Nullable
    static Post postOf(ServerLevel level, VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || ShopRoles.role(f) != ShopRoles.Role.ASSISTANT) return null;
        List<VillageFolkEntity> staff = StoreStaff.in(id, ShopRoles.Role.ASSISTANT);
        int i = staff.indexOf(f);
        if (i < 0) return null;
        List<Post> manned = new ArrayList<>();
        for (Post p : posts(level, id)) if (p.stand() != null) manned.add(p);
        return i < manned.size() ? manned.get(i) : null;
    }

    /** Is the shop floor set out by the shop's own stock (rather than straight from the stores, as before)? */
    public static boolean dresses(UUID village) {
        return ShopStock.open(village);
    }

    /**
     * Every counter of the shop's premises set out: the shop's own stock first (the food, then the dearest), then
     * what the crafts have put in the stores for the shop; a frame each, tagged as the shop's, and the price tag in
     * front at the shop's price. Returns how many counters changed.
     */
    public static int dress(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<ItemStack> goods = new ArrayList<>(Store.kinds(level, id));
        goods.sort((a, b) -> {
            boolean fa = a.get(net.minecraft.core.component.DataComponents.FOOD) != null, fb = b.get(net.minecraft.core.component.DataComponents.FOOD) != null;
            if (fa != fb) return fa ? -1 : 1;
            return Double.compare(Prices.of(b), Prices.of(a));
        });
        for (ItemStack s : Cafe.shopGoods(level, id)) {
            boolean seen = false;
            for (ItemStack o : goods) if (ItemStack.isSameItemSameComponents(o, s)) { seen = true; break; }
            if (!seen) goods.add(s);
        }
        int changed = 0, k = 0;
        for (Ledger.Building b : Store.premises(id)) {
            if (!level.isLoaded(b.anchor())) continue;
            Store.Layout l = Store.layout(b);
            for (BlockPos at : l.counters()) {
                if (!level.getBlockState(at).is(Blocks.BARREL)) continue;
                ItemStack want = k < goods.size() ? goods.get(k) : ItemStack.EMPTY;
                k++;
                if (frameOn(level, at.above(), want)) changed++;
                priceTag(level, v, at, l.door(), want);
            }
        }
        return changed;
    }

    /**
     * The frame on a counter showing this (as TownLife.frameOn hangs a stall's, fixed and unseen but for what is
     * in it), tagged as the shop's own from the moment it is hung, so a player's right-click on the counter buys
     * there (Market). True if it changed.
     */
    private static boolean frameOn(ServerLevel level, BlockPos at, ItemStack want) {
        ItemFrame frame = null;
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, new AABB(at))) {
            if (!f.getTags().contains("mca_stall")) continue;
            if (frame == null) frame = f;
            else f.discard();                                           // one frame to a counter
        }
        if (frame == null) {
            if (want.isEmpty() || !level.getBlockState(at).isAir()) return false;
            frame = new ItemFrame(level, at, Direction.UP);
            net.minecraft.nbt.CompoundTag t = frame.saveWithoutId(new net.minecraft.nbt.CompoundTag());
            t.putBoolean("Fixed", true);
            t.putBoolean("Invisible", true);
            frame.load(t);
            frame.addTag("mca_stall");
            frame.addTag("mca_counter");
            frame.addTag(FRAME_TAG);
            level.addFreshEntity(frame);
        } else if (!frame.getTags().contains(FRAME_TAG)) {
            frame.addTag(FRAME_TAG);                                    // a counter the shop sold from the stores before
        }
        ItemStack now = frame.getItem();
        if (want.isEmpty()) {
            if (now.isEmpty()) return false;
            frame.setItem(ItemStack.EMPTY, false);
            return true;
        }
        if (ItemStack.isSameItemSameComponents(now, want)) return false;
        frame.setItem(want.copyWithCount(1), false);
        return true;
    }

    /** The price tag on the counter's front: a sign out of the stores (or two planks) when there is none yet. */
    private static void priceTag(ServerLevel level, Villages.Village v, BlockPos counter, @Nullable BlockPos door, ItemStack shown) {
        if (door == null) return;
        Direction front = front(counter, door);
        BlockPos at = counter.relative(front);
        BlockState there = level.getBlockState(at);
        if (!(there.getBlock() instanceof WallSignBlock)) {
            if (shown.isEmpty() || !there.isAir()) return;
            if (!Crafts.sign(level, v)) return;
            level.setBlock(at, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, front), 3);
        }
        if (level.getBlockEntity(at) instanceof SignBlockEntity sign) TownLife.write(sign, tagLines(level, v.id(), shown));
    }

    /** A counter's price tag: the thing, what one costs at the shop, and how it is sold (by the lot). */
    static String[] tagLines(ServerLevel level, UUID village, ItemStack shown) {
        if (shown.isEmpty()) return new String[]{ "", "Sold out", "", "" };
        int each = ShopStock.price(level, village, shown);
        int lot = lotOf(shown);
        String name = shown.getHoverName().getString(), one = name, two = "";
        if (name.length() > 15) {
            int cut = name.lastIndexOf(' ', 15);
            if (cut <= 0) cut = 15;
            one = name.substring(0, cut).trim();
            two = name.substring(cut).trim();
            if (two.length() > 15) two = two.substring(0, 15);
        }
        return new String[]{ one, two, each + (each == 1 ? " coin" : " coins") + (lot > 1 ? " each" : ""), lot > 1 ? lot + " for " + each * lot : "" };
    }

    /** How many a player buys at once: the board's lot (eight loaves...), never more than sixteen. */
    static int lotOf(ItemStack shown) {
        Market.Good g = Budget.goodFor(shown);
        return g == null ? 1 : Math.max(1, Math.min(16, g.bundle()));
    }

    // ------------------------------------------------------------------ the assistants

    /**
     * A beat of an assistant's work (StoreStaff.work): to its counter and behind it, facing the customers; a word
     * now and then about what is fresh in. False with no counter for it (it helps at the bench meanwhile).
     */
    static boolean work(VillageFolkEntity f, ServerLevel level) {
        Post p = postOf(level, f);
        if (p == null || p.stand() == null) return false;
        BlockPos stand = p.stand();
        double d = f.distanceToSqr(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        String place = p.building().structure().equals(Store.STRUCTURE) ? "the store" : "the shop";
        if (d > 1.0) {
            long started = WALKING.computeIfAbsent(f.getUUID(), k -> level.getGameTime());
            if (Store.INSTANT || level.getGameTime() - started > 600L) {
                f.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);   // stuck at a doorway: put behind it
                WALKING.remove(f.getUUID());
            } else if (f.getNavigation().isDone()) {
                f.walkTo(stand, 0.9D);
            }
            DOING.put(f.getUUID(), "Going to the counter at " + place);
            return true;
        }
        WALKING.remove(f.getUUID());
        f.getNavigation().stop();
        // Face the customers: whoever is at the counter, else out across it toward the door.
        net.minecraft.world.entity.LivingEntity customer = null;
        double best = 5.0 * 5.0;
        for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                new AABB(p.counter()).inflate(4.0), e -> e != f && (e instanceof Player || e instanceof VillageFolkEntity))) {
            double dd = e.distanceToSqr(p.counter().getX() + 0.5, p.counter().getY(), p.counter().getZ() + 0.5);
            if (dd < best) { best = dd; customer = e; }
        }
        if (customer != null) f.getLookControl().setLookAt(customer, 30.0F, 30.0F);
        else {
            BlockPos ahead = p.counter().relative(p.front(), 3);
            f.getLookControl().setLookAt(ahead.getX() + 0.5, ahead.getY() + 1.5, ahead.getZ() + 0.5);
        }
        DOING.put(f.getUUID(), "Serving at the counter of " + place);
        if (customer instanceof Player && f.getRandom().nextInt(900) == 0) {
            ItemFrame frame = Market.stallFrame(level, p.counter());
            ItemStack shown = frame == null ? ItemStack.EMPTY : frame.getItem();
            FolkTalk.speak(f, shown.isEmpty() ? "Good day! Anything I can get you?"
                : FolkTalk.pick(f.getRandom(), "Good day! " + shown.getHoverName().getString() + ", " + ShopStock.price(level, f.ownerId(), shown)
                    + " a piece — fresh in this morning.", "Right-click me with your coin and it's yours."));
        }
        return true;
    }

    /** The counter nearest this folk, its post and all, or null. */
    @Nullable
    static Post nearestPost(ServerLevel level, UUID village, BlockPos near) {
        Post best = null;
        double bestD = Double.MAX_VALUE;
        for (Post p : posts(level, village)) {
            double d = p.counter().distSqr(near);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    /** Who serves a sale: the assistant behind the counter nearest the buyer, else any assistant at work, else
     *  the keeper. */
    @Nullable
    static VillageFolkEntity server(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity buyer) {
        UUID id = v.id();
        VillageFolkEntity best = null, atWork = null;
        double bestD = Double.MAX_VALUE, workD = Double.MAX_VALUE;
        for (VillageFolkEntity a : StoreStaff.in(id, ShopRoles.Role.ASSISTANT)) {
            if (a.isSleeping()) continue;
            double d = buyer == null ? 0 : a.distanceToSqr(buyer);
            Post p = postOf(level, a);
            boolean behind = p != null && p.stand() != null
                && a.distanceToSqr(p.stand().getX() + 0.5, p.stand().getY(), p.stand().getZ() + 0.5) <= 2.5 * 2.5;
            if (behind && d < bestD) { bestD = d; best = a; }
            if (!a.offWorkNow() && d < workD) { workD = d; atWork = a; }
        }
        if (best != null) return best;
        return atWork != null ? atWork : Workshop.keeper(id);
    }

    /**
     * A sale served (ShopStock.takeStacks): booked to whoever served it (the day's tally on its card), and, with the
     * buyer at the counter, handed over face to face: the assistant turns to it and swings, and a word each.
     */
    static void served(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity buyer, ItemStack one, int n) {
        VillageFolkEntity who = server(level, v, buyer);
        if (who == null) return;
        LAST_SERVER.put(v.id(), who.getUUID());
        long today = level.getDayTime() / 24000L;
        if (SERVED_DAY.getOrDefault(who.getUUID(), -1L) != today) {
            SERVED_DAY.put(who.getUUID(), today);
            SERVED.put(who.getUUID(), 0);
        }
        SERVED.merge(who.getUUID(), n, Integer::sum);
        if (buyer == null || who.distanceToSqr(buyer) > 8.0 * 8.0) return;
        who.getLookControl().setLookAt(buyer, 30.0F, 30.0F);
        buyer.getLookControl().setLookAt(who, 30.0F, 30.0F);
        who.swing(InteractionHand.MAIN_HAND);
        String what = n == 1 ? one.getHoverName().getString().toLowerCase(java.util.Locale.ROOT) : Bench.words(one.getItem(), n);
        if (level.getRandom().nextInt(3) == 0) {
            FolkTalk.speak(buyer, FolkTalk.pick(level.getRandom(), "I'll have " + (n == 1 ? "the " : "") + what + ", please.",
                "Just the " + what + " today, thank you.", "Have you " + what + "? Lovely."));
        }
        if (level.getRandom().nextInt(2) == 0) {
            FolkTalk.speak(who, FolkTalk.pick(level.getRandom(), "There you are, " + buyer.displayNameCap() + ". Mind how you go!",
                "Here you are — " + what + ". Thank you kindly!", "One " + what + ", " + buyer.displayNameCap() + ". Come again!"));
        }
    }

    /**
     * Who served the last sale in this village, from the village's own roll rather than the level's entity lookup:
     * a folk on ground only just loaded (a forced chunk the tick it is forced) is not in the level's lookup yet,
     * and the sale it served was put down to nobody.
     */
    @Nullable
    static VillageFolkEntity lastServer(UUID village) {
        UUID by = LAST_SERVER.get(village);
        if (by == null) return null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && by.equals(f.getUUID()) && f.isAlive()) return f;
        }
        return null;
    }

    /** What a sale came to: did it go through, who served it, at which counter, for how much, and what. */
    public record Sale(boolean ok, @Nullable VillageFolkEntity servedBy, @Nullable BlockPos counter, int price, ItemStack bought, String why) {}

    /**
     * A folk buying at the shop (Purchases, or anybody's errand to the shop): it must be at a counter (the nearest;
     * the caller walks it there); the shop's stock (or, short, the stores') gives it up; the folk pays the shop's
     * price out of its purse, into the treasury; the assistant at the counter hands it over. Returns the sale, or
     * why not ("walk", "purse", "none").
     */
    public static Sale folkBuys(ServerLevel level, Villages.Village v, VillageFolkEntity buyer, Predicate<ItemStack> what, int n) {
        UUID id = v.id();
        Post post = nearestPost(level, id, buyer.blockPosition());
        if (post == null) return new Sale(false, null, null, 0, ItemStack.EMPTY, "none");
        double reach = buyer.distanceToSqr(post.counter().getX() + 0.5, post.counter().getY(), post.counter().getZ() + 0.5);
        if (reach > 3.5 * 3.5) return new Sale(false, null, post.counter(), 0, ItemStack.EMPTY, "walk");
        ItemStack one = ItemStack.EMPTY;
        for (net.minecraft.world.Container c : Store.containers(level, id, true)) {
            for (int i = 0; i < c.getContainerSize() && one.isEmpty(); i++) if (!c.getItem(i).isEmpty() && what.test(c.getItem(i))) one = c.getItem(i).copyWithCount(1);
        }
        if (one.isEmpty()) one = StockKeeper.sampleFor(what);
        int price = one.isEmpty() ? n : ShopStock.price(level, id, one) * n;
        price = FolkSkills.thrifty(buyer, price);
        if (buyer.purse() < price) return new Sale(false, null, post.counter(), price, ItemStack.EMPTY, "purse");
        List<ItemStack> got = ShopStock.takeStacks(level, v, what, n, buyer);
        if (got.isEmpty()) return new Sale(false, null, post.counter(), price, ItemStack.EMPTY, "none");
        buyer.spend(price);
        Ledger.addCoins(id, price);
        Economy.spentInTown(id, price);
        Stockroom.sold(level, id, Stockroom.Seller.SHOP, got.get(0), n, price);
        for (ItemStack s : got) {
            ItemStack left = buyer.insertGiven(s.copy());
            if (!left.isEmpty()) Crafts.store(level, v, left);
        }
        VillageFolkEntity server = lastServer(id);
        buyer.brain("bought " + Bench.words(got.get(0).getItem(), n) + " at " + (Store.stands(id) ? "the store" : "the shop")
            + (server != null ? ", served by " + server.displayNameCap() : ""));
        return new Sale(true, server, post.counter(), price, got.get(0).copyWithCount(n), "");
    }

    /**
     * A player buys a lot of what is on one of the shop's counters (a right-click on it, or on its assistant with
     * coin in hand): out of the shop's stock (or, short, the stores'), at the shop's price, with the town's own
     * reckoning of the player (an outcast is not served, the unwelcome pay double, a friend and a citizen a tenth
     * less, a haggled price kept). Returns what to tell them.
     */
    public static String playerBuys(ServerLevel level, Villages.Village v, Player p, ItemStack shown, @Nullable BlockPos counter) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        Standing.Title title = Standing.of(id, p.getUUID(), level.getGameTime()).title();
        if (title == Standing.Title.OUTCAST) return "Nobody here will trade with you.";
        if (Laws.banished(id, p.getUUID(), day)) return "You're banished from " + Villages.name(id) + ". Nobody will serve you.";
        if (shown.isEmpty()) return "Nothing on this counter today.";
        Predicate<ItemStack> same = s -> ItemStack.isSameItemSameComponents(s, shown);
        int lot = lotOf(shown);
        String name = lot > 1 ? lot + " " + shown.getHoverName().getString() : shown.getHoverName().getString();
        if (ShopStock.count(level, id, same) < lot) {
            if (!p.isShiftKeyDown()) StockKeeper.missed(level, id, shown, lot);
            return "Sold out of " + shown.getHoverName().getString() + " just now — the stock keeper's been told.";
        }
        int short_ = lot - ShopStock.held(level, id, same);
        if (short_ > 0 && Budget.spare(level, id, shown) < short_) return "They can't spare " + name + " — the village needs it itself just now.";
        int price = ShopStock.price(level, id, shown) * lot;
        if (title == Standing.Title.UNWELCOME) price *= 2;
        else if (title.atLeast(Standing.Title.FRIEND)) price = Math.max(1, price - price / 10);
        if (Citizens.is(id, p.getUUID())) price = Math.max(1, price - Math.max(1, price / 10));
        price = Dealings.haggled(id, p.getUUID(), day, price);
        String coins = price == 1 ? " coin" : " coins";
        if (p.isShiftKeyDown()) return name + ": " + price + coins + ". Right-click to buy.";
        int held = Market.coinsHeld(p);
        if (held < price) return name + " for " + price + coins + ". You have " + held + ".";
        List<ItemStack> got = ShopStock.takeStacks(level, v, same, lot, null);
        if (got.isEmpty()) return "Sold out of " + shown.getHoverName().getString() + " just now.";
        Market.payOut(p, price);
        Ledger.addCoins(id, price);
        Economy.sold(id, price);
        Budget.forget(id);
        Stockroom.sold(level, id, Stockroom.Seller.SHOP, shown, lot, price);
        for (ItemStack s : got) {
            ItemStack give = s.copy();
            if (!p.getInventory().add(give)) p.drop(give, false);
        }
        level.playSound(null, p.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.8F, 1.0F);
        VillageFolkEntity server = lastServer(id);
        if (counter != null) {
            // The assistant at that very counter, if it is behind it.
            for (VillageFolkEntity a : StoreStaff.in(id, ShopRoles.Role.ASSISTANT)) {
                Post post = postOf(level, a);
                if (post != null && post.counter().equals(counter) && a.distanceToSqr(p) < 8.0 * 8.0) { server = a; break; }
            }
        }
        if (server != null && server.distanceToSqr(p) < 10.0 * 10.0) {
            server.swing(InteractionHand.MAIN_HAND);
            server.getLookControl().setLookAt(p, 30.0F, 30.0F);
            FolkTalk.speak(server, FolkTalk.pick(level.getRandom(), "Thank you kindly!", "A pleasure doing business.",
                "There you are — " + name.toLowerCase(java.util.Locale.ROOT) + ". Come again!", "Mind how you go!"));
        }
        return "Bought " + name + " for " + price + coins + (server != null ? " — served by " + server.displayNameCap() : "") + ".";
    }

    /** A player right-clicked an assistant at its counter with village coin in hand: it sells them what is on its
     *  counter. False (the folk's card opens as usual) otherwise. */
    public static boolean serveAPlayer(VillageFolkEntity assistant, ServerPlayer p) {
        if (!(assistant.level() instanceof ServerLevel level) || assistant.ownerId() == null) return false;
        if (ShopRoles.role(assistant) != ShopRoles.Role.ASSISTANT || !ShopStock.open(assistant.ownerId())) return false;
        if (!Market.isCoin(p.getMainHandItem())) return false;
        Post post = postOf(level, assistant);
        if (post == null || post.stand() == null) return false;
        if (assistant.distanceToSqr(post.stand().getX() + 0.5, post.stand().getY(), post.stand().getZ() + 0.5) > 2.5 * 2.5) return false;
        Villages.Village v = Villages.get(assistant.ownerId());
        if (v == null) return false;
        ItemFrame frame = Market.stallFrame(level, post.counter());
        ItemStack shown = frame == null ? ItemStack.EMPTY : frame.getItem();
        if (shown.isEmpty()) {
            FolkTalk.speak(assistant, "Nothing on my counter just now, I'm afraid — the stock keeper's on to it.");
            return true;
        }
        String said = playerBuys(level, v, p, shown, post.counter());
        p.displayClientMessage(Component.literal(said), true);
        return true;
    }

    // ------------------------------------------------------------------ shown

    @Nullable
    static String doing(VillageFolkEntity f) {
        return DOING.get(f.getUUID());
    }

    /** How many sales this assistant has served today. */
    public static int servedToday(VillageFolkEntity f) {
        long today = f.level().getDayTime() / 24000L;
        return SERVED_DAY.getOrDefault(f.getUUID(), -1L) == today ? SERVED.getOrDefault(f.getUUID(), 0) : 0;
    }

    /** An assistant's card line: its counter and its day. */
    static String cardLine(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level)) return "";
        Post p = postOf(level, f);
        String place = f.ownerId() != null && Store.stands(f.ownerId()) ? "the store" : "the shop";
        int served = servedToday(f);
        return (p == null ? "helps at " + place + "'s bench while there is no counter free for it"
            : "keeps a counter at " + (p.building().structure().equals(Store.STRUCTURE) ? "the store" : "the shop"))
            + "; " + served + (served == 1 ? " thing" : " things") + " sold over the counter today";
    }

    /** Every assistant behind its counter now (the stage for the smoke). */
    static void postNow(ServerLevel level, Villages.Village v) {
        for (VillageFolkEntity a : StoreStaff.in(v.id(), ShopRoles.Role.ASSISTANT)) {
            Post p = postOf(level, a);
            if (p == null || p.stand() == null) continue;
            a.teleportTo(p.stand().getX() + 0.5, p.stand().getY(), p.stand().getZ() + 0.5);
            BlockPos ahead = p.counter().relative(p.front(), 3);
            a.getLookControl().setLookAt(ahead.getX() + 0.5, ahead.getY() + 1.5, ahead.getZ() + 0.5);
            DOING.put(a.getUUID(), "Serving at the counter");
        }
    }

    /** Tests: every counter of the shop's premises and where its assistant would stand, as "counter|stand". */
    public static List<String> postsForTests(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        for (Post p : posts(level, village)) out.add(p.counter().toShortString() + "|" + (p.stand() == null ? "" : p.stand().toShortString()));
        return out;
    }

    /** Tests: the counter this assistant keeps and where it stands, as "counter|stand", or "". */
    public static String postForTests(ServerLevel level, VillageFolkEntity f) {
        Post p = postOf(level, f);
        return p == null ? "" : p.counter().toShortString() + "|" + (p.stand() == null ? "" : p.stand().toShortString());
    }

    /** Tests: the counter this assistant keeps, or null. */
    @Nullable
    public static BlockPos counterForTests(ServerLevel level, VillageFolkEntity f) {
        Post p = postOf(level, f);
        return p == null ? null : p.counter();
    }

    /** Tests: where this assistant stands to keep its counter, or null. */
    @Nullable
    public static BlockPos standForTests(ServerLevel level, VillageFolkEntity f) {
        Post p = postOf(level, f);
        return p == null ? null : p.stand();
    }
}
