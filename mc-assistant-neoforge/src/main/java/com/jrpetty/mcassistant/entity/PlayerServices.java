package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Services for players [batch: services for players]: six things a town does for the players
 * who come and go in it, each out of what the town really has.
 * <ul>
 * <li><b>Mending at the forge.</b> The smith (or, in a town with no smith, a smelter at its forge)
 *     mends a worn tool, weapon or piece of armour: a unit of its own metal out of the stores for
 *     every quarter of wear, as an anvil takes it, charged at the market's price for the metal and
 *     a fee for the work. No metal in the stores, and nothing to mend it with brought along: no
 *     mending.</li>
 * <li><b>A map of the town.</b> A citizen, or an honoured guest, may ask the storekeeper or the
 *     elder for a map: a real map, drawn on a sheet of the stores' paper, centred on the heart and
 *     filled in from the town as it stands. One a day.</li>
 * <li><b>The towns of the world, side by side</b> (/village top): every village, by its folk, with
 *     its age, what it is worth (its treasury and its stores at the market's prices) and its
 *     renown.</li>
 * <li><b>A bounty on the night.</b> On a night the watch has been busy (three monsters killed by
 *     its folk, or the bell rung), the board posts a bounty: a coin or two out of the treasury for
 *     every monster a player kills in the town before dawn, up to the night's purse, and the
 *     town's thanks.</li>
 * <li><b>Lost and Found.</b> What a player drops in the streets and leaves lying is swept up by
 *     the street sweeper (Sweepers) into a chest by the storehouse, a real chest out of the stores
 *     named Lost and Found, and kept there for whoever dropped it for three days: open the chest,
 *     or ask the storekeeper, and it is handed back. What nobody comes for goes into the stores.</li>
 * <li><b>Milestones.</b> A town of twenty-five, of fifty, of a hundred; each new age; the first
 *     diamond in the stores: each goes into the chronicle as a milestone, and the town celebrates
 *     on the square, with a salute of the fireworks maker's rockets if the stores hold any (FireworkShows)
 *     and word to the players about.</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class PlayerServices {

    private PlayerServices() {}

    public static void resetForTests() {
        NIGHTS.clear();
        LOOKED.clear();
        LOST_KEPT.clear();
    }

    /** "1 coin", "5 coins". */
    private static String coins(int n) {
        return n + (n == 1 ? " coin" : " coins");
    }

    private static long day(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    /** A grown folk of the village at this trade, alive (the first found), or null. */
    @Nullable
    private static VillageFolkEntity tradesman(UUID village, StationTask trade) {
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && f.isAlive() && f.stationTask() == trade) return f;
        }
        return null;
    }

    private static void give(Player p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false);
    }

    // ============================================================ 1. mending at the forge

    /** The smith's work, on top of the metal: two coins a job. */
    public static final int REPAIR_FEE = 2;
    /** How near its furnace (or its post) a smelter has to be to call itself at its forge. */
    static final int FORGE_NEAR = 6;

    /** What a thing is mended with, named, when nobody has any to show: the first of these it takes. */
    private static final Item[] METALS = { Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.NETHERITE_INGOT,
        Items.LEATHER, Items.OAK_PLANKS, Items.COBBLESTONE, Items.STRING, Items.PHANTOM_MEMBRANE, Items.TURTLE_SCUTE,
        Items.ARMADILLO_SCUTE, Items.BREEZE_ROD };

    /**
     * Is this smelter at its forge? A furnace, a blast furnace, an anvil or a smithing table within a
     * few blocks of it, or its post. A smelter out on the road with its ore has no fire to heat the metal
     * at, and says so.
     */
    static boolean atForge(ServerLevel level, VillageFolkEntity f) {
        BlockPos post = f.stationPos();
        if (post != null && post.closerThan(f.blockPosition(), FORGE_NEAR + 2)) return true;
        BlockPos at = f.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-FORGE_NEAR, -2, -FORGE_NEAR), at.offset(FORGE_NEAR, 2, FORGE_NEAR))) {
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.FURNACE) || s.is(Blocks.BLAST_FURNACE) || s.is(Blocks.SMITHING_TABLE)
                    || s.is(net.minecraft.tags.BlockTags.ANVIL)) return true;
        }
        return false;
    }

    /** One of a thing at the market's price today (dear when the stores hold little); by the price list if the market does not deal in it. */
    static double marketPrice(ServerLevel level, UUID village, ItemStack one) {
        Market.Good g = Market.goodFor(one);
        if (g == null) return Prices.each(one.getItem());
        double each = PriceIndex.each(level, village, g);                // [econ-prices] the town's price today
        return Market.marketDay(village, day(level)) ? each * 0.9 : each;
    }

    /**
     * "Could you mend this?" — the worn thing in the player's hand. The metal comes out of the stores
     * (an iron ingot for every quarter of an iron sword's wear, a diamond for a diamond pick's, planks for
     * a wooden shovel), at the market's price, with the fee for the work on top; what the stores have not
     * got, the player may bring, and pays only the work on it. It used to be a scrap of metal and two-fifths
     * of the thing's price however worn it was, which mended a diamond chestplate for one diamond.
     */
    public static String repair(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "I've no forge to do it at.";
        Villages.Village v = Villages.get(village);
        if (v == null) return "I've no forge to do it at.";
        StationTask trade = f.stationTask();
        VillageFolkEntity smith = tradesman(village, StationTask.SMITH);
        if (trade != StationTask.SMITH) {
            if (smith != null) return "Mending's the smith's work. Ask " + smith.displayNameCap() + ".";
            if (trade != StationTask.SMELT) {
                VillageFolkEntity smelter = tradesman(village, StationTask.SMELT);
                return smelter != null ? "We've no smith — but " + smelter.displayNameCap() + " mends things at the forge. Ask them."
                    : "Mending's the smith's work, and we've no smith, nor a smelter with a forge.";
            }
            if (!atForge(level, f)) return "I can mend that at my forge — come and find me there, by the furnace.";
        }
        long day = day(level);
        Standing.Title title = Standing.of(village, p.getUUID(), level.getGameTime()).title();
        if (title == Standing.Title.OUTCAST || Laws.banished(village, p.getUUID(), day)) return "I'll not lift a hammer for you.";
        ItemStack held = p.getMainHandItem();
        if (held.isEmpty() || !held.isDamageableItem() || !held.isDamaged()) return "Hand me whatever wants mending — it looks fine to me.";
        Item tool = held.getItem();
        Predicate<ItemStack> metal = s -> !s.isEmpty() && s != held && !s.isDamageableItem() && tool.isValidRepairItem(held, s);
        // What it is mended with, by name: what the stores hold of it, else what the player has, else the list.
        ItemStack sample = ItemStack.EMPTY;
        for (BlockPos at : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(at) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize() && sample.isEmpty(); i++) if (metal.test(c.getItem(i))) sample = c.getItem(i).copyWithCount(1);
            if (!sample.isEmpty()) break;
        }
        for (int i = 0; i < p.getInventory().getContainerSize() && sample.isEmpty(); i++) {
            if (metal.test(p.getInventory().getItem(i))) sample = p.getInventory().getItem(i).copyWithCount(1);
        }
        for (Item m : METALS) {
            if (!sample.isEmpty()) break;
            ItemStack one = new ItemStack(m);
            if (tool.isValidRepairItem(held, one)) sample = one;
        }
        String thing = held.getHoverName().getString().toLowerCase(Locale.ROOT);
        if (sample.isEmpty()) return "There's no mending a " + thing + " at a forge — nothing I've got will patch it.";
        final Item made = sample.getItem();
        Predicate<ItemStack> same = s -> metal.test(s) && s.is(made);
        String word = Stockroom.nameOf(Stockroom.key(sample)).toLowerCase(Locale.ROOT);
        // A unit of the metal for every quarter of the wear, as an anvil takes it.
        int per = Math.max(1, held.getMaxDamage() / 4);
        int needed = (held.getDamageValue() + per - 1) / per;
        int inStores = Market.stock(level, village, same);
        int fromStores = Math.min(needed, inStores);
        int fromPlayer = Math.min(needed - fromStores, Services.carried(p, same));
        if (fromStores + fromPlayer <= 0) {
            return "We've no " + word + " in the stores to mend your " + thing + " with, I'm afraid. Bring some, or come back when we have.";
        }
        double each = marketPrice(level, village, sample);
        int metalPrice = (int) Math.ceil(each * fromStores - 1.0e-9);
        int price = Dealings.haggled(village, p.getUUID(), day, metalPrice + REPAIR_FEE);
        int have = Market.coinsHeld(p);
        String bill = fromStores > 0 ? " (" + Storekeeping.list(List.of(sample.copyWithCount(fromStores))) + " from the stores, and the work)" : " for the work";
        if (have < price) return "Mending that would be " + coins(price) + bill + ". You've " + have + ".";
        // The metal: out of the stores, then out of the player's pack.
        int got = 0;
        if (fromStores > 0) {
            for (ItemStack s : Services.take(level, village, same, fromStores)) {
                got += s.getCount();
                Economy.storesOut(village, s, s.getCount());
            }
            Budget.forget(village);
        }
        if (got < fromStores) {
            // Somebody took the last of it a moment ago: charge for what came.
            metalPrice = (int) Math.ceil(each * got - 1.0e-9);
            price = Dealings.haggled(village, p.getUUID(), day, metalPrice + REPAIR_FEE);
        }
        int brought = 0;
        if (fromPlayer > 0) for (ItemStack s : Services.takeFrom(p, same, fromPlayer)) brought += s.getCount();
        int units = got + brought;
        if (units <= 0) return "Somebody's just taken the last of the " + word + ". Come back later.";
        Market.payOut(p, price);
        Ledger.addCoins(village, price);
        Economy.sold(village, price);
        int before = held.getDamageValue();
        held.setDamageValue(Math.max(0, before - units * per));
        f.swing(InteractionHand.MAIN_HAND);
        BlockPos forge = f.stationPos() != null && f.stationPos().closerThan(f.blockPosition(), 12) ? f.stationPos() : f.blockPosition();
        level.playSound(null, forge, SoundEvents.ANVIL_USE, SoundSource.NEUTRAL, 0.6F, 1.0F);
        f.persona().remember(day, "I mended " + p.getName().getString() + "'s " + thing, 1);
        String used = Storekeeping.list(List.of(sample.copyWithCount(units)));
        if (held.getDamageValue() > 0) {
            return "That's as far as the " + word + " would go — " + used + " into it, and it's better than it was. "
                + coins(price) + ", thank you.";
        }
        return "There — good as new. " + capFirst(used) + (brought > 0 && got > 0 ? ", some of it yours," : brought > 0 ? " of your own" : "")
            + " and the work: " + coins(price) + ", thank you.";
    }

    private static String capFirst(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ============================================================ 2. a map of the town

    /** "Could I have a map of the town?" — to the storekeeper, or the elder. */
    public static String townMap(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "A map of what? There's no town here.";
        Villages.Village v = Villages.get(village);
        if (v == null) return "A map of what? There's no town here.";
        VillageFolkEntity keeper = Services.keeper(village);
        if (f.stationTask() != StationTask.STORE && !f.isElder()) {
            return keeper == null ? "Maps are the storekeeper's to give, and we've none yet."
                : "Maps are " + keeper.displayNameCap() + "'s to give — " + (keeper.isElder() ? "ask the elder." : "ask at the stores.");
        }
        long day = day(level);
        UUID who = p.getUUID();
        Standing.Title title = Standing.of(village, who, level.getGameTime()).title();
        if (Laws.banished(village, who, day) || title == Standing.Title.OUTCAST) return "A map? So you can find your way back? No.";
        if (!Citizens.is(village, who) && !title.atLeast(Standing.Title.HONOURED)) {
            return "The town's maps are for its citizens and its honoured guests. Settle here, or earn your welcome, and you'll have one.";
        }
        String key = "map/" + who;
        String last = Ledger.note(village, key);
        if (last != null && last.equals(Long.toString(day))) return "You've had your map today. Tomorrow, if you need another.";
        if (!Crafts.take(level, v, s -> s.is(Items.PAPER), 1)) {
            return "I'd draw you one gladly, but there's not a sheet of paper in the stores.";
        }
        ItemStack map = drawTownMap(level, v, f.displayNameCap(), day);
        Ledger.note(village, key, Long.toString(day));
        give(p, map);
        f.persona().remember(day, "I drew " + p.getName().getString() + " a map of the town", 1);
        return "Here — the town as it stands today, drawn on a sheet from the stores, with the heart in the middle. "
            + "Mind you don't lose it.";
    }

    /** The scale a map of the town is drawn at: the smallest that holds every street of it. */
    static byte mapScale(UUID village) {
        int reach = Villages.townReach(village);
        byte scale = 0;
        while (scale < 2 && (64 << scale) < reach + 8) scale++;
        return scale;
    }

    /**
     * A map of the town, filled in: centred on the heart exactly (a map off the table snaps to the
     * world's grid of maps, which put one town's heart in a corner), at the scale that holds its
     * streets, coloured from the ground as it stands wherever the ground is loaded, the heart marked.
     */
    public static ItemStack drawTownMap(ServerLevel level, Villages.Village v, String by, long day) {
        int cx = v.centre().getX(), cz = v.centre().getZ();
        byte scale = mapScale(v.id());
        CompoundTag tag = new CompoundTag();
        tag.putString("dimension", level.dimension().location().toString());
        tag.putInt("xCenter", cx);
        tag.putInt("zCenter", cz);
        tag.putByte("scale", scale);
        tag.putBoolean("trackingPosition", true);
        tag.putBoolean("unlimitedTracking", false);
        tag.putBoolean("locked", false);
        tag.putByteArray("colors", paint(level, cx, cz, scale));
        tag.put("banners", new ListTag());
        tag.put("frames", new ListTag());
        MapItemSavedData data = MapItemSavedData.load(tag, level.registryAccess());
        MapId id = level.getFreeMapId();
        level.setMapData(id, data);
        ItemStack map = new ItemStack(Items.FILLED_MAP);
        map.set(DataComponents.MAP_ID, id);
        map.set(DataComponents.ITEM_NAME, Component.literal("Map of " + Villages.name(v.id())));
        map.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Drawn by " + by + ", day " + (day + 1))
            .withStyle(ChatFormatting.GRAY))));
        MapItemSavedData.addTargetDecoration(map, v.centre(), "heart", MapDecorationTypes.PLAINS_VILLAGE);
        return map;
    }

    /**
     * The map's colours, the way a map in a player's hand fills itself in (MapItem.update): the top
     * of each column, shaded by whether the ground rises or falls from the one north of it, water by
     * its depth. One column a pixel; only ground that is loaded (a map is never what makes the world
     * generate), the rest left blank for the player to fill in by walking.
     */
    static byte[] paint(ServerLevel level, int cx, int cz, int scale) {
        byte[] colors = new byte[128 * 128];
        int step = 1 << scale;
        int min = level.getMinBuildHeight();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int px = 0; px < 128; px++) {
            double above = Double.NaN;
            for (int pz = -1; pz < 128; pz++) {
                int x = cx + (px - 64) * step, z = cz + (pz - 64) * step;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) {
                    above = Double.NaN;
                    continue;
                }
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15) + 1;
                BlockState state = Blocks.AIR.defaultBlockState();
                MapColor colour = MapColor.NONE;
                if (y > min + 1) {
                    do {
                        y--;
                        state = chunk.getBlockState(at.set(x, y, z));
                        colour = state.getMapColor(level, at);
                    } while (colour == MapColor.NONE && y > min);
                }
                int depth = 0;
                if (!state.getFluidState().isEmpty()) {
                    int wy = y - 1;
                    BlockState below;
                    do {
                        below = chunk.getBlockState(at.set(x, wy--, z));
                        depth++;
                    } while (wy > min && !below.getFluidState().isEmpty());
                }
                if (pz >= 0 && colour != MapColor.NONE) {
                    MapColor.Brightness b;
                    if (colour == MapColor.WATER) {
                        double d = depth * 0.1 + ((px + pz) & 1) * 0.2;
                        b = d < 0.5 ? MapColor.Brightness.HIGH : d > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                    } else {
                        double d = (Double.isNaN(above) ? 0.0 : (y - above) * 4.0 / (step + 4)) + (((px + pz) & 1) - 0.5) * 0.4;
                        b = d > 0.6 ? MapColor.Brightness.HIGH : d < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                    }
                    colors[px + pz * 128] = colour.getPackedId(b);
                }
                above = y;
            }
        }
        return colors;
    }

    // ============================================================ 3. /village top

    /** One village on the leaderboard. */
    public record Row(UUID id, String name, int folk, Villages.Age age, long days, int wealth, int treasury, int renown) {}

    /** What the stores of a village are worth at the market's prices: counted now if its heart is loaded, else as last counted (each morning). */
    static int storesWorth(@Nullable ServerLevel level, Villages.Village v) {
        if (level != null && level.isLoaded(v.centre())) {
            double sum = 0;
            for (BlockPos at : Villages.storeChests(level, v.id())) {
                if (!(level.getBlockEntity(at) instanceof Container c)) continue;
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack s = c.getItem(i);
                    if (!s.isEmpty()) sum += Economy.worthOf(s);
                }
            }
            return (int) Math.round(sum);
        }
        String saved = Ledger.note(v.id(), "worth.stores");
        try {
            return saved == null ? 0 : Integer.parseInt(saved);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Every village in the world, the biggest first (and the richest first among towns the same size). */
    public static List<Row> table(MinecraftServer server) {
        List<Row> rows = new ArrayList<>();
        for (Villages.Village v : Villages.every()) {
            ServerLevel level = server.getLevel(v.dim());
            long today = level == null ? 0 : day(level);
            long founded = Chronicle.foundedOn(v.id());
            int treasury = Ledger.coins(v.id());
            rows.add(new Row(v.id(), Villages.name(v.id()), Villages.headcount(v.id()), Villages.ageOf(v.id()),
                founded < 0 ? -1 : Math.max(0, today - founded), treasury + storesWorth(level, v), treasury, Villages.renown(v.id())));
        }
        rows.sort(java.util.Comparator.comparingInt(Row::folk).reversed()
            .thenComparing(java.util.Comparator.comparingInt(Row::wealth).reversed())
            .thenComparing(Row::name));
        return rows;
    }

    /** The leaderboard in lines: a heading, then one line a village. */
    public static List<String> leaderboard(MinecraftServer server) {
        List<Row> rows = table(server);
        List<String> out = new ArrayList<>();
        if (rows.isEmpty()) {
            out.add("No villages in the world yet.");
            return out;
        }
        out.add("The villages of the world, by their folk (" + rows.size() + "):");
        int n = 0;
        for (Row r : rows) {
            n++;
            out.add(n + ". " + r.name() + " — " + r.folk() + (r.folk() == 1 ? " soul" : " folk") + ", " + r.age().label
                + (r.days() >= 0 ? " (" + (r.days() == 0 ? "founded today" : r.days() + (r.days() == 1 ? " day" : " days") + " old") + ")" : "")
                + ", worth " + coins(r.wealth()) + " (treasury " + r.treasury() + "), renown " + r.renown() + ".");
        }
        return out;
    }

    /** /village top. */
    public static LiteralArgumentBuilder<CommandSourceStack> topCommand() {
        return Commands.literal("top").executes(ctx -> {
            List<String> lines = leaderboard(ctx.getSource().getServer());
            for (String l : lines) ctx.getSource().sendSuccess(() -> Component.literal(l), false);
            return Math.max(0, lines.size() - 1);
        });
    }

    // ============================================================ 4. a bounty on the night

    /** Monsters the watch kills in a night before it counts as a busy one. */
    public static final int BUSY = 3;
    /** The most the treasury pays out in bounties in a night. */
    public static final int NIGHT_PURSE = 12;

    /** A village's night: what its watch has had to do, whether the board has a bounty up, what it has paid and to whom. */
    static final class Night {
        final long day;
        int watch;
        boolean bell, posted, spent, told;
        int paid, kills;
        final Map<UUID, Integer> paidTo = new LinkedHashMap<>();
        final Map<UUID, String> names = new LinkedHashMap<>();

        Night(long day) {
            this.day = day;
        }
    }

    private static final Map<UUID, Night> NIGHTS = new ConcurrentHashMap<>();

    /** Is it night (from dusk, when the gates shut, to before dawn)? */
    static boolean night(ServerLevel level) {
        long t = level.getDayTime() % 24000L;
        return t >= 13000L && t < 23000L;
    }

    private static Night nightOf(ServerLevel level, UUID village) {
        long day = day(level);
        Night n = NIGHTS.get(village);
        if (n == null || n.day != day) {
            if (n != null && n.posted && !n.told) summary(village, n);
            n = new Night(day);
            NIGHTS.put(village, n);
        }
        return n;
    }

    /** The town this spot is in (within its reach), if any. */
    @Nullable
    static Villages.Village townAt(ServerLevel level, BlockPos p) {
        Villages.Village best = null;
        double bestD = Double.MAX_VALUE;
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            BlockPos c = v.centre();
            int reach = Villages.townReach(v.id());
            if (Math.max(Math.abs(p.getX() - c.getX()), Math.abs(p.getZ() - c.getZ())) > reach || Math.abs(p.getY() - c.getY()) > 48) continue;
            double d = c.distSqr(p);
            if (d < bestD) {
                bestD = d;
                best = v;
            }
        }
        return best;
    }

    /** A coin a monster, two for the dangerous ones. */
    static int bountyOn(LivingEntity m) {
        if (m instanceof net.minecraft.world.entity.monster.Creeper || m instanceof net.minecraft.world.entity.monster.Witch
                || m instanceof net.minecraft.world.entity.monster.EnderMan || m instanceof net.minecraft.world.entity.monster.AbstractIllager
                || m instanceof net.minecraft.world.entity.monster.Ravager || m instanceof net.minecraft.world.entity.monster.Blaze
                || m.getMaxHealth() >= 30.0F) return 2;
        return 1;
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (!(dead.level() instanceof ServerLevel level) || !(dead instanceof Enemy) || !night(level)) return;
        Entity killer = event.getSource().getEntity();
        if (!(killer instanceof Player) && !(killer instanceof VillageFolkEntity)) return;
        Guard.run("bounties", () -> killed(level, killer, dead));
    }

    /** A monster killed in the night: by the watch (a folk of the town), or by a player (the bounty). */
    static void killed(ServerLevel level, Entity killer, LivingEntity dead) {
        Villages.Village v = townAt(level, dead.blockPosition());
        if (v == null) return;
        Night n = nightOf(level, v.id());
        n.kills++;
        if (killer instanceof VillageFolkEntity f) {
            if (!v.id().equals(f.ownerId())) return;
            n.watch++;
            if (n.watch >= BUSY) post(level, v, n, "the watch has killed " + n.watch + " monsters already");
        } else if (killer instanceof Player p && !p.isSpectator()) {
            bounty(level, v, n, p, dead);
        }
    }

    /** The board posts the night's bounty (once a night, and only with coin in the treasury to pay it). */
    static void post(ServerLevel level, Villages.Village v, Night n, String why) {
        if (n.posted || Ledger.coins(v.id()) <= 0) return;
        n.posted = true;
        String name = Villages.name(v.id());
        Raids.tellNear(level, v.centre(), Villages.townReach(v.id()) + 48, Component.literal("A busy night in " + name + " (" + why
            + "): the board posts a bounty — a coin or two for every monster killed in the town before dawn.")
            .withStyle(ChatFormatting.GOLD), false);
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity g && g.stationTask() == StationTask.GUARD && !g.isSleeping()) {
                FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "There's a bounty up tonight — any help's welcome!",
                    "Busy night. The board's paying for every one you bring down.", "Coin for every monster, the board says. Come on, then!"));
                break;
            }
        }
    }

    /** A player's kill under the night's bounty: paid out of the treasury (the night's purse allowing), and thanked. Returns the coin paid. */
    static int bounty(ServerLevel level, Villages.Village v, Night n, Player p, LivingEntity dead) {
        if (!n.posted) return 0;
        UUID id = v.id();
        long day = day(level);
        Standing.Title title = Standing.of(id, p.getUUID(), level.getGameTime()).title();
        if (title == Standing.Title.OUTCAST || Laws.banished(id, p.getUUID(), day)) return 0;
        int want = Math.min(bountyOn(dead), NIGHT_PURSE - n.paid);
        if (want <= 0) {
            if (!n.spent) {
                n.spent = true;
                p.sendSystemMessage(Component.literal("Tonight's bounty in " + Villages.name(id) + " is all paid out — but the watch thanks you all the same.")
                    .withStyle(ChatFormatting.YELLOW));
            }
            return 0;
        }
        int pay = Ledger.takeCoins(id, want);
        if (pay <= 0) return 0;
        Economy.spent(id, pay);
        give(p, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), pay));
        n.paid += pay;
        n.paidTo.merge(p.getUUID(), pay, Integer::sum);
        String name = p.getName().getString();
        n.names.put(p.getUUID(), name);
        String what = Quests.kind(dead);
        what = what.endsWith("s") ? what.substring(0, what.length() - 1) : what;
        p.sendSystemMessage(Component.literal("Bounty: " + coins(pay) + " from the treasury of " + Villages.name(id) + " for the " + what
            + ". Thank you! (" + n.paid + " of tonight's " + NIGHT_PURSE + " paid)").withStyle(ChatFormatting.YELLOW));
        // Whoever of the town is nearest says thank you, and thinks the better of you for it.
        VillageFolkEntity near = null;
        double best = 24.0 * 24.0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isSleeping() || f.isBaby()) continue;
            double d = f.distanceToSqr(dead);
            if (d < best) {
                best = d;
                near = f;
            }
        }
        if (near != null) {
            near.persona().feelFor(p.getUUID(), name, 1);
            FolkTalk.speak(near, FolkTalk.pick(near.getRandom(), "Thank you, " + name + "!", "Good riddance to it. Thanks!",
                "That's one fewer at the gates. Well done!"));
        }
        Standing.stir(id, p.getUUID());
        return pay;
    }

    /** In the morning: who earned the night's bounty, into the town's history. */
    private static void summary(UUID village, Night n) {
        n.told = true;
        if (n.paid <= 0) return;
        Villages.tell(village, n.day, String.join(" and ", n.names.values()) + " earned the watch's bounty in the night: "
            + coins(n.paid) + " out of the treasury");
    }

    /** The watch's look at the night (every five seconds): the bell rung is a busy night; the morning closes the books. */
    static void watchTick(ServerLevel level, Villages.Village v) {
        Night n = NIGHTS.get(v.id());
        if (night(level)) {
            n = nightOf(level, v.id());
            if (Raids.underAlarm(v.id())) {
                n.bell = true;
                post(level, v, n, "the bell has rung");
            }
        } else if (n != null && n.posted && !n.told) {
            summary(v.id(), n);
        }
    }

    /** For the village board: the night's bounty, if one is up. */
    @Nullable
    public static String boardLine(ServerLevel level, UUID village) {
        Night n = NIGHTS.get(village);
        if (n == null || !n.posted || !night(level) || n.day != day(level)) return null;
        if (n.paid >= NIGHT_PURSE) return "Tonight's bounty is all paid out. Thank you, everybody!";
        return "BOUNTY tonight: a coin or two for every monster killed in the town before dawn (" + n.paid + " of " + NIGHT_PURSE + " coins paid).";
    }

    /** What a folk says of the bounty, after the quest board: "" if there is none tonight. */
    public static String bountyTalk(VillageFolkEntity f) {
        if (f.ownerId() == null || !(f.level() instanceof ServerLevel level)) return "";
        String line = boardLine(level, f.ownerId());
        return line == null ? "" : " And there's a bounty up tonight: a coin or two for every monster you kill in the town before dawn.";
    }

    /** Is there a bounty up in this village tonight? */
    public static boolean bountyPosted(ServerLevel level, UUID village) {
        Night n = NIGHTS.get(village);
        return n != null && n.posted && n.day == day(level) && night(level);
    }

    /** Tests: what the night has paid out so far. */
    public static int bountyPaidForTests(UUID village) {
        Night n = NIGHTS.get(village);
        return n == null ? 0 : n.paid;
    }

    // ============================================================ 5. lost and found

    public static final String LOST_AND_FOUND = "Lost and Found";
    /** A player's drop lies this long before the sweeper takes it in (an item lasts five minutes): two minutes. */
    public static final int LOST_AFTER = 2400;
    /** With its owner this near, a drop is left: they may be coming back for it. */
    static final int OWNER_NEAR = 24;
    /** How many days the Lost and Found keeps a thing for whoever dropped it. */
    public static final int KEEP_DAYS = 3;
    /** On an item a player dropped where no thrower is written (what fell when they died, a full pack spilling over): whose it is. */
    public static final String OWNER_TAG = "mca_owner:";
    /** On a thing in the Lost and Found: whose, since when, and their name. */
    static final String BY = "mca_lost_by", SINCE = "mca_lost_day", WHO = "mca_lost_name";

    /** Whose drop this is: the player it is set aside for, the one written on it, or its thrower. Null if nobody's. */
    @Nullable
    public static UUID ownerOf(ItemEntity e) {
        if (e.getTarget() != null) return e.getTarget();
        for (String t : e.getTags()) {
            if (!t.startsWith(OWNER_TAG)) continue;
            try {
                return UUID.fromString(t.substring(OWNER_TAG.length()));
            } catch (IllegalArgumentException ignored) {
                // not one of ours
            }
        }
        Entity owner = e.getOwner();
        if (owner != null) return owner instanceof Player ? owner.getUUID() : null;
        // A thrower nobody can see just now (gone home, say): it is still written on the item.
        CompoundTag t = e.saveWithoutId(new CompoundTag());
        return t.hasUUID("Thrower") ? t.getUUID("Thrower") : null;
    }

    /** The Lost and Found chest, where it stands, or null if the town has none standing. */
    @Nullable
    public static BlockPos lostAndFoundAt(ServerLevel level, UUID village) {
        String at = Ledger.note(village, "lnf.at");
        if (at == null || at.isEmpty()) return null;
        try {
            BlockPos p = BlockPos.of(Long.parseLong(at));
            if (!level.isLoaded(p)) return p;
            return level.getBlockEntity(p) instanceof ChestBlockEntity c && isLostAndFound(c) ? p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean isLostAndFound(ChestBlockEntity c) {
        Component n = c.getCustomName();
        return n != null && n.getString().equals(LOST_AND_FOUND);
    }

    /** Has the town a storehouse to keep the Lost and Found by? */
    static boolean storehouse(UUID village) {
        return Storehouses.stands(village) || Villages.hasBuilt(village, "storage");
    }

    /**
     * Will the town take in what a player left lying? Its Lost and Found has room; or there is none yet, but
     * there is a storehouse to set one by and a chest in the stores (or the planks to knock one together)
     * to set down. A town with neither leaves a player's things where they lie, as it always did.
     */
    public static boolean lostAndFoundOpen(ServerLevel level, Villages.Village v) {
        BlockPos at = lostAndFoundAt(level, v.id());
        if (at != null) {
            if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof Container c)) return false;
            for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).isEmpty()) return true;
            return false;
        }
        if (!storehouse(v.id())) return false;
        return Market.stock(level, v.id(), s -> s.is(Items.CHEST)) > 0 || Market.stock(level, v.id(), s -> s.is(ItemTags.PLANKS)) >= 8;
    }

    /** May the broom take this player's drop to the Lost and Found? Lying long enough, whose it is known, its owner not about. */
    static boolean forTheLostAndFound(ServerLevel level, ItemEntity e, boolean open) {
        if (!open || e.getAge() < LOST_AFTER) return false;
        UUID owner = ownerOf(e);
        if (owner == null) return false;
        Player p = level.getPlayerByUUID(owner);
        return p == null || !p.isAlive() || p.distanceToSqr(e) > OWNER_NEAR * OWNER_NEAR;
    }

    /** A swept-up drop, written with whose it is, the day and their name, for the Lost and Found. */
    static ItemStack markLost(ServerLevel level, ItemStack s, UUID owner) {
        Player p = level.getPlayerByUUID(owner);
        String name = p != null ? p.getName().getString() : "";
        if (name.isEmpty() && level.getServer().getProfileCache() != null) {
            name = level.getServer().getProfileCache().get(owner).map(com.mojang.authlib.GameProfile::getName).orElse("");
        }
        final String who = name;
        final long day = day(level);
        ItemStack out = s.copy();
        CustomData.update(DataComponents.CUSTOM_DATA, out, t -> {
            t.putUUID(BY, owner);
            t.putLong(SINCE, day);
            if (!who.isEmpty()) t.putString(WHO, who);
        });
        return out;
    }

    /** Whose a thing in the Lost and Found is, or null if it is not one. */
    @Nullable
    static UUID lostBy(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        if (d == null || !d.contains(BY)) return null;
        CompoundTag t = d.copyTag();
        return t.hasUUID(BY) ? t.getUUID(BY) : null;
    }

    /** A thing handed back (or into the stores): the Lost and Found's writing taken off it, so it stacks as it did. */
    static ItemStack unmark(ItemStack s) {
        ItemStack out = s.copy();
        CustomData.update(DataComponents.CUSTOM_DATA, out, t -> {
            t.remove(BY);
            t.remove(SINCE);
            t.remove(WHO);
        });
        return out;
    }

    /** Set down the Lost and Found by the storehouse, of a chest out of the stores (or eight planks knocked together). */
    @Nullable
    static BlockPos setUpLostAndFound(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity by) {
        BlockPos have = lostAndFoundAt(level, v.id());
        if (have != null) return level.isLoaded(have) ? have : null;
        if (!storehouse(v.id())) return null;
        BlockPos base = Storehouses.standingSpot(level, v.id());
        if (base == null) base = Villages.builtAt(v.id(), "storage");
        if (base == null) return null;
        BlockPos spot = null;
        for (int d = 2; d <= 6 && spot == null; d++) {
            for (int dy : new int[]{ 0, -1, 1 }) {
                for (int dx = -d; dx <= d && spot == null; dx++) {
                    for (int dz = -d; dz <= d && spot == null; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                        BlockPos p = base.offset(dx, dy, dz);
                        if (!level.isLoaded(p) || !Villages.inStoreArea(v.id(), p)) continue;
                        if (!level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()
                                || !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) continue;
                        boolean crowded = false;
                        for (Direction side : Direction.Plane.HORIZONTAL) {
                            BlockState n = level.getBlockState(p.relative(side));
                            if (n.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                                    || n.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) crowded = true;
                        }
                        if (!crowded) spot = p;
                    }
                }
                if (spot != null) break;
            }
        }
        if (spot == null) return null;
        // A real chest, out of the stores; failing one, eight of the stores' planks knocked together.
        if (!Crafts.take(level, v, s -> s.is(Items.CHEST), 1) && !Crafts.take(level, v, s -> s.is(ItemTags.PLANKS), 8)) return null;
        level.setBlock(spot, Blocks.CHEST.defaultBlockState(), 3);
        if (level.getBlockEntity(spot) instanceof ChestBlockEntity c) {
            c.applyComponents(DataComponentMap.builder().set(DataComponents.CUSTOM_NAME, Component.literal(LOST_AND_FOUND)).build(),
                DataComponentPatch.EMPTY);
            c.setChanged();
        }
        Ledger.note(v.id(), "lnf.at", Long.toString(spot.asLong()));
        Villages.tell(v.id(), day(level), (by == null ? "the storekeeper" : by.displayNameCap())
            + " set down a Lost and Found by the storehouse, for what players leave lying in the streets");
        return spot;
    }

    /**
     * What a sweeper swept up of players' things, into the Lost and Found (set down now if it is not
     * yet). What will not go in — the chest full, or none to be had — is put down again where the sweeper
     * stands, still its owner's, so nothing a player dropped is ever lost to the town's tidiness. Empties
     * {@code lost}; returns how many went in.
     */
    public static int intoLostAndFound(ServerLevel level, UUID village, @Nullable VillageFolkEntity by, List<ItemStack> lost) {
        if (lost.isEmpty()) return 0;
        Villages.Village v = Villages.get(village);
        BlockPos at = v == null ? null : setUpLostAndFound(level, v, by);
        Container box = at != null && level.getBlockEntity(at) instanceof Container c ? c : null;
        int in = 0;
        BlockPos drop = by != null ? by.blockPosition() : at != null ? at.above() : v != null ? v.centre() : null;
        for (ItemStack s : lost) {
            if (s.isEmpty()) continue;
            ItemStack left = box == null ? s.copy() : Stacking.insert(box, s.copy());
            in += s.getCount() - left.getCount();
            if (!left.isEmpty() && drop != null) {
                UUID owner = lostBy(left);
                ItemEntity e = new ItemEntity(level, drop.getX() + 0.5, drop.getY() + 0.2, drop.getZ() + 0.5, unmark(left), 0.0, 0.0, 0.0);
                e.addTag(Sweepers.PLAYERS);
                if (owner != null) e.addTag(OWNER_TAG + owner);
                level.addFreshEntity(e);
            }
        }
        lost.clear();
        if (box != null) box.setChanged();
        return in;
    }

    /** Everything of this player's in the town's Lost and Found, handed back (into their pack, or at their feet). */
    public static List<ItemStack> reclaim(ServerLevel level, UUID village, Player p) {
        List<ItemStack> back = new ArrayList<>();
        BlockPos at = lostAndFoundAt(level, village);
        if (at == null || !level.isLoaded(at) || !(level.getBlockEntity(at) instanceof Container c)) return back;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || !p.getUUID().equals(lostBy(s))) continue;
            ItemStack mine = unmark(s);
            c.setItem(i, ItemStack.EMPTY);
            back.add(mine.copy());
            give(p, mine);
        }
        c.setChanged();
        return back;
    }

    /** "Has anything of mine turned up?" — the storekeeper (or the elder, till there is one) looks in the Lost and Found. */
    public static String lostAndFound(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "Lost something? I wouldn't know where to look.";
        VillageFolkEntity keeper = Services.keeper(village);
        if (keeper != null && keeper != f) return "The Lost and Found is " + keeper.displayNameCap() + "'s to keep — ask them, or look in it yourself, by the storehouse.";
        if (lostAndFoundAt(level, village) == null) {
            return "We've no Lost and Found yet. " + (storehouse(village) ? "When the sweeper finds something of somebody's, we'll set one down by the storehouse."
                : "Once there's a storehouse, there'll be one by it.");
        }
        List<ItemStack> back = reclaim(level, village, p);
        if (back.isEmpty()) return "Nothing of yours in the Lost and Found, I'm afraid. What the sweeper finds of yours goes in it, and we keep it "
            + KEEP_DAYS + " days.";
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 1);
        return "Yes — here: " + Storekeeping.list(back) + ", swept up out of the street. Mind you hang on to it this time!";
    }

    /** Players' things a town is keeping, for the sweeper's page: "3 things for 2 players", or "". */
    public static String lostAndFoundLine(ServerLevel level, UUID village) {
        BlockPos at = lostAndFoundAt(level, village);
        if (at == null || !level.isLoaded(at) || !(level.getBlockEntity(at) instanceof Container c)) return "";
        int things = 0;
        Set<UUID> whose = new LinkedHashSet<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            UUID by = lostBy(c.getItem(i));
            if (by == null) continue;
            things += c.getItem(i).getCount();
            whose.add(by);
        }
        return "Lost and Found (" + at.getX() + ", " + at.getY() + ", " + at.getZ() + "): " + things + (things == 1 ? " thing" : " things")
            + " kept for " + whose.size() + (whose.size() == 1 ? " player" : " players") + ".";
    }

    private static final Map<UUID, Long> LOST_KEPT = new ConcurrentHashMap<>();

    /** What nobody came for in {@link #KEEP_DAYS} days goes into the stores (looked at once a day). Returns how many went. */
    static int expire(ServerLevel level, Villages.Village v, boolean now) {
        long day = day(level);
        if (!now && LOST_KEPT.getOrDefault(v.id(), -1L) == day) return 0;
        LOST_KEPT.put(v.id(), day);
        BlockPos at = lostAndFoundAt(level, v.id());
        if (at == null || !level.isLoaded(at) || !(level.getBlockEntity(at) instanceof Container c)) return 0;
        int moved = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            CustomData d = s.isEmpty() ? null : s.get(DataComponents.CUSTOM_DATA);
            if (d == null || !d.contains(BY) || day - d.copyTag().getLong(SINCE) < KEEP_DAYS) continue;
            ItemStack plain = unmark(s);
            int n = plain.getCount();
            ItemStack left = Market.intoStores(level, v.id(), plain);
            moved += n - left.getCount();
            if (left.isEmpty()) c.setItem(i, ItemStack.EMPTY);
            else s.setCount(left.getCount());
        }
        c.setChanged();
        if (moved > 0) Villages.tell(v.id(), day, "what nobody came for in the Lost and Found went into the stores");
        return moved;
    }

    /** Tests: the Lost and Found looked over now, as on a new day. */
    public static int expireForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        return v == null ? 0 : expire(level, v, true);
    }

    /** Opening the Lost and Found: whatever of yours is in it, handed back. Nobody rummages through anybody else's. */
    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest) || !isLostAndFound(chest)) return;
        Villages.Village v = Villages.nearest(level, pos, Villages.VILLAGE_RANGE);
        if (v == null || !pos.equals(lostAndFoundAt(level, v.id()))) return;
        Player p = e.getEntity();
        if (p.isCreative() && p.isShiftKeyDown() && p.hasPermissions(2)) return;      // an operator may look inside
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getHand() != InteractionHand.MAIN_HAND) return;
        Guard.run("lost and found", () -> {
            List<ItemStack> back = reclaim(level, v.id(), p);
            p.sendSystemMessage(Component.literal(back.isEmpty()
                ? "Nothing of yours in the " + LOST_AND_FOUND + ". (What the sweeper finds of yours is kept here " + KEEP_DAYS + " days.)"
                : "From the " + LOST_AND_FOUND + ": " + Storekeeping.list(back) + ". Yours again.").withStyle(ChatFormatting.YELLOW));
            if (!back.isEmpty()) level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5F, 1.0F);
        });
    }

    // ============================================================ 6. milestones

    /** The town sizes worth a celebration. */
    static final int[] FOLK_MILESTONES = { 25, 50, 100 };
    /** Rockets a milestone is celebrated with, if the stores have the makings. */
    static final int ROCKETS = 3;

    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();

    /** The milestones a village has passed (kept with the world). */
    public static Set<String> milestones(UUID village) {
        Set<String> out = new LinkedHashSet<>();
        String s = Ledger.note(village, "milestones");
        if (s != null && !s.isEmpty()) for (String k : s.split(",")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    private static void save(UUID village, Set<String> keys) {
        Ledger.note(village, "milestones", String.join(",", keys));
    }

    private static String number(int n) {
        return switch (n) {
            case 25 -> "twenty-five";
            case 50 -> "fifty";
            case 100 -> "a hundred";
            default -> Integer.toString(n);
        };
    }

    /**
     * Has the town passed a milestone? The first look at a town only notes where it stands (a town of sixty
     * the day this came in is not owed two celebrations at once); after that, each milestone it comes to is
     * written into its history and celebrated on the square. {@code folk} is the town's size (-1: its
     * headcount).
     */
    static List<String> look(ServerLevel level, Villages.Village v, int folk) {
        UUID id = v.id();
        if (folk < 0) folk = Villages.headcount(id);
        Set<String> had = milestones(id);
        boolean first = !had.contains("seen");
        int age = Villages.ageOf(id).ordinal();
        List<String> keys = new ArrayList<>(), words = new ArrayList<>();
        for (int m : FOLK_MILESTONES) {
            if (folk >= m && !had.contains("folk" + m)) {
                keys.add("folk" + m);
                words.add("the town counted " + number(m) + " folk");
            }
        }
        for (Villages.Age a : Villages.Age.values()) {
            if (a.ordinal() > age || a.ordinal() == 0 || had.contains("age" + a.ordinal())) continue;
            keys.add("age" + a.ordinal());
            words.add("the town came into " + a.label);
        }
        if (!had.contains("diamond") && Market.stock(level, id, s -> s.is(Items.DIAMOND)) > 0) {
            keys.add("diamond");
            words.add("the first diamond came into the stores");
        }
        if (first) {
            had.add("seen");
            had.addAll(keys);
            save(id, had);
            return List.of();
        }
        if (keys.isEmpty()) return List.of();
        had.addAll(keys);
        save(id, had);
        // Several at once (a jump of an age and a size the same minute): one celebration, all of them in it.
        long day = day(level);
        for (String w : words) Villages.tell(id, day, "a milestone: " + w);
        celebrate(level, v, words.get(words.size() - 1));
        return words;
    }

    /**
     * The town celebrates on the square: the fireworks maker's rockets out of the stores (a sparkle of flame
     * where there are none: FireworkShows.salute), the folk about cheering, and word to the players near.
     * Returns the rockets let off.
     */
    static int celebrate(ServerLevel level, Villages.Village v, String words) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        // [fireworks] A salute of the fireworks maker's rockets out of the stores (FireworkShows.salute); a sparkle of flame
        // for each the stores are short of.
        int rockets = FireworkShows.salute(level, v, heart, ROCKETS);
        for (int i = rockets; i < ROCKETS; i++) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME, heart.getX() + 0.5, heart.getY() + 0.3, heart.getZ() + 0.5,
                6, 0.3, 0.2, 0.3, 0.01);
        }
        long day = day(level);
        int cheered = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isSleeping() || f.blockPosition().distSqr(heart) > 48 * 48) continue;
            f.persona().remember(day, "we celebrated: " + words, 4);
            if (cheered++ < 4) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "To " + Villages.name(id) + "!", "Hooray!",
                "Would you look at us now!", "Who'd have thought it, when we started?"));
        }
        Raids.tellNear(level, heart, 128, Component.literal(Villages.name(id) + " celebrates: " + words + "!"
            + (rockets > 0 ? " Fireworks over the square." : "")).withStyle(ChatFormatting.GOLD), false);
        return rockets;
    }

    /** Tests: the milestones looked at now, the town counted as {@code folk} (-1: its headcount). Returns what was celebrated. */
    public static List<String> milestonesForTests(ServerLevel level, UUID village, int folk) {
        Villages.Village v = Villages.get(village);
        return v == null ? List.of() : look(level, v, folk);
    }

    // ============================================================ the town's look round

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 100 != 61) return;
        Guard.run("player services", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    watchTick(level, v);
                    long now = level.getGameTime();
                    long last = LOOKED.getOrDefault(v.id(), -100000L);
                    if (now - last < 600L && now >= last) continue;
                    LOOKED.put(v.id(), now);
                    look(level, v, -1);
                    expire(level, v, false);
                }
            }
        });
    }
}
