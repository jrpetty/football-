package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Villages;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featpa] Errands to town: it sells your loot at the market and brings back what you need.
 * <ol>
 * <li><b>The screen</b> (Orders > Errand to town): click things in your inventory to hand them over to sell (they go
 *     into its pack at once, and come back with a click), set a shopping list from the town's goods at today's prices,
 *     give it coins to spend (its own spending limit still holds), and send it.</li>
 * <li><b>The walk</b>: on foot all the way to the nearest town's market stalls, a leg at a time, its chunks kept awake;
 *     a monster on the road it fights or, badly hurt, turns back from; a market shut for the night it waits for (or
 *     comes home, as you chose).</li>
 * <li><b>The market</b>: each lot sold and bought at the town's real prices through the market's own counter code
 *     (Market.sellFor, Market.buy: the same books and the same standing as you at the stall), in your name. Out of
 *     stock, too dear for what it carries, past its spending limit, its pack full: each noted, and it carries on.</li>
 * <li><b>Back</b>: it walks back to you, hands over what it bought and the coins, and tells you what it got and for how
 *     much, in its own voice.</li>
 * </ol>
 */
public final class PaErrand {

    private PaErrand() {}

    /** What the screen has set so far (what to sell is already in its pack). Per player. */
    public static final class Draft {
        public final Map<String, Integer> sell = new LinkedHashMap<>();
        public final Map<String, Integer> buy = new LinkedHashMap<>();
        public int coins;
        public boolean waitOpen = true, homeByDark = true;
    }

    static final Map<UUID, Draft> DRAFTS = new ConcurrentHashMap<>();

    public static Draft draft(UUID player) {
        return DRAFTS.computeIfAbsent(player, k -> new Draft());
    }

    public enum Stage { OUT, WAIT, SELL, BUY, BACK, DONE }

    /** An errand under way. Per assistant. */
    public static final class Run {
        public UUID town;
        public String townName = "";
        public BlockPos market = BlockPos.ZERO, home = BlockPos.ZERO;
        public final Map<String, Integer> sell = new LinkedHashMap<>(), buy = new LinkedHashMap<>();
        public final Map<String, Integer> sold = new LinkedHashMap<>(), bought = new LinkedHashMap<>();
        public int earned, spent, limit;
        public boolean waitOpen, homeByDark;
        public Stage stage = Stage.OUT;
        public final List<String> notes = new ArrayList<>();
        public long started;
        public int nearestToMarket = Integer.MAX_VALUE, maxStep100;
        public boolean turnedBack, reported;
        @Nullable public Vec3 lastPos;
    }

    static final Map<UUID, Run> RUNS = new ConcurrentHashMap<>();

    @Nullable
    public static Run run(PlayerAssistantEntity a) {
        return RUNS.get(a.getUUID());
    }

    /** Tests: market hours ignored. */
    public static boolean hoursOffForTests;

    // ------------------------------------------------------------------ the town and its market

    @Nullable
    static Villages.Village town(ServerLevel level, BlockPos near) {
        return Villages.nearest(level, near, 1200);
    }

