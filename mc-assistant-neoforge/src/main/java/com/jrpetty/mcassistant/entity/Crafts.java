package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The crafts of a grown village. Each works in a building of its own, out of the
 * village's stores and back into them, a piece of work at a time:
 * <ul>
 * <li><b>the blacksmith</b> (Iron Age, at the smithy) beats the stores' iron into what the
 *     village wears out — picks for the miners, blades and armour for the watch, shears,
 *     buckets, axes and hoes — and keeps a few of each in the stores;</li>
 * <li><b>the tailor</b> (Stone Age, at the workshop) turns the rancher's wool into beds for
 *     the houses, rugs and banners;</li>
 * <li><b>the beekeeper</b> (Stone Age, a meadow outside the town) keeps hives, plants
 *     flowers round them for the bees, and takes the honey and the comb;</li>
 * <li><b>the brewer</b> (Diamond Age, at the brewery) brews potions — healing for the watch,
 *     swiftness, night vision, leaping, water breathing — from what the stores hold;</li>
 * <li><b>the enchanter</b> (Nether Age, at the library) binds books from cane and leather
 *     and puts enchantments on the village's best tools and armour with lapis.</li>
 * </ul>
 */
public final class Crafts {

    private Crafts() {}

    /** A piece of work every so often (ticks). */
    static final int EVERY = 400;
    private static final Map<UUID, Integer> LAST = new ConcurrentHashMap<>();

    public static void resetForTests() { LAST.clear(); }

