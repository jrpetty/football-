package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * [roles-d] What a player can ask of the town's outdoor and resource trades, each of its own work (the talk menu's
 * buttons for them, client/TalkMenu, and the same words typed):
 * <ul>
 * <li><b>The farmer</b>: what is growing in its field (the crops by kind, how many ripe, the season), and a lot of the
 *     fields' produce out of the stores.</li>
 * <li><b>The woodcutter</b>: a timber order: logs of the kind the stores hold most of, as a bulk order (Commerce.bulk),
 *     at once if the town can spare them, else a quarter down and the rest within the week.</li>
 * <li><b>The fisher</b>: fresh fish out of the stores; or a seat in its boat when the fleet next goes out (Fleet).</li>
 * <li><b>The hunter</b>: leather, feathers and hides out of the stores; or a morning's hunt together: its grounds are
 *     wherever the player walks till the hunt is over, the town's own rules still kept (never a herd's animal, never the
 *     last of a kind), and a share of the meat for the player at the end.</li>
 * <li><b>The rancher</b>: wool out of the stores, or one of the town's own horses (never the last two the couriers ride),
 *     the player's now, its saddle with it.</li>
 * <li><b>The ferryman</b>: across the water now (Ferries.takeAcross), the fare a coin on the far landing.</li>
 * <li><b>The golem keeper</b>: an iron golem for the player's base, stood up the game's own way from the player's own
 *     iron and pumpkin, for the keeper's fee; the town never posts it or adopts it.</li>
 * <li><b>The engineer</b>: the lamp post nearest the player given a lamp that lights itself (Engineers.lightStreet).</li>
 * <li><b>The miner and the fletcher</b>: coal and raw ore, or arrows, out of the stores.</li>
 * </ul>
 * Everything sold comes out of the stores (nothing made from nothing) at the town's own price, into its treasury, and is
 * booked as a sale; what the town cannot spare it says so, and why. The emerald trader and the diver have their
 * buttons too; their words were theirs already (EmeraldTrader.meant, Divers.meant).
 *
 * <p>Cost: nothing ticks but the hunting parties, looked at from the hunter's own work (huntingWith), a map read.
 */
public final class OutdoorTalk {

    private OutdoorTalk() {}

    /** What a player is asking of an outdoor trade. */
    public enum Ask { GROWING, PRODUCE, TIMBER, FISH, BOAT, HUNT, HIDES, WOOL, HORSE, ACROSS, GOLEM, LAMPS, ORE, ARROWS }

    /** One trade's goods, out of the stores: what counts, a sample of each kind, and the lot it sells by default. */
    record Wares(String words, List<Item> kinds, int lot) {

        boolean has(Item it) {
            return kinds.contains(it);
        }
    }

    static final Wares PRODUCE = new Wares("produce", List.of(Items.WHEAT, Items.CARROT, Items.POTATO, Items.BEETROOT, Items.PUMPKIN,
        Items.MELON_SLICE, Items.SUGAR_CANE), 16);
    static final Wares FISH = new Wares("fish", List.of(Items.COD, Items.SALMON), 8);
    static final Wares HIDES = new Wares("hides", List.of(Items.LEATHER, Items.FEATHER, Items.RABBIT_HIDE), 8);
    static final Wares ORE = new Wares("coal and ore", List.of(Items.COAL, Items.RAW_IRON, Items.RAW_COPPER, Items.RAW_GOLD), 16);
    static final Wares ARROWS = new Wares("arrows", List.of(Items.ARROW), 16);
    static final Wares WOOL = new Wares("wool", List.of(Items.WHITE_WOOL, Items.LIGHT_GRAY_WOOL, Items.GRAY_WOOL, Items.BLACK_WOOL,
        Items.BROWN_WOOL, Items.RED_WOOL, Items.ORANGE_WOOL, Items.YELLOW_WOOL, Items.LIME_WOOL, Items.GREEN_WOOL, Items.CYAN_WOOL,
        Items.LIGHT_BLUE_WOOL, Items.BLUE_WOOL, Items.PURPLE_WOOL, Items.MAGENTA_WOOL, Items.PINK_WOOL), 8);

