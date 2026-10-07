package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [fleet] The auction. On market day a town of twelve or more from the Stone Age holds an auction on the square: its
 * rare finds and the curios of the merchant from afar go under the hammer, and so does anything a player puts up, and
 * folk and players bid against each other for them, coin for coin.
 *
 * <ul>
 * <li><b>The lots</b> are drawn in the morning: the rare finds in the stores beyond what the town keeps for itself
 *     (the diamonds over its five for the tools, enchanted books, golden apples, music discs, saddles over the stables'
 *     two, name tags, horse armour, the sea's shells and hearts, totems, the smith's templates: what the cave
 *     dwellers bring up and the fleet's nets now and then), the museum's spares (another of what it already has on
 *     show; never what it has asked the stores to keep for it), the curio a merchant from afar brought in with it, and
 *     whatever players have put up since the last one. Three of the town's at most, the finest first. Every lot is a
 *     real thing: the town's stays in the stores till it is sold, a player's is held by the town, a merchant's stays in
 *     its pack.</li>
 * <li><b>The auction</b> starts at nine. The auctioneer (the elder, or the leader, or whoever the town looks up to)
 *     takes its stand on the square, with the lot held up over it for all to see; the folk who want something come and
 *     stand before it. It calls the lots in turn, from the reserve (half what the thing is worth), and the folk bid
 *     with their own coin, up to what the thing is worth to them: its worth by their means (the poor bow out early, the
 *     wealthy go high), their nature (the thrifty least of all, the generous and the free spirits more) and their wants
 *     (a smith for a diamond, the enchanter for a book, a musician for a disc, the rancher for a saddle; a collector
 *     — a Traditionalist, or a curious soul with the means — for anything old and rare), never past what is in their
 *     purse. "Going once, going twice, sold!" when nobody raises it.</li>
 * <li><b>Players</b> bid by right-clicking the auctioneer (a bid screen: the lot, the bid, the bidder, your coin, and a
 *     button to raise it), by saying "I bid 30" to it, or with /village auction bid. A player's bid is held by the town
 *     from the moment it is made, and handed back the moment somebody beats it.</li>
 * <li><b>The proceeds</b> go to the seller: the treasury for the town's finds, the player who put the lot up, or the
 *     merchant. The lot goes to the winner: a folk keeps it at home (its keepsake) or wears it; a player gets it, or
 *     finds it waiting next time they come by the town, with anything else the town owes them.</li>
 * <li><b>The auction house.</b> An Iron Age town of twenty-five that has held its auction three market days or more
 *     builds an auction house (blueprints/auction.txt): a hall with a rostrum at the back and benches for twelve. From
 *     then on the auction is held in it, the auctioneer behind the rostrum and the bidders on the benches.</li>
 * </ul>
 * It is all in the chronicle and the gazette ("A diamond sold for 48 coins to Mara, the smith's partner"), on the
 * board, on the winners' cards, and on the Auction page of the town's books (the lots, the bids, the sales).
 */
public final class Auctions {

    private Auctions() {}

    private static final Logger LOG = LogUtils.getLogger();

    /** A town of so many, in the Stone Age or later, holds an auction on market day. */
    public static final int FROM = 12;
    /** The lots are drawn in the morning; the auction starts at nine, and is over by one. */
    static final long DRAW_AT = 1500L, START_AT = 3000L, LATEST = 7000L;
    /** The crowd has this long to gather before the first lot is called. */
    static final long GATHER = 600L;
    /** Between the auctioneer's calls: long enough to hear the bid and answer it. */
    static final long ROUND = 40L;
    static final int MOST_TOWN_LOTS = 3, MOST_PLAYER_LOTS = 4, MOST_BIDDERS = 12;
    /** The display over the auctioneer's stand: the lot held up. */
    static final String DISPLAY_TAG = "mca_auction_lot";
    /** A merchant's curio, brought in for the auction (Merchants). */
    static final String CURIO = "mca_curio";

    public enum Owner { TOWN, PLAYER, MERCHANT }

    /** A bid: who, and how much. */
    record Bid(UUID by, String name, boolean player, int coins) {}

    /** A lot: the thing itself, who puts it up and where it came from, its reserve; and its bidding. */
    static final class Lot {
        final ItemStack item;
        final Owner owner;
        @Nullable final UUID ownerId;
        final String ownerName, from;
        final int reserve, worth;
        int high;
        @Nullable UUID highBy;
        boolean highPlayer;
        String highName = "";
        final List<Bid> bids = new ArrayList<>();
        int calls;
        String result = "";
        /** What each folk bidder will go to for it, worked out when it is called. */
        final Map<UUID, Integer> most = new HashMap<>();

        Lot(ItemStack item, Owner owner, @Nullable UUID ownerId, String ownerName, String from, int reserve, int worth) {
            this.item = item;
            this.owner = owner;
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.from = from;
            this.reserve = reserve;
            this.worth = worth;
        }

        String name() {
            return lower(item.getHoverName().getString());
        }
    }

    enum Phase { LOTS, GATHER, CALLING, DONE }

    /** One town's auction day. */
    static final class Sale {
        final UUID village;
        final long day;
        final List<Lot> lots = new ArrayList<>();
        int at = -1;
        Phase phase = Phase.LOTS;
        @Nullable UUID auctioneer;
        String auctioneerName = "";
        @Nullable BlockPos rostrum;
        Direction facing = Direction.SOUTH;
        /** The auction house, if the auction is held in it. */
        @Nullable Ledger.Building house;
        long nextAt, phaseAt;
        final Map<UUID, BlockPos> bidders = new LinkedHashMap<>();
        /** The coin held for a player's bid on the lot being called. */
        final Map<UUID, Integer> escrow = new HashMap<>();
        @Nullable UUID display;
        int sold, takings;

        Sale(UUID village, long day) {
            this.village = village;
            this.day = day;
        }

        @Nullable
        Lot lot() {
            return at >= 0 && at < lots.size() ? lots.get(at) : null;
        }
    }

    private static final Map<UUID, Sale> SALES = new ConcurrentHashMap<>();
    /** Folk at the auction (the auctioneer and the crowd), by folk: what hold() looks up. */
    private static final Map<UUID, UUID> AT = new ConcurrentHashMap<>();
    /** Players with the bid screen open, and the town. */
    private static final Map<UUID, UUID> VIEWERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    private static volatile int fromForTests = -1;
    private static volatile boolean manual;

    public static void resetForTests() {
        SALES.clear();
        AT.clear();
        VIEWERS.clear();
        LAST.clear();
        DEALT_WITH.clear();
        fromForTests = -1;
        manual = false;
    }

    /** Tests: the size of town that holds an auction (-1: the usual twelve). */
    public static void fromForTests(int n) {
        fromForTests = n;
    }

    /** Tests: the rounds called by the test (true), not by the clock. */
    public static void manualForTests(boolean on) {
        manual = on;
    }

    /** Does this town hold auctions (a town of twelve, from the Stone Age)? */
    public static boolean holds(@Nullable UUID village) {
        if (village == null) return false;
        int from = fromForTests >= 0 ? fromForTests : FROM;
        return Villages.headcount(village) >= from && Villages.ageOf(village).ordinal() >= Villages.Age.STONE.ordinal();
    }

    // ------------------------------------------------------------------ the lots

    /** Rare enough for the auction: a find, not a ware. */
    static boolean rare(ItemStack s) {
        if (s.isEmpty() || Market.isCoin(s) || Homes.isKeepsake(s)) return false;
        if (s.is(Items.DIAMOND) || s.is(Items.EMERALD) || s.is(Items.ENCHANTED_BOOK) || s.is(Items.GOLDEN_APPLE)
            || s.is(Items.ENCHANTED_GOLDEN_APPLE) || s.is(Items.SADDLE) || s.is(Items.NAME_TAG) || s.is(Items.IRON_HORSE_ARMOR)
            || s.is(Items.GOLDEN_HORSE_ARMOR) || s.is(Items.DIAMOND_HORSE_ARMOR) || s.is(Items.HEART_OF_THE_SEA) || s.is(Items.NAUTILUS_SHELL)
            || s.is(Items.TOTEM_OF_UNDYING) || s.is(Items.TRIDENT) || s.is(Items.ECHO_SHARD) || s.is(Items.GOAT_HORN)
            || s.is(Items.SPYGLASS)) return true;
        if (s.get(DataComponents.JUKEBOX_PLAYABLE) != null) return true;                         // a music disc
        return s.getItem() instanceof net.minecraft.world.item.SmithingTemplateItem;
    }

    /** What the town keeps of a rare thing for itself: the diamonds for its tools, the stables' saddles, one of each of
     *  the museum's kinds while the museum has none on show, and whatever the museum has asked the stores to keep. */
    static int keep(ServerLevel level, UUID village, ItemStack s) {
        int keep = 0;
        if (s.is(Items.DIAMOND)) keep = Budget.keep(level, village, s);
        else if (s.is(Items.SADDLE)) keep = 2;
        else if (s.is(Items.GOLDEN_APPLE) || s.is(Items.NAME_TAG)) keep = 1;
        Museum.Kind k = Museum.Kind.inStores(s);
        if (k != null && !Museum.onShow(village, k.key())) keep = Math.max(keep, 1);
        int[] asked = { 0 };
        Museum.keptBack(village, (what, n, why) -> { if (what.test(s)) asked[0] += n; });
        return keep + asked[0];
    }

    /** Where a lot of the town's came from, in words. */
    static String provenance(ServerLevel level, UUID village, ItemStack s) {
        String name = lower(s.getHoverName().getString());
        Museum.Kind k = Museum.Kind.inStores(s);
        if (k != null && Museum.onShow(village, k.key())) return "a spare from the museum's collection";
        for (String h : CaveDwellers.hauls(village)) {
            if (h.toLowerCase(Locale.ROOT).contains(name)) return "brought up from the caves by the cave dwellers";
        }
        return "from the town's stores";
    }

    /** The town's lots for an auction: its finest spare rare finds, one lot of each kind, three at most. */
    static List<Lot> townLots(ServerLevel level, Villages.Village v) {
        Map<String, ItemStack> sample = new LinkedHashMap<>();
        Map<String, Integer> count = new HashMap<>();
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!rare(s)) continue;
                String key = key(s);
                sample.putIfAbsent(key, s.copyWithCount(1));
                count.merge(key, s.getCount(), Integer::sum);
            }
        }
        List<Lot> out = new ArrayList<>();
        for (Map.Entry<String, ItemStack> e : sample.entrySet()) {
            ItemStack one = e.getValue();
            int spare = count.getOrDefault(e.getKey(), 0) - keep(level, v.id(), one);
            if (spare <= 0) continue;
            int worth = Math.max(2, (int) Math.round(Prices.of(one)));
            out.add(new Lot(one, Owner.TOWN, null, Villages.name(v.id()), provenance(level, v.id(), one), reserve(worth), worth));
        }
        out.sort((a, b) -> Integer.compare(b.worth, a.worth));
        List<Lot> lots = new ArrayList<>();
        for (Lot l : out) {
            boolean same = false;
            for (Lot o : lots) if (o.item.is(l.item.getItem())) { same = true; break; }
            if (!same && lots.size() < MOST_TOWN_LOTS) lots.add(l);
        }
        return lots;
    }

    static int reserve(int worth) {
        return Math.max(1, (int) Math.round(worth * 0.5));
    }

    private static String key(ItemStack s) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()) + "#" + s.getComponentsPatch().hashCode();
    }

    /** The merchants' curios: what each merchant in town brought in for the auction (in its pack, marked). */
    static List<Lot> merchantLots(ServerLevel level, Villages.Village v) {
        List<Lot> out = new ArrayList<>();
        for (VillageFolkEntity m : Visitors.inTown(level, v.id(), Visitors.Kind.MERCHANT)) {
            for (ItemStack s : m.getInventoryItems()) {
                if (!isCurio(s)) continue;
                ItemStack one = s.copyWithCount(1);
                int worth = Math.max(2, (int) Math.round(Prices.of(unmarked(one))));
                out.add(new Lot(one, Owner.MERCHANT, m.getUUID(), m.displayNameCap(), "brought from far away by " + m.displayNameCap(),
                    reserve(worth), worth));
                break;
            }
        }
        return out;
    }

    static boolean isCurio(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d != null && d.contains(CURIO);
    }

    /** The thing without the town's marks on it (a merchant's curio mark), as the winner gets it. */
    static ItemStack unmarked(ItemStack s) {
        ItemStack out = s.copy();
        CustomData d = out.get(DataComponents.CUSTOM_DATA);
        if (d == null || !d.contains(CURIO)) return out;
        CompoundTag t = d.copyTag();
        t.remove(CURIO);
        if (t.isEmpty()) out.remove(DataComponents.CUSTOM_DATA);
        else out.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        return out;
    }

    /**
     * [fleet] What a merchant from afar brings in for the auction on top of its wares (Merchants.come): one time in two, a
     * curio from its own far country — a nautilus shell, a goat's horn, a spyglass, a music disc, a name tag, a saddle or
     * an enchanted book — marked as the auction's. Like its wares it comes from outside the town; it goes away with the
     * merchant if nobody buys it.
     */
    public static ItemStack curio(ServerLevel level, Villages.Village v, long day) {
        if (!holds(v.id())) return ItemStack.EMPTY;
        RandomSource r = level.getRandom();
        if (r.nextInt(2) != 0) return ItemStack.EMPTY;
        ItemStack s = switch (r.nextInt(8)) {
            case 0 -> new ItemStack(Items.NAUTILUS_SHELL);
            case 1 -> new ItemStack(Items.GOAT_HORN);
            case 2 -> new ItemStack(Items.SPYGLASS);
            case 3 -> new ItemStack(r.nextBoolean() ? Items.MUSIC_DISC_CAT : Items.MUSIC_DISC_FAR);
            case 4 -> new ItemStack(Items.NAME_TAG);
            case 5 -> new ItemStack(Items.SADDLE);
            case 6 -> new ItemStack(Items.MUSIC_DISC_MELLOHI);
            default -> net.minecraft.world.item.enchantment.EnchantmentHelper.enchantItem(r, new ItemStack(Items.BOOK), 20,
                level.registryAccess(), java.util.Optional.empty());
        };
        if (s.isEmpty()) return ItemStack.EMPTY;
        CompoundTag t = s.has(DataComponents.CUSTOM_DATA) ? s.get(DataComponents.CUSTOM_DATA).copyTag() : new CompoundTag();
        t.putBoolean(CURIO, true);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        return s;
    }

    // ------------------------------------------------------------------ players' lots, held by the town

    private static ListTag held(ServerLevel level, UUID village) {
        CompoundTag t = parse(Ledger.note(village, "auction.held"));
        return t.getList("lots", Tag.TAG_COMPOUND);
    }

    private static void keepHeld(UUID village, ListTag lots) {
        CompoundTag t = new CompoundTag();
        t.put("lots", lots);
        Ledger.note(village, "auction.held", t.toString());
    }

    private static CompoundTag parse(@Nullable String s) {
        if (s == null || s.isEmpty()) return new CompoundTag();
        try {
            return TagParser.parseTag(s);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            LOG.warn("[MCA-AUCTION] unreadable note: {}", e.getMessage());
            return new CompoundTag();
        }
    }

    /** The players' lots waiting for the next auction. */
    static List<Lot> playerLots(ServerLevel level, Villages.Village v) {
        List<Lot> out = new ArrayList<>();
        ListTag l = held(level, v.id());
        for (int i = 0; i < l.size(); i++) {
            CompoundTag c = l.getCompound(i);
            ItemStack s = ItemStack.parseOptional(level.registryAccess(), c.getCompound("item"));
            if (s.isEmpty() || !c.hasUUID("owner")) continue;
            int worth = Math.max(1, (int) Math.round(Prices.of(s)));
            out.add(new Lot(s, Owner.PLAYER, c.getUUID("owner"), c.getString("name"), "put up by " + c.getString("name"),
                Math.max(1, c.getInt("reserve")), worth));
        }
        return out;
    }

    /** A lot of a player's off the town's list (sold, or handed back). */
    private static void unhold(ServerLevel level, UUID village, Lot lot) {
        ListTag l = held(level, village);
        for (int i = 0; i < l.size(); i++) {
            CompoundTag c = l.getCompound(i);
            if (!c.hasUUID("owner") || !c.getUUID("owner").equals(lot.ownerId)) continue;
            ItemStack s = ItemStack.parseOptional(level.registryAccess(), c.getCompound("item"));
            if (ItemStack.isSameItemSameComponents(s, lot.item) && s.getCount() == lot.item.getCount()) {
                l.remove(i);
                keepHeld(village, l);
                return;
            }
        }
    }

    /**
     * A player puts the thing in their hand up for auction: the town holds it till the next market day's auction (today's
     * if it is still to come), at this reserve (half its worth if none is asked). Returns what to tell them.
     */
    public static String putUp(ServerLevel level, Villages.Village v, Player p, int reserve) {
        UUID id = v.id();
        if (!holds(id)) return Villages.name(id) + " holds no auctions yet: a town of " + FROM + " from the Stone Age does.";
        if (Standing.of(id, p.getUUID(), level.getGameTime()).title() == Standing.Title.OUTCAST) return "Nobody here will sell for you.";
        ItemStack hand = p.getMainHandItem();
        if (hand.isEmpty() || Market.isCoin(hand)) return "Hold the thing you want to sell in your hand.";
        ListTag l = held(level, id);
        int mine = 0;
        for (int i = 0; i < l.size(); i++) if (l.getCompound(i).hasUUID("owner") && l.getCompound(i).getUUID("owner").equals(p.getUUID())) mine++;
        if (mine >= 2) return "You've two lots waiting already. Let those go under the hammer first.";
        if (l.size() >= MOST_PLAYER_LOTS) return "The next auction's list is full. Try again after it.";
        Sale s = SALES.get(id);
        long day = level.getDayTime() / 24000L;
        ItemStack lot = hand.copy();
        hand.setCount(0);
        DEALT_WITH.put(p.getUUID(), p);
        p.getInventory().setChanged();
        int worth = Math.max(1, (int) Math.round(Prices.of(lot)));
        int asked = reserve > 0 ? reserve : reserve(worth);
        CompoundTag c = new CompoundTag();
        c.put("item", lot.save(level.registryAccess()));
        c.putUUID("owner", p.getUUID());
        c.putString("name", p.getName().getString());
        c.putInt("reserve", asked);
        l.add(c);
        keepHeld(id, l);
        String when;
        if (s != null && s.day == day && s.phase != Phase.DONE) {
            s.lots.add(new Lot(lot, Owner.PLAYER, p.getUUID(), p.getName().getString(), "put up by " + p.getName().getString(), asked, worth));
            when = "today, after the lots before it";
            push(level, v);
        } else {
            int d = Market.daysToMarket(id, day + (Market.marketDay(id, day) ? 1 : 0)) + (Market.marketDay(id, day) ? 1 : 0);
            when = d <= 0 ? "today" : "on market day, in " + d + (d == 1 ? " day" : " days");
        }
        Villages.tell(id, day, p.getName().getString() + " put " + JobMarket.a(lower(lot.getHoverName().getString())) + " up for auction");
        return "The town will auction your " + lower(lot.getHoverName().getString()) + " " + when + ", from " + asked + coinWord(asked) + ".";
    }

    // ------------------------------------------------------------------ what the town owes players

    private static String owedKey(UUID player) {
        return "auction.owed/" + player;
    }

    /** The players who have bid or put a lot up this session, so what the town owes them reaches the one that bid. */
    private static final Map<UUID, Player> DEALT_WITH = new ConcurrentHashMap<>();

    /** A player of this id about the town just now, or null. */
    @Nullable
    private static Player about(ServerLevel level, UUID village, UUID player) {
        Player p = level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(player);
        if (p == null) {
            Player seen = DEALT_WITH.get(player);
            if (seen != null && !seen.isRemoved()) p = seen;
        }
        Villages.Village v = Villages.get(village);
        if (p == null || p.level() != level || v == null || !p.blockPosition().closerThan(v.centre(), Villages.townReach(village) + 48)) return null;
        return p;
    }

    /** Coin or goods for a player: into their pack now if they are about, else kept for them till they come by. */
    static void give(ServerLevel level, UUID village, UUID player, int coins, ItemStack item, String why) {
        Player p = about(level, village, player);
        if (p != null) {
            handOver(p, coins, item);
            if (!why.isEmpty()) p.displayClientMessage(Component.literal(why), false);
            return;
        }
        CompoundTag t = parse(Ledger.note(village, owedKey(player)));
        t.putInt("coins", t.getInt("coins") + Math.max(0, coins));
        ListTag items = t.getList("items", Tag.TAG_COMPOUND);
        if (!item.isEmpty()) items.add(item.save(level.registryAccess()));
        t.put("items", items);
        ListTag whys = t.getList("why", Tag.TAG_STRING);
        if (!why.isEmpty()) whys.add(StringTag.valueOf(why));
        t.put("why", whys);
        Ledger.note(village, owedKey(player), t.toString());
    }

    /** A player gives (or is given back) so much coin and this thing, into their pack or at their feet. */
    static void handOver(Player p, int coins, ItemStack item) {
        while (coins > 0) {
            int k = Math.min(64, coins);
            ItemStack c = new ItemStack(McAssistantMod.VILLAGE_COIN.get(), k);
            if (!p.getInventory().add(c)) p.drop(c, false);
            coins -= k;
        }
        if (!item.isEmpty()) {
            ItemStack s = item.copy();
            if (!p.getInventory().add(s)) p.drop(s, false);
        }
        p.getInventory().setChanged();
    }

    /** Every few seconds: anybody the town owes, about the town now, is paid. */
    static void payOwed(ServerLevel level, Villages.Village v) {
        for (ServerPlayer p : level.players()) {
            if (!p.blockPosition().closerThan(v.centre(), Villages.townReach(v.id()) + 16)) continue;
            String s = Ledger.note(v.id(), owedKey(p.getUUID()));
            if (s == null || s.isEmpty()) continue;
            CompoundTag t = parse(s);
            Ledger.forget(v.id(), owedKey(p.getUUID()));
            int coins = t.getInt("coins");
            ListTag items = t.getList("items", Tag.TAG_COMPOUND);
            handOver(p, coins, ItemStack.EMPTY);
            List<String> what = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                ItemStack it = ItemStack.parseOptional(level.registryAccess(), items.getCompound(i));
                if (it.isEmpty()) continue;
                handOver(p, 0, it);
                what.add(lower(it.getHoverName().getString()));
            }
            if (coins > 0) what.add(0, coins + coinWord(coins));
            ListTag whys = t.getList("why", Tag.TAG_STRING);
            String why = whys.isEmpty() ? "" : " (" + whys.getString(whys.size() - 1) + ")";
            p.displayClientMessage(Component.literal("The auction at " + Villages.name(v.id()) + " had kept for you: " + String.join(", ", what) + why + "."), false);
        }
    }

    /** A world stopped in the middle of an auction: any player's bid the town held is owed back to them. */
    private static void recover(ServerLevel level, Villages.Village v) {
        String s = Ledger.note(v.id(), "auction.escrow");
        if (s == null || s.isEmpty()) return;
        CompoundTag t = parse(s);
        Ledger.forget(v.id(), "auction.escrow");
        for (String k : t.getAllKeys()) {
            try {
                give(level, v.id(), UUID.fromString(k), t.getInt(k), ItemStack.EMPTY, "your bid handed back: the auction was cut short");
            } catch (IllegalArgumentException ignored) {
                // not a player's id: nothing to give back
            }
        }
    }

    private static void keepEscrow(Sale s) {
        CompoundTag t = new CompoundTag();
        for (Map.Entry<UUID, Integer> e : s.escrow.entrySet()) t.putInt(e.getKey().toString(), e.getValue());
        if (t.isEmpty()) Ledger.forget(s.village, "auction.escrow");
        else Ledger.note(s.village, "auction.escrow", t.toString());
    }

    // ------------------------------------------------------------------ the day

    /** Once a second for each town (VillageFolkEntity.aiStep): the lots drawn, the crowd called, the lots called in turn. */
    public static void tick(ServerLevel level, @Nullable UUID village) {
        if (village == null) return;
        Villages.Village v = Villages.get(village);
        if (v == null) return;
        long now = level.getGameTime();
        Long last = LAST.get(village);
        if (last != null && now - last < 20L && now >= last) return;
        LAST.put(village, now);
        if (now % 100L < 20L) payOwed(level, v);
        long dt = level.getDayTime(), day = dt / 24000L, tod = dt % 24000L;
        Sale s = SALES.get(village);
        if (s != null && s.day != day) {
            if (s.phase != Phase.DONE) finish(level, v, s, "the day was over");
            SALES.remove(village);
            s = null;
        }
        if (s == null) {
            recover(level, v);
            if (now % 200L < 20L) clearDisplays(level, v, null);
            if (!holds(village) || !Market.marketDay(village, day) || tod < DRAW_AT || tod >= LATEST) return;
            if (Long.toString(day).equals(Ledger.note(village, "auction.day"))) return;   // held already today (a restart since)
            s = open(level, v, day);
        }
        step(level, v, s, now, tod);
    }

    private static void step(ServerLevel level, Villages.Village v, Sale s, long now, long tod) {
        switch (s.phase) {
            case LOTS -> {
                if (tod >= START_AT) gather(level, v, s, now);
            }
            case GATHER -> {
                int there = 0;
                for (Map.Entry<UUID, BlockPos> e : s.bidders.entrySet()) {
                    if (level.getEntity(e.getKey()) instanceof VillageFolkEntity f && f.blockPosition().distSqr(e.getValue()) <= 4) there++;
                }
                boolean auctioneerThere = s.auctioneer != null && s.rostrum != null && level.getEntity(s.auctioneer) instanceof VillageFolkEntity a
                    && a.blockPosition().distSqr(s.rostrum) <= 4;
                if (manual || now - s.phaseAt >= GATHER || (auctioneerThere && there * 3 >= s.bidders.size() * 2)) call(level, v, s, 0, now);
            }
            case CALLING -> {
                if (!manual && now >= s.nextAt) round(level, v, s, now);
            }
            case DONE -> { }
        }
        if (tod >= LATEST && s.phase != Phase.DONE) finish(level, v, s, "the morning was over");
    }

    /** The morning of market day: the lots drawn, and said. */
    static Sale open(ServerLevel level, Villages.Village v, long day) {
        Sale s = new Sale(v.id(), day);
        s.lots.addAll(townLots(level, v));
        s.lots.addAll(merchantLots(level, v));
        s.lots.addAll(playerLots(level, v));
        SALES.put(v.id(), s);
        if (s.lots.isEmpty()) {
            s.phase = Phase.DONE;
            return s;
        }
        List<String> names = new ArrayList<>();
        for (Lot l : s.lots) names.add(JobMarket.a(l.name()));
        Villages.tell(v.id(), day, "up for auction " + venue(v.id()) + " this morning: " + list(names));
        LOG.info("[MCA-AUCTION] {}: {} lots for today: {}", Villages.name(v.id()), s.lots.size(), names);
        return s;
    }

    /** Nine o'clock: the auctioneer to its stand on the square, and the folk who want something before it. */
    static void gather(ServerLevel level, Villages.Village v, Sale s, long now) {
        s.phase = Phase.GATHER;
        s.phaseAt = now;
        VillageFolkEntity a = auctioneerFor(level, v);
        if (a == null) {
            finish(level, v, s, "nobody to call it");
            return;
        }
        s.auctioneer = a.getUUID();
        s.auctioneerName = a.displayNameCap();
        stand(level, v, s);
        AT.put(a.getUUID(), v.id());
        if (a.peekJob() != null) a.clearQueue();
        a.brain("calling the auction " + venue(v.id()));
        FolkTalk.speak(a, "Gather round, gather round! The auction's about to start!");
        level.playSound(null, s.rostrum, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 1.5F, 1.4F);
        // Who comes: the grown folk who want something on the list and have the coin for it, the keenest first.
        List<VillageFolkEntity> keen = new ArrayList<>();
        Map<UUID, Integer> best = new HashMap<>();
        for (AssistantEntity e : Villages.folkOf(v.id())) {
            if (!(e instanceof VillageFolkEntity f) || f == a || !Fleet.fit(f) || f.isSleeping() || Fleet.out(f) || FishMarket.busy(f)) continue;
            if (f.stationTask() == AssistantEntity.StationTask.GUARD && Raids.underAlarm(v.id())) continue;
            if (f.blockPosition().distSqr(v.centre()) > 120 * 120) continue;
            int most = Integer.MIN_VALUE;
            for (Lot l : s.lots) most = Math.max(most, estimate(f, l) - l.reserve);
            if (most < 0) continue;
            keen.add(f);
            best.put(f.getUUID(), most);
        }
        keen.sort((x, y) -> Integer.compare(best.get(y.getUUID()), best.get(x.getUUID())));
        List<BlockPos> places = places(level, s, Math.min(MOST_BIDDERS, keen.size()));
        for (int i = 0; i < keen.size() && i < places.size(); i++) {
            VillageFolkEntity f = keen.get(i);
            s.bidders.put(f.getUUID(), places.get(i));
            AT.put(f.getUUID(), v.id());
            if (f.peekJob() != null) f.clearQueue();
            f.brain("off to the auction on the square");
        }
        tellPlayers(level, s, "The auction is starting " + venue(v.id()) + " at " + Villages.name(v.id()) + ": " + s.lots.size()
            + (s.lots.size() == 1 ? " lot" : " lots") + ". Right-click " + s.auctioneerName + " to bid.");
        LOG.info("[MCA-AUCTION] {}: {} calls the auction at {} with {} folk keen", Villages.name(v.id()), s.auctioneerName,
            s.rostrum == null ? "?" : s.rostrum.toShortString(), s.bidders.size());
        push(level, v);
    }

    /** Who calls it: the elder, else the leader, else the most practised grown folk free. */
    @Nullable
    static VillageFolkEntity auctioneerFor(ServerLevel level, Villages.Village v) {
        UUID elder = Villages.elder(v.id());
        if (elder != null && level.getEntity(elder) instanceof VillageFolkEntity e && Fleet.fit(e) && !e.isSleeping() && !Fleet.out(e)) return e;
        AssistantEntity lead = Villages.leader(v.id());
        if (lead instanceof VillageFolkEntity l && Fleet.fit(l) && !l.isSleeping() && !Fleet.out(l)) return l;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity f && Fleet.fit(f) && !f.isSleeping() && !Fleet.out(f)) return f;
        }
        return null;
    }

    /** The auctioneer's stand: in the auction house, once it stands, behind its rostrum (the lectern), facing the
     *  benches; before it, on the square, a spot of open ground a little off the heart, facing it, the crowd between. */
    static void stand(ServerLevel level, Villages.Village v, Sale s) {
        Ledger.Building house = house(v.id());
        if (house != null && level.isLoaded(house.anchor())) {
            int[] l = rostrumCell();
            s.house = house;
            s.rostrum = inHouse(house, l[0], 0, l[1] + 1);
            s.facing = house.facing().getOpposite();
            return;
        }
        BlockPos c = v.centre();
        int[][] spots = { { 0, -6 }, { 6, 0 }, { -6, 0 }, { 0, 6 }, { 5, -5 }, { -5, -5 }, { 5, 5 }, { -5, 5 }, { 0, -9 }, { 9, 0 } };
        for (int[] o : spots) {
            BlockPos p = surface(level, c.offset(o[0], 0, o[1]));
            if (p == null || Math.abs(p.getY() - c.getY()) > 3) continue;
            Direction toward = Math.abs(o[0]) >= Math.abs(o[1]) ? (o[0] > 0 ? Direction.WEST : Direction.EAST) : (o[1] > 0 ? Direction.NORTH : Direction.SOUTH);
            BlockPos front = surface(level, p.relative(toward, 3));
            if (front == null) continue;
            s.rostrum = p;
            s.facing = toward;
            return;
        }
        s.rostrum = surface(level, c.offset(0, 0, -4));
        if (s.rostrum == null) s.rostrum = c;
        s.facing = Direction.SOUTH;
    }

    // ------------------------------------------------------------------ the auction house

    /** The auction house, if the town has built one. */
    @Nullable
    static Ledger.Building house(UUID village) {
        return Villages.builtStructure(village, "auction");
    }

    /** A spot in the auction house by its drawing: across (right +), up, and toward the back (+). */
    static BlockPos inHouse(Ledger.Building b, int dx, int h, int dz) {
        Direction back = b.facing(), right = back.getClockWise();
        return b.anchor().relative(right, dx).relative(back, dz).above(h);
    }

    /** Where the rostrum (the lectern) stands in the drawing: {dx, dz}. */
    private static int[] rostrumCell() {
        for (com.jrpetty.mcassistant.entity.goal.Blueprints.Cell c : com.jrpetty.mcassistant.entity.goal.Blueprints.cells("auction")) {
            if (c.h() == 0 && c.key().part() == com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.LECTERN) return new int[]{ c.dx(), c.dz() };
        }
        return new int[]{ 0, 1 };
    }

    /** The bidders' benches (the stairs on the floor), the nearest the rostrum first. */
    static List<BlockPos> benches(Ledger.Building b) {
        List<int[]> cells = new ArrayList<>();
        for (com.jrpetty.mcassistant.entity.goal.Blueprints.Cell c : com.jrpetty.mcassistant.entity.goal.Blueprints.cells("auction")) {
            if (c.h() == 0 && c.key().part() == com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.BLOCK
                    && c.key().style() == com.jrpetty.mcassistant.entity.goal.Blueprints.Style.ROOF_STAIR) cells.add(new int[]{ c.dx(), c.dz() });
        }
        cells.sort(java.util.Comparator.<int[]>comparingInt(c -> -c[1]).thenComparingInt(c -> Math.abs(c[0])).thenComparingInt(c -> c[0]));
        List<BlockPos> out = new ArrayList<>();
        for (int[] c : cells) out.add(inHouse(b, c[0], 0, c[1]));
        return out;
    }

    /** How many market days the town has held an auction (the days in its sales books). */
    static int held(UUID village) {
        java.util.Set<String> days = new java.util.HashSet<>();
        for (String[] p : salesRows(village)) days.add(p[0]);
        return days.size();
    }

    /** [fleet] Does the town want an auction house (Villages.projectsWantedInOrder)? An Iron Age town of twenty-five that
     *  has held its auction on the square three market days or more. */
    public static boolean wanted(@Nullable UUID village, int folk) {
        return village != null && holds(village) && folk >= 25 && Villages.ageOf(village).ordinal() >= Villages.Age.IRON.ordinal()
            && held(village) >= 3;
    }

    /** Why it is building one (the board's "why"). */
    public static String why(UUID village) {
        return "an auction house: the market day's auction under a roof, a rostrum and benches for twelve bidders, now the town has held "
            + held(village) + " on its square";
    }

    /** Where the auction is held, in words. */
    static String venue(UUID village) {
        return house(village) != null ? "at the auction house" : "on the square";
    }

    /** Standing room at a column: the ground's top, with two of air over it. */
    @Nullable
    static BlockPos surface(ServerLevel level, BlockPos at) {
        if (!level.isLoaded(at)) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
        BlockPos p = new BlockPos(at.getX(), y, at.getZ());
        if (!level.getFluidState(p.below()).isEmpty() || !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) return null;
        if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty() || !level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) return null;
        return p;
    }

    /** The crowd's places before the stand: the auction house's benches, the nearest the rostrum first; on the square,
     *  rows of five, three to five blocks off, facing it. */
    static List<BlockPos> places(ServerLevel level, Sale s, int n) {
        List<BlockPos> out = new ArrayList<>();
        if (s.rostrum == null) return out;
        if (s.house != null) {
            for (BlockPos p : benches(s.house)) if (out.size() < n) out.add(p);
            BlockPos floor = s.rostrum.relative(s.facing, 2);
            while (out.size() < n) out.add(floor);
            return out;
        }
        Direction side = s.facing.getClockWise();
        int[] across = { 0, -1, 1, -2, 2 };
        for (int row = 0; row < 4 && out.size() < n; row++) {
            for (int k = 0; k < across.length && out.size() < n; k++) {
                BlockPos p = surface(level, s.rostrum.relative(s.facing, 3 + row).relative(side, across[k] * 2 - (row % 2)));
                if (p != null && !out.contains(p)) out.add(p);
            }
        }
        while (out.size() < n) out.add(s.rostrum.relative(s.facing, 3));
        return out;
    }

    // ------------------------------------------------------------------ what it is worth to a folk

    /** What a folk would go to for a lot, before the noise of the moment: its worth by its means, nature and wants,
     *  never past what it can spare (its purse, less a few coins for its food and anything it owes at the counter). */
    static int estimate(VillageFolkEntity f, Lot lot) {
        return most(f, lot, 1.0);
    }

    static int most(VillageFolkEntity f, Lot lot, double mood) {
        if (f.isBaby() || Visitors.is(f)) return 0;
        if (lot.owner == Owner.MERCHANT && f.getUUID().equals(lot.ownerId)) return 0;
        int spare = f.purse() - 3 - Purchases.slate(f);
        if (spare <= 0) return 0;
        double means = switch (Wealth.tier(f)) {
            case POOR -> 0.35;
            case GETTING_BY -> 0.7;
            case COMFORTABLE -> 1.0;
            case WELL_OFF -> 1.35;
            case WEALTHY -> 1.7;
        };
        double nature = Purchases.nature(f);
        if (f.knacks().has(FolkSkills.Knack.THRIFTY)) nature -= 0.25;               // the thrifty bow out early
        double m = lot.worth * means * nature * want(f, lot.item) * mood;
        return (int) Math.max(0, Math.min(spare, Math.floor(m)));
    }

    /** How much more (or less) than its worth a thing is to this folk: its trade's, its hobby's, its curiosity's. */
    static double want(VillageFolkEntity f, ItemStack s) {
        AssistantEntity.StationTask t = f.stationTask();
        Persona.Hobby hobby = f.persona().rolled() ? f.persona().hobby() : null;
        double w = 1.0;
        if (s.is(Items.DIAMOND) || s.is(Items.EMERALD)) {
            w = t == AssistantEntity.StationTask.SMITH || t == AssistantEntity.StationTask.MINE || t == AssistantEntity.StationTask.CAVE ? 1.3 : 1.0;
        } else if (s.is(Items.ENCHANTED_BOOK)) {
            w = t == AssistantEntity.StationTask.ENCHANT ? 1.5 : hobby == Persona.Hobby.READING ? 1.3 : 0.9;
        } else if (s.get(DataComponents.JUKEBOX_PLAYABLE) != null || s.is(Items.GOAT_HORN)) {
            w = hobby == Persona.Hobby.MUSIC ? 1.6 : 0.9;
        } else if (s.is(Items.SADDLE) || s.is(Items.NAME_TAG) || s.is(Items.IRON_HORSE_ARMOR) || s.is(Items.GOLDEN_HORSE_ARMOR)
                || s.is(Items.DIAMOND_HORSE_ARMOR)) {
            w = t == AssistantEntity.StationTask.RANCH ? 1.5 : 0.8;
        } else if (s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE) || s.is(Items.TOTEM_OF_UNDYING)) {
            w = t == AssistantEntity.StationTask.GUARD || t == AssistantEntity.StationTask.CAVE ? 1.3 : 1.0;
        } else if (s.is(Items.NAUTILUS_SHELL) || s.is(Items.HEART_OF_THE_SEA) || s.is(Items.TRIDENT) || s.is(Items.SPYGLASS)) {
            w = t == AssistantEntity.StationTask.FISH || hobby == Persona.Hobby.STARGAZING ? 1.4 : f.life().has(Social.Trait.CURIOUS) ? 1.25 : 0.9;
        }
        if (collector(f)) w *= 1.35;
        return w;
    }

    /** A collector: a Traditionalist at heart (old things are its love), or a curious soul with the means. */
    static boolean collector(VillageFolkEntity f) {
        Values.Value top = f.persona().rolled() ? Values.top(f) : null;
        return top == Values.Value.TRADITION
            || (f.life().has(Social.Trait.CURIOUS) && Wealth.tier(f).ordinal() >= Wealth.Tier.WELL_OFF.ordinal());
    }

    // ------------------------------------------------------------------ calling the lots

    /** The next bid the auctioneer will take: the reserve, or a step over the bid (a twelfth of it, one at least). */
    static int next(Lot lot) {
        if (lot.highBy == null) return lot.reserve;
        return lot.high + Math.max(1, (int) Math.round(lot.high / 12.0));
    }

    /** A lot called: held up, its tale told, and what each in the crowd will go to for it reckoned. */
    static void call(ServerLevel level, Villages.Village v, Sale s, int i, long now) {
        if (i >= s.lots.size()) {
            finish(level, v, s, "the last lot sold");
            return;
        }
        s.phase = Phase.CALLING;
        s.at = i;
        Lot lot = s.lots.get(i);
        lot.most.clear();
        for (UUID b : new ArrayList<>(s.bidders.keySet())) {
            if (!(level.getEntity(b) instanceof VillageFolkEntity f) || !f.isAlive()) continue;
            double mood = 0.9 + f.getRandom().nextDouble() * 0.2;
            lot.most.put(b, most(f, lot, mood));
        }
        // Nobody keen on what is left goes back to its day.
        for (UUID b : new ArrayList<>(s.bidders.keySet())) {
            if (!(level.getEntity(b) instanceof VillageFolkEntity f)) continue;
            boolean any = false;
            for (int k = i; k < s.lots.size() && !any; k++) any = estimate(f, s.lots.get(k)) >= s.lots.get(k).reserve;
            if (!any) {
                s.bidders.remove(b);
                AT.remove(b);
                f.brain("nothing left at the auction it wants");
            }
        }
        display(level, s, lot);
        VillageFolkEntity a = auctioneer(level, s);
        String num = i == 0 ? "The first lot" : i == s.lots.size() - 1 ? "And the last lot" : "Lot " + (i + 1);
        if (a != null) {
            FolkTalk.speak(a, num + ": " + JobMarket.a(lot.name()) + ", " + lot.from + ". Who'll start me at " + lot.reserve + "?");
            a.swing(InteractionHand.MAIN_HAND);
        }
        tellPlayers(level, s, "[Auction] Lot " + (i + 1) + " of " + s.lots.size() + ": " + cap(lot.name()) + " (" + lot.from + ") — bids from "
            + lot.reserve + coinWord(lot.reserve) + ". Right-click " + s.auctioneerName + " to bid.");
        s.nextAt = now + ROUND * 2;
        push(level, v);
    }

    /** One of the auctioneer's calls: a folk raises, or nobody does and it is going once, twice, sold. */
    static void round(ServerLevel level, Villages.Village v, Sale s, long now) {
        Lot lot = s.lot();
        if (lot == null) {
            finish(level, v, s, "no lot");
            return;
        }
        int next = next(lot);
        List<VillageFolkEntity> keen = new ArrayList<>();
        List<Integer> weight = new ArrayList<>();
        int total = 0;
        for (Map.Entry<UUID, Integer> e : lot.most.entrySet()) {
            if (e.getValue() < next || e.getKey().equals(lot.highBy) || !s.bidders.containsKey(e.getKey())) continue;
            if (!(level.getEntity(e.getKey()) instanceof VillageFolkEntity f) || !f.isAlive() || !present(f, s)) continue;
            if (f.purse() < next) continue;                                                // never past its purse
            keen.add(f);
            int w = e.getValue() - next + 1;
            weight.add(w);
            total += w;
        }
        VillageFolkEntity a = auctioneer(level, s);
        if (!keen.isEmpty()) {
            int r = level.getRandom().nextInt(total), k = 0;
            while (r >= weight.get(k)) { r -= weight.get(k); k++; }
            VillageFolkEntity f = keen.get(k);
            int most = lot.most.get(f.getUUID());
            int bid = next;
            // The well-off and the keen go in with a jump, to see the others off.
            if ((Wealth.tier(f).ordinal() >= Wealth.Tier.WELL_OFF.ordinal() || collector(f)) && most >= next * 3 / 2) {
                bid = Math.min(most, next + Math.max(1, (int) Math.round(next / 6.0)));
            }
            place(level, s, lot, f.getUUID(), f.displayNameCap(), false, bid);
            f.swing(InteractionHand.MAIN_HAND);
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), bid + "!", bid + " coins!", "I'll say " + bid + ".", bid + ", here!"));
            if (a != null) {
                FolkTalk.speak(a, FolkTalk.pick(f.getRandom(), bid + " from " + f.displayNameCap() + "! Do I hear " + next(lot) + "?",
                    bid + ", thank you " + f.displayNameCap() + ". " + next(lot) + " anywhere?", "I have " + bid + "! Who'll give me " + next(lot) + "?"));
            }
            tellPlayers(level, s, "[Auction] " + cap(lot.name()) + ": " + bid + coinWord(bid) + " from " + f.displayNameCap() + ". Next bid " + next(lot) + ".");
            s.nextAt = now + ROUND;
            push(level, v);
            return;
        }
        lot.calls++;
        if (lot.highBy == null) {
            if (lot.calls >= 3) {
                unsold(level, v, s, lot);
                return;
            }
            if (a != null) FolkTalk.speak(a, lot.calls == 1 ? "Come now — " + lot.reserve + " for " + JobMarket.a(lot.name()) + "? A bargain!"
                : "Nobody? Last chance at " + lot.reserve + "...");
        } else if (lot.calls == 1) {
            if (a != null) FolkTalk.speak(a, "Going once, at " + lot.high + "...");
        } else if (lot.calls == 2) {
            if (a != null) FolkTalk.speak(a, "Going twice, to " + lot.highName + " at " + lot.high + "...");
        } else {
            hammer(level, v, s, lot, now);
            return;
        }
        if (lot.calls <= 2) tellPlayers(level, s, "[Auction] " + cap(lot.name()) + (lot.highBy == null ? ": no bids yet, from " + lot.reserve
            : ": going " + (lot.calls == 1 ? "once" : "twice") + " at " + lot.high + " to " + lot.highName) + ".");
        s.nextAt = now + ROUND;
        push(level, v);
    }

    /** Is a bidder in the crowd (or near enough to be heard)? */
    static boolean present(VillageFolkEntity f, Sale s) {
        if (manual) return true;
        BlockPos at = s.bidders.get(f.getUUID());
        return at != null && f.blockPosition().distSqr(at) <= 6 * 6 || s.rostrum != null && f.blockPosition().distSqr(s.rostrum) <= 10 * 10;
    }

    /** A bid taken: the lot's new high, the last player's held coin handed back if it was beaten. */
    private static void place(ServerLevel level, Sale s, Lot lot, UUID by, String name, boolean player, int coins) {
        if (lot.highPlayer && lot.highBy != null && !lot.highBy.equals(by)) {
            Integer back = s.escrow.remove(lot.highBy);
            if (back != null && back > 0) give(level, s.village, lot.highBy, back, ItemStack.EMPTY, "outbid on the " + lot.name() + ": your " + back + coinWord(back) + " back");
            keepEscrow(s);
        }
        lot.bids.add(new Bid(by, name, player, coins));
        lot.high = coins;
        lot.highBy = by;
        lot.highPlayer = player;
        lot.highName = name;
        lot.calls = 0;
    }

    /**
     * A player's bid on the lot being called: at least the next bid, out of the coin in their pack, which the town holds
     * from now till somebody beats it. Returns what to tell them.
     */
    public static String bid(ServerLevel level, Villages.Village v, Player p, int coins) {
        UUID id = v.id();
        Sale s = SALES.get(id);
        if (s == null || s.phase != Phase.CALLING || s.lot() == null) return whenNext(level, id);
        Lot lot = s.lot();
        if (Standing.of(id, p.getUUID(), level.getGameTime()).title() == Standing.Title.OUTCAST) return "Nobody here will take your bid.";
        if (Laws.banished(id, p.getUUID(), level.getDayTime() / 24000L)) return "You're banished from " + Villages.name(id) + ".";
        if (lot.owner == Owner.PLAYER && p.getUUID().equals(lot.ownerId)) return "That's your own lot — you can't bid on it.";
        if (lot.highPlayer && p.getUUID().equals(lot.highBy)) return "You're the highest bidder already, at " + lot.high + ".";
        int min = next(lot);
        if (coins < min) return lot.highBy == null ? "Bids from " + min + coinWord(min) + "." : "The bid's " + lot.high + " — you'll have to bid " + min + " or more.";
        int held = Market.coinsHeld(p);
        if (held < coins) return "You've only " + held + coinWord(held) + " on you.";
        Market.payOut(p, coins);
        DEALT_WITH.put(p.getUUID(), p);
        s.escrow.put(p.getUUID(), coins);
        keepEscrow(s);
        place(level, s, lot, p.getUUID(), p.getName().getString(), true, coins);
        long now = level.getGameTime();
        s.nextAt = Math.max(s.nextAt, now + ROUND);
        VillageFolkEntity a = auctioneer(level, s);
        if (a != null) {
            FolkTalk.speak(a, FolkTalk.pick(a.getRandom(), coins + " from " + p.getName().getString() + "! Do I hear " + next(lot) + "?",
                "A bid from the visitor — " + coins + "! " + next(lot) + " anywhere?"));
            a.getLookControl().setLookAt(p, 30.0F, 30.0F);
        }
        tellPlayers(level, s, "[Auction] " + cap(lot.name()) + ": " + coins + coinWord(coins) + " from " + p.getName().getString() + ". Next bid " + next(lot) + ".");
        push(level, v);
        return "Your bid: " + coins + coinWord(coins) + " for the " + lot.name() + ". The town holds your coin; you'll have it back if you're beaten.";
    }

    /** No sale: nobody would go to the reserve. The town's lot stays in the stores, a player's is handed back, a
     *  merchant's goes home with it. */
    private static void unsold(ServerLevel level, Villages.Village v, Sale s, Lot lot) {
        lot.result = "unsold";
        if (lot.owner == Owner.PLAYER && lot.ownerId != null) {
            unhold(level, v.id(), lot);
            give(level, v.id(), lot.ownerId, 0, lot.item, "your " + lot.name() + " back: nobody would go to " + lot.reserve);
        }
        VillageFolkEntity a = auctioneer(level, s);
        if (a != null) FolkTalk.speak(a, "No takers. The " + lot.name() + " goes back where it came from.");
        tellPlayers(level, s, "[Auction] " + cap(lot.name()) + ": no sale.");
        record(v.id(), s.day, lot, null, 0);
        s.nextAt = level.getGameTime() + ROUND * 2;
        call(level, v, s, s.at + 1, level.getGameTime());
    }

    /**
     * Sold! The best bid that can still be paid takes it (a folk that has spent its coin since is passed over for the next
     * best): the thing out of the stores, the player's lot or the merchant's pack and to the winner, the coin out of the
     * winner's purse (or the player's held bid) to the seller.
     */
    static void hammer(ServerLevel level, Villages.Village v, Sale s, Lot lot, long now) {
        List<Bid> order = new ArrayList<>(lot.bids);
        order.sort((x, y) -> Integer.compare(y.coins(), x.coins()));
        Bid won = null;
        VillageFolkEntity folk = null;
        for (Bid b : order) {
            if (b.player()) {
                Integer held = s.escrow.get(b.by());
                if (held != null && held == b.coins() && b.by().equals(lot.highBy)) { won = b; break; }
                continue;
            }
            if (level.getEntity(b.by()) instanceof VillageFolkEntity f && f.isAlive() && f.purse() >= b.coins()) {
                won = b;
                folk = f;
                break;
            }
        }
        if (won == null) {
            unsold(level, v, s, lot);
            return;
        }
        ItemStack item = takeLot(level, v, lot);
        if (item.isEmpty()) {
            if (won.player()) {
                Integer back = s.escrow.remove(won.by());
                if (back != null) give(level, v.id(), won.by(), back, ItemStack.EMPTY, "the " + lot.name() + " was gone: your bid back");
                keepEscrow(s);
            }
            lot.result = "gone";
            VillageFolkEntity a = auctioneer(level, s);
            if (a != null) FolkTalk.speak(a, "The " + lot.name() + "'s gone from the stores! I'm sorry — no sale.");
            call(level, v, s, s.at + 1, now);
            return;
        }
        int price = won.coins();
        if (won.player()) {
            s.escrow.remove(won.by());
            keepEscrow(s);
        } else if (!folk.spend(price)) {
            // Its purse would not run to it after all: the thing back where it came from, and the lot unsold.
            putBack(level, v, lot, item);
            unsold(level, v, s, lot);
            return;
        }
        // The coin to the seller.
        switch (lot.owner) {
            case TOWN -> {
                Ledger.addCoins(v.id(), price);
                Economy.sold(v.id(), price);
            }
            case PLAYER -> {
                if (lot.ownerId != null) give(level, v.id(), lot.ownerId, price, ItemStack.EMPTY, "your " + lot.name() + " sold at auction for " + price);
            }
            case MERCHANT -> {
                if (lot.ownerId != null && level.getEntity(lot.ownerId) instanceof VillageFolkEntity m) m.earn(price);
            }
        }
        // The thing to the winner.
        String who;
        if (won.player()) {
            give(level, v.id(), won.by(), 0, item, "you won the " + lot.name() + " at auction for " + price);
            who = won.name();
        } else {
            deliver(level, v, folk, item, price, s.day);
            who = whoWords(level, folk);
        }
        lot.result = "sold to " + won.name() + " for " + price;
        s.sold++;
        s.takings += price;
        record(v.id(), s.day, lot, who, price);
        Villages.tell(v.id(), s.day, "at the auction " + JobMarket.a(lot.name()) + (lot.owner == Owner.TOWN ? "" : " " + lot.from)
            + " sold for " + price + coinWord(price) + " to " + who);
        VillageFolkEntity a = auctioneer(level, s);
        if (a != null) {
            FolkTalk.speak(a, "Sold! To " + won.name() + ", for " + price + coinWord(price) + "!");
            a.swing(InteractionHand.MAIN_HAND);
        }
        if (s.rostrum != null) {
            level.playSound(null, s.rostrum, SoundEvents.WOOD_HIT, SoundSource.BLOCKS, 1.2F, 0.8F);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, s.rostrum.getX() + 0.5, s.rostrum.getY() + 2.2, s.rostrum.getZ() + 0.5, 6, 0.4, 0.2, 0.4, 0.0);
        }
        if (folk != null) {
            FolkTalk.speak(folk, FolkTalk.pick(folk.getRandom(), "It's mine! I can hardly believe it.", "Worth every coin.",
                "Wait till they see this at home!", "Ha! I knew I'd have it."));
        }
        tellPlayers(level, s, "[Auction] Sold: " + cap(lot.name()) + " to " + won.name() + " for " + price + coinWord(price) + ".");
        LOG.info("[MCA-AUCTION] {}: {} sold to {} for {} (reserve {}, worth {}, {} bids)", Villages.name(v.id()), lot.name(), won.name(),
            price, lot.reserve, lot.worth, lot.bids.size());
        call(level, v, s, s.at + 1, now);
    }

    /** The thing itself, for the winner: out of the stores (the town's), the town's keeping (a player's), the merchant's
     *  pack. Empty if it is gone. */
    static ItemStack takeLot(ServerLevel level, Villages.Village v, Lot lot) {
        switch (lot.owner) {
            case TOWN -> {
                ItemStack one = lot.item.copyWithCount(1);
                Predicate<ItemStack> same = s -> ItemStack.isSameItemSameComponents(s, one);
                return TownWork.take(level, v, same, 1) ? one : ItemStack.EMPTY;
            }
            case PLAYER -> {
                unhold(level, v.id(), lot);
                return lot.item.copy();
            }
            case MERCHANT -> {
                if (lot.ownerId == null || !(level.getEntity(lot.ownerId) instanceof VillageFolkEntity m)) return ItemStack.EMPTY;
                ItemStack mark = lot.item;
                int got = m.removeMatching(s -> ItemStack.isSameItemSameComponents(s, mark), 1);
                return got > 0 ? unmarked(mark.copyWithCount(1)) : ItemStack.EMPTY;
            }
        }
        return ItemStack.EMPTY;
    }

    private static void putBack(ServerLevel level, Villages.Village v, Lot lot, ItemStack item) {
        switch (lot.owner) {
            case TOWN -> Crafts.store(level, v, item);
            case PLAYER -> {
                if (lot.ownerId != null) give(level, v.id(), lot.ownerId, 0, item, "your " + lot.name() + " back");
            }
            case MERCHANT -> {
                if (lot.ownerId != null && level.getEntity(lot.ownerId) instanceof VillageFolkEntity m) m.insertItem(lot.item.copy());
            }
        }
    }

    /** The winner takes it home: worn, if it is something to wear and it has nothing on there; else its own keepsake,
     *  carried home to its chest of an evening (Homes). It remembers. */
    static void deliver(ServerLevel level, Villages.Village v, VillageFolkEntity f, ItemStack item, int price, long day) {
        Homes.keepsake(item, f);
        String what = lower(item.getHoverName().getString());
        if (item.getItem() instanceof ArmorItem armour) {
            EquipmentSlot slot = armour.getEquipmentSlot();
            if (f.getItemBySlot(slot).isEmpty()) {
                f.setItemSlot(slot, item);
                item = ItemStack.EMPTY;
            }
        }
        if (!item.isEmpty()) {
            ItemStack left = f.insertGiven(item);
            if (!left.isEmpty()) net.minecraft.world.level.block.Block.popResource(level, f.blockPosition(), left);
        }
        f.persona().remember(day, "I won " + JobMarket.a(what) + " at the auction for " + price + coinWord(price), 4);
        Ledger.note(v.id(), "auction.folkwon/" + f.getUUID(), what + "|" + price + "|" + day);
    }

    /** "Mara, the smith's partner"; "Bram the miner"; or the name. */
    static String whoWords(ServerLevel level, VillageFolkEntity f) {
        String trade = f.isBaby() ? "" : f.stationTask() == AssistantEntity.StationTask.NONE ? "" : JobMarket.noun(f.stationTask());
        if (!trade.isEmpty()) return f.displayNameCap() + " the " + trade;
        UUID partner = f.life().partner();
        if (partner != null && level.getEntity(partner) instanceof VillageFolkEntity p && p.stationTask() != AssistantEntity.StationTask.NONE) {
            return f.displayNameCap() + ", the " + JobMarket.noun(p.stationTask()) + "'s partner";
        }
        return f.displayNameCap();
    }

    /** The day's auction over: the stand cleared, the crowd back to its day, a player's lot not reached handed back, the
     *  day's takings into the chronicle. */
    static void finish(ServerLevel level, Villages.Village v, Sale s, String why) {
        if (s.phase == Phase.DONE) return;
        Lot lot = s.lot();
        if (lot != null && lot.result.isEmpty() && lot.highPlayer && lot.highBy != null) {
            Integer back = s.escrow.remove(lot.highBy);
            if (back != null) give(level, v.id(), lot.highBy, back, ItemStack.EMPTY, "the auction ended before the " + lot.name() + " was sold: your bid back");
        }
        for (Map.Entry<UUID, Integer> e : s.escrow.entrySet()) give(level, v.id(), e.getKey(), e.getValue(), ItemStack.EMPTY, "your bid back");
        s.escrow.clear();
        keepEscrow(s);
        s.phase = Phase.DONE;
        if (s.at >= 0) Ledger.note(v.id(), "auction.day", Long.toString(s.day));
        for (UUID b : s.bidders.keySet()) AT.remove(b);
        if (s.auctioneer != null) AT.remove(s.auctioneer);
        clearDisplays(level, v, s);
        VillageFolkEntity a = auctioneer(level, s);
        if (a != null && s.at >= 0) {
            FolkTalk.speak(a, s.sold > 0 ? "That's the last lot. Thank you all — " + s.takings + coinWord(s.takings) + " well spent!"
                : "That's all for today. Better luck next market day.");
        }
        if (s.at >= 0) {
            Villages.tell(v.id(), s.day, "the auction " + venue(v.id()) + " sold " + s.sold + " of " + s.lots.size() + (s.lots.size() == 1 ? " lot" : " lots")
                + " for " + s.takings + coinWord(s.takings));
        }
        LOG.info("[MCA-AUCTION] {}: the auction is over ({}): {} of {} sold for {}", Villages.name(v.id()), why, s.sold, s.lots.size(), s.takings);
        push(level, v);
    }

    @Nullable
    private static VillageFolkEntity auctioneer(ServerLevel level, Sale s) {
        return s.auctioneer != null && level.getEntity(s.auctioneer) instanceof VillageFolkEntity f && f.isAlive() ? f : null;
    }

    // ------------------------------------------------------------------ the lot held up

    /** The lot over the stand for everybody to see: a picture of it, held up (a display: nothing anybody can take). */
    private static void display(ServerLevel level, Sale s, Lot lot) {
        if (s.rostrum == null) return;
        Display.ItemDisplay d = s.display != null && level.getEntity(s.display) instanceof Display.ItemDisplay old ? old : null;
        if (d == null) {
            d = EntityType.ITEM_DISPLAY.create(level);
            if (d == null) return;
            BlockPos at = s.rostrum.relative(s.facing);
            d.moveTo(at.getX() + 0.5, at.getY() + 1.6, at.getZ() + 0.5, 0.0F, 0.0F);
            CompoundTag t = d.saveWithoutId(new CompoundTag());
            t.putString("billboard", "vertical");
            d.load(t);
            d.addTag(DISPLAY_TAG);
            level.addFreshEntity(d);
            s.display = d.getUUID();
        }
        d.getSlot(0).set(unmarked(lot.item).copyWithCount(1));
    }

    private static void clearDisplays(ServerLevel level, Villages.Village v, @Nullable Sale s) {
        for (Display.ItemDisplay d : level.getEntitiesOfClass(Display.ItemDisplay.class, new AABB(v.centre()).inflate(24, 12, 24),
                e -> e.getTags().contains(DISPLAY_TAG))) d.discard();
        if (s != null) s.display = null;
    }

    // ------------------------------------------------------------------ the folk at the auction

    /** Is this folk at the auction (calling it, or in the crowd)? */
    public static boolean busy(VillageFolkEntity f) {
        return AT.containsKey(f.getUUID());
    }

    /**
     * Every tick (VillageFolkEntity.aiStep), after the storm and the fire: the auctioneer to its stand and calling; the
     * crowd to its places, facing it. And the fish market's folk (FishMarket.hold). True while that is its morning.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (FishMarket.hold(f, level)) return true;
        UUID village = AT.get(f.getUUID());
        if (village == null) return false;
        Sale s = SALES.get(village);
        long tod = level.getDayTime() % 24000L;
        if (s == null || s.phase == Phase.DONE || s.phase == Phase.LOTS || s.rostrum == null || !village.equals(f.ownerId()) || f.isSleeping()
                || tod >= LATEST + 1000L || s.day != level.getDayTime() / 24000L) {      // never kept past the morning
            AT.remove(f.getUUID());
            return false;
        }
        long now = level.getGameTime();
        if (f.getUUID().equals(s.auctioneer)) {
            if (f.blockPosition().distSqr(s.rostrum) > 1.5 * 1.5) {
                if (f.getNavigation().isDone() || now % 40L == 0L) f.walkTo(s.rostrum, 1.0D);
                return true;
            }
            f.getNavigation().stop();
            BlockPos c = s.rostrum.relative(s.facing, 4);
            f.getLookControl().setLookAt(c.getX() + 0.5, c.getY() + 1.5, c.getZ() + 0.5);
            return true;
        }
        BlockPos place = s.bidders.get(f.getUUID());
        if (place == null) {
            AT.remove(f.getUUID());
            return false;
        }
        if (f.blockPosition().distSqr(place) > 1.5 * 1.5) {
            if (f.getNavigation().isDone() || now % 40L == 0L) f.walkTo(place, 1.0D);
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(s.rostrum.getX() + 0.5, s.rostrum.getY() + 1.6, s.rostrum.getZ() + 0.5);
        return true;
    }

    // ------------------------------------------------------------------ players: the bid screen and the talk

    /** A player right-clicks a folk: the auctioneer, with the auction on (or to come today), opens the bid screen.
     *  True if it did. */
    public static boolean serveAPlayer(VillageFolkEntity f, ServerPlayer p) {
        UUID village = f.ownerId();
        Sale s = village == null ? null : SALES.get(village);
        if (s == null || s.phase == Phase.DONE || s.phase == Phase.LOTS && !f.isElder()) return false;
        if (s.phase != Phase.LOTS && !f.getUUID().equals(s.auctioneer)) return false;
        if (!(f.level() instanceof ServerLevel level)) return false;
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        screen(level, v, p, "");
        return true;
    }

    /** The bid screen's figures for a player. */
    static CompoundTag screenData(ServerLevel level, Villages.Village v, Player p, String message) {
        CompoundTag t = new CompoundTag();
        UUID id = v.id();
        Sale s = SALES.get(id);
        t.putString("town", Villages.name(id));
        t.putString("message", message);
        t.putInt("coins", Market.coinsHeld(p));
        ItemStack hand = p.getMainHandItem();
        t.putString("hand", hand.isEmpty() || Market.isCoin(hand) ? "" : hand.getHoverName().getString());
        t.putString("phase", s == null ? "NONE" : s.phase.name());
        t.putString("auctioneer", s == null ? "" : s.auctioneerName);
        t.putString("when", whenNext(level, id));
        if (s != null) {
            Lot lot = s.lot();
            if (lot != null && s.phase == Phase.CALLING) {
                CompoundTag l = new CompoundTag();
                l.put("item", unmarked(lot.item).save(level.registryAccess()));
                l.putString("name", cap(lot.name()));
                l.putString("from", lot.from);
                l.putInt("reserve", lot.reserve);
                l.putInt("worth", lot.worth);
                l.putInt("high", lot.highBy == null ? 0 : lot.high);
                l.putString("highName", lot.highName);
                l.putBoolean("mine", lot.highPlayer && p.getUUID().equals(lot.highBy));
                l.putBoolean("own", lot.owner == Owner.PLAYER && p.getUUID().equals(lot.ownerId));
                l.putInt("next", next(lot));
                l.putInt("calls", lot.calls);
                l.putInt("number", s.at + 1);
                l.putInt("of", s.lots.size());
                ListTag bids = new ListTag();
                for (int i = lot.bids.size() - 1; i >= 0 && bids.size() < 8; i--) {
                    Bid b = lot.bids.get(i);
                    bids.add(StringTag.valueOf(b.coins() + "c — " + b.name()));
                }
                l.put("bids", bids);
                t.put("lot", l);
            }
            ListTag lots = new ListTag();
            for (int i = 0; i < s.lots.size(); i++) {
                Lot x = s.lots.get(i);
                lots.add(StringTag.valueOf((i + 1) + ". " + cap(x.name()) + " — " + x.from + ", from " + x.reserve + "c"
                    + (x.result.isEmpty() ? (i == s.at && s.phase == Phase.CALLING ? " (now)" : "") : ": " + x.result)));
            }
            t.put("lots", lots);
        }
        ListTag sales = new ListTag();
        for (String line : sales(id)) {
            if (sales.size() >= 8) break;
            sales.add(StringTag.valueOf(line));
        }
        t.put("sales", sales);
        return t;
    }

    /** Open (or bring up to date) a player's bid screen. */
    static void screen(ServerLevel level, Villages.Village v, ServerPlayer p, String message) {
        VIEWERS.put(p.getUUID(), v.id());
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,
            new com.jrpetty.mcassistant.net.AuctionScreenPayload(screenData(level, v, p, message)));
    }

    /** Everybody with the bid screen open on this town's auction, brought up to date. */
    static void push(ServerLevel level, Villages.Village v) {
        if (level.getServer() == null) return;
        for (Map.Entry<UUID, UUID> e : new ArrayList<>(VIEWERS.entrySet())) {
            if (!e.getValue().equals(v.id())) continue;
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
            if (p == null || p.level() != level || !p.blockPosition().closerThan(v.centre(), Villages.townReach(v.id()) + 48)) {
                VIEWERS.remove(e.getKey());
                continue;
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,
                new com.jrpetty.mcassistant.net.AuctionScreenPayload(screenData(level, v, p, "")));
        }
    }

    /** A button on the bid screen: "bid" (coins), "put" (the thing in hand, at a reserve), "close". */
    public static void act(ServerPlayer p, String action, CompoundTag args) {
        if (!(p.level() instanceof ServerLevel level)) return;
        UUID id = VIEWERS.get(p.getUUID());
        Villages.Village v = id == null ? Villages.nearest(level, p.blockPosition(), Villages.VILLAGE_RANGE) : Villages.get(id);
        if (v == null) return;
        if (!p.blockPosition().closerThan(v.centre(), Villages.townReach(v.id()) + 48)) return;
        String said = switch (action) {
            case "bid" -> bid(level, v, p, args.getInt("coins"));
            case "put" -> putUp(level, v, p, args.getInt("reserve"));
            case "close" -> {
                VIEWERS.remove(p.getUUID());
                yield null;
            }
            default -> "";
        };
        if (said != null) screen(level, v, p, said);
    }

    /** Talking to a folk about the auction ("What's up for auction?", "I bid 30", "Put this up for auction"). */
    public static String talk(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "Auctions? There's no town here to hold one.";
        Villages.Village v = Villages.get(village);
        if (v == null) return "Auctions? There's no town here to hold one.";
        // A lot won at the old sealed-bid sale before the town held its auction on the square: collected there.
        String old = Ledger.note(village, "auction.won/" + p.getUUID());
        if (old != null && !old.isEmpty()) return Commerce.auction(f, p, text == null ? "" : text);
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (t.contains("put") || t.contains("sell")) return putUp(level, v, p, Commerce.number(t, 0));
        Sale s = SALES.get(village);
        if (t.contains("bid")) {
            int n = Commerce.number(t, 0);
            if (n > 0) return bid(level, v, p, n);
        }
        if (s != null && s.phase == Phase.CALLING && s.lot() != null) {
            Lot lot = s.lot();
            return "Up now: " + JobMarket.a(lot.name()) + ", " + lot.from + ". " + (lot.highBy == null ? "Bids from " + lot.reserve
                : lot.high + " to " + lot.highName + "; the next bid is " + next(lot)) + ". Say \"I bid " + next(lot) + "\", or right-click "
                + s.auctioneerName + ".";
        }
        if (s != null && s.phase != Phase.DONE && !s.lots.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Lot l : s.lots) names.add(JobMarket.a(l.name()));
            return "There's an auction " + venue(village) + " this morning: " + list(names) + ". Come along at nine and bid!";
        }
        return whenNext(level, village) + " Hand me what you want sold and say \"put it up\": the town keeps it for you till then.";
    }

    /** When the next auction is, in words. */
    static String whenNext(ServerLevel level, UUID village) {
        if (!holds(village)) return Villages.name(village) + " holds no auctions yet: a town of " + FROM + " from the Stone Age has one each market day.";
        long day = level.getDayTime() / 24000L;
        Sale s = SALES.get(village);
        if (Market.marketDay(village, day) && (s == null || s.phase != Phase.DONE) && level.getDayTime() % 24000L < LATEST) {
            return "The auction's this morning, " + venue(village) + ", at nine.";
        }
        int d = Market.daysToMarket(village, day + 1) + 1;
        return "The next auction is on market day, in " + d + (d == 1 ? " day." : " days.");
    }

    /** Players near the stand hear the lots and the bids (the bubbles say it to whoever is close; this says it to the
     *  player across the square too). */
    private static void tellPlayers(ServerLevel level, Sale s, String text) {
        if (s.rostrum == null) return;
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().closerThan(s.rostrum, 32)) p.displayClientMessage(Component.literal(text), true);
        }
    }

    // ------------------------------------------------------------------ the books

    /** The sales, kept with the town (the last thirty): "day|lot|price|buyer|seller|from". */
    private static void record(UUID village, long day, Lot lot, @Nullable String buyer, int price) {
        String s = Ledger.note(village, "auction.sales");
        List<String> lines = new ArrayList<>();
        if (s != null && !s.isEmpty()) lines.addAll(List.of(s.split("\n")));
        lines.add(day + "|" + clean(lot.name()) + "|" + price + "|" + clean(buyer == null ? "" : buyer) + "|" + clean(lot.ownerName) + "|" + clean(lot.from));
        while (lines.size() > 30) lines.remove(0);
        Ledger.note(village, "auction.sales", String.join("\n", lines));
    }

    private static String clean(String s) {
        return s.replace('|', '/').replace('\n', ' ');
    }

    /** The past sales, newest first: "Day 12: a diamond, from the caves, to Mara the smith for 24c". */
    public static List<String> sales(UUID village) {
        List<String> out = new ArrayList<>();
        for (String[] p : salesRows(village)) {
            try {
                long day = Long.parseLong(p[0]);
                out.add("Day " + (day + 1) + ": " + JobMarket.a(p[1]) + (p[3].isEmpty() ? " — no sale" : " to " + p[3] + " for " + p[2] + "c")
                    + (p[5].isEmpty() ? "" : " (" + p[5] + ")"));
            } catch (NumberFormatException ignored) {
                // an unreadable line: left out
            }
        }
        return out;
    }

    private static List<String[]> salesRows(UUID village) {
        List<String[]> out = new ArrayList<>();
        String s = Ledger.note(village, "auction.sales");
        if (s == null || s.isEmpty()) return out;
        String[] lines = s.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String[] p = lines[i].split("\\|", -1);
            if (p.length >= 6) out.add(p);
        }
        return out;
    }

    /** The board's lines: the auction today, on now, or done; the next one. */
    public static List<String> board(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        if (!holds(village)) return out;
        Sale s = SALES.get(village);
        long day = level.getDayTime() / 24000L;
        if (s != null && s.day == day && !s.lots.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Lot l : s.lots) names.add(l.name());
            switch (s.phase) {
                case LOTS, GATHER -> out.add("RG|Auction " + venue(village) + " at nine: " + list(names) + " — come and bid!");
                case CALLING -> {
                    Lot lot = s.lot();
                    if (lot != null) out.add("RG|The auction is on: lot " + (s.at + 1) + " of " + s.lots.size() + ", " + JobMarket.a(lot.name())
                        + (lot.highBy == null ? ", from " + lot.reserve + "c" : ", " + lot.high + "c to " + lot.highName) + ".");
                }
                case DONE -> out.add("RN|At the auction today: " + s.sold + " of " + s.lots.size() + " sold for " + s.takings + "c.");
            }
            return out;
        }
        List<String[]> rows = salesRows(village);
        for (String[] p : rows) {
            if (p[3].isEmpty()) continue;
            out.add("FN|Last sold at auction: " + JobMarket.a(p[1]) + " to " + p[3] + " for " + p[2] + "c. " + whenNext(level, village));
            return out;
        }
        out.add("FN|" + whenNext(level, village));
        return out;
    }

    /** The gazette: yesterday's sales. */
    @Nullable
    public static String gazette(UUID village, long day) {
        List<String> lines = new ArrayList<>();
        for (String[] p : salesRows(village)) {
            if (!p[0].equals(Long.toString(day - 1)) || p[3].isEmpty()) continue;
            lines.add(cap(JobMarket.a(p[1])) + " sold for " + p[2] + " coins to " + p[3] + ".");
        }
        if (lines.isEmpty()) return null;
        java.util.Collections.reverse(lines);
        StringBuilder sb = new StringBuilder("§lThe auction§r");
        for (int i = 0; i < Math.min(4, lines.size()); i++) sb.append('\n').append(lines.get(i));
        return sb.toString();
    }

    /** Its card: what it won, or the auction it is at. */
    public static String cardLine(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return "";
        Sale s = SALES.get(village);
        if (s != null && AT.containsKey(f.getUUID())) {
            if (f.getUUID().equals(s.auctioneer)) return "calling the auction " + venue(village);
            Lot lot = s.lot();
            Integer most = lot == null ? null : lot.most.get(f.getUUID());
            return "at the auction" + (lot == null ? "" : ", for the " + lot.name() + (most != null && most >= lot.reserve ? " (up to " + most + "c)" : " (only looking)"));
        }
        String won = Ledger.note(village, "auction.folkwon/" + f.getUUID());
        if (won == null || won.isEmpty()) return "";
        String[] p = won.split("\\|");
        if (p.length < 3) return "";
        try {
            return "won " + JobMarket.a(p[0]) + " at the auction for " + p[1] + "c on day " + (Long.parseLong(p[2]) + 1);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /** The Auction page of the town's books: today's lots and the bids, the past sales, and the fleet's panel. */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        CompoundTag out = new CompoundTag();
        UUID id = v.id();
        out.putBoolean("holds", holds(id));
        out.putInt("from", FROM);
        out.putString("when", whenNext(level, id));
        Sale s = SALES.get(id);
        out.putString("phase", s == null ? "NONE" : s.phase.name());
        out.putString("auctioneer", s == null ? "" : s.auctioneerName);
        ListTag lots = new ListTag();
        if (s != null) {
            for (int i = 0; i < s.lots.size(); i++) {
                Lot l = s.lots.get(i);
                CompoundTag c = new CompoundTag();
                c.putString("item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(l.item.getItem()).toString());
                c.putString("name", cap(l.name()));
                c.putString("from", l.from);
                c.putInt("reserve", l.reserve);
                c.putInt("worth", l.worth);
                c.putInt("high", l.highBy == null ? 0 : l.high);
                c.putString("highName", l.highName);
                c.putString("result", l.result);
                c.putBoolean("now", i == s.at && s.phase == Phase.CALLING);
                ListTag bids = new ListTag();
                for (int k = l.bids.size() - 1; k >= 0 && bids.size() < 6; k--) bids.add(StringTag.valueOf(l.bids.get(k).coins() + "c " + l.bids.get(k).name()));
                c.put("bids", bids);
                lots.add(c);
            }
            out.putInt("sold", s.sold);
            out.putInt("takings", s.takings);
            out.putInt("crowd", s.bidders.size());
        }
        out.put("lots", lots);
        ListTag past = new ListTag();
        for (String line : sales(id)) past.add(StringTag.valueOf(line));
        out.put("sales", past);
        ListTag waiting = new ListTag();
        for (Lot l : playerLots(level, v)) waiting.add(StringTag.valueOf(cap(l.name()) + ", put up by " + l.ownerName + ", from " + l.reserve + "c"));
        out.put("waiting", waiting);
        out.put("fleet", Fleet.report(level, id));
        return out;
    }

    private static String list(List<String> words) {
        if (words.size() <= 1) return words.isEmpty() ? "nothing" : words.get(0);
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.get(words.size() - 1);
    }

    private static String coinWord(int n) {
        return n == 1 ? " coin" : " coins";
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the day's auction opened now (the lots drawn), whatever the day and the hour. Returns the lots' names. */
    public static List<String> openForTests(ServerLevel level, Villages.Village v) {
        SALES.remove(v.id());
        Sale s = open(level, v, level.getDayTime() / 24000L);
        List<String> out = new ArrayList<>();
        for (Lot l : s.lots) out.add(l.name() + " (" + l.owner + ", reserve " + l.reserve + ", worth " + l.worth + ")");
        return out;
    }

    /** Tests: the crowd gathered and the first lot called now. */
    public static int startForTests(ServerLevel level, Villages.Village v) {
        Sale s = SALES.get(v.id());
        if (s == null || s.lots.isEmpty()) return 0;
        gather(level, v, s, level.getGameTime());
        if (s.phase == Phase.GATHER) call(level, v, s, 0, level.getGameTime());
        return s.bidders.size();
    }

    /** Tests: one of the auctioneer's calls. */
    public static void roundForTests(ServerLevel level, Villages.Village v) {
        Sale s = SALES.get(v.id());
        if (s != null && s.phase == Phase.CALLING) round(level, v, s, level.getGameTime());
    }

    /** Tests: the lot being called ("name high highName calls"), or null when there is none. */
    @Nullable
    public static String lotForTests(UUID village) {
        Sale s = SALES.get(village);
        Lot lot = s == null || s.phase != Phase.CALLING ? null : s.lot();
        return lot == null ? null : lot.name() + " " + lot.high + " " + lot.highName + " " + lot.calls;
    }

    /** Tests: the next bid the auctioneer will take on the lot being called (0: none being called), and who has it now. */
    public static int nextBidForTests(UUID village) {
        Sale s = SALES.get(village);
        Lot lot = s == null || s.phase != Phase.CALLING ? null : s.lot();
        return lot == null ? 0 : next(lot);
    }

    @Nullable
    public static UUID highBidderForTests(UUID village) {
        Sale s = SALES.get(village);
        Lot lot = s == null || s.phase != Phase.CALLING ? null : s.lot();
        return lot == null ? null : lot.highBy;
    }

    /** Tests: where the auctioneer stands today, then the crowd's places. */
    public static List<BlockPos> standForTests(UUID village) {
        Sale s = SALES.get(village);
        List<BlockPos> out = new ArrayList<>();
        if (s == null || s.rostrum == null) return out;
        out.add(s.rostrum);
        out.addAll(s.bidders.values());
        return out;
    }

    /** Tests: the auction house's rostrum (where the auctioneer stands) and its benches. */
    public static BlockPos rostrumForTests(Ledger.Building b) {
        int[] l = rostrumCell();
        return inHouse(b, l[0], 0, l[1] + 1);
    }

    public static List<BlockPos> benchesForTests(Ledger.Building b) {
        return benches(b);
    }

    /** Tests: what each folk will go to for the lot being called. */
    public static Map<UUID, Integer> mostForTests(UUID village) {
        Sale s = SALES.get(village);
        Lot lot = s == null ? null : s.lot();
        return lot == null ? Map.of() : new HashMap<>(lot.most);
    }

    /** Tests: the bids on the lot being called (or the last one), "name coins player". */
    public static List<String> bidsForTests(UUID village) {
        Sale s = SALES.get(village);
        List<String> out = new ArrayList<>();
        if (s == null || s.lots.isEmpty()) return out;
        Lot lot = s.lots.get(Math.max(0, Math.min(s.lots.size() - 1, s.at)));
        for (Bid b : lot.bids) out.add(b.name() + " " + b.coins() + (b.player() ? " player" : ""));
        return out;
    }

    /** Tests: each lot's result. */
    public static List<String> resultsForTests(UUID village) {
        Sale s = SALES.get(village);
        List<String> out = new ArrayList<>();
        if (s != null) for (Lot l : s.lots) out.add(l.name() + ": " + l.result);
        return out;
    }

    /** Tests: is the auction over? */
    public static boolean doneForTests(UUID village) {
        Sale s = SALES.get(village);
        return s == null || s.phase == Phase.DONE;
    }

    /** Tests: the lots of the day cut to the first (so a test's auction is one lot). */
    public static void onlyFirstLotForTests(UUID village) {
        Sale s = SALES.get(village);
        if (s != null && s.lots.size() > 1) s.lots.subList(1, s.lots.size()).clear();
    }

    // ------------------------------------------------------------------ /village auction

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("auction")
            .executes(Auctions::cmdStatus)
            .then(Commands.literal("books").executes(Auctions::cmdBooks))
            .then(Commands.literal("bid").then(Commands.argument("coins", IntegerArgumentType.integer(1, 9999)).executes(ctx -> {
                Villages.Village v = Fleet.here(ctx);
                if (v == null || !(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
                String said = bid(ctx.getSource().getLevel(), v, p, IntegerArgumentType.getInteger(ctx, "coins"));
                ctx.getSource().sendSuccess(() -> Component.literal(said), false);
                return 1;
            })))
            .then(Commands.literal("put").executes(ctx -> {
                Villages.Village v = Fleet.here(ctx);
                if (v == null || !(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
                String said = putUp(ctx.getSource().getLevel(), v, p, 0);
                ctx.getSource().sendSuccess(() -> Component.literal(said), false);
                return 1;
            }))
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = Fleet.here(ctx);
                if (v == null) return 0;
                ServerLevel level = ctx.getSource().getLevel();
                List<String> lots = openForTests(level, v);
                int crowd = startForTests(level, v);
                ctx.getSource().sendSuccess(() -> Component.literal("AUCTION " + lots.size() + " lots " + lots + "; a crowd of " + crowd), false);
                return lots.size();
            }));
    }

    private static int cmdStatus(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = Fleet.here(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        List<String> lines = new ArrayList<>();
        lines.add("The auction at " + Villages.name(v.id()) + ": " + whenNext(level, v.id()));
        Sale s = SALES.get(v.id());
        if (s != null) {
            lines.add("  today: " + s.phase.name().toLowerCase(Locale.ROOT) + (s.auctioneerName.isEmpty() ? "" : ", called by " + s.auctioneerName)
                + ", a crowd of " + s.bidders.size() + ", " + s.sold + " sold for " + s.takings + "c");
            for (int i = 0; i < s.lots.size(); i++) {
                Lot l = s.lots.get(i);
                lines.add("  " + (i + 1) + ". " + l.name() + " (" + l.from + "), reserve " + l.reserve + ", worth " + l.worth
                    + (l.highBy == null ? "" : ", bid " + l.high + " by " + l.highName) + (l.result.isEmpty() ? "" : ": " + l.result));
            }
        }
        for (String line : sales(v.id())) {
            if (lines.size() > 14) break;
            lines.add("  " + line);
        }
        String text = String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return lines.size();
    }

    private static int cmdBooks(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = Fleet.here(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return cmdStatus(ctx);
        CompoundTag books = Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "Auction");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }
}
