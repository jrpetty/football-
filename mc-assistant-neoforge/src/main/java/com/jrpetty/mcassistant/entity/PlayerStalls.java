package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * A stall of the player's own on the square, let a week at a time.
 * <ul>
 * <li><b>Renting.</b> Ask any folk ("Could I rent a stall?"), right-click a stall standing to let, or
 *     /village stall rent: a week on the square for the rent (five coins in a hamlet, more in a bigger
 *     place, as its wages are: Wealth.standing), paid into the treasury. The market's hands set up a
 *     booth for it: a barrel, a sign on its lid with the renter's name, two posts and an awning, out of
 *     the stores (a barrel or seven planks, a sign or two, four lengths of fence or eight planks, three
 *     wool), or out of the renter's own pack when the stores can't spare them. Nothing out of nothing.
 *     From the Stone Age, with a player about and the makings to spare, the market keeps one standing
 *     empty, "to let".</li>
 * <li><b>Stocking and prices.</b> Whatever goes in its barrel is for sale. Sneak and right-click the
 *     barrel (or right-click its sign) for the stall's screen: each kind in the barrel with a price for
 *     a lot of it (by the lot, as the market sells it: eight bread, one pick), the village's going
 *     price beside it, the till and the books. A kind not priced sells at the going price; 0 keeps it
 *     back.</li>
 * <li><b>The folk buy.</b> On market day, and whenever a folk wants something a stall has (the tool
 *     of its trade, food in a hungry village, a comfort for its home, something nice when it is well
 *     off), it walks to the stall and weighs the price against the going one: the thrifty want it
 *     cheaper, the comfortable pay a tenth over, the well-off a quarter, the wealthy a little more,
 *     and nobody half as much again; and nobody pays more than the village asks for what the village
 *     has itself. At the market's own stalls on market day it weighs the players' stalls too, as it
 *     would any other seller. Paid out of its own purse into the stall's till; the goods go into its
 *     pack, or home with it. Too dear, and it says so.</li>
 * <li><b>The books.</b> Every sale booked (who, what, for how much), a week of takings and all of them,
 *     what was turned down as too dear and what the going price was; on the stall's screen, its sign,
 *     the Shops page of the town's books, and a word to the player whenever something sells.</li>
 * <li><b>When the rent runs out.</b> The stall shuts; three days on it is given back, and what is in
 *     its barrel stays there for the player to collect (nobody else may open it), the till kept for
 *     them too. Once it is emptied the stall is let again. Nothing is lost.</li>
 * </ul>
 * Kept in the village's ledger (notes "stall/&lt;player&gt;", "stall.price/", "stall.sales/",
 * "stall.dear/", "stall.week/" and "pstall.booths"), so it outlasts a restart.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class PlayerStalls {

    private PlayerStalls() {}

    /** Days of a week's rent, and the days of grace after it runs out before the stall is given back. */
    public static final int WEEK = 7, GRACE = 3;
    /** Weeks of rent that may be paid ahead. */
    static final int MOST_WEEKS = 4;
    /** Stalls on one square, at most. */
    public static final int MOST_BOOTHS = 4;
    /** Sales and turnings-down kept on the books. */
    static final int SALES_KEPT = 40, DEAR_KEPT = 12;
    /** The most over the going price anybody pays: half as much again, and then only the rich. */
    static final double FAR_OVER = 1.5;
    /** Folk at one stall in a day, at most: market day fills the square, not one booth. */
    static final int VISITS_A_DAY = 30;

    private static final String BOOTHS = "pstall.booths";
    private static final String STALL = "stall/", PRICES = "stall.price/", SALES = "stall.sales/", DEAR = "stall.dear/",
        WEEKS = "stall.week/";

    /** Where a booth goes on the square, from its heart, and which way its counter faces (to the middle):
     *  clear of the market's own stalls, the well, the monuments, the bell and the ways in from the gates. */
    private static final int[][] SPOTS = {
        { 4, 11, 2 }, { -4, 11, 2 }, { 4, -11, 0 }, { -4, -11, 0 },
        { 11, 4, 1 }, { 11, -4, 1 }, { -11, 4, 3 }, { -11, -4, 3 } };

    // ------------------------------------------------------------------ what is kept

    /** A booth on the square: its barrel (or chest), and the way its counter faces. */
    public record Booth(BlockPos at, Direction front) {
        /** The sign on its lid. */
        public BlockPos sign() { return at.above(); }
        /** Where a customer stands. */
        public BlockPos stand() { return at.relative(front, 2); }

        String code() { return at.getX() + "," + at.getY() + "," + at.getZ() + "," + front.get2DDataValue(); }

        @Nullable
        static Booth parse(String s) {
            String[] p = s.split(",");
            if (p.length < 4) return null;
            try {
                return new Booth(new BlockPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())),
                    Direction.from2DDataValue(Integer.parseInt(p[3].trim())));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** Where a let stall stands with its rent: open, its rent run out (shut), given back (its goods
     *  kept to collect), or no booth at all (a till still to collect, say). */
    public enum State { OPEN, DUE, HELD, NONE }

    /** One sale: the day, who bought, the ware, how many, and the coin. */
    record Sale(long day, String who, String key, int count, int price) {}

    /** One turning-down: the day, who, the ware, the stall's price for it and the going price. */
    record Dear(long day, String who, String key, int price, int going) {}

    /** A player's tenancy of a stall in one village, with its books. */
    static final class Stall {
        final UUID owner;
        String name = "";
        @Nullable BlockPos at;
        long paidTill = -1, since = -1, gaveBack = -1, dueTold = -1, toldDay = -1;
        int till, soldAll, takenAll;
        final Map<String, Integer> prices = new LinkedHashMap<>();
        final List<Sale> sales = new ArrayList<>();
        final List<Dear> dear = new ArrayList<>();
        long weekDay = -1;
        final int[] weekCount = new int[WEEK];
        final int[] weekCoin = new int[WEEK];

        Stall(UUID owner) {
            this.owner = owner;
        }

        State state(long day) {
            if (at == null) return State.NONE;
            if (day <= paidTill) return State.OPEN;
            if (gaveBack >= 0 || day > paidTill + GRACE) return State.HELD;
            return State.DUE;
        }
    }

    @Nullable
    static Stall load(UUID village, UUID owner) {
        String s = Ledger.note(village, STALL + owner);
        if (s == null || s.isEmpty()) return null;
        Stall st = new Stall(owner);
        if (s.startsWith("2;")) {
            String[] f = s.split(";", -1);
            if (f.length < 10) return null;
            st.at = f[1].equals("-") ? null : pos(f[1]);
            st.paidTill = num(f[2]);
            st.till = (int) num(f[3]);
            st.since = num(f[4]);
            st.gaveBack = num(f[5]);
            st.soldAll = (int) num(f[6]);
            st.takenAll = (int) num(f[7]);
            st.dueTold = num(f[8]);
            st.toldDay = f.length > 10 ? num(f[9]) : -1;
            st.name = f[f.length - 1];
        } else {
            // A stall from before its books: "x,y,z|paid to".
            String[] f = s.split("\\|", -1);
            st.at = pos(f[0]);
            st.paidTill = f.length > 1 ? num(f[1]) : -1;
            st.since = st.paidTill - WEEK;
        }
        String pr = Ledger.note(village, PRICES + owner);
        if (pr != null && !pr.isEmpty()) {
            for (String kv : pr.split(";")) {
                int eq = kv.lastIndexOf('=');
                if (eq > 0) st.prices.put(kv.substring(0, eq), (int) num(kv.substring(eq + 1)));
            }
        }
        String sa = Ledger.note(village, SALES + owner);
        if (sa != null && !sa.isEmpty()) {
            for (String e : sa.split("\\|")) {
                String[] f = e.split("~", -1);
                if (f.length >= 5) st.sales.add(new Sale(num(f[0]), f[1], f[2], (int) num(f[3]), (int) num(f[4])));
            }
        }
        String de = Ledger.note(village, DEAR + owner);
        if (de != null && !de.isEmpty()) {
            for (String e : de.split("\\|")) {
                String[] f = e.split("~", -1);
                if (f.length >= 5) st.dear.add(new Dear(num(f[0]), f[1], f[2], (int) num(f[3]), (int) num(f[4])));
            }
        }
        String wk = Ledger.note(village, WEEKS + owner);
        if (wk != null && !wk.isEmpty()) {
            String[] f = wk.split(";", -1);
            if (f.length >= 3) {
                st.weekDay = num(f[0]);
                ints(f[1], st.weekCount);
                ints(f[2], st.weekCoin);
            }
        }
        return st;
    }

    static void save(UUID village, Stall st) {
        String at = st.at == null ? "-" : st.at.getX() + "," + st.at.getY() + "," + st.at.getZ();
        Ledger.note(village, STALL + st.owner, "2;" + at + ";" + st.paidTill + ";" + st.till + ";" + st.since + ";" + st.gaveBack
            + ";" + st.soldAll + ";" + st.takenAll + ";" + st.dueTold + ";" + st.toldDay + ";" + clean(st.name));
        StringBuilder pr = new StringBuilder();
        for (Map.Entry<String, Integer> e : st.prices.entrySet()) {
            if (pr.length() > 0) pr.append(';');
            pr.append(e.getKey()).append('=').append(e.getValue());
        }
        Ledger.note(village, PRICES + st.owner, pr.toString());
        StringBuilder sa = new StringBuilder();
        for (int i = 0; i < Math.min(SALES_KEPT, st.sales.size()); i++) {
            Sale s = st.sales.get(i);
            if (sa.length() > 0) sa.append('|');
            sa.append(s.day()).append('~').append(clean(s.who())).append('~').append(s.key()).append('~').append(s.count()).append('~').append(s.price());
        }
        Ledger.note(village, SALES + st.owner, sa.toString());
        StringBuilder de = new StringBuilder();
        for (int i = 0; i < Math.min(DEAR_KEPT, st.dear.size()); i++) {
            Dear d = st.dear.get(i);
            if (de.length() > 0) de.append('|');
            de.append(d.day()).append('~').append(clean(d.who())).append('~').append(d.key()).append('~').append(d.price()).append('~').append(d.going());
        }
        Ledger.note(village, DEAR + st.owner, de.toString());
        Ledger.note(village, WEEKS + st.owner, st.weekDay + ";" + csv(st.weekCount) + ";" + csv(st.weekCoin));
    }

    /** A tenancy done with altogether: no booth and nothing in the till. */
    private static void forget(UUID village, UUID owner) {
        for (String k : new String[]{ STALL, PRICES, SALES, DEAR, WEEKS }) Ledger.forget(village, k + owner);
    }

    /** Every tenancy in a village. */
    static List<Stall> all(UUID village) {
        List<Stall> out = new ArrayList<>();
        for (Map.Entry<String, String> e : Commerce.notesStarting(village, STALL)) {
            UUID owner;
            try {
                owner = UUID.fromString(e.getKey().substring(STALL.length()));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            Stall st = load(village, owner);
            if (st != null) out.add(st);
        }
        return out;
    }

    static List<Booth> booths(UUID village) {
        List<Booth> out = new ArrayList<>();
        String s = Ledger.note(village, BOOTHS);
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            Booth b = Booth.parse(part);
            if (b != null) out.add(b);
        }
        return out;
    }

    private static void saveBooths(UUID village, List<Booth> all) {
        StringBuilder sb = new StringBuilder();
        for (Booth b : all) {
            if (sb.length() > 0) sb.append(';');
            sb.append(b.code());
        }
        Ledger.note(village, BOOTHS, sb.toString());
    }

    private static void dropBooth(UUID village, BlockPos at) {
        List<Booth> all = booths(village);
        if (all.removeIf(b -> b.at().equals(at))) saveBooths(village, all);
    }

    /** The booth whose barrel (or whose sign) this is, or null. */
    @Nullable
    public static Booth boothAt(UUID village, BlockPos pos) {
        for (Booth b : booths(village)) if (b.at().equals(pos) || b.sign().equals(pos)) return b;
        return null;
    }

    /** Who has this booth (open, shut or holding their goods), or null if it is to let. */
    @Nullable
    static Stall tenantOf(UUID village, Booth b) {
        for (Stall st : all(village)) if (b.at().equals(st.at)) return st;
        return null;
    }

    /** A tenancy's booth: on the list, or (a stall from before the list) one facing the square's middle. */
    @Nullable
    static Booth boothOf(UUID village, Stall st) {
        if (st.at == null) return null;
        for (Booth b : booths(village)) if (b.at().equals(st.at)) return b;
        Villages.Village v = Villages.get(village);
        Direction front = Direction.NORTH;
        if (v != null) {
            int dx = v.centre().getX() - st.at.getX(), dz = v.centre().getZ() - st.at.getZ();
            front = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        }
        Booth b = new Booth(st.at, front);
        List<Booth> all = booths(village);
        all.add(b);
        saveBooths(village, all);
        return b;
    }

    // ------------------------------------------------------------------ the wares and their prices

    /** A ware's name on the books: the item, and a star for an enchanted one (priced apart). */
    public static String key(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()) + (s.isEnchanted() ? "*" : "");
    }

    /** One of a ware, from its name on the books. */
    static ItemStack sampleOf(String key) {
        String id = key.endsWith("*") ? key.substring(0, key.length() - 1) : key;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item it = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
        if (it == Items.AIR) return ItemStack.EMPTY;
        ItemStack s = new ItemStack(it);
        if (key.endsWith("*")) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return s;
    }

    /** How many make a lot of this: as the market sells it (eight bread, sixteen wheat, one pick). */
    public static int lot(ItemStack s) {
        Market.Good g = Market.goodFor(s.copyWithCount(1));
        int n = g == null ? 1 : g.bundle();
        return Math.max(1, Math.min(n, s.getMaxStackSize()));
    }

    /** The village's going price for a lot of this: what its own counter asks today (dear when its
     *  stores are short of it, kinder on market day, a slow ware marked down). 0 if nobody here deals in it. */
    public static int going(ServerLevel level, UUID village, ItemStack s) {
        if (s.isEmpty() || Market.isCoin(s)) return 0;
        ItemStack one = s.copyWithCount(1);
        Market.Good g = Budget.goodFor(one);
        if (g == null) return 0;
        boolean md = Market.marketDay(village, today(level));
        int have = Market.stock(level, village, x -> ItemStack.isSameItemSameComponents(x, one));
        int p = Market.price(level, village, g, one, have, md);
        int lot = lot(one);
        if (lot != g.bundle()) p = (int) Math.max(1, Math.round(p * lot / (double) g.bundle()));
        return Math.max(1, p);
    }

    /** What the stall asks for a lot of this ware: the player's price, or the going price if none is set. */
    static int asking(ServerLevel level, UUID village, Stall st, String key, ItemStack sample) {
        Integer set = st.prices.get(key);
        return set != null ? set : going(level, village, sample);
    }

    // ------------------------------------------------------------------ who would pay what

    /** What a folk goes to a stall for. {@code need}: it keeps nothing back in its purse for it. */
    public enum Use {
        FOOD(true), TREAT(false), TOOL(true), HOME(false), LUXURY(false);

        final boolean need;

        Use(boolean need) {
            this.need = need;
        }
    }

    /** Over the going price, how far this folk will go: the thrifty want it cheaper, the comfortable pay a
     *  tenth over, the well-off a quarter, the wealthy two fifths; a generous one a tenth more, a tenth more
     *  for its favourite; nobody half as much again. */
    static double willing(VillageFolkEntity f, boolean favourite) {
        double k = switch (Wealth.tier(f)) {
            case POOR, GETTING_BY -> 1.0;
            case COMFORTABLE -> 1.1;
            case WELL_OFF -> 1.25;
            case WEALTHY -> 1.4;
        };
        if (f.knacks().has(FolkSkills.Knack.THRIFTY)) k = 0.9;
        if (f.life().has(Social.Trait.GENEROUS)) k += 0.1;
        if (favourite) k += 0.1;
        return Math.min(FAR_OVER, k);
    }

    /** The most a folk this willing pays for what the village would ask {@code going} for: never more
     *  than the village asks when the village has it itself. */
    static int most(double going, double k, boolean villageHasIt) {
        if (going <= 0) return 0;
        int whole = (int) Math.ceil(going - 1e-9);
        int most = k < 1.0 ? Math.max(1, (int) Math.floor(going * k + 1e-9)) : Math.max(whole, (int) Math.floor(going * k + 1e-9));
        if (villageHasIt) most = Math.min(most, Math.max(1, whole));
        return most;
    }

    /** Food a folk would buy: not the rotten, the poisonous nor the café's drinks. */
    static boolean food(ItemStack s) {
        return s.get(DataComponents.FOOD) != null && !s.is(Items.ROTTEN_FLESH) && !s.is(Items.SPIDER_EYE)
            && !s.is(Items.POISONOUS_POTATO) && !s.is(Items.PUFFERFISH) && !s.is(Items.CHORUS_FRUIT) && !Cafe.isDrink(s);
    }

    /** A comfort for a home, as the folk buy them (VillageFolkEntity.homeComfort): a bookshelf once well off. */
    static boolean comfort(ItemStack s, Wealth.Tier tier) {
        if (tier.ordinal() < Wealth.Tier.COMFORTABLE.ordinal()) return false;
        return s.is(ItemTags.WOOL_CARPETS) || s.is(Items.FLOWER_POT) || s.is(ItemTags.CANDLES) || s.is(Items.LANTERN)
            || s.is(Items.CHEST) || s.is(Items.BARREL) || (s.is(Items.BOOKSHELF) && tier.ordinal() >= Wealth.Tier.WELL_OFF.ordinal());
    }

    /** Something nice: what the shop sells for pleasure (Budget.luxury, Cafe.shopWorthy), not a tool. */
    static boolean luxury(ItemStack s) {
        return (Budget.luxury(s) || Cafe.shopWorthy(s)) && !s.isDamageableItem() && !Cafe.isDrink(s);
    }

    /** One thing a folk is after, and what for. */
    record Want(Predicate<ItemStack> what, Use use, @Nullable Item favourite) {}

    /** Its favourite food (Persona), as an item. */
    @Nullable
    static Item favourite(VillageFolkEntity f) {
        String food = f.persona().food();
        if (food == null || food.isEmpty()) return null;
        ResourceLocation rl = ResourceLocation.tryParse(food.contains(":") ? food : "minecraft:" + food);
        Item it = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
        return it == Items.AIR ? null : it;
    }

    /**
     * What a folk wants that a stall might have, most pressing first: the tool of its trade if it carries
     * none; food if the village is hungry, or a treat on market day (its favourite first); a comfort for its
     * home while its standing runs to more; something nice when it is well off (or comfortable, on market day).
     */
    static List<Want> wants(ServerLevel level, Villages.Village v, VillageFolkEntity f, boolean marketDay) {
        List<Want> out = new ArrayList<>();
        Predicate<ItemStack> tool = Cafe.toolFor(f.stationTask());
        if (tool != null && f.countCarried(tool) == 0) out.add(new Want(tool, Use.TOOL, null));
        Item fav = favourite(f);
        if (Market.hungry(v.id())) out.add(new Want(PlayerStalls::food, Use.FOOD, fav));
        else if (marketDay) out.add(new Want(PlayerStalls::food, Use.TREAT, fav));
        Wealth.Tier tier = Wealth.tier(f);
        if (f.bedPos() != null && f.comforts() < tier.comforts) out.add(new Want(s -> comfort(s, tier), Use.HOME, null));
        if (tier.ordinal() >= Wealth.Tier.WELL_OFF.ordinal() || (marketDay && tier == Wealth.Tier.COMFORTABLE)) {
            out.add(new Want(PlayerStalls::luxury, Use.LUXURY, null));
        }
        return out;
    }

    // ------------------------------------------------------------------ a sale

    /** A sale at a player's stall: whose, what (and how many), and the coin. */
    public record Sold(UUID owner, String ownerName, ItemStack what, int price) {
        /** "bread from Steve's stall", "an iron pickaxe from Steve's stall". */
        public String words() {
            String w = what.getCount() > 1 || lot(what) > 1 ? nameOf(what).toLowerCase(Locale.ROOT) : thing(what, 1);
            return w + " from " + ownerName + "'s stall";
        }
    }

    /** A lot on offer, as one folk weighs it. */
    private record Offer(ItemStack sample, String key, int n, int price, double going, boolean favourite) {
        double ratio() { return going <= 0 ? 99 : price / going; }
    }

    /**
     * A folk weighs what a stall has that it wants: of the lots it thinks fair and can pay for, its
     * favourite, else the best bargain; it buys one, out of its own purse into the stall's till, and the
     * goods go into its pack (or home with it). Nothing fair, and the dearest it looked at goes on the
     * stall's books as too dear (said out loud if {@code there}, standing at the stall). Null if it bought
     * nothing.
     */
    @Nullable
    static Sold buyFrom(ServerLevel level, Villages.Village v, VillageFolkEntity f, Stall st, Booth b, Predicate<ItemStack> want,
                        Use use, @Nullable Item favourite, boolean there) {
        if (!level.isLoaded(b.at()) || !(level.getBlockEntity(b.at()) instanceof Container box)) return null;
        UUID id = v.id();
        Offer best = null, dearest = null;
        List<String> looked = new ArrayList<>();
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack s = box.getItem(i);
            if (s.isEmpty() || Market.isCoin(s) || s.isDamaged() || !want.test(s)) continue;
            String key = key(s);
            if (looked.contains(key)) continue;
            looked.add(key);
            int going = going(level, id, s);
            if (going <= 0) continue;                                      // nobody here deals in it
            int price = asking(level, id, st, key, s);
            if (price <= 0) continue;                                      // kept back
            int lot = lot(s);
            int n = Math.min(lot, count(box, s));
            int priceN = n >= lot ? price : Math.max(1, (int) Math.ceil(price * n / (double) lot - 1e-9));
            double goingN = going * n / (double) lot;
            boolean fav = favourite != null && s.is(favourite);
            boolean villageHas = Market.stock(level, id, x -> ItemStack.isSameItemSameComponents(x, s)) > 0;
            Offer o = new Offer(s.copyWithCount(1), key, n, priceN, goingN, fav);
            if (priceN > most(goingN, willing(f, fav), villageHas)) {
                if (dearest == null || o.ratio() > dearest.ratio()) dearest = o;
                continue;
            }
            if (f.purse() < priceN + (use.need ? 0 : 2)) continue;
            if (best == null || (o.favourite() && !best.favourite())
                    || (o.favourite() == best.favourite() && o.ratio() < best.ratio())) best = o;
        }
        if (best == null) {
            if (dearest != null) tooDear(level, v, f, st, dearest, there);
            return null;
        }
        ItemStack got = take(box, best.sample(), best.n());
        if (got.getCount() < best.n() || !f.spend(best.price())) {
            putBack(box, got);
            return null;
        }
        long day = today(level);
        roll(st, day);
        st.till += best.price();
        st.soldAll += got.getCount();
        st.takenAll += best.price();
        st.weekCount[0] += got.getCount();
        st.weekCoin[0] += best.price();
        String who = f.displayNameCap();
        st.sales.add(0, new Sale(day, who, best.key(), got.getCount(), best.price()));
        while (st.sales.size() > SALES_KEPT) st.sales.remove(st.sales.size() - 1);
        save(id, st);
        Sold sold = new Sold(st.owner, st.name, got.copy(), best.price());
        deliver(level, v, f, got, use);
        sign(level, id, b, st);
        String lotWords = thing(sold.what(), sold.what().getCount());
        tellOwner(level, st.owner, who + " bought " + sold.what().getCount() + " " + nameOf(sold.what()).toLowerCase(Locale.ROOT)
            + " for " + best.price() + coinWord(best.price()) + " at your stall in " + Villages.name(id) + ". Till: " + st.till + ".");
        f.brain("bought " + lotWords + " at " + st.name + "'s stall");
        f.persona().remember(day, "I bought " + lotWords + " at " + st.name + "'s stall", 1);
        if (f.getRandom().nextInt(3) == 0) f.persona().feelFor(st.owner, st.name, 1);
        if (there) {
            level.playSound(null, b.at(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.6F, 1.1F);
            FolkTalk.speak(f, boughtLine(f, sold, best, use));
        }
        return sold;
    }

    /** Too dear: on the stall's books (once a folk a ware a day), a word to its keeper (once a ware a day),
     *  and, at the stall, said out loud. */
    private static void tooDear(ServerLevel level, Villages.Village v, VillageFolkEntity f, Stall st, Offer o, boolean there) {
        long day = today(level);
        String who = f.displayNameCap();
        int going = (int) Math.max(1, Math.round(o.going()));
        boolean booked = false;
        for (Dear d : st.dear) if (d.day() == day && d.who().equals(who) && d.key().equals(o.key())) { booked = true; break; }
        if (!booked) {
            st.dear.add(0, new Dear(day, who, o.key(), o.price(), going));
            while (st.dear.size() > DEAR_KEPT) st.dear.remove(st.dear.size() - 1);
            save(v.id(), st);
            String told = st.owner + "/" + o.key();
            if (TOLD_DEAR.getOrDefault(told, -1L) != day) {
                TOLD_DEAR.put(told, day);
                tellOwner(level, st.owner, who + " thought " + o.price() + coinWord(o.price()) + " for " + o.n() + " "
                    + nameOf(o.sample()).toLowerCase(Locale.ROOT) + " too dear at your stall in " + Villages.name(v.id())
                    + " (the going price is " + going + ").");
            }
        }
        if (there && GRUMBLED.getOrDefault(f.getUUID(), -1L) != day) {
            GRUMBLED.put(f.getUUID(), day);
            FolkTalk.speak(f, dearLine(f, o, going));
        }
    }

    private static String boughtLine(VillageFolkEntity f, Sold s, Offer o, Use use) {
        String what = thing(s.what(), s.what().getCount());
        String price = words(s.price());
        boolean bargain = o.ratio() < 0.95;
        return switch (use) {
            case FOOD -> FolkTalk.pick(f.getRandom(), "Food at last — bless " + s.ownerName() + "'s stall.",
                capital(what) + ". That'll see us through.", capital(price) + " for " + what + "? Done, and gladly.");
            case TOOL -> FolkTalk.pick(f.getRandom(), capital(what) + " from " + s.ownerName() + "'s stall — back to work.",
                "Just the tool I was short of. Thank you kindly.");
            case HOME -> FolkTalk.pick(f.getRandom(), capital(what) + " for my home, from " + s.ownerName() + "'s stall.",
                "That'll look lovely by my bed.");
            case LUXURY -> FolkTalk.pick(f.getRandom(), "Couldn't resist — " + what + "!", "A little something for myself. Why not?");
            default -> o.favourite() ? FolkTalk.pick(f.getRandom(), capital(what) + "! My favourite, and a fair price.",
                    "I'd have paid more for " + what + ", if I'm honest.")
                : bargain ? FolkTalk.pick(f.getRandom(), capital(price) + " for " + what + "? That's a bargain.",
                    "Cheaper than the market. I'll be back.")
                : FolkTalk.pick(f.getRandom(), capital(what) + " — a fair price.", capital(price) + " for " + what + ". Fair enough.");
        };
    }

    private static String dearLine(VillageFolkEntity f, Offer o, int going) {
        String name = nameOf(o.sample()).toLowerCase(Locale.ROOT);
        String price = capital(words(o.price()));
        if (o.price() > going * FAR_OVER) {
            return FolkTalk.pick(f.getRandom(), price + " for " + name + "? Not likely.",
                price + " coins for " + name + "? The market wants " + words(going) + ".",
                "Who'd pay " + words(o.price()) + " for " + name + "? Not me.");
        }
        return FolkTalk.pick(f.getRandom(), price + " for " + name + "? I'll get it cheaper at the market.",
            "Bit dear for me, " + name + " at " + words(o.price()) + ".",
            f.knacks().has(FolkSkills.Knack.THRIFTY) ? "A coin saved is a coin earned. Not at that price." : "Maybe if it were " + words(going) + ".");
    }

    /** Into the folk's pack; a comfort carried home to be set up; a treat or a comfort marked its own;
     *  what its pack won't hold, into the stores. */
    private static void deliver(ServerLevel level, Villages.Village v, VillageFolkEntity f, ItemStack got, Use use) {
        if (use == Use.HOME && f.carryHome(got.copyWithCount(1))) got.shrink(1);
        if (got.isEmpty()) return;
        if (use == Use.HOME || use == Use.LUXURY) Homes.keepsake(got, f);
        ItemStack left = f.insertItem(got);
        if (!left.isEmpty()) Crafts.store(level, v, left);
    }

    private static int count(Container box, ItemStack like) {
        int n = 0;
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack s = box.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, like)) n += s.getCount();
        }
        return n;
    }

    /** So many of this out of a barrel (from its last stacks first, so the first one shown stays). */
    private static ItemStack take(Container box, ItemStack like, int n) {
        int got = 0;
        for (int i = box.getContainerSize() - 1; i >= 0 && got < n; i--) {
            ItemStack s = box.getItem(i);
            if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, like)) continue;
            int k = Math.min(n - got, s.getCount());
            s.shrink(k);
            if (s.isEmpty()) box.setItem(i, ItemStack.EMPTY);
            got += k;
        }
        box.setChanged();
        return like.copyWithCount(got);
    }

    private static void putBack(Container box, ItemStack s) {
        if (s.isEmpty()) return;
        ItemStack left = Stacking.insert(box, s);
        box.setChanged();
        if (!left.isEmpty() && box instanceof net.minecraft.world.level.block.entity.BlockEntity be && be.getLevel() != null) {
            Block.popResource(be.getLevel(), be.getBlockPos().above(), left);
        }
    }

    // ------------------------------------------------------------------ folk at the stalls

    /** A folk's trip to a player's stall today. */
    private static final class Trip {
        long day;
        @Nullable UUID owner;
        String ownerName = "";
        BlockPos stand = BlockPos.ZERO, at = BlockPos.ZERO;
        int setOff;
        int walked = -1000;
        boolean done;
        boolean market;
    }

    private static final Map<UUID, Trip> TRIPS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> VISITS = new ConcurrentHashMap<>();
    /** When a folk last went home from a stall with nothing, it thinking everything too dear. */
    private static final Map<String, Long> TURNED_AWAY = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> GRUMBLED = new ConcurrentHashMap<>();
    private static final Map<String, Long> TOLD_DEAR = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> DAILY = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> TURN = new ConcurrentHashMap<>();

    public static void resetForTests() {
        TRIPS.clear();
        VISITS.clear();
        TURNED_AWAY.clear();
        GRUMBLED.clear();
        TOLD_DEAR.clear();
        DAILY.clear();
        TURN.clear();
    }

    /**
     * Off work, once a day (and again a couple of hours on if it found nothing): a folk that wants
     * something one of the players' stalls has walks to it and looks over what is there (browse). On
     * market day everybody with coin goes to look; on other days only one with a want a stall can meet.
     * True while it is about it.
     */
    public static boolean errand(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || f.purse() < 1) return false;
        String booths = Ledger.note(village, BOOTHS);
        if (booths == null || booths.isEmpty()) return false;
        long day = today(level);
        Trip t = TRIPS.get(f.getUUID());
        if (t == null || t.day != day || (t.done && t.owner == null && f.tickCount - t.setOff > 2400)) {
            t = plan(level, f, village, day);
            TRIPS.put(f.getUUID(), t);
        }
        if (t.done || t.owner == null) return false;
        Villages.Village v = Villages.get(village);
        if (v == null) {
            t.done = true;
            return false;
        }
        if (f.blockPosition().distSqr(t.stand) > 2.5 * 2.5) {
            if (f.tickCount - t.setOff > 1200) {                              // could not get there
                t.done = true;
                return false;
            }
            if (f.getNavigation().isDone() || f.tickCount - t.walked >= 100) {
                f.walkTo(t.stand, 0.9D);
                t.walked = f.tickCount;
            }
            f.hobbyNow = "on the way to " + t.ownerName + "'s stall";
            return true;
        }
        t.done = true;
        f.getNavigation().stop();
        f.getLookControl().setLookAt(t.at.getX() + 0.5, t.at.getY() + 1.0, t.at.getZ() + 0.5);
        if (browse(level, v, f, t.owner, t.market) != null) f.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /** Which stall a folk goes to today, if any: one open, in reach, with something it wants, not one that
     *  turned it away yesterday (except on market day), and not already thronged. */
    private static Trip plan(ServerLevel level, VillageFolkEntity f, UUID village, long day) {
        Trip t = new Trip();
        t.day = day;
        t.setOff = f.tickCount;
        Villages.Village v = Villages.get(village);
        if (v == null) { t.done = true; return t; }
        boolean md = Market.marketDay(village, day);
        List<Want> wants = wants(level, v, f, md);
        if (wants.isEmpty()) { t.done = true; return t; }
        List<Stall> stalls = all(village);
        int start = stalls.isEmpty() ? 0 : Math.floorMod(f.getUUID().hashCode(), stalls.size());
        for (int i = 0; i < stalls.size(); i++) {
            Stall st = stalls.get((start + i) % stalls.size());
            if (st.state(day) != State.OPEN) continue;
            Booth b = boothOf(village, st);
            if (b == null || !level.isLoaded(b.at()) || !(level.getBlockEntity(b.at()) instanceof Container box)) continue;
            if (b.stand().distSqr(f.blockPosition()) > 96.0 * 96.0) continue;
            String visits = st.owner + "/" + day;
            if (VISITS.getOrDefault(visits, 0) >= VISITS_A_DAY) continue;
            Long away = TURNED_AWAY.get(f.getUUID() + "/" + st.owner);
            if (!md && away != null && day - away <= 1) continue;
            if (!hasAny(box, wants)) continue;
            VISITS.merge(visits, 1, Integer::sum);
            t.owner = st.owner;
            t.ownerName = st.name;
            t.stand = b.stand();
            t.at = b.at();
            t.market = md;
            return t;
        }
        t.done = true;
        return t;
    }

    private static boolean hasAny(Container box, List<Want> wants) {
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack s = box.getItem(i);
            if (s.isEmpty() || s.isDamaged() || Market.isCoin(s)) continue;
            for (Want w : wants) if (w.what().test(s)) return true;
        }
        return false;
    }

    /**
     * A folk at a player's stall looks over what is there for what it wants, most pressing first, and buys
     * what it thinks fair (two things at most on market day, one on other days). Returns what it bought
     * ("bread from Steve's stall"), or null.
     */
    @Nullable
    public static String browse(ServerLevel level, Villages.Village v, VillageFolkEntity f, UUID owner, boolean marketDay) {
        Stall st = load(v.id(), owner);
        long day = today(level);
        if (st == null || st.state(day) != State.OPEN) return null;
        Booth b = boothOf(v.id(), st);
        if (b == null) return null;
        List<String> bought = new ArrayList<>();
        for (Want w : wants(level, v, f, marketDay)) {
            if (bought.size() >= (marketDay ? 2 : 1)) break;
            Sold s = buyFrom(level, v, f, st, b, w.what(), w.use(), w.favourite(), true);
            if (s != null) bought.add(s.words());
        }
        if (bought.isEmpty()) {
            TURNED_AWAY.put(f.getUUID() + "/" + owner, day);
            return null;
        }
        return String.join(" and ", bought);
    }

    /**
     * The players' stalls as one more seller: a folk about to buy something of the village (its treat on
     * market day; the shop's tool or luxury, for a path that wants it) buys it at a player's stall instead
     * when one has it at a price this folk thinks fair, and no dearer than the village asks when the
     * village has it too. Returns what was bought ("bread from Steve's stall"), or null.
     */
    @Nullable
    public static String instead(ServerLevel level, Villages.Village v, VillageFolkEntity f, Predicate<ItemStack> want, Use use,
                                 @Nullable Item favourite) {
        String booths = Ledger.note(v.id(), BOOTHS);
        if (booths == null || booths.isEmpty()) return null;
        long day = today(level);
        Trip trip = TRIPS.get(f.getUUID());
        for (Stall st : all(v.id())) {
            if (st.state(day) != State.OPEN) continue;
            if (trip != null && trip.day == day && trip.done && st.owner.equals(trip.owner)) continue;   // looked it over today already
            Booth b = boothOf(v.id(), st);
            if (b == null) continue;
            Sold s = buyFrom(level, v, f, st, b, want, use, favourite, false);
            if (s != null) return s.words();
        }
        return null;
    }

    /** Tests and /village stall market: market day at the players' stalls, now: every folk with coin looks
     *  over every open stall. Returns the coin taken. */
    public static int marketDayForTests(ServerLevel level, Villages.Village v) {
        int before = takings(v.id());
        long day = today(level);
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.purse() <= 0) continue;
            for (Stall st : all(v.id())) if (st.state(day) == State.OPEN) browse(level, v, f, st.owner, true);
        }
        return takings(v.id()) - before;
    }

    private static int takings(UUID village) {
        int n = 0;
        for (Stall st : all(village)) n += st.takenAll;
        return n;
    }

    // ------------------------------------------------------------------ renting

    /** A week on the square: five coins in a hamlet, more in a bigger place (as its wages are). */
    public static int rent(UUID village) {
        return Math.max(5, (int) Math.round(5 * Wealth.standing(village) / 10.0));
    }

    /**
     * A player takes a stall for a week: the booth named (if it is to let), else one to let, else one put up
     * for it on the square out of the stores (or the player's own makings). The rent into the treasury.
     * A player with a stall already pays another week. Returns what to tell them.
     */
    public static String rent(ServerLevel level, Villages.Village v, Player p, @Nullable Booth wanted) {
        UUID id = v.id();
        long day = today(level);
        if (Standing.of(id, p.getUUID(), level.getGameTime()).title() == Standing.Title.OUTCAST) return "Nobody here will let a stall to you.";
        if (Laws.banished(id, p.getUUID(), day)) return "You're banished from " + Villages.name(id) + ". No stall for you.";
        Stall st = load(id, p.getUUID());
        if (st != null && st.at != null) return renew(level, v, p, st);
        boolean paid = st != null && st.paidTill >= day;                 // a week paid, its booth lost: one again, for nothing
        int rent = rent(id);
        int coins = Market.coinsHeld(p);
        if (!paid && coins < rent) {
            return "A stall on the square is " + rent + coinWord(rent) + " a week, paid to the treasury. You've " + coins + ".";
        }
        Booth b = wanted != null && tenantOf(id, wanted) == null && standing(level, wanted) ? wanted : freeBooth(level, id);
        if (b == null) {
            if (booths(id).size() >= MOST_BOOTHS) return "Every stall on the square is let just now. Come back when one is free.";
            Booth spot = spot(level, v);
            if (spot == null) return "There's no room on the square for another stall just now.";
            String why = putUp(level, v, spot, p, false);
            if (why != null) return why;
            b = spot;
        }
        if (!paid) {
            Market.payOut(p, rent);
            Ledger.addCoins(id, rent);
            Economy.rent(id, rent);
        }
        if (st == null) st = new Stall(p.getUUID());
        st.name = p.getName().getString();
        st.at = b.at();
        if (!paid) st.paidTill = day + WEEK - 1;
        st.since = day;
        st.gaveBack = -1;
        st.dueTold = -1;
        save(id, st);
        sign(level, id, b, st);
        Villages.tell(id, day, st.name + " rented a stall on the square");
        int md = Market.daysToMarket(id, day);
        return (paid ? "Your stall's set up again" : "The stall's yours for " + rent + coinWord(rent) + ", paid to the treasury")
            + ", till day " + st.paidTill + " — on the square at " + b.at().getX() + ", " + b.at().getZ() + ". Fill its barrel; sneak and "
            + "right-click it (or its sign) to set your prices, the going price beside each. The folk buy "
            + (md == 0 ? "today — it's market day" : "on market day (in " + md + (md == 1 ? " day" : " days") + ")")
            + " and whenever they want what you've got, with their own coin, into your till.";
    }

    /** Another week's rent (up to four weeks ahead); a stall shut or given back opens again. */
    static String renew(ServerLevel level, Villages.Village v, Player p, Stall st) {
        long day = today(level);
        int rent = rent(v.id());
        if (st.paidTill - day >= (MOST_WEEKS - 1) * WEEK) return "Your stall's paid up to day " + st.paidTill + ". That's far enough ahead.";
        int coins = Market.coinsHeld(p);
        if (coins < rent) return "Another week is " + rent + coinWord(rent) + ". You've " + coins + ".";
        Market.payOut(p, rent);
        Ledger.addCoins(v.id(), rent);
        Economy.rent(v.id(), rent);
        boolean reopened = st.state(day) != State.OPEN;
        st.paidTill = Math.max(st.paidTill, day - 1) + WEEK;
        st.gaveBack = -1;
        st.dueTold = -1;
        save(v.id(), st);
        Booth b = boothOf(v.id(), st);
        if (b != null) sign(level, v.id(), b, st);
        return (reopened ? "Paid, and your stall's open again" : "Paid") + ": it's yours till day " + st.paidTill + ".";
    }

    /** The coin in the player's till, into their pack. Returns how much. */
    public static int payTill(ServerLevel level, UUID village, Player p) {
        Stall st = load(village, p.getUUID());
        if (st == null || st.till <= 0) return 0;
        int n = st.till;
        st.till = 0;
        for (int left = n; left > 0; left -= 64) Dealings.giveCoins(p, Math.min(64, left));
        if (st.at == null) forget(village, p.getUUID());
        else save(village, st);
        return n;
    }

    /** Everything in the stall's barrel back into the player's pack (what won't fit, at their feet). Returns how many. */
    static int goodsBack(ServerLevel level, Booth b, Player p) {
        if (!level.isLoaded(b.at()) || !(level.getBlockEntity(b.at()) instanceof Container box)) return 0;
        int n = 0;
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack s = box.getItem(i);
            if (s.isEmpty()) continue;
            n += s.getCount();
            Dealings.give(p, s.copy());
            box.setItem(i, ItemStack.EMPTY);
        }
        box.setChanged();
        return n;
    }

    /** The player gives the stall up: their goods and the till back to them, the booth to let. The rent
     *  paid is the market's. Returns what to tell them. */
    public static String giveUp(ServerLevel level, Villages.Village v, Player p) {
        UUID id = v.id();
        Stall st = load(id, p.getUUID());
        if (st == null || st.at == null) return "You've no stall here to give up.";
        Booth b = boothOf(id, st);
        int goods = b == null ? 0 : goodsBack(level, b, p);
        int till = st.till;
        st.till = 0;
        for (int left = till; left > 0; left -= 64) Dealings.giveCoins(p, Math.min(64, left));
        st.at = null;
        forget(id, p.getUUID());
        if (b != null) sign(level, id, b, null);
        Villages.tell(id, today(level), st.name + " gave up their stall on the square");
        return "Your stall's given up" + (goods > 0 ? ": your " + goods + " things back to you" : "")
            + (till > 0 ? (goods > 0 ? ", and " : ": ") + till + coinWord(till) + " from the till" : "") + ". It's to let again.";
    }

    /** Prices from the stall's screen: a ware's price for a lot (0 keeps it back; under 0, the going price). */
    static String setPrices(ServerLevel level, Villages.Village v, Stall st, CompoundTag prices) {
        int n = 0;
        for (String key : prices.getAllKeys()) {
            if (n >= 40 || sampleOf(key).isEmpty()) continue;
            int price = prices.getInt(key);
            if (price < 0) st.prices.remove(key);
            else st.prices.put(key, Math.min(9999, price));
            n++;
        }
        save(v.id(), st);
        Booth b = boothOf(v.id(), st);
        if (b != null) sign(level, v.id(), b, st);
        return n == 0 ? "" : "Prices set.";
    }

    @Nullable
    private static Booth freeBooth(ServerLevel level, UUID village) {
        for (Booth b : booths(village)) if (tenantOf(village, b) == null && standing(level, b)) return b;
        return null;
    }

    /** Is the booth's barrel still there (or out of sight, and taken on trust)? */
    private static boolean standing(ServerLevel level, Booth b) {
        return !level.isLoaded(b.at()) || level.getBlockEntity(b.at()) instanceof Container;
    }

    // ------------------------------------------------------------------ the booth

    /** Where a new booth goes: one of the square's places for it, else anywhere round the square that takes
     *  a barrel, its counter to the middle. Null if nowhere will. */
    @Nullable
    static Booth spot(ServerLevel level, Villages.Village v) {
        BlockPos c = v.centre();
        List<Booth> taken = booths(v.id());
        for (int[] s : SPOTS) {
            Booth b = fits(level, c, c.getX() + s[0], c.getZ() + s[1], Direction.from2DDataValue(s[2]), true);
            if (b != null && !crowds(taken, b)) return b;
        }
        for (int r = 6; r <= TownPlan.PLAZA - 2; r++) {
            for (int i = -r; i <= r; i += 2) {
                for (int[] d : new int[][]{ { i, -r }, { i, r }, { -r, i }, { r, i } }) {
                    Direction front = Math.abs(d[0]) >= Math.abs(d[1]) ? (d[0] > 0 ? Direction.WEST : Direction.EAST)
                        : (d[1] > 0 ? Direction.NORTH : Direction.SOUTH);
                    Booth b = fits(level, c, c.getX() + d[0], c.getZ() + d[1], front, false);
                    if (b != null && !crowds(taken, b)) return b;
                }
            }
        }
        return null;
    }

    private static boolean crowds(List<Booth> taken, Booth b) {
        for (Booth o : taken) {
            if (Math.max(Math.abs(o.at().getX() - b.at().getX()), Math.abs(o.at().getZ() - b.at().getZ())) <= 2) return true;
        }
        return false;
    }

    /** A booth here, if the ground takes one: sound and near the square's level, room for the barrel, the
     *  sign on its lid and a customer in front (and, if {@code roomy}, for its awning and posts, a second
     *  customer and its keeper behind); never across the ways in from the gates. */
    @Nullable
    private static Booth fits(ServerLevel level, BlockPos heart, int x, int z, Direction front, boolean roomy) {
        if (Math.abs(x - heart.getX()) <= TownPlan.AVENUE || Math.abs(z - heart.getZ()) <= TownPlan.AVENUE) return null;
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (Math.abs(y - heart.getY()) > 3) return null;
        BlockPos at = new BlockPos(x, y, z);
        BlockState under = level.getBlockState(at.below());
        if (!under.isFaceSturdy(level, at.below(), Direction.UP) || !level.getFluidState(at.below()).isEmpty()) return null;
        for (int h = 0; h <= 1; h++) if (!clear(level, at.above(h))) return null;
        if (!clear(level, at.relative(front)) || !clear(level, at.relative(front).above())) return null;
        if (roomy) {
            // The awning over it, room to buy at in front and to keep the stall behind, and its posts.
            if (!clear(level, at.above(2))) return null;
            for (BlockPos p : new BlockPos[]{ at.relative(front, 2), at.relative(front.getOpposite()) }) {
                if (!clear(level, p) || !clear(level, p.above())) return null;
            }
            Direction across = front.getClockWise();
            for (int a : new int[]{ -1, 1 }) for (int h = 0; h <= 2; h++) if (!clear(level, at.relative(across, a).above(h))) return null;
        }
        return new Booth(at, front);
    }

    private static boolean clear(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return (s.isAir() || s.canBeReplaced()) && s.getFluidState().isEmpty();
    }

    /** Where one of the makings came from, to go back there if the rest can't be had. */
    private enum From { NONE, FREE, STORES, PLANKS, PLAYER }

    /**
     * Put up a booth: its barrel and the sign on its lid, out of the stores (a barrel, or seven planks; a
     * sign, or two planks; never while the village is short of timber), else the player's own (a barrel or
     * a chest, and a sign); then its posts and awning if they can be had. Nothing taken if the barrel and
     * the sign can't both be. Returns why not, or null when it stands.
     */
    @Nullable
    static String putUp(ServerLevel level, Villages.Village v, Booth b, @Nullable Player p, boolean free) {
        boolean timber = free || !shortOfTimber(level, v);
        Block box = Blocks.BARREL;
        From boxFrom;
        if (free) boxFrom = From.FREE;
        else if (Crafts.take(level, v, s -> s.is(Items.BARREL), 1)) boxFrom = From.STORES;
        else if (timber && Crafts.usePlanks(level, v, 7)) boxFrom = From.PLANKS;
        else if (p != null && fromPack(p, s -> s.is(Items.BARREL), 1) != null) boxFrom = From.PLAYER;
        else if (p != null && fromPack(p, s -> s.is(Items.CHEST), 1) != null) { boxFrom = From.PLAYER; box = Blocks.CHEST; }
        else return "The stores can't spare a barrel for a stall just now" + (p != null ? ": bring one (or a chest) and a sign, and it's yours." : ".");
        Block sign = Blocks.OAK_SIGN;
        boolean signed = free || Crafts.take(level, v, s -> s.is(ItemTags.SIGNS), 1) || (timber && Crafts.usePlanks(level, v, 2));
        if (!signed && p != null) {
            ItemStack mine = fromPack(p, s -> s.is(ItemTags.SIGNS) && Block.byItem(s.getItem()) instanceof StandingSignBlock, 1);
            if (mine != null) {
                sign = Block.byItem(mine.getItem());
                signed = true;
            }
        }
        if (!signed) {
            switch (boxFrom) {
                case STORES -> Crafts.store(level, v, new ItemStack(Items.BARREL));
                case PLANKS -> Crafts.giveBack(level, v, Items.OAK_PLANKS, 7);
                case PLAYER -> { if (p != null) Dealings.give(p, new ItemStack(box == Blocks.CHEST ? Items.CHEST : Items.BARREL)); }
                default -> { }
            }
            return "The stores can't spare a sign for a stall just now" + (p != null ? ": bring one, and it's yours." : ".");
        }
        BlockState boxState = box == Blocks.CHEST ? Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, b.front())
            : Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP);
        level.setBlock(b.at(), boxState, 3);
        level.setBlock(b.sign(), sign.defaultBlockState().setValue(StandingSignBlock.ROTATION, RotationSegment.convertToSegment(b.front())), 3);
        List<Booth> all = booths(v.id());
        all.removeIf(o -> o.at().equals(b.at()));
        all.add(b);
        saveBooths(v.id(), all);
        dress(level, v, b, p, free);
        sign(level, v.id(), b, tenantOf(v.id(), b));
        return null;
    }

    /** Has the booth its posts? */
    static boolean dressed(ServerLevel level, Booth b) {
        Direction across = b.front().getClockWise();
        return level.getBlockState(b.at().relative(across, 1)).getBlock() instanceof FenceBlock
            && level.getBlockState(b.at().relative(across, -1)).getBlock() instanceof FenceBlock;
    }

    /**
     * A booth's posts and awning: four lengths of fence (or eight planks) and three wool, out of the stores
     * (not while the village is short of timber), else the player's own; all or none, and only where there
     * is room for them. Returns whether it went up.
     */
    static boolean dress(ServerLevel level, Villages.Village v, Booth b, @Nullable Player p, boolean free) {
        Direction across = b.front().getClockWise();
        List<BlockPos> posts = new ArrayList<>();
        List<BlockPos> awning = new ArrayList<>();
        for (int a : new int[]{ -1, 1 }) for (int h = 0; h <= 1; h++) posts.add(b.at().relative(across, a).above(h));
        for (int a = -1; a <= 1; a++) awning.add(b.at().relative(across, a).above(2));
        for (BlockPos q : posts) if (!clear(level, q)) return false;
        for (BlockPos q : awning) if (!clear(level, q)) return false;
        Block fence = Blocks.OAK_FENCE;
        List<Block> wool = new ArrayList<>();
        if (free) {
            for (int i = 0; i < 3; i++) wool.add(i == 1 ? Blocks.WHITE_WOOL : Blocks.RED_WOOL);
        } else if (!shortOfTimber(level, v) && Market.bedsShort(v.id()) == 0 && Crafts.stock(level, v, s -> s.is(ItemTags.WOOL)) >= 3
                && Crafts.stock(level, v, s -> s.is(ItemTags.WOODEN_FENCES))
                    + (Crafts.stock(level, v, s -> s.is(ItemTags.PLANKS)) + 4 * Crafts.stock(level, v, s -> s.is(ItemTags.LOGS))) / 2 >= 4 + 8) {
            int fences = 0;
            while (fences < 4 && Crafts.fence(level, v)) fences++;
            if (fences < 4) {
                Crafts.giveBack(level, v, Items.OAK_FENCE, fences);
                return false;
            }
            for (int i = 0; i < 3; i++) {
                ItemStack w = Crafts.takeOne(level, v, s -> s.is(ItemTags.WOOL));
                if (w.isEmpty()) break;
                wool.add(Block.byItem(w.getItem()));
            }
            if (wool.size() < 3) {
                Crafts.giveBack(level, v, Items.OAK_FENCE, 4);
                for (Block w : wool) Crafts.store(level, v, new ItemStack(w));
                return false;
            }
        } else if (p != null && Commerce.taking(p, s -> s.is(ItemTags.WOODEN_FENCES)) >= 4 && Commerce.taking(p, s -> s.is(ItemTags.WOOL)) >= 3) {
            List<ItemStack> f = Commerce.takeFrom(p, s -> s.is(ItemTags.WOODEN_FENCES), 4);
            if (!f.isEmpty() && Block.byItem(f.get(0).getItem()) instanceof FenceBlock fb) fence = fb;
            for (ItemStack w : Commerce.takeFrom(p, s -> s.is(ItemTags.WOOL), 3)) {
                for (int i = 0; i < w.getCount(); i++) wool.add(Block.byItem(w.getItem()));
            }
        } else {
            return false;
        }
        for (BlockPos q : posts) level.setBlock(q, Block.updateFromNeighbourShapes(fence.defaultBlockState(), level, q), 3);
        for (int i = 0; i < awning.size(); i++) level.setBlock(awning.get(i), wool.get(Math.min(i, wool.size() - 1)).defaultBlockState(), 3);
        return true;
    }

    private static boolean shortOfTimber(ServerLevel level, Villages.Village v) {
        for (Villages.Need n : Villages.needs(level, v.id())) if (n.task() == Villages.Task.LOGS) return true;
        return false;
    }

    /** So many of what matches out of a player's pack, all or none: the first stack of it, or null. */
    @Nullable
    private static ItemStack fromPack(Player p, Predicate<ItemStack> what, int n) {
        if (Commerce.taking(p, what) < n) return null;
        List<ItemStack> got = Commerce.takeFrom(p, what, n);
        return got.isEmpty() ? null : got.get(0);
    }

    /** The sign on a booth's lid: whose it is and what it sells, to let, its rent due, or its goods kept. */
    static void sign(ServerLevel level, UUID village, Booth b, @Nullable Stall st) {
        if (!level.isLoaded(b.sign()) || !(level.getBlockEntity(b.sign()) instanceof SignBlockEntity sign)) return;
        long day = today(level);
        String[] lines;
        if (st == null || st.at == null) {
            int rent = rent(village);
            lines = new String[]{ "Stall to let", rent + "c a week", "Right-click", "to rent it" };
        } else {
            String whose = clip(st.name.length() <= 8 ? st.name + "'s stall" : st.name);
            State s = st.state(day);
            if (s == State.DUE) {
                lines = new String[]{ whose, "Shut:", "rent due", rent(village) + "c a week" };
            } else if (s == State.HELD) {
                lines = new String[]{ clip(st.name + "'s"), "goods, kept", "to collect", "" };
            } else {
                lines = new String[]{ whose, "", "", Market.marketDay(village, day) ? "Market day!" : "Open" };
                int k = 1;
                if (level.getBlockEntity(b.at()) instanceof Container box) {
                    List<String> seen = new ArrayList<>();
                    for (int i = 0; i < box.getContainerSize() && k <= 2; i++) {
                        ItemStack it = box.getItem(i);
                        if (it.isEmpty() || Market.isCoin(it) || seen.contains(key(it))) continue;
                        seen.add(key(it));
                        int price = asking(level, village, st, key(it), it);
                        if (price <= 0) continue;
                        int lot = lot(it);
                        lines[k++] = clip((lot > 1 ? lot + " " : "") + nameOf(it) + " " + price + "c");
                    }
                }
                if (k == 1) lines[1] = "(nothing in";
                if (k == 1) lines[2] = "just now)";
            }
        }
        TownLife.write(sign, lines);
    }

    // ------------------------------------------------------------------ the day

    /**
     * The market's look at the players' stalls, from TownLife's round (Commerce.daily): once a day every
     * tenancy looked over (its rent run out, given back, emptied and let again, yesterday's takings in the
     * chronicle, its booth's posts and sign); and now and then, with a player about and the makings to
     * spare, a booth put up to let.
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = today(level);
        String bs = Ledger.note(id, BOOTHS);
        if (bs != null && !bs.isEmpty() && DAILY.getOrDefault(id, -1L) != day) {
            DAILY.put(id, day);
            com.jrpetty.mcassistant.Guard.run("player stalls", () -> morning(level, v, day));
        }
        if (TURN.merge(id, 1, Integer::sum) % 6 == 0) com.jrpetty.mcassistant.Guard.run("a stall to let", () -> toLet(level, v));
    }

    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        String place = Villages.name(id);
        for (Stall st : all(id)) {
            boolean changed = false;
            Booth b = st.at == null ? null : boothOf(id, st);
            if (b != null && level.isLoaded(b.at()) && !(level.getBlockEntity(b.at()) instanceof Container)) {
                // Its barrel gone (broken, burnt): what was in it fell where it stood.
                dropBooth(id, b.at());
                st.at = null;
                b = null;
                changed = true;
                tellOwner(level, st.owner, "Your stall's barrel in " + place + " is gone. Ask anybody for your stall and it's set up again"
                    + (st.paidTill >= day ? ", the rent paid." : "."));
            }
            State s = st.state(day);
            if (s == State.DUE && st.dueTold < 0) {
                st.dueTold = day;
                changed = true;
                tellOwner(level, st.owner, "Your stall in " + place + " is shut: its rent ran out on day " + st.paidTill + ". "
                    + rent(id) + " coins for another week (its screen, or ask anybody), or it's given back on day " + (st.paidTill + GRACE + 1)
                    + ", your goods kept in it for you.");
            }
            if (s == State.HELD && st.gaveBack < 0) {
                st.gaveBack = day;
                changed = true;
                Villages.tell(id, day, st.name + "'s stall was given back, its rent unpaid; their goods are kept in it for them");
                tellOwner(level, st.owner, "Your stall in " + place + " was given back, its rent unpaid. Your goods are kept in its barrel"
                    + " for you to collect" + (st.till > 0 ? ", and " + st.till + " coins in its till" : "") + ".");
            }
            if (s == State.HELD && b != null && level.isLoaded(b.at()) && level.getBlockEntity(b.at()) instanceof Container box && box.isEmpty()) {
                st.at = null;                                                 // collected: the booth's to let again
                changed = true;
            }
            roll(st, day);
            if (st.toldDay != day && st.weekCoin[1] > 0) {
                st.toldDay = day;
                changed = true;
                Villages.tell(id, day, "folk spent " + st.weekCoin[1] + " coins at " + st.name + "'s stall yesterday, for "
                    + st.weekCount[1] + " things");
            }
            if (st.at == null && st.till <= 0) forget(id, st.owner);
            else if (changed) save(id, st);
            if (b != null) {
                if (!dressed(level, b)) dress(level, v, b, null, false);
                sign(level, id, b, st.at == null ? null : st);
            }
        }
        for (Booth b : booths(id)) {
            if (!level.isLoaded(b.at())) continue;
            if (!(level.getBlockEntity(b.at()) instanceof Container)) {
                dropBooth(id, b.at());
                continue;
            }
            if (tenantOf(id, b) == null) sign(level, id, b, null);
        }
    }

    /** One booth standing to let, from the Stone Age, while a player is about and the stores can spare its
     *  makings: put up by the market's hands (TownJobs), out of the stores. */
    private static void toLet(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Villages.ageOf(id).ordinal() < Villages.Age.STONE.ordinal()) return;
        List<Booth> all = booths(id);
        if (all.size() >= MOST_BOOTHS) return;
        for (Booth b : all) if (tenantOf(id, b) == null) return;
        boolean near = false;
        for (ServerPlayer p : level.players()) if (p.blockPosition().closerThan(v.centre(), 96)) { near = true; break; }
        if (!near) return;
        int wood = Crafts.stock(level, v, s -> s.is(ItemTags.PLANKS)) + 4 * Crafts.stock(level, v, s -> s.is(ItemTags.LOGS));
        boolean barrel = Crafts.stock(level, v, s -> s.is(Items.BARREL)) > 0;
        if (wood < (barrel ? 0 : 7) + 2 + 8 + 64 || Crafts.stock(level, v, s -> s.is(ItemTags.WOOL)) < 3
                || Market.bedsShort(id) > 0) return;   // and the builders' timber kept, and the beds' wool
        if (shortOfTimber(level, v)) return;
        Booth spot = spot(level, v);
        if (spot == null) return;
        if (!TownJobs.atWork(level, v, "market", spot.at(), "putting up a stall to let")) return;
        if (putUp(level, v, spot, null, false) == null) {
            Villages.tell(id, today(level), "a stall went up on the square, to let");
        }
    }

    // ------------------------------------------------------------------ the player at a stall

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        BlockState state = level.getBlockState(pos);
        boolean box = state.is(Blocks.BARREL) || state.is(Blocks.CHEST);
        if (!box && !(state.getBlock() instanceof SignBlock)) return;
        Villages.Village v = Villages.nearest(level, pos, TownPlan.PLAZA * 3);
        if (v == null) return;
        Booth b = boothAt(v.id(), pos);
        if (b == null) return;
        Player p = e.getEntity();
        Stall st = tenantOf(v.id(), b);
        boolean mine = st != null && st.owner.equals(p.getUUID());
        // Its own keeper, without crouching, opens the barrel to stock it (or, given back, to collect).
        if (mine && box && !p.isShiftKeyDown()) {
            if (e.getHand() == InteractionHand.MAIN_HAND && st.state(today(level)) == State.OPEN) {
                p.displayClientMessage(Component.literal("What's in this barrel is for sale. Crouch and right-click it (or its sign) for your prices and the till."), true);
            }
            return;
        }
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getHand() != InteractionHand.MAIN_HAND) return;
        if (st == null || mine) {
            if (p instanceof ServerPlayer sp) open(sp, level, v, b);
            return;
        }
        p.displayClientMessage(Component.literal(onView(level, v.id(), st, b)), false);
    }

    /** What another player is told at somebody's stall. */
    private static String onView(ServerLevel level, UUID village, Stall st, Booth b) {
        State s = st.state(today(level));
        if (s == State.HELD) return st.name + "'s stall, given back: their goods are kept in it for them.";
        if (s == State.DUE) return st.name + "'s stall: shut, its rent due.";
        List<String> wares = new ArrayList<>();
        if (level.getBlockEntity(b.at()) instanceof Container box) {
            List<String> seen = new ArrayList<>();
            for (int i = 0; i < box.getContainerSize() && wares.size() < 6; i++) {
                ItemStack it = box.getItem(i);
                if (it.isEmpty() || Market.isCoin(it) || seen.contains(key(it))) continue;
                seen.add(key(it));
                int price = asking(level, village, st, key(it), it);
                if (price > 0) wares.add(thing(it, lot(it)) + " " + price + "c");
            }
        }
        return st.name + "'s stall" + (wares.isEmpty() ? ": nothing out just now." : ": " + String.join(", ", wares) + ". The folk buy here.");
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || e.getPlayer() == null || e.getPlayer().isCreative()) return;
        BlockState state = e.getState();
        if (!(state.is(Blocks.BARREL) || state.is(Blocks.CHEST) || state.getBlock() instanceof SignBlock)) return;
        Villages.Village v = Villages.nearest(level, e.getPos(), TownPlan.PLAZA * 3);
        if (v == null) return;
        Booth b = boothAt(v.id(), e.getPos());
        if (b == null) return;
        e.setCanceled(true);
        Stall st = tenantOf(v.id(), b);
        e.getPlayer().displayClientMessage(Component.literal(st == null ? "That's the market's stall, to let."
            : st.owner.equals(e.getPlayer().getUUID()) ? "Your stall: give it up on its screen to have your goods back."
            : "That's " + st.name + "'s stall."), true);
    }

    /** The stall's screen, for whoever opened it. */
    public static void open(ServerPlayer p, ServerLevel level, Villages.Village v, Booth b) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,
            new com.jrpetty.mcassistant.net.StallScreenPayload(screen(level, v, b, p)));
    }

    /** A button on the stall's screen. Never trusted as sent: the stall must be there, near, and theirs. */
    public static void act(ServerPlayer p, String action, CompoundTag args) {
        ServerLevel level = p.serverLevel();
        BlockPos at = new BlockPos(args.getInt("x"), args.getInt("y"), args.getInt("z"));
        if (p.distanceToSqr(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5) > 24.0 * 24.0) {
            p.displayClientMessage(Component.literal("You're too far from the stall."), true);
            return;
        }
        Villages.Village v = Villages.nearest(level, at, TownPlan.PLAZA * 3);
        if (v == null) return;
        Booth b = boothAt(v.id(), at);
        if (b == null) return;
        Stall st = tenantOf(v.id(), b);
        boolean mine = st != null && st.owner.equals(p.getUUID());
        String said = switch (action) {
            case "rent" -> st == null ? rent(level, v, p, b) : mine ? renew(level, v, p, st) : "That stall's let to " + st.name + ".";
            case "prices" -> mine ? setPrices(level, v, st, args.getCompound("prices")) : "";
            case "till" -> {
                if (!mine) yield "";
                int n = payTill(level, v.id(), p);
                yield n > 0 ? "You take " + n + coinWord(n) + " from the till." : "The till's empty.";
            }
            case "goods" -> {
                if (!mine) yield "";
                int n = goodsBack(level, b, p);
                yield n > 0 ? "Your " + n + " things back from the stall." : "There's nothing in it.";
            }
            case "giveup" -> mine ? giveUp(level, v, p) : "";
            default -> "";
        };
        if (!said.isEmpty()) p.displayClientMessage(Component.literal(said), !action.equals("rent") && !action.equals("giveup"));
        open(p, level, v, b);
    }

    /**
     * The stall's screen: as a booth to let (its rent), or, for its tenant, everything — its rent, the till,
     * each kind of thing in its barrel with its lot, the player's price and the going price, what sold of it
     * this week; the week's takings and all of them, the last sales (who, what, for how much) and what was
     * turned down as too dear.
     */
    public static CompoundTag screen(ServerLevel level, Villages.Village v, Booth b, Player viewer) {
        UUID id = v.id();
        long day = today(level);
        CompoundTag t = new CompoundTag();
        t.putString("village", Villages.name(id));
        t.putInt("x", b.at().getX());
        t.putInt("y", b.at().getY());
        t.putInt("z", b.at().getZ());
        t.putLong("day", day);
        t.putInt("rent", rent(id));
        t.putInt("coins", Market.coinsHeld(viewer));
        t.putInt("marketIn", Market.daysToMarket(id, day));
        Stall st = tenantOf(id, b);
        if (st == null) {
            t.putString("mode", "let");
            return t;
        }
        State s = st.state(day);
        t.putString("mode", !st.owner.equals(viewer.getUUID()) ? "view" : s == State.HELD ? "held" : "own");
        stallTag(level, id, st, b, t);
        return t;
    }

    /** One tenancy's books, into a tag (the screen, and the Shops page). */
    private static void stallTag(ServerLevel level, UUID id, Stall st, @Nullable Booth b, CompoundTag t) {
        long day = today(level);
        roll(st, day);
        t.putString("owner", st.name);
        t.putString("state", st.state(day).name().toLowerCase(Locale.ROOT));
        t.putLong("paidTill", st.paidTill);
        t.putLong("since", st.since);
        t.putLong("gaveBack", st.gaveBack);
        t.putInt("till", st.till);
        int n7 = 0, c7 = 0;
        for (int i = 0; i < WEEK; i++) { n7 += st.weekCount[i]; c7 += st.weekCoin[i]; }
        t.putInt("sold7", n7);
        t.putInt("coin7", c7);
        t.putInt("soldToday", st.weekCount[0]);
        t.putInt("coinToday", st.weekCoin[0]);
        t.putInt("soldAll", st.soldAll);
        t.putInt("takenAll", st.takenAll);
        if (b != null) {
            t.putInt("bx", b.at().getX());
            t.putInt("bz", b.at().getZ());
        }
        // The wares: what is in the barrel, a row a kind, then what is priced and sold out.
        Map<String, ItemStack> samples = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        boolean worn = false;
        if (b != null && level.isLoaded(b.at()) && level.getBlockEntity(b.at()) instanceof Container box) {
            for (int i = 0; i < box.getContainerSize(); i++) {
                ItemStack s = box.getItem(i);
                if (s.isEmpty() || Market.isCoin(s)) continue;
                if (s.isDamaged()) { worn = true; continue; }
                String k = key(s);
                samples.putIfAbsent(k, s.copyWithCount(1));
                counts.merge(k, s.getCount(), Integer::sum);
            }
        }
        for (String k : st.prices.keySet()) {
            if (samples.containsKey(k)) continue;
            ItemStack s = sampleOf(k);
            if (s.isEmpty()) continue;
            samples.put(k, s);
            counts.put(k, 0);
        }
        Map<String, int[]> sold = new java.util.HashMap<>();
        for (Sale s : st.sales) {
            if (day - s.day() >= WEEK) continue;
            int[] a = sold.computeIfAbsent(s.key(), k -> new int[2]);
            a[0] += s.count();
            a[1] += s.price();
        }
        ListTag wares = new ListTag();
        for (Map.Entry<String, ItemStack> e : samples.entrySet()) {
            ItemStack s = e.getValue();
            CompoundTag w = new CompoundTag();
            w.putString("key", e.getKey());
            w.putString("item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
            w.putBoolean("ench", s.isEnchanted() || e.getKey().endsWith("*"));
            w.putString("name", nameOf(s));
            w.putInt("count", counts.getOrDefault(e.getKey(), 0));
            w.putInt("lot", lot(s));
            int going = going(level, id, s);
            w.putInt("going", going);
            Integer set = st.prices.get(e.getKey());
            w.putBoolean("set", set != null);
            w.putInt("price", set != null ? set : going);
            int[] a = sold.getOrDefault(e.getKey(), new int[2]);
            w.putInt("sold7", a[0]);
            w.putInt("coin7", a[1]);
            wares.add(w);
        }
        t.put("wares", wares);
        t.putBoolean("worn", worn);
        ListTag sales = new ListTag();
        for (int i = 0; i < Math.min(12, st.sales.size()); i++) {
            Sale s = st.sales.get(i);
            CompoundTag x = new CompoundTag();
            x.putLong("day", s.day());
            x.putString("who", s.who());
            x.putString("what", nameOf(sampleOf(s.key())).toLowerCase(Locale.ROOT));
            x.putString("item", s.key().endsWith("*") ? s.key().substring(0, s.key().length() - 1) : s.key());
            x.putInt("count", s.count());
            x.putInt("price", s.price());
            sales.add(x);
        }
        t.put("sales", sales);
        ListTag dear = new ListTag();
        for (int i = 0; i < Math.min(6, st.dear.size()); i++) {
            Dear d = st.dear.get(i);
            CompoundTag x = new CompoundTag();
            x.putLong("day", d.day());
            x.putString("who", d.who());
            x.putString("what", nameOf(sampleOf(d.key())).toLowerCase(Locale.ROOT));
            x.putInt("price", d.price());
            x.putInt("going", d.going());
            dear.add(x);
        }
        t.put("dear", dear);
    }

    /** The players' stalls for the town's books (the Shops page): every tenancy with its books, and how many
     *  booths stand to let. */
    public static ListTag report(ServerLevel level, UUID village) {
        ListTag out = new ListTag();
        for (Stall st : all(village)) {
            CompoundTag t = new CompoundTag();
            t.putInt("rent", rent(village));
            stallTag(level, village, st, boothOf(village, st), t);
            out.add(t);
        }
        int free = 0;
        for (Booth b : booths(village)) if (tenantOf(village, b) == null) free++;
        if (free > 0) {
            CompoundTag t = new CompoundTag();
            t.putString("state", "let");
            t.putInt("free", free);
            t.putInt("rent", rent(village));
            out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------ talk

    /** "Could I rent a stall?", "pay the rent", "take the till", "give up my stall", or how it stands. */
    public static String talk(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "A stall? There's no market here.";
        Villages.Village v = Villages.get(village);
        if (v == null) return "A stall? There's no market here.";
        String t = text.toLowerCase(Locale.ROOT);
        Stall st = load(village, p.getUUID());
        if (st != null) {
            if (t.contains("till") || t.contains("takings") || t.contains("collect")) {
                int n = payTill(level, village, p);
                return n > 0 ? "Here's what your stall took: " + n + coinWord(n) + "." : "Your till's empty just now.";
            }
            if (t.contains("give up") || t.contains("give it up") || t.contains("give back")) return giveUp(level, v, p);
            if (st.at != null && (t.contains("pay") || t.contains("another week"))) return renew(level, v, p, st);
            if (st.at == null && st.till > 0) {
                int n = payTill(level, village, p);
                return "We kept your stall's takings for you: " + n + coinWord(n) + ". Ask again if you want a stall.";
            }
            if (st.at != null) return status(level, v, st);
        }
        return rent(level, v, p, null);
    }

    private static String status(ServerLevel level, Villages.Village v, Stall st) {
        long day = today(level);
        roll(st, day);
        int n7 = 0, c7 = 0;
        for (int i = 0; i < WEEK; i++) { n7 += st.weekCount[i]; c7 += st.weekCoin[i]; }
        int md = Market.daysToMarket(v.id(), day);
        return switch (st.state(day)) {
            case DUE -> "Your stall's shut: the rent ran out on day " + st.paidTill + ". " + rent(v.id()) + " coins for another week (say \"pay the rent\"), "
                + "or it's given back on day " + (st.paidTill + GRACE + 1) + ", your goods kept for you.";
            case HELD -> "We gave your stall back on day " + st.gaveBack + ", the rent unpaid. Your goods are in its barrel for you to collect"
                + (st.till > 0 ? ", and " + st.till + " coins in its till (say \"take the till\")" : "") + " — or pay the rent and it's yours again.";
            default -> "Your stall's on the square at " + st.at.getX() + ", " + st.at.getZ() + ", paid up to day " + st.paidTill + ". "
                + (n7 > 0 ? "It's sold " + n7 + " things this week for " + c7 + coinWord(c7) + ". " : "Nothing's sold this week yet. ")
                + (st.till > 0 ? "There's " + st.till + coinWord(st.till) + " in its till: say \"take the till\". " : "")
                + (md == 0 ? "It's market day!" : "Market day's in " + md + (md == 1 ? " day." : " days."))
                + " Crouch and right-click it to set your prices.";
        };
    }

    // ------------------------------------------------------------------ /village stall

    /** /village stall: the stalls here; rent; screen; till; books; price &lt;coins&gt; &lt;item&gt;; market and lapse (ops). */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("stall")
            .executes(PlayerStalls::cmdList)
            .then(Commands.literal("rent").executes(PlayerStalls::cmdRent))
            .then(Commands.literal("screen").executes(PlayerStalls::cmdScreen))
            .then(Commands.literal("till").executes(PlayerStalls::cmdTill))
            .then(Commands.literal("books").executes(PlayerStalls::cmdBooks))
            .then(Commands.literal("price")
                .then(Commands.argument("coins", IntegerArgumentType.integer(-1, 9999))
                    .then(Commands.argument("item", StringArgumentType.greedyString())
                        .executes(PlayerStalls::cmdPrice))))
            .then(Commands.literal("market").requires(src -> src.hasPermission(2)).executes(PlayerStalls::cmdMarket))
            .then(Commands.literal("lapse").requires(src -> src.hasPermission(2)).executes(PlayerStalls::cmdLapse));
    }

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, at, Villages.VILLAGE_RANGE);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village near enough."));
        return v;
    }

    private static int cmdList(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        long day = today(level);
        StringBuilder sb = new StringBuilder("Stalls in " + Villages.name(v.id()) + " (rent " + rent(v.id()) + "c a week):");
        for (Booth b : booths(v.id())) {
            Stall st = tenantOf(v.id(), b);
            sb.append("\nBOOTH ").append(b.at().getX()).append(' ').append(b.at().getY()).append(' ').append(b.at().getZ())
                .append(" facing ").append(b.front().getName()).append(" — ");
            if (st == null) { sb.append("to let"); continue; }
            roll(st, day);
            int n7 = 0, c7 = 0;
            for (int i = 0; i < WEEK; i++) { n7 += st.weekCount[i]; c7 += st.weekCoin[i]; }
            sb.append(st.name).append("'s, ").append(st.state(day).name().toLowerCase(Locale.ROOT)).append(", paid to day ").append(st.paidTill)
                .append(", till ").append(st.till).append(", sold ").append(n7).append(" this week for ").append(c7).append("c, ")
                .append(st.takenAll).append("c in all");
            if (level.getBlockEntity(b.at()) instanceof Container box) {
                List<String> seen = new ArrayList<>();
                for (int i = 0; i < box.getContainerSize(); i++) {
                    ItemStack it = box.getItem(i);
                    if (it.isEmpty() || Market.isCoin(it) || seen.contains(key(it))) continue;
                    seen.add(key(it));
                    sb.append("; ").append(count(box, it)).append(' ').append(nameOf(it).toLowerCase(Locale.ROOT)).append(" at ")
                        .append(asking(level, v.id(), st, key(it), it)).append("c a ").append(lot(it)).append(" (going ")
                        .append(going(level, v.id(), it)).append("c)");
                }
            }
            for (int i = 0; i < Math.min(3, st.sales.size()); i++) {
                Sale s = st.sales.get(i);
                sb.append("; sold ").append(s.count()).append(' ').append(nameOf(sampleOf(s.key())).toLowerCase(Locale.ROOT)).append(" to ")
                    .append(s.who()).append(" for ").append(s.price()).append("c on day ").append(s.day());
            }
        }
        if (booths(v.id()).isEmpty()) sb.append(" none yet.");
        String out = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    private static int cmdRent(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ServerLevel level = p.serverLevel();
        String said = rent(level, v, p, null);
        Stall st = load(v.id(), p.getUUID());
        Booth b = st == null ? null : boothOf(v.id(), st);
        String head = b == null ? "NO-STALL " : "STALL " + b.at().getX() + " " + b.at().getY() + " " + b.at().getZ() + " facing " + b.front().getName() + " ";
        ctx.getSource().sendSuccess(() -> Component.literal(head + said), false);
        return b == null ? 0 : 1;
    }

    private static int cmdScreen(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        Stall st = load(v.id(), p.getUUID());
        Booth b = st == null ? null : boothOf(v.id(), st);
        if (b == null) b = freeBooth(p.serverLevel(), v.id());
        if (b == null) {
            ctx.getSource().sendFailure(Component.literal("No stall of yours here, and none to let."));
            return 0;
        }
        open(p, p.serverLevel(), v, b);
        return 1;
    }

    private static int cmdTill(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        int n = payTill(p.serverLevel(), v.id(), p);
        ctx.getSource().sendSuccess(() -> Component.literal(n > 0 ? "You take " + n + coinWord(n) + " from your stall's till." : "Your till's empty."), false);
        return n;
    }

    private static int cmdBooks(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        CompoundTag data = Annals.snapshot(p.serverLevel(), v);
        data.putInt("tab", 4);
        data.putString("shopSeller", "players");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(data));
        return 1;
    }

    private static int cmdPrice(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        Stall st = load(v.id(), p.getUUID());
        if (st == null || st.at == null) {
            ctx.getSource().sendFailure(Component.literal("You've no stall here."));
            return 0;
        }
        String words = StringArgumentType.getString(ctx, "item").trim().toLowerCase(Locale.ROOT);
        Item it = null;
        ResourceLocation rl = ResourceLocation.tryParse(words.contains(":") ? words : "minecraft:" + words.replace(' ', '_'));
        if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) it = BuiltInRegistries.ITEM.get(rl);
        if (it == null || it == Items.AIR) it = Services.itemNamed(words);
        if (it == null || it == Items.AIR) {
            ctx.getSource().sendFailure(Component.literal("What's \"" + words + "\"?"));
            return 0;
        }
        int coins = IntegerArgumentType.getInteger(ctx, "coins");
        CompoundTag one = new CompoundTag();
        one.putInt(key(new ItemStack(it)), coins);
        setPrices(p.serverLevel(), v, st, one);
        ItemStack s = new ItemStack(it);
        int going = going(p.serverLevel(), v.id(), s);
        String said = coins < 0 ? nameOf(s) + " at the going price, " + going + "c a " + lot(s) + "."
            : coins == 0 ? nameOf(s) + " kept back." : nameOf(s) + ": " + coins + "c a " + lot(s) + " (going " + going + "c).";
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return 1;
    }

    private static int cmdMarket(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        long day = today(level);
        List<String> done = new ArrayList<>();
        int coin = 0;
        for (Stall st : all(v.id())) {
            if (st.state(day) != State.OPEN) continue;
            Booth b = boothOf(v.id(), st);
            if (b == null) continue;
            // The nearest folk with coin come to the stall, stand round its front and look it over.
            List<VillageFolkEntity> buyers = new ArrayList<>();
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (a instanceof VillageFolkEntity f && !f.isBaby() && !f.isSleeping() && f.purse() > 0) buyers.add(f);
            }
            buyers.sort(java.util.Comparator.comparingDouble(f -> f.blockPosition().distSqr(b.at())));
            Direction across = b.front().getClockWise();
            int k = 0;
            for (VillageFolkEntity f : buyers) {
                if (k >= 5) break;
                BlockPos stand = b.stand().relative(across, k - 2);
                f.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
                f.getNavigation().stop();
                f.getLookControl().setLookAt(b.at().getX() + 0.5, b.at().getY() + 1.0, b.at().getZ() + 0.5);
                int before = f.purse();
                String got = browse(level, v, f, st.owner, true);
                coin += before - f.purse();
                done.add(f.displayNameCap() + (got == null ? " bought nothing" : " bought " + got));
                k++;
            }
        }
        String out = "Market day at the stalls: " + coin + " coins taken. " + String.join("; ", done);
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return coin;
    }

    private static int cmdLapse(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ServerLevel level = p.serverLevel();
        Stall st = load(v.id(), p.getUUID());
        if (st == null) return 0;
        long day = today(level);
        st.paidTill = day - GRACE - 1;
        st.dueTold = day;
        save(v.id(), st);
        DAILY.remove(v.id());
        morning(level, v, day);
        Stall now = load(v.id(), p.getUUID());
        String out = "The rent ran out " + (GRACE + 1) + " days ago: " + (now == null ? "gone" : now.state(day).name().toLowerCase(Locale.ROOT)) + ".";
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    // ------------------------------------------------------------------ small things

    /** Tests: whose stall and where. */
    @Nullable
    public static Booth boothOf(UUID village, UUID owner) {
        Stall st = load(village, owner);
        return st == null ? null : boothOf(village, st);
    }

    /** Tests and the books: the coin in a player's till here. */
    public static int till(UUID village, UUID owner) {
        Stall st = load(village, owner);
        return st == null ? 0 : st.till;
    }

    /** Tests: how a player's stall stands today. */
    public static State stateOf(ServerLevel level, UUID village, UUID owner) {
        Stall st = load(village, owner);
        return st == null ? State.NONE : st.state(today(level));
    }

    /** Tests: the stall's rent runs out so many days before today. */
    public static void lapseForTests(ServerLevel level, Villages.Village v, UUID owner, int daysAgo) {
        Stall st = load(v.id(), owner);
        if (st == null) return;
        st.paidTill = today(level) - daysAgo;
        save(v.id(), st);
        DAILY.remove(v.id());
        morning(level, v, today(level));
    }

    /** Tests: how many folk thought something at this stall too dear. */
    public static int turnedDown(UUID village, UUID owner) {
        Stall st = load(village, owner);
        return st == null ? 0 : st.dear.size();
    }

    /** Tests: a price set, as the screen sets it (0 keeps it back, under 0 the going price). */
    public static void priceForTests(ServerLevel level, Villages.Village v, UUID owner, ItemStack ware, int price) {
        Stall st = load(v.id(), owner);
        if (st == null) return;
        CompoundTag one = new CompoundTag();
        one.putInt(key(ware), price);
        setPrices(level, v, st, one);
    }

    static void roll(Stall st, long today) {
        if (st.weekDay < 0 || today < st.weekDay) {
            java.util.Arrays.fill(st.weekCount, 0);
            java.util.Arrays.fill(st.weekCoin, 0);
            st.weekDay = today;
            return;
        }
        int days = (int) Math.min(WEEK, today - st.weekDay);
        if (days <= 0) return;
        for (int[] a : new int[][]{ st.weekCount, st.weekCoin }) {
            for (int i = WEEK - 1; i >= 0; i--) a[i] = i - days >= 0 ? a[i - days] : 0;
        }
        st.weekDay = today;
    }

    private static void tellOwner(ServerLevel level, UUID owner, String text) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
        if (p != null) p.displayClientMessage(Component.literal("[Stall] " + text), false);
    }

    static long today(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    /** "Bread", "Iron Pickaxe": what the market calls a lot of it, else the thing's own name. */
    static String nameOf(ItemStack s) {
        if (s.isEmpty()) return "";
        Market.Good g = Market.goodFor(s.copyWithCount(1));
        return g != null && g.bundle() > 1 ? g.name() : s.getHoverName().getString();
    }

    /** "8 bread", "an iron pickaxe": so many of a thing, in a sentence. */
    static String thing(ItemStack s, int n) {
        String name = nameOf(s).toLowerCase(Locale.ROOT);
        if (n > 1 || lot(s) > 1) return (n > 1 ? n + " " : "") + name;
        return ("aeiou".indexOf(name.isEmpty() ? 'x' : name.charAt(0)) >= 0 ? "an " : "a ") + name;
    }

    private static final String[] ONES = { "nothing", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen" };
    private static final String[] TENS = { "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety" };

    /** A sum of coin as a folk says it: "thirty", "forty-five", and figures past ninety-nine. */
    static String words(int n) {
        if (n < 0 || n > 99) return Integer.toString(n);
        if (n < 20) return ONES[n];
        return TENS[n / 10] + (n % 10 == 0 ? "" : "-" + ONES[n % 10]);
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String coinWord(int n) {
        return n == 1 ? " coin" : " coins";
    }

    private static String clip(String s) {
        return s.length() > 15 ? s.substring(0, 15) : s;
    }

    private static String clean(String s) {
        return s.replace(';', ',').replace('|', '/').replace('~', '-');
    }

    @Nullable
    private static BlockPos pos(String s) {
        String[] p = s.split(",");
        if (p.length < 3) return null;
        return new BlockPos((int) num(p[0]), (int) num(p[1]), (int) num(p[2]));
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String csv(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    private static void ints(String s, int[] into) {
        String[] p = s.split(",");
        for (int i = 0; i < into.length && i < p.length; i++) into[i] = (int) num(p[i]);
    }
}