    /** A golem's iron (four blocks of nine), and the keeper's fee for standing it up. */
    static final int GOLEM_IRON = Golems.IRON_EACH, GOLEM_FEE = 10;
    /** A horse's price (a saddle on it is the traders' price besides), and the horses the town keeps for its riders. */
    static final int HORSE_PRICE = 30, HORSES_KEPT = 2;
    /** How long a hunting party lasts at most (ticks), how far the player may stray before it is over, and the radius
     *  of the ground it hunts round the player. */
    static final long PARTY_FOR = 3600L;
    static final int PARTY_STRAY = 64, PARTY_GROUND = 24;
    /** The tag on a golem stood up for a player: the town never posts or adopts it (Golems.adopt). */
    public static final String PLAYERS_GOLEM = "mca_players_golem";

    /** Game meat: what the hunter's pack fills with, and what a party's share is paid in. */
    static final Predicate<ItemStack> GAME = s -> s.is(Items.BEEF) || s.is(Items.PORKCHOP) || s.is(Items.MUTTON) || s.is(Items.CHICKEN)
        || s.is(Items.RABBIT);

    /** A hunting party: the player, till when, and the game meat the hunter carried when it set out. */
    record Party(UUID player, String name, long until, int carried) {}

    private static final Map<UUID, Party> PARTIES = new ConcurrentHashMap<>();

    public static void resetForTests() {
        PARTIES.clear();
    }

    // ------------------------------------------------------------------ what is meant

    private static final Pattern DIGITS = Pattern.compile("\\b\\d{1,4}\\b");

    private static boolean has(String t, String... words) {
        for (String w : words) if (t.contains(w)) return true;
        return false;
    }

    /** What these words ask of a folk of this trade, or null if they ask nothing of its own work. Pure: the tests check
     *  every menu phrase is understood by its trade. */
    @Nullable
    public static Ask ask(StationTask trade, String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        boolean buy = has(t, "buy", "sell me", "could i have", "can i have", "i'd like", "some of your");
        return switch (trade) {
            case FARM -> has(t, "growing", "your crops", "your field", "the harvest") ? Ask.GROWING
                : has(t, "produce", "vegetable") || buy && sold(PRODUCE, t) ? Ask.PRODUCE : null;
            case WOOD -> has(t, "timber", "lumber") || buy && has(t, " log", "wood") ? Ask.TIMBER : null;
            case FISH -> has(t, "boat", "come out", "go out with", "sail") ? Ask.BOAT
                : has(t, "fresh fish") || buy && (t.contains("fish") || sold(FISH, t)) ? Ask.FISH : null;
            case HUNT -> has(t, "come hunting", "go hunting", "hunt with", "take me hunting", "hunting with") ? Ask.HUNT
                : has(t, "leather", "feather", "hide") ? Ask.HIDES : null;
            case RANCH -> t.contains("horse") ? Ask.HORSE : t.contains("wool") || has(t, "fleece") ? Ask.WOOL : null;
            case FERRY -> has(t, "across", "cross", "row me", "the other side", "far bank") ? Ask.ACROSS : null;
            case GOLEMS -> t.contains("golem") && (buy || has(t, "build", "make", "for my", "for me")) ? Ask.GOLEM : null;
            case REDSTONE -> has(t, "light my", "light the", "lamp", "street light", "lights on my") ? Ask.LAMPS : null;
            case MINE -> buy && (has(t, "coal", " ore", "raw iron", "raw copper", "raw gold") || sold(ORE, t)) ? Ask.ORE : null;
            case FLETCHER -> buy && t.contains("arrow") ? Ask.ARROWS : null;
            default -> null;
        };
    }

    /** Does this line name one of these goods? */
    private static boolean sold(Wares g, String t) {
        Item it = Services.itemNamed(t);
        return it != null && g.has(it);
    }

