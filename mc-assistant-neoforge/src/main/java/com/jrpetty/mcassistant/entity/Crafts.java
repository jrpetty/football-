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
        if (f.tickCount - last < EVERY && f.tickCount >= last) return false;
        LAST.put(f.getUUID(), f.tickCount);
        return now(f, level, v);
    }

    /** The piece of work, now (the tests). */
    public static boolean now(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        String made = switch (f.stationTask()) {
            case SMITH -> smith(level, v);
            case TAILOR -> tailor(level, v);
            case BEEKEEP -> beekeep(level, v, f);
            case BREW -> brew(level, v);
            case ENCHANT -> enchant(level, v);
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

    /** Sticks out of planks: a plank makes two. */
    private static boolean sticks(ServerLevel level, Villages.Village v, int n) {
        return n <= 0 || take(level, v, s -> s.is(ItemTags.PLANKS), (n + 1) / 2);
    }

    private static int guards(Villages.Village v) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a.stationTask() == AssistantEntity.StationTask.GUARD) n++;
        return n;
    }

    // ------------------------------------------------------------------ the blacksmith

    private record Smithing(Item item, int iron, int sticks, int keep) {}

    @Nullable
    static String smith(ServerLevel level, Villages.Village v) {
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
        int iron = stock(level, v, s -> s.is(Items.IRON_INGOT));
        for (Smithing w : wants) {
            Item it = w.item();
            if (stock(level, v, s -> s.is(it)) >= w.keep()) continue;
            if (iron < w.iron() + 4) continue;                       // a few bars kept back for the village
            if (w.sticks() > 0 && stock(level, v, s -> s.is(ItemTags.PLANKS)) < (w.sticks() + 1) / 2) continue;
            if (!take(level, v, s -> s.is(Items.IRON_INGOT), w.iron())) return null;
            sticks(level, v, w.sticks());
            ItemStack made = new ItemStack(it);
            store(level, v, made.copy());
            return name(made);
        }
        return null;
    }

    // ------------------------------------------------------------------ the tailor

    @Nullable
    static String tailor(ServerLevel level, Villages.Village v) {
        Predicate<ItemStack> wool = s -> s.is(ItemTags.WOOL);
        int have = stock(level, v, wool);
        // A bed for every house that has a bed short, then rugs, then banners for the washing.
        if (stock(level, v, s -> s.is(ItemTags.BEDS)) < 2 && have >= 3
                && stock(level, v, s -> s.is(ItemTags.PLANKS)) >= 3) {
            Item colour = woolColour(level, v);
            if (!take(level, v, s -> s.is(colour), 3) && !take(level, v, wool, 3)) return null;
            take(level, v, s -> s.is(ItemTags.PLANKS), 3);
            ItemStack bed = new ItemStack(byColour(colour, "_bed", Items.RED_BED));
            store(level, v, bed.copy());
            return name(bed);
        }
        if (stock(level, v, s -> s.is(ItemTags.WOOL_CARPETS)) < 8 && have >= 2) {
            Item colour = woolColour(level, v);
            if (!take(level, v, s -> s.is(colour), 2) && !take(level, v, wool, 2)) return null;
            ItemStack rugs = new ItemStack(byColour(colour, "_carpet", Items.WHITE_CARPET), 3);
            store(level, v, rugs.copy());
            return name(rugs);
        }
        if (stock(level, v, s -> s.is(ItemTags.BANNERS)) < 2 && have >= 6
                && stock(level, v, s -> s.is(ItemTags.PLANKS)) >= 1) {
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

    /** The beekeeper's work: two hives on its meadow, flowers round them, and the honey taken when it is ready. */
    @Nullable
    static String beekeep(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        WorkZone z = f.workZone();
        if (z == null) return null;
        BlockPos c = z.center();
        int r = Math.min(6, z.radius());
        List<BlockPos> hives = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -3, -r), c.offset(r, 4, r))) {
            if (level.getBlockState(p).getBlock() instanceof BeehiveBlock) hives.add(p.immutable());
        }
        // Honey first: a full hive is a hive about to swarm.
        for (BlockPos p : hives) {
            BlockState st = level.getBlockState(p);
            if (st.getValue(BeehiveBlock.HONEY_LEVEL) < BeehiveBlock.MAX_HONEY_LEVELS) continue;
            level.setBlock(p, st.setValue(BeehiveBlock.HONEY_LEVEL, 0), 3);
            ItemStack got;
            if (take(level, v, s -> s.is(Items.GLASS_BOTTLE), 1) || take(level, v, s -> s.is(Items.GLASS), 1)) {
                got = new ItemStack(Items.HONEY_BOTTLE);
            } else {
                got = new ItemStack(Items.HONEYCOMB, 3);
            }
            store(level, v, got.copy());
            return name(got);
        }
        if (hives.size() < 2) {
            BlockPos at = hiveSpot(level, c, r, hives.size());
            if (at == null) return null;
            level.setBlock(at, Blocks.BEEHIVE.defaultBlockState()
                .setValue(BeehiveBlock.FACING, net.minecraft.core.Direction.SOUTH), 3);
            // A swarm settles in it.
            for (int i = 0; i < 2; i++) {
                Bee bee = EntityType.BEE.create(level);
                if (bee == null) continue;
                bee.moveTo(at.getX() + 0.5, at.getY() + 1.2, at.getZ() + 1.5, 0.0F, 0.0F);
                bee.setPersistenceRequired();
                level.addFreshEntity(bee);
            }
            plantFlowers(level, at, 6);
            return "a new hive";
        }
        // Between harvests: flowers for the bees.
        if (plantFlowers(level, hives.get(0), 2) > 0) return "flowers planted for the bees";
        return null;
    }

    @Nullable
    private static BlockPos hiveSpot(ServerLevel level, BlockPos c, int r, int k) {
        int[][] tries = { { 2, 2 }, { -2, 2 }, { 2, -2 }, { -2, -2 }, { 0, 3 }, { 3, 0 } };
        for (int i = k; i < tries.length + k; i++) {
            int[] t = tries[i % tries.length];
            int x = c.getX() + t[0], zz = c.getZ() + t[1];
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, zz);
            BlockPos p = new BlockPos(x, y, zz);
            if (Math.abs(y - c.getY()) > 4) continue;
            if (!level.getBlockState(p.below()).isSolid() || !level.getBlockState(p).isAir()
                    || !level.getBlockState(p.above()).isAir()) continue;
            return p;
        }
        return null;
    }

    private static final net.minecraft.world.level.block.Block[] FLOWERS = { Blocks.POPPY, Blocks.DANDELION,
        Blocks.CORNFLOWER, Blocks.OXEYE_DAISY, Blocks.ALLIUM, Blocks.AZURE_BLUET };

    /** Flowers on the grass round a spot, up to so many. Returns how many went in. */
    static int plantFlowers(ServerLevel level, BlockPos near, int want) {
        int put = 0;
        for (int i = 0; i < 24 && put < want; i++) {
            int x = near.getX() + level.getRandom().nextInt(9) - 4, z = near.getZ() + level.getRandom().nextInt(9) - 4;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            if (!level.getBlockState(p).isAir() || !level.getBlockState(p.below()).is(Blocks.GRASS_BLOCK)) continue;
            level.setBlock(p, FLOWERS[level.getRandom().nextInt(FLOWERS.length)].defaultBlockState(), 3);
            put++;
        }
        return put;
    }

    // ------------------------------------------------------------------ the brewer

    private record Brew(Holder<Potion> potion, Predicate<ItemStack> reagent, @Nullable Predicate<ItemStack> extra, int keep) {}

    @Nullable
    static String brew(ServerLevel level, Villages.Village v) {
        int watch = Math.max(1, guards(v));
        List<Brew> book = List.of(
            new Brew(Potions.HEALING, s -> s.is(Items.MELON_SLICE) || s.is(Items.GLISTERING_MELON_SLICE),
                s -> s.is(Items.GOLD_INGOT) || s.is(Items.GOLD_NUGGET), Math.min(9, watch * 2 + 1)),
            new Brew(Potions.SWIFTNESS, s -> s.is(Items.SUGAR) || s.is(Items.SUGAR_CANE), null, 3),
            new Brew(Potions.NIGHT_VISION, s -> s.is(Items.GOLDEN_CARROT), null, 3),
            new Brew(Potions.LEAPING, s -> s.is(Items.RABBIT_FOOT), null, 2),
            new Brew(Potions.WATER_BREATHING, s -> s.is(Items.PUFFERFISH), null, 2),
            new Brew(Potions.FIRE_RESISTANCE, s -> s.is(Items.MAGMA_CREAM), null, 2),
            new Brew(Potions.STRENGTH, s -> s.is(Items.BLAZE_POWDER), null, 2));
        // Three bottles a batch: made of glass if the stores have no bottles.
        boolean bottles = stock(level, v, s -> s.is(Items.GLASS_BOTTLE)) >= 3;
        if (!bottles && stock(level, v, s -> s.is(Items.GLASS)) < 3) return null;
        for (Brew b : book) {
            Holder<Potion> pot = b.potion();
            Predicate<ItemStack> isIt = s -> s.is(Items.POTION) && s.has(net.minecraft.core.component.DataComponents.POTION_CONTENTS)
                && s.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS).is(pot);
            if (stock(level, v, isIt) >= b.keep()) continue;
            if (stock(level, v, b.reagent()) < 1) continue;
            if (b.extra() != null && stock(level, v, b.extra()) < 1) continue;
            if (!take(level, v, b.reagent(), 1)) continue;
            if (b.extra() != null) take(level, v, b.extra(), 1);
            if (bottles) take(level, v, s -> s.is(Items.GLASS_BOTTLE), 3);
            else take(level, v, s -> s.is(Items.GLASS), 3);
            ItemStack one = PotionContents.createItemStack(Items.POTION, pot);
            for (int i = 0; i < 3; i++) store(level, v, one.copy());
            return "three " + one.getHoverName().getString().toLowerCase().replace("potion of ", "potions of ");
        }
        return null;
    }

    // ------------------------------------------------------------------ the enchanter

    @Nullable
    static String enchant(ServerLevel level, Villages.Village v) {
        // Books first: three cane makes three paper, and three paper and a hide a book.
        if (stock(level, v, s -> s.is(Items.BOOK)) < 2 && stock(level, v, s -> s.is(Items.SUGAR_CANE) || s.is(Items.PAPER)) >= 3
                && stock(level, v, s -> s.is(Items.LEATHER)) >= 1) {
            if (!take(level, v, s -> s.is(Items.PAPER), 3) && !take(level, v, s -> s.is(Items.SUGAR_CANE), 3)) return null;
            take(level, v, s -> s.is(Items.LEATHER), 1);
            store(level, v, new ItemStack(Items.BOOK));
            return "a book";
        }
        if (stock(level, v, s -> s.is(Items.LAPIS_LAZULI)) < 3 || stock(level, v, s -> s.is(Items.BOOK)) < 1) return null;
        var reg = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || s.isEnchanted() || s.getCount() != 1) continue;
                List<Map.Entry<ResourceKey<Enchantment>, Integer>> spell = spellFor(s);
                if (spell.isEmpty()) continue;
                if (!take(level, v, x -> x.is(Items.LAPIS_LAZULI), 3) || !take(level, v, x -> x.is(Items.BOOK), 1)) return null;
                for (Map.Entry<ResourceKey<Enchantment>, Integer> e : spell) {
                    reg.getHolder(e.getKey()).ifPresent(h -> s.enchant(h, e.getValue()));
                }
                c.setChanged();
                return "an enchanted " + s.getHoverName().getString().toLowerCase();
            }
        }
        return null;
    }

    /** What the enchanter puts on a thing: what it is for, made better and longer-lasting. */
    private static List<Map.Entry<ResourceKey<Enchantment>, Integer>> spellFor(ItemStack s) {
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        boolean good = path.startsWith("iron_") || path.startsWith("diamond_") || path.startsWith("netherite_") || s.is(Items.BOW);
        if (!good) return List.of();
        if (path.endsWith("_pickaxe") || path.endsWith("_shovel") || path.endsWith("_axe")) {
            return List.of(Map.entry(Enchantments.EFFICIENCY, 3), Map.entry(Enchantments.UNBREAKING, 2));
        }
        if (path.endsWith("_sword")) return List.of(Map.entry(Enchantments.SHARPNESS, 3), Map.entry(Enchantments.UNBREAKING, 2));
        if (path.endsWith("_helmet") || path.endsWith("_chestplate") || path.endsWith("_leggings") || path.endsWith("_boots")) {
            return List.of(Map.entry(Enchantments.PROTECTION, 2), Map.entry(Enchantments.UNBREAKING, 2));
        }
        if (s.is(Items.BOW)) return List.of(Map.entry(Enchantments.POWER, 2));
        return List.of();
    }
}