    /** One piece of the craft's work, if one is due. Returns whether it did any. */
    public static boolean work(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null) return false;
        int last = LAST.getOrDefault(f.getUUID(), -100000);
        // Quicker for a veteran, a cheerful hand and a happy village; slower without the craft's
        // own building and its tools (the anvil, the brewing stand, the enchanting table...).
        int every = EVERY * (100 - f.workBonusPercent()) / 100;
        String building = VillageFolkEntity.buildingFor(f.stationTask());
        if (building != null && Villages.builtAt(v.id(), building) == null) every = every * 3 / 2;
        if (f.tickCount - last < every && f.tickCount >= last) return false;
        LAST.put(f.getUUID(), f.tickCount);
        return now(f, level, v);
    }

    /** The piece of work, now (the tests). */
    public static boolean now(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        String made = switch (f.stationTask()) {
            case SMITH -> smith(level, v, f);
            case TAILOR -> tailor(level, v, f);
            case BEEKEEP -> beekeep(level, v, f);
            case BREW -> brew(level, v, f);
            case ENCHANT -> enchant(level, v, f);
            case COOK -> Cafe.cook(level, v);
            case SHOP -> Cafe.keepShop(level, v);
            default -> null;
        };
        if (made == null) return false;
        f.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, f.blockPosition(), sound(f.stationTask()), SoundSource.NEUTRAL, 0.7F, 1.0F);
        f.note(AssistantEntity.Deed.THINGS_MADE, 1);
        f.brain("made " + made);
        if (f.getRandom().nextInt(4) == 0) f.say(said(f.stationTask(), made));
        return true;
    }

    private static SoundEvent sound(AssistantEntity.StationTask t) {
        return switch (t) {
            case SMITH -> SoundEvents.ANVIL_USE;
            case TAILOR -> SoundEvents.UI_LOOM_TAKE_RESULT;
            case BEEKEEP -> SoundEvents.BEEHIVE_SHEAR;
            case BREW -> SoundEvents.BREWING_STAND_BREW;
            case ENCHANT -> SoundEvents.ENCHANTMENT_TABLE_USE;
            case COOK -> SoundEvents.SMOKER_SMOKE;
            default -> SoundEvents.VILLAGER_WORK_LIBRARIAN;
        };
    }

    private static String said(AssistantEntity.StationTask t, String made) {
        return switch (t) {
            case SMITH -> "There — " + made + ", fresh off the anvil.";
            case TAILOR -> "Finished " + made + ". Neat stitching, if I say so myself.";
            case BEEKEEP -> "The bees have been busy: " + made + ".";
            case BREW -> "A fresh brew: " + made + ".";
            case ENCHANT -> made + ". I can feel it humming.";
            case COOK -> "Fresh from the kitchen: " + made + ".";
            default -> "Done: " + made + ".";
        };
    }

    // ------------------------------------------------------------------ the stores

    /** How much of what matches the village's stores hold. */
    static int stock(ServerLevel level, Villages.Village v, Predicate<ItemStack> what) {
        return Market.stock(level, v.id(), what);
    }

    /** Take so many from the stores, all or nothing. */
    static boolean take(ServerLevel level, Villages.Village v, Predicate<ItemStack> what, int n) {
        return n <= 0 || TownWork.take(level, v, what, n);
    }

    /** Into the stores; whatever does not fit is dropped at the heart. */
    static void store(ServerLevel level, Villages.Village v, ItemStack s) {
        ItemStack left = Market.intoStores(level, v.id(), s);
        if (!left.isEmpty()) net.minecraft.world.level.block.Block.popResource(level, v.centre().above(), left);
    }

    private static String name(ItemStack s) {
        return (s.getCount() > 1 ? s.getCount() + " " : "a ") + s.getHoverName().getString().toLowerCase();
    }

    static String named(ItemStack s) {
        return name(s);
    }

    /** How many of what matches a hand has to work with: its own pack and the stores together. */
    static int have(ServerLevel level, Villages.Village v, VillageFolkEntity f, Predicate<ItemStack> what) {
        return f.countCarried(what) + stock(level, v, what);
    }

    /** Use so many of what matches: out of the hand's own pack first, then the stores. All or nothing. */
    static boolean use(ServerLevel level, Villages.Village v, VillageFolkEntity f, Predicate<ItemStack> what, int n) {
        if (n <= 0) return true;
        if (have(level, v, f, what) < n) return false;
        int fromPack = Math.min(n, f.countCarried(what));
        if (fromPack < n && !take(level, v, what, n - fromPack)) return false;
        f.removeMatching(what, fromPack);
        return true;
    }

    /** So many planks in the stores, sawn from the stores' logs if there are too few (a log makes four):
     *  the woodcutters bank logs, and the crafts work in planks. */
    static boolean planks(ServerLevel level, Villages.Village v, int n) {
        int have = stock(level, v, s -> s.is(ItemTags.PLANKS));
        if (have >= n) return true;
        int logs = (n - have + 3) / 4;
        if (stock(level, v, s -> s.is(ItemTags.LOGS)) < logs || !take(level, v, s -> s.is(ItemTags.LOGS), logs)) return false;
        store(level, v, new ItemStack(Items.OAK_PLANKS, logs * 4));
        return true;
    }

    /** Sticks out of planks: a plank makes two. */
    private static boolean sticks(ServerLevel level, Villages.Village v, int n) {
        return n <= 0 || (planks(level, v, (n + 1) / 2) && take(level, v, s -> s.is(ItemTags.PLANKS), (n + 1) / 2));
    }

    private static int guards(Villages.Village v) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a.stationTask() == AssistantEntity.StationTask.GUARD) n++;
        return n;
    }

    // ------------------------------------------------------------------ the blacksmith

    private record Smithing(Item item, int iron, int sticks, int keep) {}

    @Nullable
    static String smith(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        // The anvil it brought, set down in the smithy where the drawing has it.
        if (f.countCarried(s -> s.is(ItemTags.ANVIL)) > 0) {
            Trades.workstation(f, level, v, Blocks.ANVIL, s -> s.is(ItemTags.ANVIL),
                com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.ANVIL);
        }
        int watch = Math.max(1, guards(v));
        List<Smithing> wants = List.of(
            new Smithing(Items.IRON_PICKAXE, 3, 2, 2),
            new Smithing(Items.IRON_SWORD, 2, 1, Math.min(4, watch + 1)),
            new Smithing(Items.IRON_HELMET, 5, 0, Math.min(3, watch)),
            new Smithing(Items.IRON_CHESTPLATE, 8, 0, Math.min(3, watch)),
            new Smithing(Items.IRON_LEGGINGS, 7, 0, Math.min(3, watch)),
            new Smithing(Items.IRON_BOOTS, 4, 0, Math.min(3, watch)),
            new Smithing(Items.SHEARS, 2, 0, 1),
            new Smithing(Items.BUCKET, 3, 0, 2),
            new Smithing(Items.IRON_AXE, 3, 2, 1),
            new Smithing(Items.IRON_HOE, 2, 2, 1),
            new Smithing(Items.IRON_SHOVEL, 1, 2, 1));
        // The watch's bows and arrows, turn about with the iron work: a guard on the wall with
        // nothing to shoot is no guard, and a miner with a broken pick is no miner.
        boolean fletchFirst = (level.getGameTime() / EVERY) % 2 == 0;
        if (fletchFirst) {
            String fletched = fletch(level, v, guards(v));
            if (fletched != null) return fletched;
        }
        String forged = forge(level, v, wants);
        if (forged != null || fletchFirst) return forged;
        return fletch(level, v, guards(v));
    }

    @Nullable
    private static String forge(ServerLevel level, Villages.Village v, List<Smithing> wants) {
        int iron = stock(level, v, s -> s.is(Items.IRON_INGOT));
        for (Smithing w : wants) {
            Item it = w.item();
            if (stock(level, v, s -> s.is(it)) >= w.keep()) continue;
            if (iron < w.iron() + 4) continue;                       // a few bars kept back for the village
            if (w.sticks() > 0 && !planks(level, v, (w.sticks() + 1) / 2)) continue;
            if (!take(level, v, s -> s.is(Items.IRON_INGOT), w.iron())) return null;
            sticks(level, v, w.sticks());
            ItemStack made = new ItemStack(it);
            store(level, v, made.copy());
            return name(made);
        }
        return null;
    }

    /** A bow for every guard (three string, three sticks), and arrows for them, as a player makes
     *  them: a flint, a stick and a feather make four. The flint is knapped from the miners'
     *  gravel if the stores have none; the feathers are the rancher's chickens'. */
    @Nullable
    static String fletch(ServerLevel level, Villages.Village v, int watch) {
        if (watch <= 0) return null;
        if (stock(level, v, s -> s.is(Items.BOW)) < watch && stock(level, v, s -> s.is(Items.STRING)) >= 3
                && planks(level, v, 2)) {
            if (!take(level, v, s -> s.is(Items.STRING), 3)) return null;
            sticks(level, v, 3);
            store(level, v, new ItemStack(Items.BOW));
            return "a bow for the watch";
        }
        if (stock(level, v, s -> s.is(Items.ARROW)) < 32 * watch && stock(level, v, s -> s.is(Items.FEATHER)) >= 1) {
            if (stock(level, v, s -> s.is(Items.FLINT)) < 1) {
                if (stock(level, v, s -> s.is(Items.GRAVEL)) < 3 || !take(level, v, s -> s.is(Items.GRAVEL), 3)) return null;
                store(level, v, new ItemStack(Items.FLINT));
                return "a flint knapped out of the gravel, for arrowheads";
            }
            if (!planks(level, v, 1)) return null;
            if (!take(level, v, s -> s.is(Items.FLINT), 1) || !take(level, v, s -> s.is(Items.FEATHER), 1)) return null;
            sticks(level, v, 1);
            ItemStack arrows = new ItemStack(Items.ARROW, 4);
            store(level, v, arrows.copy());
            return name(arrows);
        }
        return null;
    }

    // ------------------------------------------------------------------ the tailor

    @Nullable
    static String tailor(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        Predicate<ItemStack> wool = s -> s.is(ItemTags.WOOL);
        int have = stock(level, v, wool);
        // The loom: the one it brought, or one made of two string and two planks, in the workshop.
        BlockPos loom = Trades.workstation(f, level, v, Blocks.LOOM, s -> s.is(Items.LOOM),
            com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.LOOM);
        if (loom == null && f.countCarried(s -> s.is(Items.LOOM)) == 0 && Villages.hasBuilt(v.id(), "workshop")
                && stock(level, v, s -> s.is(Items.STRING)) >= 2 && planks(level, v, 2)
                && take(level, v, s -> s.is(Items.STRING), 2) && take(level, v, s -> s.is(ItemTags.PLANKS), 2)) {
            ItemStack spare = f.insertItem(new ItemStack(Items.LOOM));
            if (!spare.isEmpty()) store(level, v, spare);
            loom = Trades.workstation(f, level, v, Blocks.LOOM, s -> s.is(Items.LOOM),
                com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.LOOM);
            // ...and on with the work: setting the loom up is not the day's piece.
        }
        // A bed for every house that has a bed short, then rugs, then banners for the washing.
        // While folk sleep on the ground and the houses have room for their beds, the wool is
        // for beds (Grow.furnish carries them in) and not for rugs and banners.
        int adults = 0, bedded = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a.isBaby()) continue;
            adults++;
            if (a.bedPos() != null) bedded++;
        }
        boolean bedsFirst = bedded < adults && bedded < Villages.bedsPlanned(v.id());
        int beds = stock(level, v, s -> s.is(ItemTags.BEDS));
        if ((beds < 2 || (bedsFirst && beds < 4)) && have >= 3 && planks(level, v, 3)) {
            Item colour = woolColour(level, v);
            if (!take(level, v, s -> s.is(colour), 3) && !take(level, v, wool, 3)) return null;
            take(level, v, s -> s.is(ItemTags.PLANKS), 3);
            ItemStack bed = new ItemStack(byColour(colour, "_bed", Items.RED_BED));
            store(level, v, bed.copy());
            return name(bed);
        }
        // String for the smith's bows and the fishers' rods, spun from the wool.
        if (stock(level, v, s -> s.is(Items.STRING)) < 6 && have >= 4) {
            if (!take(level, v, wool, 1)) return null;
            ItemStack string = new ItemStack(Items.STRING, 4);
            store(level, v, string.copy());
            return "four lengths of string, spun from wool";
        }
        if (bedsFirst) return null;
        // Boots in the village's colour, of the rancher's leather: everybody's, a pair each.
        int barefoot = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!a.isBaby() && a.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET).isEmpty()) barefoot++;
        }
        if (barefoot > stock(level, v, s -> s.is(Items.LEATHER_BOOTS)) && stock(level, v, s -> s.is(Items.LEATHER)) >= 4
                && take(level, v, s -> s.is(Items.LEATHER), 4)) {
            ItemStack boots = new ItemStack(Items.LEATHER_BOOTS);
            boots.set(net.minecraft.core.component.DataComponents.DYED_COLOR,
                new net.minecraft.world.item.component.DyedItemColor(Villages.colour(v.id()), false));
            store(level, v, boots);
            return "a pair of boots in the village's colour";
        }
        if (stock(level, v, s -> s.is(ItemTags.WOOL_CARPETS)) < 8 && have >= 2) {
            Item colour = woolColour(level, v);
            if (!take(level, v, s -> s.is(colour), 2) && !take(level, v, wool, 2)) return null;
            ItemStack rugs = new ItemStack(byColour(colour, "_carpet", Items.WHITE_CARPET), 3);
            store(level, v, rugs.copy());
            return name(rugs);
        }
        // Banners are woven on the loom.
        if (loom != null && stock(level, v, s -> s.is(ItemTags.BANNERS)) < 2 && have >= 6 && planks(level, v, 1)) {
            Item colour = woolColour(level, v);
            if (!take(level, v, s -> s.is(colour), 6) && !take(level, v, wool, 6)) return null;
            sticks(level, v, 1);
            ItemStack banner = new ItemStack(byColour(colour, "_banner", Items.WHITE_BANNER));
            store(level, v, banner.copy());
            return name(banner);
        }
        return null;
    }

    /** The colour of wool the stores hold most of. */
    private static Item woolColour(ServerLevel level, Villages.Village v) {
        Item best = Items.WHITE_WOOL;
        int most = 0;
        for (Item w : new Item[]{ Items.WHITE_WOOL, Items.RED_WOOL, Items.BLUE_WOOL, Items.YELLOW_WOOL, Items.GREEN_WOOL,
                Items.BROWN_WOOL, Items.BLACK_WOOL, Items.GRAY_WOOL, Items.LIGHT_GRAY_WOOL, Items.ORANGE_WOOL,
                Items.PINK_WOOL, Items.PURPLE_WOOL, Items.CYAN_WOOL, Items.LIME_WOOL, Items.LIGHT_BLUE_WOOL, Items.MAGENTA_WOOL }) {
            int n = stock(level, v, s -> s.is(w));
            if (n > most) { most = n; best = w; }
        }
        return best;
    }

    /** "red_wool" -> "red_bed": the same colour of another thing. */
    private static Item byColour(Item wool, String suffix, Item fallback) {
        String path = BuiltInRegistries.ITEM.getKey(wool).getPath().replace("_wool", suffix);
        Item it = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(path));
        return it == Items.AIR ? fallback : it;
    }

    // ------------------------------------------------------------------ the beekeeper

    /** The most hives one beekeeper keeps on its meadow. */
    static final int MAX_HIVES = 4;
    private static final Map<UUID, Long> BRED = new ConcurrentHashMap<>();

    /**
     * The beekeeper's work, as a player would do it. The first hive is the one it brought, swarm
     * and all (Trades.kit); after that:
     * <ul>
     * <li>a full hive is emptied with shears (three honeycomb) or a glass bottle (honey), as
     *     the village needs comb for new hives or honey for the café;</li>
     * <li>when the bees fill the hives, another is made from three honeycomb and six planks
     *     (the bees move in by themselves);</li>
     * <li>with room in the hives, two bees are fed a flower each and breed;</li>
     * <li>and flowers are kept round the hives: from the stores, grown with bone meal, or dug
     *     up wild and replanted on the meadow.</li>
     * </ul>
     */
    @Nullable
    static String beekeep(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        WorkZone z = f.workZone();
        if (z == null) return null;
        BlockPos c = z.center();
        int r = Math.min(6, z.radius());
        if (!Land.areaLoaded(level, c, r + 8)) return null;
        List<BlockPos> hives = hivesAt(level, c, r);
        // Honey first: a full hive is a hive about to swarm.
        for (BlockPos p : hives) {
            BlockState st = level.getBlockState(p);
            if (st.getValue(BeehiveBlock.HONEY_LEVEL) < BeehiveBlock.MAX_HONEY_LEVELS) continue;
            boolean wantComb = stock(level, v, s -> s.is(Items.HONEYCOMB)) < 3 && hives.size() < MAX_HIVES;
            boolean shears = shears(level, v, f);
            ItemStack got;
            if (shears && (wantComb || !bottle(level, v))) {
                got = new ItemStack(Items.HONEYCOMB, 3);
                wearShears(f);
            } else if (bottle(level, v) && take(level, v, s -> s.is(Items.GLASS_BOTTLE), 1)) {
                got = new ItemStack(Items.HONEY_BOTTLE);
            } else {
                continue;                                   // nothing to take it with
            }
            level.setBlock(p, st.setValue(BeehiveBlock.HONEY_LEVEL, 0), 3);
            store(level, v, got.copy());
            return name(got);
        }
        // No hive yet: the one it carries (the one it brought lets its swarm out).
        if (hives.isEmpty()) return setHive(level, f, c, r, 0);
        int bees = beesAt(level, c, r, hives);
        // The hives are filling up: another, out of comb and planks.
        if (hives.size() < MAX_HIVES && bees >= 2 * hives.size()) {
            if (f.countCarried(s -> s.is(Items.BEEHIVE)) > 0) {
                String set = setHive(level, f, c, r, hives.size());
                if (set != null) return set;                    // no room for it: on with the rest
            }
            if (hiveSpot(level, c, r, hives.size()) != null
                    && stock(level, v, s -> s.is(Items.HONEYCOMB)) >= 3 && planks(level, v, 6)
                    && take(level, v, s -> s.is(Items.HONEYCOMB), 3) && take(level, v, s -> s.is(ItemTags.PLANKS), 6)) {
                ItemStack left = f.insertItem(new ItemStack(Items.BEEHIVE));
                if (!left.isEmpty()) store(level, v, left);
                String set = setHive(level, f, c, r, hives.size());
                if (set != null) return "a new hive, made of honeycomb and planks";
            }
        }
        // Room in the hives: two bees fed a flower each, and there will be a third.
        if (bees < 3 * hives.size() && level.isDay()
                && Math.abs(level.getGameTime() - BRED.getOrDefault(f.getUUID(), -100000L)) > 6000L) {
            List<Bee> pair = level.getEntitiesOfClass(Bee.class, new net.minecraft.world.phys.AABB(c).inflate(r + 6),
                b -> b.isAlive() && !b.isBaby() && b.canFallInLove() && !b.isInLove());
            if (pair.size() >= 2 && flowers(level, v, c, r, 2)) {
                pair.get(0).setInLove(null);
                pair.get(1).setInLove(null);
                BRED.put(f.getUUID(), level.getGameTime());
                return "two bees fed a flower each; there'll be a new bee soon";
            }
        }
        // Between harvests: flowers round the hives.
        String bloom = bloom(level, v, hives.get(0), 6);
        return bloom;
    }

    /** The hives on a meadow (wild nests count too). */
    static List<BlockPos> hivesAt(ServerLevel level, BlockPos c, int r) {
        List<BlockPos> hives = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -3, -r), c.offset(r, 4, r))) {
            if (level.getBlockState(p).getBlock() instanceof BeehiveBlock) hives.add(p.immutable());
        }
        return hives;
    }

    /** The bees of a meadow: those out flying and those at home in the hives. */
    static int beesAt(ServerLevel level, BlockPos c, int r, List<BlockPos> hives) {
        int n = level.getEntitiesOfClass(Bee.class, new net.minecraft.world.phys.AABB(c).inflate(r + 10), Bee::isAlive).size();
        for (BlockPos p : hives) {
            if (level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.BeehiveBlockEntity h) n += h.getOccupantCount();
        }
        return n;
    }

    /** Set a hive the beekeeper carries down on its meadow. The one it brought lets its swarm out. */
    @Nullable
    private static String setHive(ServerLevel level, VillageFolkEntity f, BlockPos c, int r, int k) {
        ItemStack hive = ItemStack.EMPTY;
        for (ItemStack s : f.getInventoryItems()) {
            if (s.is(Items.BEEHIVE) && (hive.isEmpty() || Trades.isSwarm(s))) hive = s;
        }
        if (hive.isEmpty()) return null;
        BlockPos at = hiveSpot(level, c, r, k);
        if (at == null) return null;
        boolean swarm = Trades.isSwarm(hive);
        hive.shrink(1);
        if (!level.getBlockState(at.above()).isAir()) level.removeBlock(at.above(), false);
        level.setBlock(at, Blocks.BEEHIVE.defaultBlockState()
            .setValue(BeehiveBlock.FACING, net.minecraft.core.Direction.SOUTH), 3);
        if (!swarm) return "a hive set up for the bees to move into";
        // The swarm it brought, out of the hive and about the meadow; they know it for home.
        for (int i = 0; i < 2; i++) {
            Bee bee = EntityType.BEE.create(level);
            if (bee == null) continue;
            bee.moveTo(at.getX() + 0.5, at.getY() + 1.2, at.getZ() + 1.5, 0.0F, 0.0F);
            bee.setPersistenceRequired();
            level.addFreshEntity(bee);
        }
        bloom(level, null, at, 4);
        return "the hive it brought set up, and its swarm let out";
    }

    /** Shears in hand, or out of the stores (the smith makes them). */
    private static boolean shears(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (f.countCarried(s -> s.is(Items.SHEARS)) > 0) return true;
        if (!take(level, v, s -> s.is(Items.SHEARS), 1)) return false;
        ItemStack left = f.insertItem(new ItemStack(Items.SHEARS));
        if (!left.isEmpty()) { store(level, v, left); return false; }
        return true;
    }

    private static void wearShears(VillageFolkEntity f) {
        for (ItemStack s : f.getInventoryItems()) {
            if (!s.is(Items.SHEARS)) continue;
            s.setDamageValue(s.getDamageValue() + 1);
            if (s.getDamageValue() >= s.getMaxDamage()) s.shrink(1);
            return;
        }
    }

    /** A glass bottle in the stores: blown from three glass, three at a time, if there are none. */
    static boolean bottle(ServerLevel level, Villages.Village v) {
        if (stock(level, v, s -> s.is(Items.GLASS_BOTTLE)) >= 1) return true;
        if (stock(level, v, s -> s.is(Items.GLASS)) < 3 || !take(level, v, s -> s.is(Items.GLASS), 3)) return false;
        store(level, v, new ItemStack(Items.GLASS_BOTTLE, 3));
        return true;
    }

    /** So many bottles in the stores, blowing them from glass as needed. */
    static boolean bottles(ServerLevel level, Villages.Village v, int n) {
        while (stock(level, v, s -> s.is(Items.GLASS_BOTTLE)) < n) {
            if (stock(level, v, s -> s.is(Items.GLASS)) < 3 || !take(level, v, s -> s.is(Items.GLASS), 3)) return false;
            store(level, v, new ItemStack(Items.GLASS_BOTTLE, 3));
        }
        return true;
    }

    @Nullable
    private static BlockPos hiveSpot(ServerLevel level, BlockPos c, int r, int k) {
        int[][] tries = { { 2, 2 }, { -2, 2 }, { 2, -2 }, { -2, -2 }, { 0, 3 }, { 3, 0 } };
        for (int i = k; i < tries.length + k; i++) {
            int[] t = tries[i % tries.length];
            int x = c.getX() + t[0], zz = c.getZ() + t[1];
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, zz);
            BlockPos p = new BlockPos(x, y, zz);
            if (Math.abs(y - c.getY()) > 3) continue;
            if (!level.getBlockState(p.below()).isSolid() || !free(level, p) || !free(level, p.above())) continue;
            return p;
        }
        return null;
    }

    /** Room for a hive or a flower: air, or grass and the like that a beekeeper pulls up (not water). */
    private static boolean free(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        return st.isAir() || (st.canBeReplaced() && st.getFluidState().isEmpty() && !isFlower(st));
    }

    private static boolean isFlower(BlockState st) {
        return st.is(net.minecraft.tags.BlockTags.SMALL_FLOWERS);
    }

    /** Feed so many flowers to the bees: from the stores, or picked from the meadow if it has plenty. */
    private static boolean flowers(ServerLevel level, Villages.Village v, BlockPos c, int r, int n) {
        if (take(level, v, s -> s.is(ItemTags.SMALL_FLOWERS), n)) return true;
        List<BlockPos> bed = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -2, -r), c.offset(r, 3, r))) {
            if (isFlower(level.getBlockState(p))) bed.add(p.immutable());
        }
        if (bed.size() < n + 4) return false;                        // leave the bees their meadow
        for (int i = 0; i < n; i++) level.destroyBlock(bed.get(i), false);
        return true;
    }

    /**
     * Flowers round the hives, up to so many: planted from the stores (players bring them for the
     * quest board), grown with bone meal on the grass (the watch's bones), or a wild one dug up
     * and planted on the meadow. Returns what was done, or null.
     */
    @Nullable
    static String bloom(ServerLevel level, @Nullable Villages.Village v, BlockPos near, int want) {
        int have = 0;
        for (BlockPos p : BlockPos.betweenClosed(near.offset(-4, -2, -4), near.offset(4, 3, 4))) {
            if (isFlower(level.getBlockState(p))) have++;
        }
        if (have >= want) return null;
        // From the stores.
        if (v != null) {
            int put = 0;
            while (put < 2 && stock(level, v, s -> s.is(ItemTags.SMALL_FLOWERS)) > 0) {
                ItemStack one = takeOne(level, v, s -> s.is(ItemTags.SMALL_FLOWERS));
                if (one.isEmpty()) break;
                Block b = Block.byItem(one.getItem());
                BlockPos at = grassSpot(level, near);
                if (at == null || b == Blocks.AIR) { store(level, v, one); break; }
                level.setBlock(at, b.defaultBlockState(), 3);           // over any grass there
                put++;
            }
            if (put > 0) return "flowers from the stores planted for the bees";
            // Bone meal on the grass: a bone makes three.
            if (stock(level, v, s -> s.is(Items.BONE_MEAL)) < 1 && take(level, v, s -> s.is(Items.BONE), 1)) {
                store(level, v, new ItemStack(Items.BONE_MEAL, 3));
            }
            BlockPos grass = grassSpot(level, near);
            if (grass != null && take(level, v, s -> s.is(Items.BONE_MEAL), 1)) {
                BlockState g = level.getBlockState(grass.below());
                if (g.getBlock() instanceof net.minecraft.world.level.block.BonemealableBlock m) {
                    m.performBonemeal(level, level.getRandom(), grass.below(), g);
                    level.levelEvent(1505, grass.below(), 15);         // the bone meal sparkle
                    return "bone meal on the meadow, for flowers";
                }
            }
        }
        // A wild one, dug up and brought to the meadow.
        BlockPos wild = wildFlower(level, near, 24, v == null ? null : v.id());
        BlockPos at = grassSpot(level, near);
        if (wild == null || at == null) return null;
        BlockState flower = level.getBlockState(wild);
        level.removeBlock(wild, false);
        level.setBlock(at, flower.getBlock().defaultBlockState(), 3);
        return "a wild flower dug up and planted by the hives";
    }

    /** Is this spot on another beekeeper's meadow (not the one at {@code near})? */
    private static boolean meadowOfAnother(BlockPos near, BlockPos p, @Nullable UUID village) {
        if (village == null) return false;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.stationTask() != AssistantEntity.StationTask.BEEKEEP || a.workZone() == null) continue;
            BlockPos c = a.workZone().center();
            if (c.distManhattan(near) <= 12) continue;                  // our own
            if (Math.max(Math.abs(c.getX() - p.getX()), Math.abs(c.getZ() - p.getZ())) <= 8) return true;
        }
        return false;
    }

    /** One of what matches out of the stores. */
    static ItemStack takeOne(ServerLevel level, Villages.Village v, Predicate<ItemStack> what) {
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !what.test(s)) continue;
                ItemStack one = s.split(1);
                c.setChanged();
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    /** An open spot on the grass near here (for a flower). */
    @Nullable
    private static BlockPos grassSpot(ServerLevel level, BlockPos near) {
        for (int i = 0; i < 24; i++) {
            int x = near.getX() + level.getRandom().nextInt(9) - 4, z = near.getZ() + level.getRandom().nextInt(9) - 4;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            if (free(level, p) && level.getBlockState(p.below()).is(Blocks.GRASS_BLOCK)) return p;
        }
        return null;
    }

    /** A wild flower out in the country round here, not on the meadow itself. */
    @Nullable
    private static BlockPos wildFlower(ServerLevel level, BlockPos near, int r, @Nullable UUID village) {
        if (!Land.areaLoaded(level, near, r)) return null;
        for (int ring = 6; ring <= r; ring += 2) {
            for (int dx = -ring; dx <= ring; dx += 2) {
                for (int dz = -ring; dz <= ring; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = near.getX() + dx, z = near.getZ() + dz;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos p = new BlockPos(x, y, z);
                    if (!isFlower(level.getBlockState(p))) continue;
                    if (village != null && Land.inABuilding(village, p)) continue;    // a garden's, a grave's
                    if (meadowOfAnother(near, p, village)) continue;
                    return p;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the brewer

    /** A brew: the potion, the reagent that makes it from an awkward potion, and how many the village likes to have. */
    private record Brew(Holder<Potion> potion, Item reagent, int keep) {}

    private static List<Brew> brews(Villages.Village v) {
        int watch = Math.max(1, guards(v));
        return List.of(
            new Brew(Potions.HEALING, Items.GLISTERING_MELON_SLICE, Math.min(9, watch * 2 + 1)),
            new Brew(Potions.SWIFTNESS, Items.SUGAR, 3),
            new Brew(Potions.NIGHT_VISION, Items.GOLDEN_CARROT, 3),
            new Brew(Potions.REGENERATION, Items.GHAST_TEAR, 2),
            new Brew(Potions.LEAPING, Items.RABBIT_FOOT, 2),
            new Brew(Potions.WATER_BREATHING, Items.PUFFERFISH, 2),
            new Brew(Potions.FIRE_RESISTANCE, Items.MAGMA_CREAM, 2),
            new Brew(Potions.STRENGTH, Items.BLAZE_POWDER, 2));
    }

    private static boolean isPotion(ItemStack s, Holder<Potion> pot) {
        return s.is(Items.POTION) && s.getOrDefault(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
            PotionContents.EMPTY).is(pot);
    }

    /**
     * The brewer's work, at a real brewing stand (the one it brought, set down in the brewery).
     * Blaze powder fires it; three bottles of water and a nether wart make three awkward potions;
     * then the reagent of whatever the village is shortest of goes in, and twenty seconds later
     * the potions come out to the stores. Its own patch of nether wart, on the soul sand it
     * brought, keeps it in wart.
     */
    @Nullable
    static String brew(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        BlockPos at = Trades.workstation(f, level, v, Blocks.BREWING_STAND, s -> s.is(Items.BREWING_STAND),
            com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.BREWING);
        String patch = wartPatch(level, v, f, at != null ? at : (f.workZone() != null ? f.workZone().center() : f.blockPosition()));
        if (at == null || !(level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity stand)) {
            return patch;
        }
        // Fire: a blaze powder in the fuel slot (it burns for twenty brews).
        if (stand.getItem(4).isEmpty() && use(level, v, f, s -> s.is(Items.BLAZE_POWDER), 1)) {
            stand.setItem(4, new ItemStack(Items.BLAZE_POWDER));
        }
        ItemStack a = stand.getItem(0), b = stand.getItem(1), c = stand.getItem(2);
        boolean full = !a.isEmpty() && !b.isEmpty() && !c.isEmpty();
        boolean idle = stand.getItem(3).isEmpty();
        if (full && idle) {
            boolean awkward = isPotion(a, Potions.AWKWARD) && isPotion(b, Potions.AWKWARD) && isPotion(c, Potions.AWKWARD);
            boolean water = isPotion(a, Potions.WATER) || isPotion(b, Potions.WATER) || isPotion(c, Potions.WATER);
            if (!awkward && !water) {
                // Done: out to the stores.
                ItemStack one = a.copy();
                for (int i = 0; i < 3; i++) {
                    store(level, v, stand.getItem(i).copy());
                    stand.setItem(i, ItemStack.EMPTY);
                }
                stand.setChanged();
                return "three " + one.getHoverName().getString().toLowerCase().replace("potion of ", "potions of ");
            }
            if (water && isPotion(a, Potions.WATER) && isPotion(b, Potions.WATER) && isPotion(c, Potions.WATER)) {
                // Water waiting for its wart (the wart ran out, or a player took it): put one in.
                if (use(level, v, f, s -> s.is(Items.NETHER_WART), 1)) {
                    stand.setItem(3, new ItemStack(Items.NETHER_WART));
                    stand.setChanged();
                    return "a nether wart into the waiting water";
                }
                return patch;
            }
            if (awkward) {
                Brew want = wanted(level, v, f);
                if (want == null) return patch;
                if (!reagent(level, v, f, want, true)) return patch;
                stand.setItem(3, new ItemStack(want.reagent()));
                stand.setChanged();
                return "in goes " + new ItemStack(want.reagent()).getHoverName().getString().toLowerCase()
                    + ", for " + PotionContents.createItemStack(Items.POTION, want.potion()).getHoverName().getString().toLowerCase();
            }
            // A mixed set (some water, some awkward, or a player's potions): out to the stores, start clean.
            for (int i = 0; i < 3; i++) {
                store(level, v, stand.getItem(i).copy());
                stand.setItem(i, ItemStack.EMPTY);
            }
            stand.setChanged();
            return patch;
        }
        if (idle && !(a.isEmpty() && b.isEmpty() && c.isEmpty()) && !full) {
            // One or two bottles in it (a player's doing): out to the stores, start clean.
            for (int i = 0; i < 3; i++) {
                if (!stand.getItem(i).isEmpty()) store(level, v, stand.getItem(i).copy());
                stand.setItem(i, ItemStack.EMPTY);
            }
            stand.setChanged();
            return patch;
        }
        if (a.isEmpty() && b.isEmpty() && c.isEmpty() && idle) {
            // Nothing wanted that it has the makings of: no point brewing awkward potions to stand.
            if (wanted(level, v, f) == null) return patch;
            if (have(level, v, f, s -> s.is(Items.NETHER_WART)) < 1 || !bottles(level, v, 3)) return patch;
            if (!take(level, v, s -> s.is(Items.GLASS_BOTTLE), 3)) return patch;
            use(level, v, f, s -> s.is(Items.NETHER_WART), 1);
            for (int i = 0; i < 3; i++) stand.setItem(i, PotionContents.createItemStack(Items.POTION, Potions.WATER));
            stand.setItem(3, new ItemStack(Items.NETHER_WART));
            stand.setChanged();
            return "three bottles of water and a nether wart into the brewing stand";
        }
        return patch;                                                // brewing: twenty seconds
    }

    /** What the village is shortest of, against what it likes to keep, that the brewer has the reagent for. */
    @Nullable
    private static Brew wanted(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        Brew best = null;
        double bestFill = 1.0;
        for (Brew b : brews(v)) {
            Holder<Potion> pot = b.potion();
            double fill = stock(level, v, s -> isPotion(s, pot)) / (double) b.keep();
            if (fill >= bestFill || !reagent(level, v, f, b, false)) continue;
            best = b;
            bestFill = fill;
        }
        return best;
    }

    /**
     * Has the brewer (or can it make) a brew's reagent — and with {@code use}, takes it. A
     * glistering melon is a melon slice and eight gold nuggets (a gold ingot is nine); a golden
     * carrot, a carrot and the same gold; sugar is ground from sugar cane; blaze powder is only
     * spent on strength while there is plenty left to fire the stand.
     */
    private static boolean reagent(ServerLevel level, Villages.Village v, VillageFolkEntity f, Brew b, boolean use) {
        Item r = b.reagent();
        if (r == Items.BLAZE_POWDER) {
            return have(level, v, f, s -> s.is(Items.BLAZE_POWDER)) >= 5 && (!use || use(level, v, f, s -> s.is(Items.BLAZE_POWDER), 1));
        }
        if (have(level, v, f, s -> s.is(r)) >= 1) return !use || use(level, v, f, s -> s.is(r), 1);
        Predicate<ItemStack> base = r == Items.GLISTERING_MELON_SLICE ? s -> s.is(Items.MELON_SLICE)
            : r == Items.GOLDEN_CARROT ? s -> s.is(Items.CARROT)
            : r == Items.SUGAR ? s -> s.is(Items.SUGAR_CANE) : null;
        if (base == null) return false;
        int keep = r == Items.GOLDEN_CARROT ? 12 : 0;              // the farmers' seed carrots stay
        if (stock(level, v, base) < 1 + keep) return false;
        boolean gilt = r == Items.GLISTERING_MELON_SLICE || r == Items.GOLDEN_CARROT;
        boolean nuggets = stock(level, v, s -> s.is(Items.GOLD_NUGGET)) >= 8;
        if (gilt && !nuggets && stock(level, v, s -> s.is(Items.GOLD_INGOT)) < 1) return false;
        if (!use) return true;
        if (!take(level, v, base, 1)) return false;
        if (gilt) {
            if (nuggets) take(level, v, s -> s.is(Items.GOLD_NUGGET), 8);
            else if (take(level, v, s -> s.is(Items.GOLD_INGOT), 1)) store(level, v, new ItemStack(Items.GOLD_NUGGET));
        }
        return true;
    }

    /**
     * The brewer's own patch of nether wart, beside the brewery: the soul sand it brought laid
     * in the ground, wart planted on it, and the ripe wart picked (two to four a plant) and one
     * put back. Returns what it did, or null.
     */
    @Nullable
    private static String wartPatch(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos near) {
        if (!Land.areaLoaded(level, near, 10)) return null;
        List<BlockPos> sand = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(near.offset(-8, -3, -8), near.offset(8, 3, 8))) {
            if (level.getBlockState(p).is(Blocks.SOUL_SAND)) sand.add(p.immutable());
        }
        // Ripe wart: picked, and one put back.
        for (BlockPos p : sand) {
            BlockState w = level.getBlockState(p.above());
            if (!w.is(Blocks.NETHER_WART) || w.getValue(net.minecraft.world.level.block.NetherWartBlock.AGE) < 3) continue;
            int got = 2 + level.getRandom().nextInt(3);
            level.setBlock(p.above(), Blocks.NETHER_WART.defaultBlockState(), 3);
            ItemStack wart = new ItemStack(Items.NETHER_WART, got - 1);
            ItemStack left = f.insertItem(wart);
            if (!left.isEmpty()) store(level, v, left);
            return (got - 1) + " nether wart from the patch";
        }
        // Bare soul sand: a wart in it, keeping one back for the stand.
        for (BlockPos p : sand) {
            if (!level.getBlockState(p.above()).isAir()) continue;
            if (f.countCarried(s -> s.is(Items.NETHER_WART)) < 2) break;
            f.removeMatching(s -> s.is(Items.NETHER_WART), 1);
            level.setBlock(p.above(), Blocks.NETHER_WART.defaultBlockState(), 3);
            return "a nether wart planted in the patch";
        }
        // Soul sand it carries: into the ground beside the brewery, a patch of four.
        if (sand.size() < 4 && f.countCarried(s -> s.is(Items.SOUL_SAND)) > 0) {
            UUID village = v.id();
            for (int ring = 5; ring <= 8; ring++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        int x = near.getX() + dx, z = near.getZ() + dz;
                        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                        BlockPos g = new BlockPos(x, y, z);
                        if (Math.abs(y - near.getY()) > 3) continue;
                        BlockState gs = level.getBlockState(g);
                        if (!(gs.is(Blocks.GRASS_BLOCK) || gs.is(Blocks.DIRT) || gs.is(Blocks.COARSE_DIRT) || gs.is(Blocks.PODZOL))) continue;
                        if (!level.getBlockState(g.above()).isAir() || Land.inABuilding(village, g)) continue;
                        if (!sand.isEmpty() && sand.get(0).distManhattan(g) > 3) continue;   // one patch, together
                        f.removeMatching(s -> s.is(Items.SOUL_SAND), 1);
                        level.setBlock(g, Blocks.SOUL_SAND.defaultBlockState(), 3);
                        return "soul sand laid for a patch of nether wart";
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the enchanter

    /**
     * The enchanter's work, at the enchanting table (the one it brought, set down in the library):
     * a book bound when the stores have none (three paper pressed from the farmers' cane, and the
     * rancher's leather), then the smith's best work enchanted with three lapis and a book. The
     * library's bookshelves round the table make the enchantments stronger, as they do for a player.
     */
    @Nullable
    static String enchant(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        BlockPos table = Trades.workstation(f, level, v, Blocks.ENCHANTING_TABLE, s -> s.is(Items.ENCHANTING_TABLE),
            com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.ENCHANTING);
        if (stock(level, v, s -> s.is(Items.BOOK)) < 1 && stock(level, v, s -> s.is(Items.LEATHER)) >= 1) {
            if (stock(level, v, s -> s.is(Items.PAPER)) < 3 && stock(level, v, s -> s.is(Items.SUGAR_CANE)) >= 3
                    && take(level, v, s -> s.is(Items.SUGAR_CANE), 3)) {
                store(level, v, new ItemStack(Items.PAPER, 3));
            }
            if (stock(level, v, s -> s.is(Items.PAPER)) >= 3 && take(level, v, s -> s.is(Items.PAPER), 3)
                    && take(level, v, s -> s.is(Items.LEATHER), 1)) {
                store(level, v, new ItemStack(Items.BOOK));
                return "a book bound";
            }
        }
        if (table == null) return null;
        if (have(level, v, f, s -> s.is(Items.LAPIS_LAZULI)) < 3 || stock(level, v, s -> s.is(Items.BOOK)) < 1) return null;
        // Bookshelves as the game counts them: two blocks out from the table, level with it or one
        // up, with nothing but air between (fifteen is as strong as it gets).
        int shelves = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != 2) continue;
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos p = table.offset(dx, dy, dz);
                    BlockPos between = table.offset(dx / 2, dy, dz / 2);
                    if (level.getBlockState(p).is(Blocks.BOOKSHELF) && level.getBlockState(between).isAir()) shelves++;
                }
            }
        }
        shelves = Math.min(15, shelves);
        int tier = shelves >= 15 ? 3 : shelves >= 6 ? 2 : 1;
        var reg = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || s.isEnchanted() || s.getCount() != 1) continue;
                List<Map.Entry<ResourceKey<Enchantment>, Integer>> spell = spellFor(s, tier);
                if (spell.isEmpty()) continue;
                if (!use(level, v, f, x -> x.is(Items.LAPIS_LAZULI), 3) || !take(level, v, x -> x.is(Items.BOOK), 1)) return null;
                for (Map.Entry<ResourceKey<Enchantment>, Integer> e : spell) {
                    reg.getHolder(e.getKey()).ifPresent(h -> s.enchant(h, e.getValue()));
                }
                c.setChanged();
                level.playSound(null, table, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
                return "an enchanted " + s.getHoverName().getString().toLowerCase()
                    + (shelves > 0 ? " (" + shelves + " bookshelves round the table)" : "");
            }
        }
        return null;
    }

    /** What the enchanter puts on a thing: what it is for, made better and longer-lasting; stronger
     *  with more bookshelves round the table. */
    private static List<Map.Entry<ResourceKey<Enchantment>, Integer>> spellFor(ItemStack s, int tier) {
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        boolean good = path.startsWith("iron_") || path.startsWith("diamond_") || path.startsWith("netherite_") || s.is(Items.BOW);
        if (!good) return List.of();
        int lasting = tier >= 3 ? 2 : 1;
        if (path.endsWith("_pickaxe") || path.endsWith("_shovel") || path.endsWith("_axe")) {
            return List.of(Map.entry(Enchantments.EFFICIENCY, tier), Map.entry(Enchantments.UNBREAKING, lasting));
        }
        if (path.endsWith("_sword")) return List.of(Map.entry(Enchantments.SHARPNESS, tier), Map.entry(Enchantments.UNBREAKING, lasting));
        if (path.endsWith("_helmet") || path.endsWith("_chestplate") || path.endsWith("_leggings") || path.endsWith("_boots")) {
            return List.of(Map.entry(Enchantments.PROTECTION, Math.max(1, tier - 1)), Map.entry(Enchantments.UNBREAKING, lasting));
        }
        if (s.is(Items.BOW)) return List.of(Map.entry(Enchantments.POWER, tier));
        return List.of();
    }
}