    /**
     * Said to a farmer, woodcutter, fisher, hunter, rancher, ferryman, golem keeper or engineer of its own work: the
     * answer, and whatever comes of it. Null when the words ask nothing of its trade (the rest of the talk hears them).
     */
    @Nullable
    public static String talk(VillageFolkEntity f, Player p, String text) {
        if (f.isBaby() || !(f.level() instanceof ServerLevel level) || f.ownerId() == null) return null;
        Ask a = ask(f.stationTask(), text);
        if (a == null) return null;
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return null;
        String refused = refused(level, v, p, a);
        if (refused != null) return refused;
        return switch (a) {
            case GROWING -> growing(level, v, f);
            case PRODUCE -> sell(level, v, f, p, text, PRODUCE, "from our fields");
            case TIMBER -> timber(level, v, f, p, text);
            case FISH -> sell(level, v, f, p, text, FISH, "landed this morning");
            case BOAT -> Fleet.takeGuest(f, p);
            case HUNT -> hunting(level, v, f, p);
            case HIDES -> sell(level, v, f, p, text, HIDES, "off the hunting grounds");
            case WOOL -> sell(level, v, f, p, text, WOOL, "off our own sheep");
            case HORSE -> horse(level, v, f, p);
            case ACROSS -> Ferries.takeAcross(f, p);
            case GOLEM -> golem(level, v, f, p);
            case LAMPS -> Engineers.lightStreet(level, v, f, p);
            case ORE -> sell(level, v, f, p, text, ORE, "up from the mine");
            case ARROWS -> sell(level, v, f, p, text, ARROWS, "fletched here in the town");
        };
    }

    /** Somebody the town has turned out gets nothing sold to it (a crossing is still a crossing). */
    @Nullable
    private static String refused(ServerLevel level, Villages.Village v, Player p, Ask a) {
        if (a == Ask.GROWING || a == Ask.ACROSS) return null;
        long day = level.getDayTime() / 24000L;
        Standing.Title title = Standing.of(v.id(), p.getUUID(), level.getGameTime()).title();
        if (title == Standing.Title.OUTCAST || Laws.banished(v.id(), p.getUUID(), day)) return "Not for you. Not after what you've done.";
        return null;
    }

    /** A thing's name in lower-case words: its shown name, or its id in words where the server has no names loaded. */
    static String word(Item it) {
        String shown = new ItemStack(it).getHoverName().getString();
        String w = shown.startsWith("item.") || shown.startsWith("block.")
            ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it).getPath().replace('_', ' ') : shown;
        return w.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ the farmer

    /** "What's growing?": the crops in its field by kind and how many are ripe, the season, and what the stores can spare. */
    static String growing(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        WorkZone z = f.workZone();
        Map<String, int[]> crops = new LinkedHashMap<>();
        if (z != null) {
            BlockPos c = z.center();
            int r = Math.min(10, z.radius());
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = c.getX() + dx, zz = c.getZ() + dz;
                    if (!level.isLoaded(new BlockPos(x, c.getY(), zz))) continue;
                    BlockPos at = Fields.cropIn(level, x, c.getY(), zz);
                    if (at == null) continue;
                    BlockState st = level.getBlockState(at);
                    // The crop's own name (a wheat plant's item is its seeds).
                    String name = word(st.getBlock().asItem()).replace(" seeds", "");
                    int[] n = crops.computeIfAbsent(name, k -> new int[2]);
                    n[0]++;
                    if (st.getBlock() instanceof CropBlock cb && cb.isMaxAge(st)) n[1]++;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        if (crops.isEmpty()) {
            sb.append("Nothing up in my field just now: it's turned and waiting for seed.");
        } else {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, int[]> e : crops.entrySet()) {
                parts.add(e.getValue()[0] + " " + e.getKey() + (e.getValue()[1] > 0 ? " (" + e.getValue()[1] + " ripe)" : ""));
            }
            sb.append("In my field: ").append(String.join(", ", parts)).append('.');
        }
        Seasons.Season season = Seasons.season(v.id(), level.getDayTime() / 24000L);
        sb.append(' ').append(switch (season) {
            case SPRING -> "Spring: everything comes on quick.";
            case SUMMER -> "Summer: steady growing weather.";
            case AUTUMN -> "Autumn: it's slowing, and the harvest wants getting in.";
            case WINTER -> "Winter: precious little grows now.";
        });
        List<String> spare = new ArrayList<>();
        for (Item it : PRODUCE.kinds()) {
            int n = Budget.spare(level, v.id(), new ItemStack(it));
            if (n > 0) spare.add(n + " " + word(it));
            if (spare.size() >= 3) break;
        }
        sb.append(spare.isEmpty() ? " The town needs all it has; there's nothing to sell." : " We could spare " + String.join(", ", spare)
            + ", if you're buying.");
        return sb.toString();
    }