    /** Where it trades: the nearest market stall's counter (a barrel under a goods frame), else the town's heart. */
    static BlockPos marketOf(ServerLevel level, Villages.Village v, BlockPos from) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        if (level.hasChunkAt(v.centre())) {
            for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, new AABB(v.centre()).inflate(72, 24, 72),
                    fr -> fr.getTags().contains("mca_stall") || fr.getTags().contains("mca_counter"))) {
                double d = f.blockPosition().distSqr(from);
                if (d < bd) { bd = d; best = f.blockPosition().below(); }
            }
        }
        return best != null ? best : v.centre();
    }

    /** Dusk to dawn. */
    static boolean night(ServerLevel level) {
        long t = level.getDayTime() % 24000L;
        return t > 12500L && t < 23300L;
    }

    static boolean open(ServerLevel level) {
        return hoursOffForTests || com.jrpetty.mcassistant.entity.ServiceRole.SHOP.hours.isOpen(level.getDayTime() % 24000L);
    }

    /** The goods it offers on the shopping list: the everyday things a town sells, that this town would sell now. */
    static final Item[] EVERYDAY = {Items.BREAD, Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.BAKED_POTATO, Items.APPLE, Items.CARROT,
        Items.WHEAT, Items.OAK_LOG, Items.OAK_PLANKS, Items.COBBLESTONE, Items.STONE_BRICKS, Items.GLASS, Items.TORCH, Items.COAL,
        Items.IRON_INGOT, Items.ARROW, Items.STRING, Items.WHITE_WOOL, Items.LEATHER, Items.PAPER, Items.BOOK, Items.HONEY_BOTTLE,
        Items.GOLDEN_CARROT, Items.SHIELD, Items.BOW, Items.IRON_SWORD};

    // ------------------------------------------------------------------ the screen's buttons

    public static void act(ServerPlayer p, PlayerAssistantEntity a, String op, CompoundTag args) {
        Draft d = draft(p.getUUID());
        switch (op) {
            case "er_give" -> {                                         // a stack out of your inventory into its pack, to sell
                int slot = args.getInt("slot");
                if (slot < 0 || slot >= p.getInventory().getContainerSize()) return;
                ItemStack s = p.getInventory().getItem(slot);
                if (s.isEmpty() || Market.isCoin(s)) return;
                if (Market.goodFor(s) == null) { PaKit.tell(p, "bad", "Not handed over: the market doesn't buy " + s.getHoverName().getString().toLowerCase() + ". Pick something a town trades in."); break; }
                int room = PaContainers.packRoom(a, s);
                if (room <= 0) { PaTalk.reply(a, PaLogiTalk.line(a, "kit.er_pack_full")); PaKit.tell(p, "bad", "Not handed over: its pack is full. Take something back from To sell, or empty its pack in its Pack tab."); break; }
                int n = Math.min(room, s.getCount());
                ItemStack moved = s.split(n);
                ItemStack left = a.insertItem(moved);
                if (!left.isEmpty()) { p.getInventory().add(left); n -= left.getCount(); }
                p.getInventory().setChanged();
                d.sell.merge(id(moved.getItem()), n, Integer::sum);
                PaKit.tell(p, "ok", "Handed over " + n + " " + name(moved.getItem()) + " to sell: it's in its pack now" + (left.isEmpty() ? "" : " (the rest wouldn't fit)")
                    + ". Click it under To sell to have it back.");
            }
            case "er_back" -> {                                         // handed back
                String id = args.getString("item");
                Item it = item(id);
                int n = d.sell.getOrDefault(id, 0);
                if (n <= 0 || it == Items.AIR) return;
                int took = a.removeMatching(s -> s.is(it), n);
                ItemStack back = new ItemStack(it, took);
                while (!back.isEmpty()) {
                    ItemStack part = back.split(Math.min(back.getCount(), back.getMaxStackSize()));
                    if (!p.getInventory().add(part)) p.drop(part, false);
                }
                d.sell.remove(id);
                PaKit.tell(p, "ok", "Gave you back " + took + " " + name(it) + ". It won't sell them.");
            }
            case "er_buy" -> {                                          // the shopping list
                String id = args.getString("item");
                if (item(id) == Items.AIR) return;
                int n = Math.max(0, Math.min(640, d.buy.getOrDefault(id, 0) + args.getInt("by")));
                if (n == 0) d.buy.remove(id); else d.buy.put(id, n);
                PaKit.tell(p, "ok", n == 0 ? name(item(id)) + " is off the list." : "On the list: " + n + " " + name(item(id)) + ". It buys by the town's lot, so it may come back with a few over.");
            }
            case "er_coins" -> {                                        // coins to spend: yours into its pack, or back
                int by = args.getInt("by");
                if (by > 0) {
                    int have = Market.coinsHeld(p);
                    int n = Math.min(by, have);
                    if (n <= 0) break;
                    int moved = 0;
                    for (int i = 0; i < p.getInventory().getContainerSize() && moved < n; i++) {
                        ItemStack s = p.getInventory().getItem(i);
                        if (!Market.isCoin(s)) continue;
                        int k = Math.min(n - moved, s.getCount());
                        ItemStack part = s.split(k);
                        ItemStack left = a.insertItem(part);
                        moved += k - left.getCount();
                        if (!left.isEmpty()) { s.grow(left.getCount()); break; }
                    }
                    p.getInventory().setChanged();
                    d.coins += moved;
                    PaKit.tell(p, moved > 0 ? "ok" : "bad", moved > 0 ? "Handed it " + moved + " coins to spend. Whatever's left comes back to you with the goods." : "Not handed over: its pack had no room for coins.");
                } else if (by < 0) {
                    int n = Math.min(-by, coinsIn(a));
                    int took = a.removeMatching(Market::isCoin, n);
                    ItemStack back = new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get(), took);
                    while (!back.isEmpty()) { ItemStack part = back.split(Math.min(64, back.getCount())); if (!p.getInventory().add(part)) p.drop(part, false); }
                    d.coins = Math.max(0, d.coins - took);
                    PaKit.tell(p, "ok", "Took back " + took + " coins.");
                }
            }
            case "er_opt" -> {
                if (args.contains("wait")) d.waitOpen = args.getBoolean("wait");
                if (args.contains("dark")) d.homeByDark = args.getBoolean("dark");
                PaKit.tell(p, "ok", (d.waitOpen ? "If the market's shut it waits for it to open" : "If the market's shut it comes home") + "; "
                    + (d.homeByDark ? "night falling on the road, it turns back." : "it keeps going after dark."));
            }
            case "er_show" -> {
                Run r = run(a);
                if (r != null && r.stage != Stage.DONE) PaKit.show(p, a.blockPosition(), a.displayNameCap() + " (on its errand)");
                else {
                    Villages.Village v = town(p.serverLevel(), p.blockPosition());
                    if (v == null) PaKit.tell(p, "bad", "There's no town near enough to show.");
                    else PaKit.show(p, marketOf(p.serverLevel(), v, a.blockPosition()), "the market in " + Villages.name(v.id()));
                }
            }
            case "er_go" -> go(p, a, d);
            case "er_call" -> callBack(a);
            default -> { }
        }
        PaKit.sendErrand(p, a);
    }

    /** "Send it": the errand on its plan (Jobs, the HUD), the draft cleared. */
    public static boolean go(ServerPlayer p, PlayerAssistantEntity a, Draft d) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = town(level, p.blockPosition());
        if (v == null) { PaTalk.reply(a, PaLogiTalk.line(a, "kit.er_no_town")); PaKit.tell(p, "bad", "Not sent: there's no town within about 1200 blocks."); return false; }
        if (d.sell.isEmpty() && d.buy.isEmpty()) { PaTalk.reply(a, PaLogiTalk.line(a, "kit.er_nothing")); PaKit.tell(p, "bad", "Not sent: nothing to sell and nothing on the list."); return false; }
        Run r = new Run();
        r.town = v.id();
        r.townName = Villages.name(v.id());
        r.market = marketOf(level, v, a.blockPosition());
        r.home = p.blockPosition();
        r.sell.putAll(d.sell);
        r.buy.putAll(d.buy);
        PaData.Rec rec = a.rec();
        r.limit = Math.min(PaConfig.spendMax(), rec == null ? 64 : rec.spend);
        r.waitOpen = d.waitOpen;
        r.homeByDark = d.homeByDark;
        r.started = level.getGameTime();
        RUNS.put(a.getUUID(), r);
        DRAFTS.remove(p.getUUID());
        a.plan.steps.removeIf(s -> s.task != null && s.task.kind == PaTask.Kind.ERRAND);
        PaPlan.Step s = PaOrders.step("run an errand to " + r.townName, PaIntent.Kind.TRADE_BUY, 900);
        s.task = new PaTask(PaTask.Kind.ERRAND, r.market, "errand", 0);
        a.plan.steps.add(s);
        a.plan.paused = false;
        int dist = (int) Math.sqrt(a.blockPosition().distSqr(r.market));
        String what = list(r.sell, 3);
        String shop = list(r.buy, 3);
        String key = r.sell.isEmpty() ? "kit.er_go_buy" : r.buy.isEmpty() ? "kit.er_go_sell" : "kit.er_go";
        PaTalk.reply(a, PaLogiTalk.line(a, key, "item", r.townName, "what", what, "need", shop, "n", String.valueOf(dist)));
        PaKit.tell(p, "ok", "Sent to " + r.townName + " (" + dist + " blocks) on foot. It sells, buys, walks back and tells you how it went. Call it back any time from here.");
        return true;
    }

    /** "Come back now". */
    public static void callBack(PlayerAssistantEntity a) {
        Run r = run(a);
        if (r == null || r.stage == Stage.DONE) return;
        r.stage = Stage.BACK;
        r.notes.add("you called me back");
        PaTalk.reply(a, PaLogiTalk.line(a, "kit.er_called"));
        if (a.getOwnerPlayer() instanceof ServerPlayer sp) PaKit.tell(sp, "ok", "Called back: it's walking to you with whatever it's sold and bought so far.");
    }

    // ------------------------------------------------------------------ words

    static String id(Item it) {
        return BuiltInRegistries.ITEM.getKey(it).toString();
    }

    static Item item(String id) {
        try {
            return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        } catch (RuntimeException e) {
            return Items.AIR;
        }
    }

    static String list(Map<String, Integer> m, int most) {
        Map<Item, Integer> w = new LinkedHashMap<>();
        m.forEach((k, v) -> { Item it = item(k); if (it != Items.AIR && v > 0) w.merge(it, v, Integer::sum); });
        return PaBuild.words(w, most);
    }

    static int coinsIn(PlayerAssistantEntity a) {
        int n = 0;
        for (ItemStack s : a.getInventoryItems()) if (Market.isCoin(s)) n += s.getCount();
        return n;
    }

    // ------------------------------------------------------------------ trading in its player's name

    /** Its player's stand-in at the counter (their name and standing; the coins and goods are the assistant's). */
    static ServerPlayer agent(ServerLevel level, PlayerAssistantEntity a) {
        UUID owner = a.getOwnerId() == null ? UUID.nameUUIDFromBytes(("pa" + a.getUUID()).getBytes()) : a.getOwnerId();
        String name = a.ownerName().isEmpty() ? "Assistant" : a.ownerName();
        ServerPlayer fp = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level, new GameProfile(owner, name.length() > 16 ? name.substring(0, 16) : name));
        fp.getInventory().clearContent();
        fp.setPos(a.getX(), a.getY(), a.getZ());
        fp.setShiftKeyDown(false);
        return fp;
    }

    /** Everything the stand-in holds, into its pack (what won't fit at its feet). */
    static void unload(PlayerAssistantEntity a, ServerPlayer fp) {
        for (int i = 0; i < fp.getInventory().getContainerSize(); i++) {
            ItemStack s = fp.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            ItemStack left = a.insertItem(s.copy());
            if (!left.isEmpty()) a.spawnAtLocation(left);
            fp.getInventory().setItem(i, ItemStack.EMPTY);
        }
    }

    /** One lot sold: out of its pack, the coins into its pack. The coins it took, or -1 with why in the notes. */
    static int sellOne(ServerLevel level, PlayerAssistantEntity a, Villages.Village v, Run r, Item it) {
        int slot = -1;
        var inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) if (inv.get(i).is(it)) { slot = i; break; }
        if (slot < 0) return -1;
        ServerPlayer fp = agent(level, a);
        ItemStack lot = inv.get(slot);
        String said;
        int got;
        try {
            said = Market.sellFor(level, v, fp, lot);
            if (lot.isEmpty()) inv.set(slot, ItemStack.EMPTY);
            got = Market.coinsHeld(fp);
        } finally {
            unload(a, fp);
        }
        if (got > 0) { r.earned += got; return got; }
        r.notes.add(said);
        return -1;
    }

    /** One lot bought with its own coin: -1 with why in the notes when it can't. */
    static int buyOne(ServerLevel level, PlayerAssistantEntity a, Villages.Village v, Run r, Item it) {
        ServerPlayer fp = agent(level, a);
        ItemStack shown = new ItemStack(it);
        int price = Market.quoteFor(level, v, fp, shown);
        if (price <= 0) { r.notes.add(PaLogiTalk.line(a, "kit.er_out_of_stock", "item", name(it), "what", r.townName)); return -1; }
        if (r.spent + price > r.limit) { r.notes.add(PaLogiTalk.line(a, "kit.er_limit", "item", name(it), "n", String.valueOf(r.limit))); return -2; }
        int have = coinsIn(a);
        if (have < price) { r.notes.add(PaLogiTalk.line(a, "kit.er_skint", "item", name(it), "n", String.valueOf(price), "need", String.valueOf(have))); return -2; }
        Market.Good g = Market.goodFor(shown);
        int lotSize = g == null ? 1 : Math.max(1, g.bundle());
        if (PaContainers.packRoom(a, shown) < lotSize) { r.notes.add(PaLogiTalk.line(a, "kit.er_pack_full")); return -2; }
        int took = a.removeMatching(Market::isCoin, price);
        fp.getInventory().add(new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get(), took));
        String said;
        try {
            said = Market.buy(level, v, fp, shown);
        } finally {
            unload(a, fp);
        }
        if (!said.startsWith("Bought")) { r.notes.add(said); return -1; }
        r.spent += price;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^Bought (\\d+) ").matcher(said);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    static String name(Item it) {
        return new ItemStack(it).getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------------ the errand itself

    static final class ErrandGoal extends Goal {

        private final PlayerAssistantEntity a;
        @Nullable private PaTask t;
        private int cool, pathAt = -100, bestAt, saidAt, lastSaidDist;
        private double best = Double.MAX_VALUE;
        @Nullable private Vec3 leg;
        private int sellIdx, buyIdx;
        private final List<String> sellKeys = new ArrayList<>(), buyKeys = new ArrayList<>();
        private int buyGot;

        ErrandGoal(PlayerAssistantEntity a) {
            this.a = a;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return a.task != null && a.task.kind == PaTask.Kind.ERRAND && a.getTarget() == null && !a.plan.paused && !a.isSleeping();
        }

        @Override
        public boolean canContinueToUse() {
            return a.task == t && t != null && a.getTarget() == null && !a.plan.paused;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void start() {
            t = a.task;
            pathAt = -100;
            best = Double.MAX_VALUE;
            bestAt = a.tickCount;
            leg = null;
        }

        @Override
        public void stop() {
            a.getNavigation().stop();
            a.setSprinting(false);
        }

        private void finish() {
            if (t != null) { t.progress = 100; t.result = ""; }
            a.task = null;
            a.setSprinting(false);
        }

        @Override
        public void tick() {
            Run r = run(a);
            if (t == null) return;
            if (r == null) { PaTalk.reply(a, PaLogiTalk.line(a, "kit.er_lost")); finish(); return; }
            ServerLevel level = (ServerLevel) a.level();
            // never a teleport: the longest step it took in a tick, kept for the tests
            if (r.lastPos != null) r.maxStep100 = Math.max(r.maxStep100, (int) (a.position().distanceTo(r.lastPos) * 100));
            r.lastPos = a.position();
            if (cool > 0) { cool--; return; }
            Villages.Village v = Villages.get(r.town);
            if (v == null && r.stage != Stage.BACK && r.stage != Stage.DONE) { r.notes.add("the town's gone"); r.stage = Stage.BACK; }
            // hurt on the road: home, not further
            if ((r.stage == Stage.OUT || r.stage == Stage.WAIT) && a.getHealth() < a.getMaxHealth() * 0.35F) {
                r.turnedBack = true;
                r.stage = Stage.BACK;
                PaTalk.reply(a, PaLogiTalk.line(a, "kit.er_danger"));
            }
            switch (r.stage) {
                case OUT -> out(level, r);
                case WAIT -> waitOpen(level, r);
                case SELL -> sell(level, r, v);
                case BUY -> buy(level, r, v);
                case BACK -> back(level, r);
                case DONE -> finish();
            }
            t.progress = switch (r.stage) { case OUT -> 10 + Math.min(30, Math.max(0, 30 - r.nearestToMarket / 4)); case WAIT -> 40; case SELL -> 50; case BUY -> 65; case BACK -> 80; case DONE -> 100; };
            PaPlan.Step step = a.plan.current;
            if (step != null && step.task == t) step.label = switch (r.stage) {
                case OUT -> "walk to the market in " + r.townName;
                case WAIT -> "wait for the market in " + r.townName + " to open";
                case SELL -> "sell your things in " + r.townName;
                case BUY -> "buy what you need in " + r.townName;
                case BACK -> "walk back from " + r.townName;
                case DONE -> "run an errand to " + r.townName;
            };
        }

        private boolean walk(Vec3 to, double arrive) {
            double d = a.position().distanceTo(to);
            if (d <= arrive) { a.getNavigation().stop(); a.setSprinting(false); return true; }
            if (d < best - 1.0) { best = d; bestAt = a.tickCount; }
            boolean run = d > 12;
            a.setSprinting(run && !a.isInWater());
            double speed = run ? 0.75 : 0.8;
            boolean repath = a.getNavigation().isDone() || a.tickCount - pathAt >= 30 || leg != null && a.position().distanceToSqr(leg) < 25;
            if (!repath) return false;
            pathAt = a.tickCount;
            if (d <= PaCome.LEG + 4) {
                leg = null;
                a.getNavigation().moveTo(to.x, to.y, to.z, speed);
                return false;
            }
            Vec3 dir = to.subtract(a.position()).multiply(1, 0, 1).normalize();
            for (int len : new int[]{PaCome.LEG, 14, 8}) for (double turn : new double[]{0, 30, -30, 60, -60}) {
                Vec3 l = PaCome.ground(a, a.position().add(dir.yRot((float) Math.toRadians(turn)).scale(len)));
                if (l == null) continue;
                Path path = a.getNavigation().createPath(BlockPos.containing(l), 1);
                if (path == null) continue;
                a.getNavigation().moveTo(path, speed);
                leg = l;
                return false;
            }
            a.getNavigation().moveTo(to.x, to.y, to.z, speed);
            return false;
        }

        private boolean stuck() {
            return a.tickCount - bestAt > 1200;
        }

        private void resetWalk() {
            best = Double.MAX_VALUE;
            bestAt = a.tickCount;
            pathAt = -100;
            leg = null;
        }

        private void out(ServerLevel level, Run r) {
            r.nearestToMarket = Math.min(r.nearestToMarket, (int) Math.sqrt(a.blockPosition().distSqr(r.market)));
            if (r.homeByDark && night(level)) {
                r.notes.add(PaLogiTalk.line(a, "kit.er_night"));
                r.stage = Stage.BACK;
                resetWalk();
                return;
            }
            int dist = (int) Math.sqrt(a.blockPosition().distSqr(r.market));
            if (dist > 60 && a.tickCount - saidAt > 600 && Math.abs(lastSaidDist - dist) > 30) {
                saidAt = a.tickCount;
                lastSaidDist = dist;
                PaTalk.comment(a, 2, PaLogiTalk.line(a, "kit.er_road", "item", r.townName, "n", String.valueOf(dist)), true);
            }
            if (walk(Vec3.atBottomCenterOf(r.market), 3.5)) {
                resetWalk();
                if (!open(level)) {
                    if (r.waitOpen && !(r.homeByDark && night(level))) {
                        r.stage = Stage.WAIT;
                        PaTalk.comment(a, 1, PaLogiTalk.line(a, "kit.er_shut_wait", "item", r.townName), true);
                    } else {
                        r.notes.add(PaLogiTalk.line(a, "kit.er_shut", "item", r.townName));
                        r.stage = Stage.BACK;
                    }
                    return;
                }
                beginTrading(r);
                return;
            }
            if (stuck()) { r.notes.add(PaLogiTalk.line(a, "kit.er_stuck", "item", r.townName)); r.stage = Stage.BACK; resetWalk(); }
        }

        private void beginTrading(Run r) {
            sellKeys.clear();
            sellKeys.addAll(r.sell.keySet());
            buyKeys.clear();
            buyKeys.addAll(r.buy.keySet());
            sellIdx = buyIdx = 0;
            buyGot = 0;
            r.stage = sellKeys.isEmpty() ? Stage.BUY : Stage.SELL;
            a.playAnim("wave");
        }

        private void waitOpen(ServerLevel level, Run r) {
            a.getNavigation().stop();
            if (a.tickCount % 200 == 0) a.playAnim("think");
            if (open(level)) { beginTrading(r); return; }
            if (level.getDayTime() % 24000L > 23500L || level.getGameTime() - r.started > 24000L * 2) { r.notes.add(PaLogiTalk.line(a, "kit.er_shut", "item", r.townName)); r.stage = Stage.BACK; }
        }

        private void sell(ServerLevel level, Run r, @Nullable Villages.Village v) {
            if (v == null) { r.stage = Stage.BACK; return; }
            a.getLookControl().setLookAt(Vec3.atCenterOf(r.market));
            if (!open(level)) { r.notes.add(PaLogiTalk.line(a, "kit.er_shut", "item", r.townName)); r.stage = Stage.BUY; return; }
            if (sellIdx >= sellKeys.size()) { r.stage = Stage.BUY; return; }
            String key = sellKeys.get(sellIdx);
            Item it = item(key);
            int want = r.sell.getOrDefault(key, 0) - r.sold.getOrDefault(key, 0);
            int had = a.countMatching(s -> s.is(it));
            if (want <= 0 || had <= 0) { sellIdx++; return; }
            int coins = sellOne(level, a, v, r, it);
            int sold = had - a.countMatching(s -> s.is(it));
            if (coins < 0 || sold <= 0) { sellIdx++; return; }
            r.sold.merge(key, sold, Integer::sum);
            a.swing(InteractionHand.MAIN_HAND);
            a.playAnim("hand_over");
            cool = 14;
        }

        private void buy(ServerLevel level, Run r, @Nullable Villages.Village v) {
            if (v == null) { r.stage = Stage.BACK; return; }
            a.getLookControl().setLookAt(Vec3.atCenterOf(r.market));
            if (buyIdx >= buyKeys.size() || !open(level)) {
                if (!open(level) && buyIdx < buyKeys.size()) r.notes.add(PaLogiTalk.line(a, "kit.er_shut", "item", r.townName));
                r.stage = Stage.BACK;
                resetWalk();
                PaTalk.comment(a, 2, PaLogiTalk.line(a, "kit.er_heading_back", "item", r.townName, "n", String.valueOf(r.earned), "need", String.valueOf(r.spent)), true);
                return;
            }
            String key = buyKeys.get(buyIdx);
            Item it = item(key);
            int want = r.buy.getOrDefault(key, 0) - r.bought.getOrDefault(key, 0);
            if (want <= 0 || it == Items.AIR) { buyIdx++; return; }
            int got = buyOne(level, a, v, r, it);
            if (got == -2) { buyIdx = buyKeys.size(); return; }                // money or room gone: nothing more will go
            if (got < 0) { buyIdx++; return; }
            r.bought.merge(key, got, Integer::sum);
            a.swing(InteractionHand.MAIN_HAND);
            a.playAnim("count_coins");
            cool = 14;
        }

        private void back(ServerLevel level, Run r) {
            ServerPlayer owner = a.getOwnerPlayer() instanceof ServerPlayer sp && sp.level() == a.level() && sp.isAlive() ? sp : null;
            Vec3 to = owner != null ? owner.position() : Vec3.atBottomCenterOf(r.home);
            if (walk(to, owner != null ? 3.0 : 2.0)) {
                report(level, r, owner);
                r.stage = Stage.DONE;
                finish();
                return;
            }
            if (stuck()) {
                report(level, r, null);
                r.stage = Stage.DONE;
                finish();
            }
        }

        /** What it got and for how much, in its own voice; the goods and the coins into its player's hands. */
        private void report(ServerLevel level, Run r, @Nullable ServerPlayer owner) {
            if (r.reported) return;
            r.reported = true;
            int handed = 0;
            if (owner != null) {
                for (String k : r.bought.keySet()) {
                    Item it = item(k);
                    int n = a.removeMatching(s -> s.is(it), r.bought.get(k));
                    while (n > 0) {
                        int part = Math.min(n, it.getDefaultMaxStackSize());
                        ItemStack s = new ItemStack(it, part);
                        if (!owner.getInventory().add(s)) { a.insertItem(s); break; }
                        n -= part;
                        handed += part;
                    }
                }
                int coins = coinsIn(a);
                if (coins > 0) {
                    int n = a.removeMatching(Market::isCoin, coins);
                    ItemStack c = new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get(), n);
                    while (!c.isEmpty()) { ItemStack part = c.split(Math.min(64, c.getCount())); if (!owner.getInventory().add(part)) a.insertItem(part); }
                }
                a.swing(InteractionHand.MAIN_HAND);
                a.playAnim("hand_over");
            }
            String sold = list(r.sold, 4), bought = list(r.bought, 4);
            String key = r.sold.isEmpty() && r.bought.isEmpty() ? "kit.er_report_none" : r.bought.isEmpty() ? "kit.er_report_sold" : r.sold.isEmpty() ? "kit.er_report_bought" : "kit.er_report";
            String line = PaLogiTalk.line(a, key, "item", r.townName, "what", sold, "need", bought, "n", String.valueOf(r.earned), "spent", String.valueOf(r.spent));
            List<String> why = new ArrayList<>();
            for (String n : r.notes) if (!n.isBlank() && !why.contains(n) && why.size() < 3) why.add(n.trim());
            if (!why.isEmpty()) line = line + " " + String.join(" ", why);
            PaTalk.reply(a, line.trim());
        }
    }

    /** For the screen: the errand under way. */
    static CompoundTag runTag(Run r) {
        CompoundTag t = new CompoundTag();
        t.putString("town", r.townName);
        t.putString("stage", r.stage.name());
        t.putInt("earned", r.earned);
        t.putInt("spent", r.spent);
        t.putString("sold", list(r.sold, 6));
        t.putString("bought", list(r.bought, 6));
        ListTag notes = new ListTag();
        for (String n : r.notes) notes.add(net.minecraft.nbt.StringTag.valueOf(n));
        t.put("notes", notes);
        return t;
    }

    /** The town's everyday goods at today's price for this player (a lot each), for the shopping list. */
    static ListTag goods(ServerLevel level, ServerPlayer p, @Nullable Villages.Village v, Draft d) {
        ListTag l = new ListTag();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Item> items = new ArrayList<>(List.of(EVERYDAY));
        for (String k : d.buy.keySet()) { Item it = item(k); if (it != Items.AIR && !items.contains(it)) items.add(0, it); }
        for (Item it : items) {
            String key = id(it);
            if (!seen.add(key)) continue;
            ItemStack one = new ItemStack(it);
            if (Market.goodFor(one) == null && !d.buy.containsKey(key)) continue;
            CompoundTag g = new CompoundTag();
            g.putString("i", key);
            g.putInt("price", v == null ? -1 : Market.quoteFor(level, v, p, one));
            g.putInt("want", d.buy.getOrDefault(key, 0));
            l.add(g);
        }
        return l;
    }

    /** Danger near it on the road (for the HUD's word and the tests). */
    static boolean danger(PlayerAssistantEntity a) {
        return !a.level().getEntitiesOfClass(Monster.class, a.getBoundingBox().inflate(10), Monster::isAlive).isEmpty();
    }
}
