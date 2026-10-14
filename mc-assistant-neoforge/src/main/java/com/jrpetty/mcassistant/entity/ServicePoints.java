package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [shops-homes] Service points: the one way a town serves anybody, player or folk, at its shop, store, café, tavern,
 * bank, smithy and tailor's (ServiceRole).
 * <ul>
 * <li><b>The point.</b> A service counter in the trade's building (fitted in place of the counter's first cask, out of
 *     the stores: fitOut), or the trade's own bench (the bank's ledger desk, the smith's anvil, the tailor's loom).
 *     The keeper stands behind it; the queue forms in front.</li>
 * <li><b>Hours and the keeper.</b> Each trade keeps hours (ServiceRole.hours), and serves only with its keeper in
 *     (at work, awake, near the point); the tavern's bar serves itself with nobody behind it. Closed, it says when
 *     it opens next.</li>
 * <li><b>Players.</b> Right-click the point (or the keeper, or the trade's talk option) and its screen opens: the
 *     keeper and hours in the header, the trade's own panel (its Desk) in the body. Every button comes back here and
 *     is checked on the server (still near, still open, a fresh request: no double-buying on a double click or lag)
 *     before the desk moves a thing or a coin; the screen is kept up to date while it is open.</li>
 * <li><b>Folk</b> walk up, queue and are served a few seconds each by the keeper, a word on each side
 *     (ServiceQueue), and buy through the same desk at the same prices.</li>
 * </ul>
 */
public final class ServicePoints {

    private ServicePoints() {}

    /** How near a player must be to the point (or the keeper it opened on) to be served. */
    static final double REACH = 6.0, KEEPER_REACH = 8.0;
    /** How near the point the keeper must be to be "in". */
    static final double KEEPER_IN = 24.0;
    /** How often an open screen is looked at again for changes (ticks), and sent regardless. */
    static final int REFRESH = 20, RESEND = 200;
    /** How long the town's points are kept before they are looked for again (ticks). */
    static final long POINTS_FOR = 200L;

    // ------------------------------------------------------------------ the desks

    /** A trade's business at its point: the body of its screen, and what each of its buttons does. */
    public interface Desk {
        /** The panel's kind for the client ("shop", or "desk" for the plain rows of buttons). */
        String kind(ServiceRole role);

        /** The body of the screen for this visitor (role-specific; see the client's panels). */
        CompoundTag body(Visit visit, String tab);

        /** Do what was asked: validated already for reach, hours and freshness. */
        Result act(Visit visit, String action, CompoundTag args);

        /** A cheap fingerprint of what the body shows, to send it again only when it changed. */
        default int fingerprint(Visit visit) {
            return 0;
        }
    }

    /** What a button came to: whether it went through, what to tell the player, and whether coin changed hands. */
    public record Result(boolean ok, String message, boolean coin) {
        public static Result no(String why) { return new Result(false, why, false); }

        public static Result done(String what) { return new Result(true, what, true); }

        public static Result said(String what) { return new Result(true, what, false); }
    }

    /** Somebody at a point: where, which trade, which town, who keeps it, the player (null for a folk), and what the
     *  screen keeps between requests (a shop's basket), on the server. */
    public record Visit(ServerLevel level, Villages.Village village, ServiceRole role, @Nullable Point point,
                        @Nullable ServerPlayer player, @Nullable VillageFolkEntity keeper, CompoundTag state) {}

    /** A point: its trade, its town, where it is, which side the customers stand on, where the keeper stands. */
    public record Point(ServiceRole role, UUID village, BlockPos pos, Direction front, @Nullable BlockPos stand,
                        Ledger.Building building) {

        /** The i-th place in its queue (0 at the counter): straight out in front, then to the side. */
        public BlockPos queueSpot(ServerLevel level, int i) {
            BlockPos ahead = pos.relative(front, 1 + i);
            if (i == 0 || StoreFloor.standable(level, ahead)) return ahead;
            BlockPos side = pos.relative(front).relative(front.getClockWise(), i);
            return StoreFloor.standable(level, side) ? side : pos.relative(front).relative(front.getCounterClockWise(), i);
        }
    }

    // ------------------------------------------------------------------ finding them

    private record Found(long at, List<Point> points) {}

    private static final Map<String, Found> POINTS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        POINTS.clear();
        SESSIONS.clear();
        FITTED.clear();
        ServiceQueue.resetForTests();
        HOURS_OFF_FOR_TESTS = false;
    }

    /** Tests: every trade open at any hour (a test's clock is wherever it is). */
    public static boolean HOURS_OFF_FOR_TESTS = false;

    /** The points of this trade in this town, the grandest building's first (looked for again every ten seconds). */
    public static List<Point> of(ServerLevel level, UUID village, ServiceRole role) {
        String key = village + "/" + role.id;
        long now = level.getGameTime();
        Found f = POINTS.get(key);
        if (f != null && now - f.at() < POINTS_FOR && now >= f.at()) return f.points();
        List<Point> out = new ArrayList<>();
        for (String structure : role.structures) {
            for (Ledger.Building b : Ledger.buildings(village)) {
                if (!b.structure().equals(structure) || !level.isLoaded(b.anchor())) continue;
                BlockPos door = TownLife.fittings(b).door();
                for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
                    BlockState s = level.getBlockState(p.pos());
                    if (!role.point.is(s)) continue;
                    Direction front = s.hasProperty(com.jrpetty.mcassistant.block.ServiceCounterBlock.FACING)
                        && s.getBlock() instanceof com.jrpetty.mcassistant.block.ServiceCounterBlock
                        ? s.getValue(com.jrpetty.mcassistant.block.ServiceCounterBlock.FACING) : StoreFloor.front(p.pos(), door);
                    out.add(new Point(role, village, p.pos().immutable(), front, standBehind(level, p.pos(), front), b));
                }
            }
        }
        List<Point> kept = List.copyOf(out);
        POINTS.put(key, new Found(now, kept));
        return kept;
    }

    /** The point nearest here of this trade in this town, or null. */
    @Nullable
    public static Point nearest(ServerLevel level, UUID village, ServiceRole role, BlockPos near) {
        Point best = null;
        double bestD = Double.MAX_VALUE;
        for (Point p : of(level, village, role)) {
            double d = p.pos().distSqr(near);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    /** The point at this block, if it is one (a service counter in a trade's building, the bank's desk...). */
    @Nullable
    public static Point at(ServerLevel level, BlockPos pos) {
        Villages.Village v = Villages.nearest(level, pos, 128);
        if (v == null) return null;
        BlockState s = level.getBlockState(pos);
        for (ServiceRole role : ServiceRole.values()) {
            if (!role.point.is(s)) continue;
            for (Point p : of(level, v.id(), role)) if (p.pos().equals(pos)) return p;
        }
        return null;
    }

    /** Where the keeper stands: behind the point, else beside it. */
    @Nullable
    private static BlockPos standBehind(ServerLevel level, BlockPos at, Direction front) {
        for (BlockPos p : new BlockPos[]{ at.relative(front.getOpposite()), at.relative(front.getClockWise()), at.relative(front.getCounterClockWise()) }) {
            if (StoreFloor.standable(level, p)) return p.immutable();
        }
        return null;
    }

    // ------------------------------------------------------------------ open or shut

    /** Who keeps this trade now: the assistant at the shop's counter or its keeper, the cook, the banker, the smith... */
    @Nullable
    public static VillageFolkEntity keeper(ServerLevel level, UUID village, ServiceRole role, @Nullable Point point) {
        VillageFolkEntity best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos near = point != null ? point.pos() : null;
        VillageFolkEntity hosting = Hospitality.keeperFor(village, role);      // [dining] the barkeep at its bar, the host at its counter
        if (hosting != null && usable(hosting)) return hosting;
        if (role == ServiceRole.SHOP) {
            VillageFolkEntity k = Workshop.keeper(village);
            for (VillageFolkEntity a : StoreStaff.in(village, ShopRoles.Role.ASSISTANT)) {
                if (!usable(a)) continue;
                double d = near == null ? 0 : a.distanceToSqr(near.getX() + 0.5, near.getY(), near.getZ() + 0.5);
                if (d < bestD) { bestD = d; best = a; }
            }
            if (k != null && usable(k)) {
                double d = near == null ? 0 : k.distanceToSqr(near.getX() + 0.5, near.getY(), near.getZ() + 0.5);
                if (best == null || d < bestD) best = k;
            }
            return best;
        }
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != role.trade || !usable(f)) continue;
            double d = near == null ? 0 : f.distanceToSqr(near.getX() + 0.5, near.getY(), near.getZ() + 0.5);
            if (d < bestD) { bestD = d; best = f; }
        }
        return best;
    }

    private static boolean usable(VillageFolkEntity f) {
        return f.isAlive() && !f.isBaby();
    }

    /** How a trade stands now: open or shut, who is in, and in words. */
    public record Status(boolean open, @Nullable VillageFolkEntity keeper, String words) {}

    /** Is this trade open at this point now, and who serves? */
    public static Status status(ServerLevel level, UUID village, ServiceRole role, @Nullable Point point) {
        VillageFolkEntity keeper = keeper(level, village, role, point);
        long t = level.getDayTime() % 24000L;
        String name = keeper == null ? "" : keeper.displayNameCap();
        if (!HOURS_OFF_FOR_TESTS && !role.hours.isOpen(t)) {
            return new Status(false, keeper, "Closed. " + role.hours.nextOpening(t) + " (hours " + role.hours.words() + ").");
        }
        if (keeper == null) {
            if (role.selfServe()) return new Status(true, null, "Open. Nobody behind the bar: the coin goes in the jar.");
            return new Status(false, null, "Closed: the town has no " + role.keeperNoun + " just now.");
        }
        if (keeper.isSleeping()) return new Status(false, keeper, "Closed: " + name + " is asleep.");
        if (point != null && keeper.distanceToSqr(point.pos().getX() + 0.5, point.pos().getY(), point.pos().getZ() + 0.5) > KEEPER_IN * KEEPER_IN) {
            if (role.selfServe()) return new Status(true, keeper, "Open. " + name + " is out: the coin goes in the jar.");
            return new Status(false, keeper, name + " is away from the " + (role.point == ServiceRole.Point.COUNTER ? "counter" : role.title.toLowerCase(java.util.Locale.ROOT))
                + ". Back soon.");
        }
        return new Status(true, keeper, "Open. " + name + " serves (" + role.hours.words() + ").");
    }

    // ------------------------------------------------------------------ the player's screen

    /** A player's open screen: what it is of, what it was opened on, and the last request it answered. */
    static final class Session {
        final ServiceRole role;
        final UUID village;
        @Nullable final BlockPos point;
        @Nullable final UUID keeper;
        String tab;
        int lastNonce = -1;
        int lastPrint = Integer.MIN_VALUE;
        long lastSent;
        String message = "";
        boolean messageOk = true;
        /** The desk's own state for this screen (a shop's basket), kept on the server. */
        final CompoundTag state = new CompoundTag();
        /** [dining] The order screen of a dining room (DiningDesk), and where it was opened (a table, the menu board):
         *  the player must stay near that, not the counter. */
        boolean dining;
        @Nullable BlockPos near;

        Session(ServiceRole role, UUID village, @Nullable BlockPos point, @Nullable UUID keeper, String tab) {
            this.role = role;
            this.village = village;
            this.point = point;
            this.keeper = keeper;
            this.tab = tab;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    /** A player right-clicked a point: its screen. False if it is not one. */
    public static boolean openAt(ServerPlayer p, BlockPos pos) {
        return openAt(p, pos, "");
    }

    /** As openAt, on this tab ("" for the trade's first). */
    public static boolean openAt(ServerPlayer p, BlockPos pos, String tab) {
        if (!(p.level() instanceof ServerLevel level)) return false;
        Point point = at(level, pos);
        if (point == null) {
            p.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "A service counter: set it in a town's shop, café, tavern or store and it serves there."), true);
            return true;
        }
        open(p, new Session(point.role(), point.village(), point.pos(), null, tab.isEmpty() ? firstTab(point.role()) : tab));
        return true;
    }

    /** A player spoke to a keeper (its talk options, or a right-click with coin): its trade's screen, on it. */
    public static boolean openFor(ServerPlayer p, VillageFolkEntity keeper, ServiceRole role, String tab) {
        UUID village = keeper.ownerId();
        if (village == null || !(p.level() instanceof ServerLevel level)) return false;
        Point point = nearest(level, village, role, keeper.blockPosition());
        if (point != null && point.pos().distSqr(p.blockPosition()) > 32 * 32) point = null;
        open(p, new Session(role, village, point == null ? null : point.pos(), keeper.getUUID(), tab.isEmpty() ? firstTab(role) : tab));
        return true;
    }

    /** [dining] A player at a table (or the menu board) in a café, a tavern or a restaurant: the order screen for it. */
    public static void openDining(ServerPlayer p, DiningPlaces.Place place, @Nullable DiningPlaces.Table table) {
        if (!(p.level() instanceof ServerLevel level)) return;
        Point point = nearest(level, place.village, place.kind.role, place.centre());
        if (point != null && !point.building().equals(place.building)) point = null;
        Session s = new Session(place.kind.role, place.village, point == null ? null : point.pos(), null, "menu");
        s.dining = true;
        s.near = table != null ? table.cell() : place.board != null ? place.board : p.blockPosition().immutable();
        s.state.putLong("place", place.building.anchor().asLong());
        if (table != null) {
            s.state.putInt("table", table.number());
            s.state.putString("mode", "table");
        }
        open(p, s);
    }

    /** [dining] The desk a screen is of: the dining room's order screen, or the trade's own. */
    private static Desk deskOf(Session s) {
        return s.dining ? DiningDesk.INSTANCE : s.role.desk();
    }

    private static String firstTab(ServiceRole role) {
        return role == ServiceRole.SMITHY || role == ServiceRole.TAILOR ? "mend" : "buy";
    }

    private static void open(ServerPlayer p, Session s) {
        SESSIONS.put(p.getUUID(), s);
        send(p, s, true);
    }

    /** The screen closed (its Esc): nothing more is sent to it. */
    public static void closed(ServerPlayer p) {
        SESSIONS.remove(p.getUUID());
    }

    /** The visit this session is, now: null if the player has walked off or the town is gone. */
    @Nullable
    private static Visit visit(ServerPlayer p, Session s) {
        if (!(p.level() instanceof ServerLevel level) || !p.isAlive()) return null;
        Villages.Village v = Villages.get(s.village);
        if (v == null) return null;
        Point point = null;
        if (s.near != null) {                                                  // [dining] at a table: near it is near enough
            if (s.point != null) for (Point q : of(level, s.village, s.role)) if (q.pos().equals(s.point)) { point = q; break; }
            if (p.distanceToSqr(s.near.getX() + 0.5, s.near.getY() + 0.5, s.near.getZ() + 0.5) > (REACH + 2) * (REACH + 2)) return null;
        } else if (s.point != null) {
            for (Point q : of(level, s.village, s.role)) if (q.pos().equals(s.point)) { point = q; break; }
            if (point == null) return null;                                    // taken up meanwhile
            if (p.distanceToSqr(s.point.getX() + 0.5, s.point.getY() + 0.5, s.point.getZ() + 0.5) > (REACH + 2) * (REACH + 2)
                    && !nearKeeper(level, p, s)) return null;
        } else if (!nearKeeper(level, p, s)) {
            return null;
        }
        Status st = status(level, s.village, s.role, point);
        return new Visit(level, v, s.role, point, p, st.keeper(), s.state);
    }

    private static boolean nearKeeper(ServerLevel level, ServerPlayer p, Session s) {
        if (s.keeper == null) return false;
        return level.getEntity(s.keeper) instanceof VillageFolkEntity k && k.isAlive() && k.distanceToSqr(p) <= KEEPER_REACH * KEEPER_REACH;
    }

    /** Send the screen (opening it, or bringing an open one up to date). */
    private static void send(ServerPlayer p, Session s, boolean force) {
        Visit visit = visit(p, s);
        if (visit == null) {
            SESSIONS.remove(p.getUUID());
            deliver(p, closedTag());
            return;
        }
        Desk desk = deskOf(s);
        int print = desk.fingerprint(visit) * 31 + statusPrint(visit) * 17 + s.tab.hashCode() + s.message.hashCode()
            + EarnDesk.print(visit) * 7;                                                   // [earning] the line, the shift
        long now = visit.level().getGameTime();
        if (!force && print == s.lastPrint && now - s.lastSent < RESEND) return;
        s.lastPrint = print;
        s.lastSent = now;
        deliver(p, screen(visit, s));
    }

    /** To the player's screen: a connection that cannot take it (a test's stand-in player) is told once, not crashed. */
    private static void deliver(ServerPlayer p, CompoundTag screen) {
        try {
            PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.ServiceScreenPayload(screen));
        } catch (RuntimeException e) {
            LOG_ONCE.computeIfAbsent("send", k -> {
                com.mojang.logging.LogUtils.getLogger().info("[shops-homes] a service screen could not be sent: {}", e.toString());
                return Boolean.TRUE;
            });
        }
    }

    private static int statusPrint(Visit v) {
        Status st = status(v.level(), v.village().id(), v.role(), v.point());
        return st.words().hashCode() * 3 + (st.open() ? 1 : 0) + Market.coinsHeld(v.player()) * 7919;
    }

    private static CompoundTag closedTag() {
        CompoundTag t = new CompoundTag();
        t.putBoolean("gone", true);
        return t;
    }

    /** The whole screen: the header (the trade, its keeper, its hours, your standing and coin), and the desk's body. */
    static CompoundTag screen(Visit visit, Session s) {
        ServerLevel level = visit.level();
        UUID id = visit.village().id();
        Status st = status(level, id, visit.role(), visit.point());
        CompoundTag t = new CompoundTag();
        t.putString("role", visit.role().id);
        t.putString("title", Villages.name(id) + " " + visit.role().title);
        VillageFolkEntity k = st.keeper();
        t.putString("keeper", k == null ? "" : k.displayNameCap());
        t.putString("keeperRole", k == null ? "" : cap(ShopRoles.role(k) == ShopRoles.Role.ASSISTANT ? "shop assistant" : visit.role().keeperNoun));
        t.putInt("keeperId", k == null ? -1 : k.getId());
        t.putString("hours", visit.role().hours.words());
        t.putBoolean("open", st.open());
        t.putString("status", st.words());
        ServerPlayer p = visit.player();
        if (p != null) {
            t.putString("standing", Standing.of(id, p.getUUID(), level.getGameTime()).title().words);
            t.putBoolean("citizen", Citizens.is(id, p.getUUID()));
            t.putInt("coins", Market.coinsHeld(p));
        }
        if (visit.point() != null) {
            t.putInt("x", visit.point().pos().getX());
            t.putInt("y", visit.point().pos().getY());
            t.putInt("z", visit.point().pos().getZ());
        }
        t.putString("tab", s.tab);
        t.putInt("nonce", s.lastNonce);
        t.putString("kind", deskOf(s).kind(visit.role()));
        CompoundTag body = deskOf(s).body(visit, s.tab);
        if (!s.dining && "desk".equals(t.getString("kind"))) EarnDesk.extend(visit, body);     // [earning] we buy, work, the changer
        t.put("body", body);
        t.putString("message", s.message);
        t.putBoolean("messageOk", s.messageOk);
        return t;
    }

    /**
     * A button pressed on a service screen. Checked here before the desk does anything: the screen is this player's
     * open one, the request is new (each carries a number higher than the last: a double click or a resend after lag
     * does nothing twice), the player is still near, and the trade is open for anything that moves goods or coin.
     */
    public static void act(ServerPlayer p, String role, String action, CompoundTag args, int nonce) {
        Session s = SESSIONS.get(p.getUUID());
        if (s == null || !s.role.id.equals(role)) return;
        if (action.equals("close")) { closed(p); return; }
        if (nonce <= s.lastNonce) return;                                   // already answered: never twice
        s.lastNonce = nonce;
        Visit visit = visit(p, s);
        if (visit == null) {
            send(p, s, true);
            return;
        }
        if (action.equals("tab")) {
            s.tab = args.getString("tab");
            s.message = "";
            send(p, s, true);
            return;
        }
        if (action.equals("refresh")) {
            send(p, s, true);
            return;
        }
        Status st = status(visit.level(), visit.village().id(), visit.role(), visit.point());
        Result r;
        if (!st.open() && !readOnly(action)) {
            r = Result.no(st.words());
        } else {
            try {
                r = EarnDesk.handles(action) ? EarnDesk.act(visit, action, args)            // [earning]
                    : deskOf(s).act(visit, action, args);
            } catch (RuntimeException e) {
                LOG_ONCE.computeIfAbsent(visit.role().id + "/" + action, k -> {
                    com.mojang.logging.LogUtils.getLogger().warn("[shops-homes] service {} {} failed", k, e.toString());
                    return Boolean.TRUE;
                });
                r = Result.no("Something went wrong at the counter; nothing changed hands.");
            }
        }
        s.message = r.message();
        s.messageOk = r.ok();
        if (r.ok() && r.coin()) {
            visit.level().playSound(null, p.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.6F, 1.4F);
            VillageFolkEntity k = visit.keeper();
            if (k != null && k.distanceToSqr(p) < 10 * 10) {
                k.swing(InteractionHand.MAIN_HAND);
                k.getLookControl().setLookAt(p, 30.0F, 30.0F);
            }
        }
        send(p, s, true);
    }

    private static final Map<String, Boolean> LOG_ONCE = new ConcurrentHashMap<>();

    /** What anybody may do at a closed point: look, and read. */
    private static boolean readOnly(String action) {
        return action.equals("statement") || action.equals("look") || action.equals("basket") || action.equals("use")
            || action.equals("add") || action.equals("remove") || action.equals("clear") || action.equals("mode")    // [dining] the basket, and
            || action.equals("payNow") || action.equals("pay")                                                      // the bill, after hours too
            || action.equals("quitShift");                                                                           // [earning] clocking off
    }

    /** Every second, the open screens brought up to date if what they show has changed (only those, and cheaply). */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (SESSIONS.isEmpty() || event.getServer().getTickCount() % REFRESH != 0) return;
        for (Map.Entry<UUID, Session> e : SESSIONS.entrySet()) {
            ServerPlayer p = event.getServer().getPlayerList().getPlayer(e.getKey());
            if (p == null) { SESSIONS.remove(e.getKey()); continue; }
            send(p, e.getValue(), false);
        }
    }

    /** The trade's own bench in a town building (the bank's desk, the smithy's anvil, the tailor's loom): its service
     *  screen, unless the player crouches to use the thing itself. */
    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getEntity() instanceof ServerPlayer p)) return;
        if (e.getHand() != InteractionHand.MAIN_HAND || p.isShiftKeyDown()) return;
        BlockState s = level.getBlockState(e.getPos());
        if (!ServiceRole.Point.LECTERN.is(s) && !ServiceRole.Point.ANVIL.is(s) && !ServiceRole.Point.LOOM.is(s)) return;
        if (!p.getMainHandItem().isEmpty() && !Market.isCoin(p.getMainHandItem())) return;   // a book for the lectern, a pick to mend: as ever
        Point point = at(level, e.getPos());
        if (point == null) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        open(p, new Session(point.role(), point.village(), point.pos(), null, firstTab(point.role())));
    }

    /** "Use it yourself" from a bench's screen: the anvil's, the loom's or the lectern's own menu. */
    static Result useItYourself(Visit visit) {
        ServerPlayer p = visit.player();
        if (p == null || visit.point() == null) return Result.no("There's nothing here to use.");
        BlockState s = visit.level().getBlockState(visit.point().pos());
        var provider = s.getMenuProvider(visit.level(), visit.point().pos());
        if (provider == null) return Result.no("There's nothing here to use.");
        SESSIONS.remove(p.getUUID());
        p.openMenu(provider);
        return Result.said("");
    }

    // ------------------------------------------------------------------ the talk options and the keeper

    /**
     * A talk option that is a trade's business (For sale, the bank, Mend this, a haggle): its screen, on the folk
     * spoken to, when it keeps that trade (or the town has it and the folk can send the player there). False: the
     * folk answers as before.
     */
    public static boolean fromTalk(VillageFolkEntity folk, ServerPlayer p, TalkTopic topic) {
        UUID village = folk.ownerId();
        if (village == null) return false;
        ServiceRole role = switch (topic) {
            case FOR_SALE, HAGGLE -> folk.stationTask() == AssistantEntity.StationTask.COOK ? ServiceRole.CAFE
                : folk.stationTask() == AssistantEntity.StationTask.SHOP ? ServiceRole.SHOP : null;
            case BANK -> folk.stationTask() == AssistantEntity.StationTask.BANK ? ServiceRole.BANK : null;
            case REPAIR -> folk.stationTask() == AssistantEntity.StationTask.SMITH ? ServiceRole.SMITHY
                : folk.stationTask() == AssistantEntity.StationTask.TAILOR ? ServiceRole.TAILOR : null;
            default -> null;
        };
        if (role == null) return false;
        if (topic == TalkTopic.HAGGLE) {
            boolean opened = openFor(p, folk, role, "buy");
            Session s = SESSIONS.get(p.getUUID());
            if (opened && s != null) act(p, role.id, "haggle", new CompoundTag(), s.lastNonce + 1);
            return opened;
        }
        return openFor(p, folk, role, topic == TalkTopic.REPAIR ? "mend" : "");
    }

    /** A player right-clicked a keeper at its post with coin in hand: its trade's screen. */
    public static boolean coinAtKeeper(VillageFolkEntity keeper, ServerPlayer p) {
        UUID village = keeper.ownerId();
        if (village == null || !Market.isCoin(p.getMainHandItem()) || !(p.level() instanceof ServerLevel level)) return false;
        ServiceRole role = null;
        for (ServiceRole r : ServiceRole.values()) if (r.trade == keeper.stationTask()) { role = r; break; }
        if (role == null || role == ServiceRole.TAVERN) return false;
        Point point = nearest(level, village, role, keeper.blockPosition());
        if (point == null || point.pos().distSqr(keeper.blockPosition()) > 4 * 4) return false;
        return openFor(p, keeper, role, "");
    }

    // ------------------------------------------------------------------ the fitting

    private static final Map<UUID, Long> FITTED = new ConcurrentHashMap<>();

    /**
     * Once a day: every building of a counter trade (the shop, the store, the café, the tavern) without its service
     * counter has one fitted, in place of the cask at its counter nearest the door: the cask and what was in it go
     * back to the stock or the stores, and the counter is made of four of the stores' planks and that cask (three
     * slabs over a barrel between two planks). Nothing from nothing: short of the planks, it waits.
     */
    static void fitOut(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        if (FITTED.getOrDefault(v.id(), -1L) == day) return;
        FITTED.put(v.id(), day);
        for (ServiceRole role : ServiceRole.values()) {
            if (role.point != ServiceRole.Point.COUNTER) continue;
            for (String structure : role.structures) {
                for (Ledger.Building b : Ledger.buildings(v.id())) {
                    if (!b.structure().equals(structure) || !level.isLoaded(b.anchor())) continue;
                    if (hasCounter(level, b)) continue;
                    fit(level, v, b);
                }
            }
        }
    }

    /** The points looked for afresh (a stage set a counter in). */
    static void resetPointsForStage() {
        POINTS.clear();
    }

    /** Tests: fit the town's counters now. */
    public static void fitOutForTests(ServerLevel level, Villages.Village v) {
        FITTED.remove(v.id());
        POINTS.clear();
        fitOut(level, v);
        POINTS.clear();
    }

    private static boolean hasCounter(ServerLevel level, Ledger.Building b) {
        for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
            if (ServiceRole.Point.COUNTER.is(level.getBlockState(p.pos()))) return true;
        }
        return false;
    }

    /**
     * The cask a building's service counter goes in place of: one of the counter proper, beside the gap the keeper gets
     * behind by (a counter cask with open floor to one side and behind it), else the counter nearest the door.
     */
    @Nullable
    static BlockPos counterSpot(ServerLevel level, Ledger.Building b) {
        List<BlockPos> counters = b.structure().equals(Store.STRUCTURE) ? Store.layout(b).counters() : Cafe.counters(b);
        if (counters.isEmpty()) return null;
        BlockPos door = TownLife.fittings(b).door();
        for (BlockPos c : counters) {
            Direction front = StoreFloor.front(c, door);
            boolean behind = StoreFloor.standable(level, c.relative(front.getOpposite()));
            boolean byTheGap = StoreFloor.standable(level, c.relative(front.getClockWise())) || StoreFloor.standable(level, c.relative(front.getCounterClockWise()));
            boolean inRow = counters.contains(c.relative(front.getClockWise())) || counters.contains(c.relative(front.getCounterClockWise()));
            if (behind && byTheGap && inRow) return c;
        }
        return counters.get(0);
    }

    private static boolean fit(ServerLevel level, Villages.Village v, Ledger.Building b) {
        BlockPos at = counterSpot(level, b);
        if (at == null || !level.getBlockState(at).is(Blocks.BARREL)) return false;
        if (!mayPlace(level, v, at, b)) return false;
        if (!Crafts.usePlanks(level, v, 4)) return false;                      // the slabs and the sides
        // What was in the cask: back into the shop's own stock, else the stores.
        if (level.getBlockEntity(at) instanceof Container c) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) continue;
                ItemStack left = Store.put(level, v.id(), s.copy());
                if (!left.isEmpty()) Crafts.store(level, v, left);
                c.setItem(i, ItemStack.EMPTY);
            }
        }
        BlockPos door = TownLife.fittings(b).door();
        Direction front = StoreFloor.front(at, door);
        level.setBlock(at, McAssistantMod.SERVICE_COUNTER.get().defaultBlockState()
            .setValue(com.jrpetty.mcassistant.block.ServiceCounterBlock.FACING, front), 3);
        POINTS.clear();
        return true;
    }

    /** The town's one gate for a block it places ([integrity] TownBlocks): a counter only ever goes where the
     *  building's own cask stood, one of its drawing's own cells (as a renovation of it). */
    private static boolean mayPlace(ServerLevel level, Villages.Village v, BlockPos at, Ledger.Building b) {
        return level.isLoaded(at) && TownBlocks.mayBuild(level, v, at, b.anchor(), TownBlocks.Why.RENOVATE);
    }

    /** Is this block one of the shop's counters (a cask, or the service counter that took a cask's place)? */
    public static boolean isCounter(BlockState s) {
        return s.is(Blocks.BARREL) || s.getBlock() instanceof com.jrpetty.mcassistant.block.ServiceCounterBlock;
    }

    /** A player clicked one of a shop's or a café's counter casks: that trade's screen (null role: a market stall,
     *  which deals as it always did). False when the trade has no point to serve at yet. */
    public static boolean openAtCounterCask(ServerPlayer p, BlockPos cask, @Nullable ItemFrame frame) {
        if (!(p.level() instanceof ServerLevel level)) return false;
        Villages.Village v = Villages.nearest(level, cask, 96);
        if (v == null) return false;
        ServiceRole role = frame != null && frame.getTags().contains(StoreFloor.FRAME_TAG) ? ServiceRole.SHOP : null;
        if (role == null) {
            for (Ledger.Building b : Ledger.buildings(v.id())) {
                if (b.structure().equals("cafe") && Cafe.counters(b).contains(cask)) { role = ServiceRole.CAFE; break; }
            }
        }
        if (role == null) return false;
        Point point = nearest(level, v.id(), role, cask);
        if (point == null || point.pos().distSqr(cask) > 20 * 20) return false;
        Session s = new Session(role, v.id(), point.pos(), null, "buy");
        if (frame != null && !frame.getItem().isEmpty()) s.message = "On that counter: " + Goods.name(frame.getItem()) + ".";
        open(p, s);
        return true;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Tests: a player's open service screen as the server last made it, or null. */
    @Nullable
    public static CompoundTag screenForTests(ServerPlayer p) {
        Session s = SESSIONS.get(p.getUUID());
        Visit v = s == null ? null : visit(p, s);
        return v == null ? null : screen(v, s);
    }

    /** Tests: the nonce the screen would send next. */
    public static int nextNonceForTests(ServerPlayer p) {
        Session s = SESSIONS.get(p.getUUID());
        return s == null ? 0 : s.lastNonce + 1;
    }

    /** Tests: the last thing said to this player's screen. */
    public static String messageForTests(ServerPlayer p) {
        Session s = SESSIONS.get(p.getUUID());
        return s == null ? "" : s.message;
    }
}