    // ------------------------------------------------------------------ selling out of the stores

    /**
     * A lot of a trade's goods out of the stores, at the town's price, into its treasury: the kind named (if it is one of
     * its goods), else the kind the town can best spare; so many as were asked for, else its usual lot. What the town
     * cannot spare it says so, and why; the player short of coin is told the price.
     */
    static String sell(ServerLevel level, Villages.Village v, VillageFolkEntity f, Player p, String text, Wares goods, String from) {
        UUID village = v.id();
        Item named = Services.itemNamed(text);
        Item it = named != null && goods.has(named) ? named : best(level, village, goods);
        if (it == null) {
            List<String> wants = Budget.wants(level, village);
            return "I can't spare any " + goods.words() + " just now: " + (wants.isEmpty() ? "the town needs every bit of it itself."
                : "the town's short itself (it wants " + String.join(", ", wants) + "), and sees to itself first.");
        }
        ItemStack sample = new ItemStack(it);
        String word = word(it);
        int want = DIGITS.matcher(text).find() ? Services.countIn(text) : goods.lot();
        int spare = Budget.spare(level, village, sample);
        if (spare <= 0) return "We can't spare any " + Bench.plural(word) + ": the town needs all it has.";
        int n = Math.min(want, Math.min(spare, sample.getMaxStackSize()));
        boolean unwelcome = Standing.of(village, p.getUUID(), level.getGameTime()).title() == Standing.Title.UNWELCOME;
        int price = price(level, village, sample, n, unwelcome);
        int coins = Market.coinsHeld(p);
        if (coins < price) return n + " " + word + " would be " + price + " coins. You've " + coins + ".";
        Predicate<ItemStack> what = s -> s.is(it) && s.getComponentsPatch().isEmpty();
        int count = 0;
        for (ItemStack s : Services.take(level, village, what, n)) {
            count += s.getCount();
            if (!p.getInventory().add(s)) p.drop(s, false);
        }
        if (count == 0) return "Somebody's just taken the last of it.";
        price = price(level, village, sample, count, unwelcome);
        Market.payOut(p, price);
        Ledger.addCoins(village, price);
        Economy.sold(village, price);
        Budget.forget(village);
        Stockroom.sold(level, village, Stockroom.Seller.STORES, sample, count, price);
        f.persona().remember(level.getDayTime() / 24000L, "sold " + p.getName().getString() + " " + count + " " + word, 1);
        return "There: " + count + " " + word + ", " + from + ". " + price + (price == 1 ? " coin" : " coins") + " to the town's chest.";
    }

    /** The town's price for so many: its asking price, never under the least it lets them go for (twice to the unwelcome). */
    private static int price(ServerLevel level, UUID village, ItemStack sample, int n, boolean unwelcome) {
        int ask = Market.askLot(level, village, sample, n) * (unwelcome ? 2 : 1);
        return Math.max(ask, Market.leastLot(level, village, sample, n));
    }

    /** Of a trade's goods, the kind the town can spare most of, or null if it can spare none. */
    @Nullable
    private static Item best(ServerLevel level, UUID village, Wares goods) {
        Item best = null;
        int most = 0;
        for (Item it : goods.kinds()) {
            int n = Budget.spare(level, village, new ItemStack(it));
            if (n > most) { most = n; best = it; }
        }
        return best;
    }

    // ------------------------------------------------------------------ the woodcutter

    /** The logs a timber order is of when none is named: the kinds a town's woods give. */
    private static final List<Item> LOGS = List.of(Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.JUNGLE_LOG, Items.ACACIA_LOG,
        Items.DARK_OAK_LOG, Items.MANGROVE_LOG, Items.CHERRY_LOG);

