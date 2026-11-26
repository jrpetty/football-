package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.FittingBlockEntity;
import com.jrpetty.mcassistant.block.TillBlock;
import com.jrpetty.mcassistant.block.TillBlockEntity;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.TillItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [till] The checkout: one simple way to buy, for players and folk alike, at every shop, café, firm and player's shop.
 * <ul>
 * <li><b>The till</b> (block/TillBlock) stands on the counter. Right-click it, or the shopkeeper behind it, and the
 *     checkout opens: what's for sale (icon, name, price, how many are left; sold out greyed), − and + on each line (shift:
 *     ten), the basket on the right with every line, the total and the coin you carry, and one Pay button. A second tab sells
 *     back, if the business buys.</li>
 * <li><b>Staffed or not.</b> A till serves with somebody behind it; with nobody there it says so ("Nobody at the till: back at
 *     9") and offers self-service if the owner allows it. A kiosk serves itself, always, and sells what its owner set.</li>
 * <li><b>The sale</b> is the business's own (TillShops): its stock, its prices, its books; the coin into its till.</li>
 * <li><b>Folk</b> buy at the same till through the same code (folkCheckout, and the town's counter sale): the till says who
 *     it is serving, rings, and the keeper hands it over.</li>
 * </ul>
 * Every button comes back here and is checked again (still near, a fresh request, the business open): nothing the screen
 * says about a price or a stock is believed.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Tills {

    private Tills() {}

    static final double REACH = 7.0;
    /** A total over this asks "sure?" first (the screen's). */
    public static final int CONFIRM_OVER = 50;
    /** The most of one line a basket holds. */
    static final int MOST = 640;
    static final String HINT = "tillHint";

    // ------------------------------------------------------------------ the tills the world has

    /** A till in the world: its town, its business, and whether it's a kiosk. */
    record Known(UUID village, String key, boolean kiosk) {}

    private static final Map<BlockPos, Known> KNOWN = new ConcurrentHashMap<>();

    public static void known(TillBlockEntity be) {
        if (be.village() != null) KNOWN.put(be.getBlockPos().immutable(), new Known(be.village(), be.key(), be.kiosk()));
    }

    public static void forget(BlockPos pos) {
        KNOWN.remove(pos);
    }

    public static void resetForTests() {
        KNOWN.clear();
        SESSIONS.clear();
    }

    /** The tills of this business, nearest {@code near} first. */
    static List<BlockPos> tillsOf(UUID village, String key, BlockPos near) {
        List<BlockPos> out = new ArrayList<>();
        for (Map.Entry<BlockPos, Known> e : KNOWN.entrySet()) if (e.getValue().village().equals(village) && e.getValue().key().equals(key)) out.add(e.getKey());
        out.sort((a, b) -> Double.compare(a.distSqr(near), b.distSqr(near)));
        return out;
    }

    // ------------------------------------------------------------------ setting one

    /** Set by a player: bound to the business it stands in, theirs or the town's. */
    public static void placed(ServerLevel level, BlockPos pos, ServerPlayer p) {
        if (!(level.getBlockEntity(pos) instanceof TillBlockEntity be)) return;
        String[] b = TillShops.resolve(level, pos);
        String what = be.kiosk() ? "kiosk" : "till";
        if (b == null) {
            p.displayClientMessage(Component.literal("A " + what + " rings up for whatever business it stands in. This one stands in none yet.")
                .withStyle(ChatFormatting.YELLOW), false);
            return;
        }
        UUID village = UUID.fromString(b[0]);
        TillShops.Source src = TillShops.of(level, village, b[1]);
        if (src == null) return;
        if ((b[1].startsWith("pshop:") || b[1].startsWith("firm:")) && !src.ownedBy(p.getUUID())) {
            p.displayClientMessage(Component.literal("That's not your business. The " + what + " stays a paperweight.").withStyle(ChatFormatting.YELLOW), false);
            return;
        }
        be.bind(village, b[1], p.getUUID());
        be.setSelfServe(be.kiosk() || !b[1].startsWith("town:"));
        known(be);
        refreshSign(level, pos, be, src);
        if (b[1].startsWith("pshop:")) Ledger.note(village, "till/" + b[1], "1");
        p.displayClientMessage(Component.literal("The " + what + " rings up for " + src.name(level) + "."
            + (be.kiosk() ? " Customers serve themselves at it." : " Folk and players pay here.")).withStyle(ChatFormatting.GREEN), false);
    }

    /**
     * The town's shop and café each get a till on the counter beside their service counter (the service counter keeps its
     * ware on show), out of the town's own stock of brass and planks; nothing from nothing: a building with no counter
     * yet waits. Once a day, with the counters (ServicePoints.fitOut). The counter it stands on shows no ware (StoreFloor).
     */
    static void fitTill(ServerLevel level, Villages.Village v, Ledger.Building b, ServiceRole role) {
        if (role != ServiceRole.SHOP && role != ServiceRole.CAFE) return;
        String key = "town:" + role.id;
        ServicePoints.Point point = null;
        for (ServicePoints.Point q : ServicePoints.of(level, v.id(), role)) if (q.building().equals(b)) { point = q; break; }
        if (point == null) return;
        BlockPos sc = point.pos();
        for (int dx = -2; dx <= 2; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -2; dz <= 2; dz++) {
            if (level.getBlockEntity(sc.offset(dx, dy, dz)) instanceof TillBlockEntity be) {   // one already: bound to this trade
                if (!be.bound()) { be.bind(v.id(), key, null); be.setSelfServe(false); }
                known(be);
                return;
            }
        }
        List<BlockPos> counters = b.structure().equals(Store.STRUCTURE) ? Store.layout(b).counters() : Cafe.counters(b);
        List<BlockPos> spots = new ArrayList<>();
        for (Direction d : new Direction[]{ point.front().getClockWise(), point.front().getCounterClockWise() }) {
            BlockPos c = sc.relative(d);
            if (counters.contains(c) && ServicePoints.isCounter(level.getBlockState(c))) spots.add(c);
        }
        spots.add(sc);                                                       // no counter beside it: on the service counter
        for (BlockPos c : spots) {
            BlockPos at = c.above();
            if (!level.isLoaded(at)) continue;
            for (ItemFrame fr : level.getEntitiesOfClass(ItemFrame.class, new AABB(at))) {
                if (fr.getTags().contains("mca_stall") && !c.equals(sc)) fr.discard();   // the counter's ware makes room for the till
            }
            if (!level.getBlockState(at).isAir() || !level.getEntitiesOfClass(ItemFrame.class, new AABB(at)).isEmpty()) continue;
            if (!TownBlocks.mayBuild(level, v, at, b.anchor(), TownBlocks.Why.RENOVATE)) continue;
            level.setBlock(at, TillItems.TILL.get().defaultBlockState().setValue(TillBlock.FACING, point.front()), 3);
            if (level.getBlockEntity(at) instanceof TillBlockEntity be) {
                be.bind(v.id(), key, null);
                be.setSelfServe(false);
                known(be);
            }
            return;
        }
    }

    /** The town's till for this trade by its service counter, or null. */
    @Nullable
    public static BlockPos townTill(UUID village, ServiceRole role, BlockPos near) {
        List<BlockPos> t = tillsOf(village, "town:" + role.id, near);
        return !t.isEmpty() && t.get(0).distSqr(near) <= 3 * 3 ? t.get(0) : null;
    }

    /** Is there a till on this counter (it shows no ware: StoreFloor.dress)? */
    public static boolean tillOn(ServerLevel level, BlockPos counter) {
        return level.getBlockEntity(counter.above()) instanceof TillBlockEntity;
    }

    /** A player's shop gets its till on its counter, once (its owner may pick it up and set it elsewhere after). */
    static void fitShop(ServerLevel level, PlayerShops.Shop s) {
        String key = "pshop:" + s.key();
        if ("1".equals(Ledger.note(s.village, "till/" + key)) || !tillsOf(s.village, key, s.anchor).isEmpty()) return;
        ShopNeeds.Survey sv = PlayerShops.survey(level, s);
        for (BlockPos c : sv.counters) {
            BlockPos at = c.above();
            if (!level.isLoaded(at) || !level.getBlockState(at).isAir()) continue;
            BlockState cs = level.getBlockState(c);
            Direction front = cs.hasProperty(com.jrpetty.mcassistant.block.ShopCounterBlock.FACING)
                ? cs.getValue(com.jrpetty.mcassistant.block.ShopCounterBlock.FACING) : Direction.NORTH;
            level.setBlock(at, TillItems.TILL.get().defaultBlockState().setValue(TillBlock.FACING, front), 3);
            if (level.getBlockEntity(at) instanceof TillBlockEntity be) {
                be.bind(s.village, key, s.owner);
                be.setSelfServe(true);
                known(be);
                Ledger.note(s.village, "till/" + key, "1");
            }
            return;
        }
    }

    /** A firm of the town's folk gets a kiosk beside its Core when the Core is first set (once: its owner may move it). Its
     *  hands serve at it when they're by; with nobody by, customers serve themselves. A player's firm sets its own till. */
    static void fitFirm(ServerLevel level, UUID village, String key, BlockPos core) {
        if ("1".equals(Ledger.note(village, "till/" + key))) return;
        TillShops.Source src = TillShops.of(level, village, key);
        if (!(src instanceof TillShops.Firm f) || f.b.owners.stream().anyMatch(s -> s.holder() == Business.Holder.PLAYER)) return;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos at = core.relative(d);
            if (!level.isLoaded(at) || !level.getBlockState(at).isAir() || !level.getBlockState(at.above()).isAir()) continue;
            if (!level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) continue;
            Villages.Village v = Villages.get(village);
            if (v == null || !TownBlocks.mayPlace(level, v, at, TownBlocks.Why.RENOVATE)) continue;
            level.setBlock(at, TillItems.KIOSK.get().defaultBlockState().setValue(TillBlock.FACING, d), 3);
            if (level.getBlockEntity(at) instanceof TillBlockEntity be) {
                be.bind(village, key, null);
                be.setSelfServe(true);
                known(be);
                Ledger.note(village, "till/" + key, "1");
            }
            return;
        }
    }

    // ------------------------------------------------------------------ a player's screen

    static final class Session {
        final UUID village;
        final String key;
        @Nullable final BlockPos till;
        final BlockPos near;
        final boolean kiosk;
        boolean self;
        String tab = "buy";
        final Map<String, Integer> basket = new LinkedHashMap<>();
        final Map<String, Integer> sell = new LinkedHashMap<>();
        int lastNonce = -1;
        String message = "", voice = "";
        boolean ok = true;
        int lastPrint = Integer.MIN_VALUE;
        long lastSent;
        boolean first;

        Session(UUID village, String key, @Nullable BlockPos till, BlockPos near, boolean kiosk) {
            this.village = village;
            this.key = key;
            this.till = till;
            this.near = near;
            this.kiosk = kiosk;
            this.self = kiosk;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    /** Right-click on a till or a kiosk. */
    public static void openAt(ServerPlayer p, BlockPos pos) {
        ServerLevel level = p.serverLevel();
        if (!(level.getBlockEntity(pos) instanceof TillBlockEntity be)) return;
        if (!be.bound()) {
            String[] b = TillShops.resolve(level, pos);
            if (b != null && b[1].startsWith("town:")) {
                be.bind(UUID.fromString(b[0]), b[1], null);
                be.setSelfServe(be.kiosk());
                known(be);
            }
        }
        TillShops.Source src = be.bound() ? TillShops.of(level, be.village(), be.key()) : null;
        if (src == null) {
            p.displayClientMessage(Component.literal(be.bound() ? "This till's business has shut. It rings for nobody now."
                : "A till: set it at the counter of your shop, or a business of yours, and customers pay at it.").withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        known(be);
        open(p, new Session(be.village(), be.key(), pos.immutable(), pos.immutable(), be.kiosk()));
    }

    /** A customer at a shelf (or a price board, or the basket stand): the business's checkout, with that ware in the basket. */
    public static boolean browse(ServerPlayer p, UUID village, String key, BlockPos at, ItemStack ware) {
        ServerLevel level = p.serverLevel();
        TillShops.Source src = TillShops.of(level, village, key);
        if (src == null) return false;
        List<BlockPos> tills = tillsOf(village, key, at);
        BlockPos till = !tills.isEmpty() && tills.get(0).distSqr(at) <= 20 * 20 ? tills.get(0) : null;
        boolean kiosk = till != null && KNOWN.get(till) != null && KNOWN.get(till).kiosk();
        Session s = new Session(village, key, till, at.immutable(), kiosk);
        if (!ware.isEmpty()) {
            for (TillShops.Ware w : src.wares(level, p)) {
                if (ItemStack.isSameItemSameComponents(w.sample(), ware.copyWithCount(1)) && w.stock() > 0) { s.basket.put(w.id(), Math.min(w.lot(), w.stock())); break; }
            }
        }
        open(p, s);
        return true;
    }

    /** A player right-clicked a folk: if it is on duty behind a till, the checkout. */
    public static boolean keeperClicked(VillageFolkEntity f, ServerPlayer p) {
        if (f.ownerId() == null || KNOWN.isEmpty()) return false;
        ServerLevel level = p.serverLevel();
        for (Map.Entry<BlockPos, Known> e : KNOWN.entrySet()) {
            if (!e.getValue().village().equals(f.ownerId()) || e.getValue().kiosk()) continue;
            BlockPos t = e.getKey();
            if (f.distanceToSqr(t.getX() + 0.5, t.getY(), t.getZ() + 0.5) > 3.5 * 3.5) continue;
            TillShops.Source src = TillShops.of(level, e.getValue().village(), e.getValue().key());
            if (src == null) continue;
            TillShops.Staff st = src.staff(level, t);
            if (st.keeper() != f) continue;
            openAt(p, t);
            return true;
        }
        return false;
    }

    private static void open(ServerPlayer p, Session s) {
        CompoundTag pd = p.getPersistentData();
        if (!pd.getBoolean(HINT)) {
            pd.putBoolean(HINT, true);
            s.first = true;
            p.sendSystemMessage(Component.literal("The till: + and − for how many (shift: ten), the total on the right, then Pay. That's it.")
                .withStyle(ChatFormatting.GOLD));
        }
        SESSIONS.put(p.getUUID(), s);
        p.serverLevel().playSound(null, s.near, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.25F, 1.8F);
        send(p, s, true);
    }

    public static void closed(ServerPlayer p) {
        SESSIONS.remove(p.getUUID());
    }

    /** The screen, now: null when the player has walked off, or the business is gone. */
    @Nullable
    private static TillShops.Source live(ServerPlayer p, Session s) {
        if (!p.isAlive()) return null;
        if (p.distanceToSqr(s.near.getX() + 0.5, s.near.getY() + 0.5, s.near.getZ() + 0.5) > REACH * REACH
            && (s.till == null || p.distanceToSqr(s.till.getX() + 0.5, s.till.getY() + 0.5, s.till.getZ() + 0.5) > REACH * REACH)) return null;
        if (s.till != null && !(p.serverLevel().getBlockEntity(s.till) instanceof TillBlockEntity)) return null;
        return TillShops.of(p.serverLevel(), s.village, s.key);
    }

    private static void send(ServerPlayer p, Session s, boolean force) {
        TillShops.Source src = live(p, s);
        if (src == null) {
            SESSIONS.remove(p.getUUID());
            CompoundTag gone = new CompoundTag();
            gone.putBoolean("gone", true);
            deliver(p, gone);
            return;
        }
        CompoundTag t = screen(p, s, src);
        int print = t.hashCode();
        long now = p.serverLevel().getGameTime();
        if (!force && print == s.lastPrint && now - s.lastSent < 200) return;
        s.lastPrint = print;
        s.lastSent = now;
        deliver(p, t);
    }

    private static void deliver(ServerPlayer p, CompoundTag t) {
        try {
            PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.TillScreenPayload(t));
        } catch (RuntimeException e) {
            // a test's stand-in player has no connection: the screen is read off screenForTests instead
        }
    }

    /** What may be done here now: buy at all, and why not. */
    record Can(boolean serve, boolean offerSelf, String why) {}

    static Can can(ServerLevel level, Session s, TillShops.Source src, TillShops.Staff st) {
        if (!st.open()) return new Can(false, false, st.words());
        if (s.self || st.staffed()) return new Can(true, false, "");
        boolean allows = s.till != null && level.getBlockEntity(s.till) instanceof TillBlockEntity be && be.selfServe();
        if (!allows && s.till != null) {
            for (BlockPos k : tillsOf(s.village, s.key, s.till)) {
                Known kn = KNOWN.get(k);
                if (kn != null && kn.kiosk() && k.distSqr(s.till) <= 24 * 24) { allows = true; break; }
            }
        }
        return new Can(false, allows, st.words() + (allows ? " Self-service is allowed." : ""));
    }

    /** The checkout as the screen shows it. */
    static CompoundTag screen(ServerPlayer p, Session s, TillShops.Source src) {
        ServerLevel level = p.serverLevel();
        var reg = level.registryAccess();
        TillShops.Staff st = src.staff(level, s.till);
        Can can = can(level, s, src, st);
        boolean owner = src.ownedBy(p.getUUID());
        TillBlockEntity be = s.till != null && level.getBlockEntity(s.till) instanceof TillBlockEntity b ? b : null;
        CompoundTag t = new CompoundTag();
        t.putString("name", src.name(level));
        t.putString("town", Villages.name(s.village));
        VillageFolkEntity k = st.keeper();
        t.putString("keeper", k == null ? "" : k.displayNameCap());
        t.putInt("keeperId", k == null ? -1 : k.getId());
        t.putBoolean("staffed", st.staffed());
        t.putBoolean("open", st.open());
        t.putString("status", s.self && !st.staffed() && st.open() ? (s.kiosk ? "Self-service kiosk." : "Serving yourself. The till trusts you.") : st.words());
        t.putBoolean("kiosk", s.kiosk);
        t.putBoolean("self", s.self);
        t.putBoolean("offerSelf", can.offerSelf() && !s.self);
        t.putBoolean("canServe", can.serve());
        t.putString("tab", s.tab);
        t.putInt("nonce", s.lastNonce);
        t.putInt("coins", Market.coinsHeld(p));
        t.putInt("bank", Bank.balanceOf(s.village, p.getUUID()));
        t.putBoolean("owner", owner);
        t.putBoolean("selfAllowed", be != null && be.selfServe());
        t.putBoolean("first", s.first);
        t.putInt("confirmOver", CONFIRM_OVER);
        t.putString("message", s.message);
        t.putBoolean("ok", s.ok);
        t.putString("voice", s.voice);
        t.putString("trait", k == null ? "" : TillVoice.trait(k));
        ListTag offers = new ListTag();
        for (String o : src.offers(level, p)) offers.add(net.minecraft.nbt.StringTag.valueOf(o));
        t.put("offers", offers);
        t.putBoolean("haggles", src instanceof TillShops.Town && k != null && st.staffed());
        t.putBoolean("counter", src instanceof TillShops.Town town0 && town0.role == ServiceRole.SHOP && !s.kiosk);   // work, orders, wanted
        // What's for sale.
        List<TillShops.Ware> wares = visible(src.wares(level, p), s, be, owner);
        ListTag items = new ListTag();
        for (TillShops.Ware w : wares) {
            CompoundTag e = new CompoundTag();
            e.putString("id", w.id());
            e.put("item", w.sample().save(reg));
            e.putString("name", Goods.name(w.sample()));
            e.putInt("price", w.price());
            e.putInt("lot", w.lot());
            e.putString("priceWords", w.priceWords());
            e.putInt("stock", w.stock());
            e.putString("note", w.note());
            if (owner && be != null && be.kiosk()) e.putBoolean("onKiosk", be.kioskWares().isEmpty() || be.kioskWares().contains(w.id()));
            items.add(e);
        }
        t.put("items", items);
        // The basket, priced by the business (the screen never prices anything itself).
        s.basket.entrySet().removeIf(e -> e.getValue() <= 0);
        TillShops.Quote q = src.quote(level, p, s.basket, wares);
        CompoundTag qt = new CompoundTag();
        ListTag ql = new ListTag();
        for (Map.Entry<String, int[]> e : q.lines().entrySet()) {
            CompoundTag l = new CompoundTag();
            l.putString("id", e.getKey());
            l.putInt("n", e.getValue()[0]);
            l.putInt("price", e.getValue()[1]);
            ql.add(l);
        }
        qt.put("lines", ql);
        ListTag adj = new ListTag();
        for (int i = 0; i < q.labels().size(); i++) {
            CompoundTag a = new CompoundTag();
            a.putString("label", q.labels().get(i));
            a.putInt("amount", q.amounts().get(i));
            adj.add(a);
        }
        qt.put("adjust", adj);
        qt.putInt("subtotal", q.subtotal());
        qt.putInt("total", q.total());
        t.put("quote", qt);
        t.putString("why", whyNot(p, s, can, q, src));
        // Selling back.
        List<CompoundTag> buys = src.buys(level, p, s.till);
        ListTag sell = new ListTag();
        int sellTotal = 0;
        for (CompoundTag b : buys) {
            sell.add(b);
            int lots = s.sell.getOrDefault(b.getString("id"), 0);
            if (lots > 0 && b.getInt("bid") > 0) sellTotal += lots * b.getInt("bid");
        }
        t.put("sell", sell);
        t.putBoolean("buys", !buys.isEmpty() || src instanceof TillShops.Town town && Buyers.buys(town.role));
        CompoundTag sb = new CompoundTag();
        for (Map.Entry<String, Integer> e : s.sell.entrySet()) if (e.getValue() > 0) sb.putInt(e.getKey(), e.getValue());
        t.put("sellBasket", sb);
        t.putInt("sellTotal", sellTotal);
        return t;
    }

    /** The wares the screen shows: a kiosk's only those its owner set (its owner sees them all, to choose). */
    private static List<TillShops.Ware> visible(List<TillShops.Ware> all, Session s, @Nullable TillBlockEntity be, boolean owner) {
        if (be == null || !be.kiosk() || be.kioskWares().isEmpty() || owner) return all;
        List<TillShops.Ware> out = new ArrayList<>();
        for (TillShops.Ware w : all) if (be.kioskWares().contains(w.id())) out.add(w);
        return out;
    }

    /** Why Pay is resting, or "" when it can go. */
    static String whyNot(ServerPlayer p, Session s, Can can, TillShops.Quote q, TillShops.Source src) {
        if (q.lines().isEmpty()) return "Add something with +.";
        if (src.ownedBy(p.getUUID())) return "It's your own stock.";
        if (!can.serve()) return can.offerSelf() ? "Nobody at the till. Serve yourself?" : can.why();
        int held = Market.coinsHeld(p);
        if (held < q.total()) {
            int bank = Bank.balanceOf(s.village, p.getUUID());
            return "Short by " + (q.total() - held) + "c" + (bank >= q.total() - held ? ": draw it at the bank." : ".");
        }
        return "";
    }

    // ------------------------------------------------------------------ the buttons

    public static void act(ServerPlayer p, String action, CompoundTag args, int nonce) {
        Session s = SESSIONS.get(p.getUUID());
        if (s == null) return;
        if (action.equals("close")) { closed(p); return; }
        if (nonce <= s.lastNonce) return;                                   // answered already: never twice
        s.lastNonce = nonce;
        TillShops.Source src = live(p, s);
        if (src == null) { send(p, s, true); return; }
        ServerLevel level = p.serverLevel();
        s.first = false;
        switch (action) {
            case "set" -> {
                String id = args.getString("id");
                int n = Math.max(0, Math.min(MOST, args.getInt("n")));
                if (id.length() > 160) break;
                if (n == 0) s.basket.remove(id);
                else if (s.basket.size() < 24 || s.basket.containsKey(id)) s.basket.put(id, n);
                s.message = "";
            }
            case "sset" -> {
                String id = args.getString("id");
                int n = Math.max(0, Math.min(MOST, args.getInt("n")));
                if (n == 0) s.sell.remove(id);
                else if (id.length() <= 160) s.sell.put(id, n);
                s.message = "";
            }
            case "clear" -> { s.basket.clear(); s.sell.clear(); s.message = ""; }
            case "tab" -> { s.tab = args.getString("tab").equals("sell") ? "sell" : "buy"; s.message = ""; }
            case "self" -> {
                TillShops.Staff st = src.staff(level, s.till);
                if (can(level, s, src, st).offerSelf()) { s.self = true; s.message = "Serving yourself. Ring it up honestly."; s.ok = true; }
            }
            case "pay" -> pay(p, s, src);
            case "sell" -> sell(p, s, src);
            case "haggle" -> {
                TillShops.Staff st = src.staff(level, s.till);
                if (src instanceof TillShops.Town && st.keeper() != null && st.staffed()) {
                    String said = Dealings.haggle(st.keeper(), p);
                    FolkTalk.speak(st.keeper(), said);
                    s.voice = st.keeper().displayNameCap() + ": \"" + said + "\"";
                    s.ok = said.contains(" off");
                    s.message = "";
                }
            }
            case "counter" -> {                                             // the rest of the counter's business: the old screen
                if (src instanceof TillShops.Town town) {
                    ServicePoints.Point point = town.point(level, s.till);
                    if (point != null) {
                        closed(p);
                        ServicePoints.openAt(p, point.pos(), "sell");
                        return;
                    }
                }
            }
            case "talk" -> {
                TillShops.Staff st = src.staff(level, s.till);
                if (st.keeper() != null) {
                    closed(p);
                    FolkTalk.open(st.keeper(), p);
                    return;
                }
            }
            case "ownerSelf" -> {
                if (src.ownedBy(p.getUUID()) && s.till != null && level.getBlockEntity(s.till) instanceof TillBlockEntity be) {
                    be.setSelfServe(!be.selfServe());
                    s.message = be.selfServe() ? "Customers may serve themselves when nobody's in." : "Staffed only: nobody's in, nobody buys.";
                    s.ok = true;
                }
            }
            case "kioskWare" -> {
                if (src.ownedBy(p.getUUID()) && s.till != null && level.getBlockEntity(s.till) instanceof TillBlockEntity be && be.kiosk()) {
                    String id = args.getString("id");
                    if (be.kioskWares().isEmpty()) {                          // all of them till now: all but this one
                        for (TillShops.Ware w : src.wares(level, p)) if (!w.id().equals(id)) be.toggleKioskWare(w.id());
                    } else {
                        be.toggleKioskWare(id);
                    }
                }
            }
            default -> { }
        }
        send(p, s, true);
    }

    private static void pay(ServerPlayer p, Session s, TillShops.Source src) {
        ServerLevel level = p.serverLevel();
        TillShops.Staff st = src.staff(level, s.till);
        Can can = can(level, s, src, st);
        if (!can.serve()) { s.message = can.why(); s.ok = false; return; }
        if (s.basket.isEmpty()) { s.message = "Your basket is empty."; s.ok = false; return; }
        // A kiosk sells only what its owner put on it.
        if (s.till != null && level.getBlockEntity(s.till) instanceof TillBlockEntity be && be.kiosk() && !be.kioskWares().isEmpty()) {
            s.basket.keySet().removeIf(id -> !be.kioskWares().contains(id));
        }
        TillShops.Outcome o = src.buy(level, p, new LinkedHashMap<>(s.basket), s.till, s.self && !st.staffed());
        s.message = o.message();
        s.ok = o.ok();
        if (!o.ok()) return;
        s.basket.clear();
        VillageFolkEntity k = st.staffed() ? st.keeper() : null;
        s.voice = k != null ? k.displayNameCap() + ": \"" + TillVoice.paid(level.getRandom(), k) + "\"" : TillVoice.selfPaid(level.getRandom(), s.kiosk);
        if (k != null) FolkTalk.speak(k, s.voice.substring(s.voice.indexOf('"') + 1, s.voice.length() - 1));
        ring(level, s.till != null ? s.till : s.near, p.getName().getString(), k, p);
    }

    private static void sell(ServerPlayer p, Session s, TillShops.Source src) {
        ServerLevel level = p.serverLevel();
        TillShops.Staff st = src.staff(level, s.till);
        if (!st.staffed()) { s.message = "Nobody at the till to buy it off you."; s.ok = false; return; }
        if (s.sell.isEmpty()) { s.message = "Nothing to sell: + on what you carry."; s.ok = false; return; }
        int got = 0;
        List<String> said = new ArrayList<>();
        boolean any = false;
        Map<String, Integer> lots = new HashMap<>();
        for (CompoundTag b : src.buys(level, p, s.till)) lots.put(b.getString("id"), Math.max(1, b.getInt("lot")));
        for (Map.Entry<String, Integer> e : new ArrayList<>(s.sell.entrySet())) {
            TillShops.Outcome o = src.sell(level, p, e.getKey(), e.getValue() * lots.getOrDefault(e.getKey(), 1), s.till);
            if (o.ok()) { any = true; got += o.coins(); }
            said.add(o.message());
        }
        s.sell.clear();
        s.ok = any;
        s.message = any ? "Sold for " + TillShops.coins(got) + "." : String.join(" ", said);
        if (any) {
            VillageFolkEntity k = st.keeper();
            if (k != null) s.voice = k.displayNameCap() + ": \"" + TillVoice.bought(level.getRandom()) + "\"";
            ring(level, s.till != null ? s.till : s.near, p.getName().getString(), k, p);
        }
    }

    /** The till rings: its ding and the coins, "Serving: Hazel" over it, the keeper's hand. */
    static void ring(ServerLevel level, BlockPos at, String who, @Nullable VillageFolkEntity keeper, @Nullable net.minecraft.world.entity.Entity customer) {
        level.playSound(null, at, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 0.7F, 1.9F);
        level.playSound(null, at, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.5F, 1.6F);
        level.playSound(null, at, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 0.35F, 1.3F);
        if (level.getBlockEntity(at) instanceof TillBlockEntity be) be.serve(who, level.getGameTime() + 80);
        if (keeper != null) {
            keeper.swing(InteractionHand.MAIN_HAND);
            if (customer != null) keeper.getLookControl().setLookAt(customer, 30.0F, 30.0F);
        }
    }

    // ------------------------------------------------------------------ the folk at the same till

    /**
     * A folk at a till buys {@code n} of this: it must be standing at it (the caller walks it there; nobody is moved), and
     * the business sells to it as to anybody, through its own sale (TillShops). The till rings and says who it's serving.
     * At a kiosk nobody watches, a dishonest folk may now and then help itself to one more (Mischief: a theft like any other).
     */
    @Nullable
    public static ItemStack folkCheckout(ServerLevel level, BlockPos till, VillageFolkEntity f, ItemStack one, int n) {
        if (!(level.getBlockEntity(till) instanceof TillBlockEntity be) || !be.bound()) return null;
        if (f.distanceToSqr(till.getX() + 0.5, till.getY(), till.getZ() + 0.5) > 3.5 * 3.5) return null;
        TillShops.Source src = TillShops.of(level, be.village(), be.key());
        if (src == null) return null;
        TillShops.Staff st = src.staff(level, till);
        if (!st.open() || !(st.staffed() || be.kiosk() || be.selfServe())) return null;
        if (be.kiosk() && !be.kioskWares().isEmpty()) {
            String id = src instanceof TillShops.Town ? Goods.lineId(one) : Stockroom.key(one);
            if (!be.kioskWares().contains(id) && !be.kioskWares().contains(PlayerStalls.key(one))) return null;
        }
        f.getLookControl().setLookAt(till.getX() + 0.5, till.getY() + 0.5, till.getZ() + 0.5);
        ItemStack got = src.folkBuy(level, f, one, n);
        if (got == null) return null;
        VillageFolkEntity k = st.staffed() ? st.keeper() : null;
        ring(level, till, f.displayNameCap(), k, f);
        if (k != null && level.getRandom().nextInt(3) == 0) FolkTalk.speak(k, TillVoice.folkPaid(level.getRandom(), f));
        if (k == null && be.kiosk()) Fittings.maybeLift(level, be, f);
        return got;
    }

    /** A sale made at the business's counter some other way (the town's counter sale, a customer at a player's shop): its
     *  till rings and says who it served. */
    public static void rang(ServerLevel level, UUID village, String key, BlockPos near, String who, @Nullable VillageFolkEntity keeper) {
        if (KNOWN.isEmpty()) return;
        List<BlockPos> tills = tillsOf(village, key, near);
        if (tills.isEmpty() || tills.get(0).distSqr(near) > 12 * 12) return;
        ring(level, tills.get(0), who, keeper, null);
    }

    // ------------------------------------------------------------------ the sign over it

    static void refreshSign(ServerLevel level, BlockPos pos, TillBlockEntity be, TillShops.Source src) {
        TillShops.Staff st = src.staff(level, pos);
        String status = !st.open() ? st.words() : st.staffed() ? (st.keeper() != null ? st.keeper().displayNameCap() + " at the till" : "Open")
            : be.kiosk() ? "Self-service" : be.selfServe() ? "Self-service: nobody in" : st.words();
        be.show(src.name(level), status, st.open() && (st.staffed() || be.kiosk() || be.selfServe()));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (!SESSIONS.isEmpty() && tick % 20 == 0) {
            for (Map.Entry<UUID, Session> e : SESSIONS.entrySet()) {
                ServerPlayer p = event.getServer().getPlayerList().getPlayer(e.getKey());
                if (p == null) { SESSIONS.remove(e.getKey()); continue; }
                send(p, e.getValue(), false);
            }
        }
        if (tick % 40 == 7 && !KNOWN.isEmpty()) {
            ServerLevel level = event.getServer().overworld();
            for (Map.Entry<BlockPos, Known> e : KNOWN.entrySet()) {
                BlockPos pos = e.getKey();
                if (!level.isLoaded(pos) || level.getNearestPlayer(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 20, false) == null) continue;
                if (!(level.getBlockEntity(pos) instanceof TillBlockEntity be)) { KNOWN.remove(pos); continue; }
                TillShops.Source src = TillShops.of(level, e.getValue().village(), e.getValue().key());
                if (src != null) com.jrpetty.mcassistant.Guard.run("till sign", () -> refreshSign(level, pos, be, src));
            }
        }
    }

    // ------------------------------------------------------------------ tests

    @Nullable
    public static CompoundTag screenForTests(ServerPlayer p) {
        Session s = SESSIONS.get(p.getUUID());
        if (s == null) return null;
        TillShops.Source src = live(p, s);
        return src == null ? null : screen(p, s, src);
    }

    public static int nextNonceForTests(ServerPlayer p) {
        Session s = SESSIONS.get(p.getUUID());
        return s == null ? 0 : s.lastNonce + 1;
    }

    public static void pressForTests(ServerPlayer p, String action, CompoundTag args) {
        act(p, action, args, nextNonceForTests(p));
    }

    public static String messageForTests(ServerPlayer p) {
        Session s = SESSIONS.get(p.getUUID());
        return s == null ? "" : s.message;
    }

    /** Tests: a till set here and bound to this business (as a town or an owner would). */
    public static void bindForTests(ServerLevel level, BlockPos pos, UUID village, String key, @Nullable UUID owner, boolean self) {
        if (level.getBlockEntity(pos) instanceof TillBlockEntity be) {
            be.bind(village, key, owner);
            be.setSelfServe(self);
            known(be);
        }
    }

    public static String keyOfShopForTests(PlayerShops.Shop s) {
        return "pshop:" + s.key();
    }
}