    /** "A timber order": a bulk order of logs (Commerce.bulk), of the kind named or the kind the stores hold most of. */
    static String timber(ServerLevel level, Villages.Village v, VillageFolkEntity f, Player p, String text) {
        Item named = Services.itemNamed(text);
        String[] open = Commerce.fields(v.id(), "bulk/" + p.getUUID());
        if (open != null && open.length >= 4 && named == null) return Commerce.bulk(f, p, text);   // its order collected
        if (named != null && new ItemStack(named).is(ItemTags.LOGS)) return Commerce.bulk(f, p, text);
        Item log = Items.OAK_LOG;
        int most = -1;
        for (Item it : LOGS) {
            int n = Market.stock(level, v.id(), s -> s.is(it));
            if (n > most) { most = n; log = it; }
        }
        int count = DIGITS.matcher(text).find() ? Commerce.number(text, 64) : 64;
        return Commerce.bulk(f, p, "I'd like to order " + count + " " + word(log));
    }

    // ------------------------------------------------------------------ the hunter

    /** "Can I come hunting?": a party, till dusk or an hour of the day, its grounds wherever the player walks. */
    static String hunting(ServerLevel level, Villages.Village v, VillageFolkEntity f, Player p) {
        long time = level.getDayTime() % 24000L;
        if (time >= 10000L && time < 23000L) return "It's too late in the day to go out. Come and find me at first light.";
        if (Raids.underAlarm(v.id())) return "With the bell ringing? Nobody's going anywhere.";
        if (OutdoorWeather.keepsIn(f)) return "Not in this. The game's gone to ground, and so am I.";
        Party now = PARTIES.get(f.getUUID());
        if (now != null && !now.player().equals(p.getUUID()) && level.getGameTime() < now.until()) {
            return "I'm out with " + now.name() + " already. Another morning.";
        }
        PARTIES.put(f.getUUID(), new Party(p.getUUID(), p.getName().getString(), level.getGameTime() + PARTY_FOR, f.countCarried(GAME)));
        f.brain("out hunting with " + p.getName().getString());
        return "Come on, then. Keep close and keep quiet: I'll hunt wherever you lead, but never a herd's beast and never the last of"
            + " a kind. Half the meat we take is yours when we're done.";
    }

    /**
     * Where the hunter's grounds are just now, out with a player: where the player is (null when it is not in a party,
     * or the party is over: dusk, an hour gone, the player gone off, the rain, the bell; its share handed over then).
     */
    @Nullable
    public static BlockPos huntingWith(VillageFolkEntity f, ServerLevel level) {
        if (PARTIES.isEmpty()) return null;
        Party party = PARTIES.get(f.getUUID());
        if (party == null) return null;
        Player p = level.getPlayerByUUID(party.player());
        long time = level.getDayTime() % 24000L;
        String over = p == null || !p.isAlive() || p.distanceToSqr(f) > (double) PARTY_STRAY * PARTY_STRAY ? "you went off"
            : level.getGameTime() >= party.until() || time >= 11500L ? "the day's hunting done"
            : OutdoorWeather.keepsIn(f) || Raids.underAlarm(f.ownerId()) ? "called in" : null;
        if (over != null) {
            endParty(f, party, p, over);
            return null;
        }
        return p.blockPosition();
    }

    /** Is the hunter out with a player (its patch's leash let go: VillageFolkEntity.walksAbroad)? */
    public static boolean partying(VillageFolkEntity f) {
        return !PARTIES.isEmpty() && PARTIES.containsKey(f.getUUID());
    }

    /** The party over: half the game meat taken since it set out, out of its pack into the player's. */
    private static void endParty(VillageFolkEntity f, Party party, @Nullable Player p, String why) {
        PARTIES.remove(f.getUUID());
        int taken = Math.max(0, f.countCarried(GAME) - party.carried());
        int share = taken / 2;
        int given = 0;
        if (p != null && share > 0 && p.distanceToSqr(f) <= 16 * 16) {
            for (ItemStack s : f.getInventoryItems()) {
                if (given >= share) break;
                if (s.isEmpty() || !GAME.test(s)) continue;
                int n = Math.min(share - given, s.getCount());
                ItemStack out = s.split(n);
                if (!p.getInventory().add(out)) p.drop(out, false);
                given += n;
            }
        }
        f.brain("the hunt with " + party.name() + " over: " + why);
        FolkTalk.speak(f, given > 0 ? "That's the hunting done. Here's your share: " + given + " of the meat. Good hunting!"
            : taken > 0 ? "That's the hunting done, " + party.name() + "." : "Nothing today. The game's like that. Another morning, " + party.name() + ".");
    }

    // ------------------------------------------------------------------ the rancher's horses

    /** "Could I buy a horse?": one of the town's own tamed horses (never the last two), the player's now, its saddle with it. */
    static String horse(ServerLevel level, Villages.Village v, VillageFolkEntity f, Player p) {
        List<AbstractHorse> herd = new ArrayList<>();
        for (AbstractHorse h : Stables.animals(level, v.id())) {
            if (Stables.ours(h, v.id()) && !h.isBaby() && !h.isVehicle() && !Stables.handled(h.getUUID())) herd.add(h);
        }
        if (herd.size() <= HORSES_KEPT) {
            return herd.isEmpty() ? "We've no horses broken to sell. I'm still gentling the wild ones."
                : "We've only " + herd.size() + ", and the couriers and the caravans need them. When there's a foal or two more.";
        }
        AbstractHorse h = herd.get(herd.size() - 1);
        boolean saddled = h.isSaddled();
        int price = HORSE_PRICE + (saddled ? Stables.SADDLE_PRICE : 0);
        int coins = Market.coinsHeld(p);
        String name = Stables.name(h);
        if (coins < price) return name + " would be " + price + " coins" + (saddled ? ", saddle and all" : "") + ". You've " + coins + ".";
        Market.payOut(p, price);
        Ledger.addCoins(v.id(), price);
        Economy.sold(v.id(), price);
        h.setOwnerUUID(p.getUUID());
        h.removeTag(Stables.STEED);
        h.removeTag(Drover.HERD);
        long day = level.getDayTime() / 24000L;
        Villages.tell(v.id(), day, f.displayNameCap() + " sold " + name + ", one of the town's horses, to " + p.getName().getString());
        f.persona().remember(day, "sold " + name + " to " + p.getName().getString(), 2);
        return name + "'s yours: " + price + " coins to the town" + (saddled ? ", saddle and all. Ride it home" : ". Put a lead on it and walk"
            + " it home") + "; it likes an apple now and then.";
    }

    // ------------------------------------------------------------------ the golem keeper

    /**
     * "A golem for my base": an iron golem stood up beside the player the game's own way, of the player's own four blocks
     * of iron (or thirty-six ingots, made into blocks) and a pumpkin (carved, or carved there and then), for the keeper's
     * fee into its own purse. Tagged the player's: the town never posts it or takes it in hand. With anything short, or no
     * room for the T, nothing is taken and the player is told what is wanting.
     */
    static String golem(ServerLevel level, Villages.Village v, VillageFolkEntity f, Player p) {
        int ingots = Commerce.taking(p, s -> s.is(Items.IRON_INGOT)), blocks = Commerce.taking(p, s -> s.is(Items.IRON_BLOCK));
        int iron = ingots + Golems.INGOTS_A_BLOCK * blocks;
        if (iron < GOLEM_IRON) {
            return "It's your iron it's made of: " + GOLEM_IRON + " ingots, or four blocks. You've " + iron + " ingots' worth. Bring the rest"
                + " and a pumpkin for its head.";
        }
        boolean carved = Commerce.taking(p, s -> s.is(Items.CARVED_PUMPKIN)) > 0;
        if (!carved && Commerce.taking(p, s -> s.is(Items.PUMPKIN)) == 0) return "And a pumpkin for its head. A carved one, or I'll carve it.";
        if (Market.coinsHeld(p) < GOLEM_FEE) return "My work's " + GOLEM_FEE + " coins. You've " + Market.coinsHeld(p) + ".";
        Direction across = p.getDirection().getClockWise();
        BlockPos base = Golems.tSpot(level, p.blockPosition().relative(p.getDirection(), 3), across, 4);
        if (base == null) return "There's no room here to stand one up: I want flat ground, clear above and either side. Somewhere more open.";
        if (!TownBlocks.mayAll(level, v, TownBlocks.Why.CIVIC, Golems.tCells(base, across))) {
            return "Not there: that's somebody's ground. Stand somewhere open and ask again.";
        }
        // The player's iron: blocks first, ingots made up into the rest (nine to a block, as the grid does).
        int fromBlocks = Math.min(Golems.BLOCKS, blocks);
        Commerce.takeFrom(p, s -> s.is(Items.IRON_BLOCK), fromBlocks);
        Commerce.takeFrom(p, s -> s.is(Items.IRON_INGOT), (Golems.BLOCKS - fromBlocks) * Golems.INGOTS_A_BLOCK);
        Commerce.takeFrom(p, s -> s.is(carved ? Items.CARVED_PUMPKIN : Items.PUMPKIN), 1);
        if (!carved) {
            ItemStack seeds = new ItemStack(Items.PUMPKIN_SEEDS, 4);              // carved where it sits: its seeds the player's
            if (!p.getInventory().add(seeds)) p.drop(seeds, false);
        }
        IronGolem g = stand(level, base, across, p.getDirection().getOpposite());
        if (g == null) {
            // The game's check did not take: everything back.
            for (ItemStack s : List.of(new ItemStack(Items.IRON_BLOCK, Golems.BLOCKS), new ItemStack(Items.CARVED_PUMPKIN))) {
                if (!p.getInventory().add(s)) p.drop(s, false);
            }
            return "It wouldn't stand. Something in the way the iron doesn't like. Your iron's back; try somewhere more open.";
        }
        Market.payOut(p, GOLEM_FEE);
        f.earn(GOLEM_FEE);
        g.addTag(PLAYERS_GOLEM);
        g.setCustomName(Component.literal(p.getName().getString() + "'s golem"));
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        long day = level.getDayTime() / 24000L;
        Villages.tell(v.id(), day, f.displayNameCap() + " stood up an iron golem for " + p.getName().getString() + ", of " + p.getName().getString() + "'s own iron");
        f.persona().remember(day, "built " + p.getName().getString() + " an iron golem", 2);
        return "There: up it gets. It's yours, made by hand, and it'll never turn on you. Put a lead on it and walk it home; it'll"
            + " guard your place as ours guard the square. " + GOLEM_FEE + " coins for the work.";
    }

    /** The T of four blocks of iron and a carved pumpkin set on it, the ordinary way: the golem the game stands up, or null
     *  (the blocks taken off again). */
    @Nullable
    private static IronGolem stand(ServerLevel level, BlockPos base, Direction across, Direction face) {
        for (BlockPos q : Golems.tCells(base, across)) if (!level.getBlockState(q).isAir()) level.removeBlock(q, false);
        BlockPos body = base.above(), head = base.above(2);
        Set<UUID> before = new HashSet<>();
        for (IronGolem g : level.getEntitiesOfClass(IronGolem.class, new AABB(base).inflate(4))) before.add(g.getUUID());
        for (BlockPos q : new BlockPos[]{ base, body, body.relative(across), body.relative(across.getOpposite()) }) {
            level.setBlock(q, Blocks.IRON_BLOCK.defaultBlockState(), 3);
        }
        level.playSound(null, body, SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 1.0F, 0.9F);
        level.setBlock(head, Blocks.CARVED_PUMPKIN.defaultBlockState().setValue(CarvedPumpkinBlock.FACING, face), 3);
        for (IronGolem g : level.getEntitiesOfClass(IronGolem.class, new AABB(base).inflate(3), g -> !before.contains(g.getUUID()))) return g;
        for (BlockPos q : new BlockPos[]{ base, body, body.relative(across), body.relative(across.getOpposite()), head }) level.removeBlock(q, false);
        return null;
    }

    // ------------------------------------------------------------------ for the books and the tests

    /** The hunting parties out now, in words (/village outdoor). */
    public static List<String> parties(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, Party> e : PARTIES.entrySet()) {
            if (level.getEntity(e.getKey()) instanceof VillageFolkEntity f && village.equals(f.ownerId())) {
                out.add(f.displayNameCap() + " is out hunting with " + e.getValue().name());
            }
        }
        return out;
    }

    /** Tests: the party over now (its share handed over). */
    public static void endPartyForTests(VillageFolkEntity f, ServerLevel level) {
        Party party = PARTIES.get(f.getUUID());
        if (party != null) endParty(f, party, level.getPlayerByUUID(party.player()), "the test");
    }
}
