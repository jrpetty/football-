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
 *     buckets, axes and hoes — and keeps a few of each in the stores; and out of the iron the
 *     village can spare after that, lanterns (nuggets and a torch) and a cauldron;</li>
 * <li><b>the tailor</b> (Stone Age, at the workshop) turns the rancher's wool into beds for
 *     the houses, rugs and banners, and binds the books the library's shelves are made of;</li>
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
        // Quicker for a practised hand (a percent a level at its craft, to thirty), a cheerful one, a
        // happy village and the town's research; slower without the craft's own building and its
        // tools (the anvil, the brewing stand, the enchanting table...). The same pace as all the
        // village's work (AssistantEntity.workBonusPercent): twenty seconds a piece for a new hand,
        // fourteen at level thirty, nine at the very most.
        int every = f.pacedTicks(EVERY, 100);
        String building = VillageFolkEntity.buildingFor(f.stationTask());
        if (building != null && Villages.builtAt(v.id(), building) == null) every = every * 3 / 2;
        if (f.tickCount - last < every && f.tickCount >= last) return false;
        LAST.put(f.getUUID(), f.tickCount);
        return now(f, level, v);
    }

    /** The piece of work, now (the tests). */
    public static boolean now(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        // What goes in and out of the stores while it works is its making, item by item (Economy).
        Economy.openCraft(v.id(), f.stationTask());
        String made;
        try {
            made = Luxuries.craft(level, v, f, true);                 // a turn at what the houses wait on (Luxuries)
            if (made == null) made = Pets.craft(level, v, f);         // [pets] a turn at the pets' beds, collars, bowls, treats
            if (made == null) made = TradeGoods.craft(level, v, f);   // [player-civic] a master's own: the reinforced pick, the stout, the pie, a journal
            if (made == null) made = WorkTools.craft(level, v, f);    // [workitems] ropes and sacks, the saw, props, crates, boxes, milestones
            if (made == null) made = FieldTools.craft(level, v, f);   // [fields] the copper can, sickle and smoker, the satchel; the shop's any of them
            if (made == null) made = Kitchen.craft(level, v, f);      // [kitchen] lunches, cheese, cakes, pies; mead, cider; tea and bandages
            if (made == null) made = Pastimes.craft(level, v, f);     // [leisure] the quilts, lutes, boards, kites, footballs, lanterns, slates
            if (made == null) made = switch (f.stationTask()) {
                case SMITH -> smith(level, v, f);
                case TAILOR -> tailor(level, v, f);
                case BEEKEEP -> beekeep(level, v, f);
                case BREW -> brew(level, v, f);
                case ENCHANT -> enchant(level, v, f);
                case COOK -> Cafe.cook(level, v, f);
                case SHOP -> Cafe.keepShop(level, v, f);
                case BANK -> Bank.work(level, v, f);          // the vault's bars, the ledger on the lectern
                default -> null;
            };
            if (made == null) made = Luxuries.craft(level, v, f, false);   // its own work done: the houses' wants
        } finally {
            Economy.closeCraft();
        }
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
            case BANK -> "There — " + made + ". Every coin counted.";
            default -> "Done: " + made + ".";
        };
    }

    // ------------------------------------------------------------------ the stores

    /** How much of what matches the village's stores hold. */
    static int stock(ServerLevel level, Villages.Village v, Predicate<ItemStack> what) {
        return Market.stock(level, v.id(), what);
    }

    /** Take so many from the stores, all or nothing. */
    /**
     * A child's first things, made for it out of the village's stores: wooden tools and a
     * crafting table (fourteen planks, or four logs), a loaf or two and a handful of seed. What
     * the stores cannot run to, it goes without: it learns to make its own.
     */
    public static void childKit(VillageFolkEntity folk) {
        UUID id = folk.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null || !(folk.level() instanceof ServerLevel level)) return;
        if (take(level, v, s -> s.is(net.minecraft.tags.ItemTags.PLANKS), 14) || take(level, v, s -> s.is(net.minecraft.tags.ItemTags.LOGS), 4)) {
            folk.insertItem(new ItemStack(Items.WOODEN_PICKAXE));
            folk.insertItem(new ItemStack(Items.WOODEN_AXE));
            folk.insertItem(new ItemStack(Items.WOODEN_SWORD));
            folk.insertItem(new ItemStack(Items.CRAFTING_TABLE));
        }
        for (int i = 0; i < 2; i++) {
            ItemStack food = takeOne(level, v, s -> s.is(Items.BREAD) || s.is(Items.BAKED_POTATO) || s.is(Items.APPLE) || s.is(Items.CARROT));
            if (food.isEmpty()) break;
            folk.insertItem(food);
        }
        if (take(level, v, s -> s.is(Items.WHEAT_SEEDS), 4)) folk.insertItem(new ItemStack(Items.WHEAT_SEEDS, 4));
    }

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

    // What the town's small works are made of (the posts and signs, the stalls, the road, the
    // jetty, the garden fence): never out of nothing, but out of the stores, the thing itself if
    // one is put by, else what it is made of. Each says whether it was paid; nothing is taken
    // when it can't be.

    /** So many planks used up out of the stores, sawn from the logs if need be. All or nothing. */
    static boolean usePlanks(ServerLevel level, Villages.Village v, int n) {
        return n <= 0 || (planks(level, v, n) && take(level, v, s -> s.is(ItemTags.PLANKS), n));
    }

    /** One wooden thing out of the stores, else the planks it is made of. */
    static boolean wooden(ServerLevel level, Villages.Village v, Predicate<ItemStack> thing, int planks) {
        return take(level, v, thing, 1) || usePlanks(level, v, planks);
    }

    /** A sign: one put by, or two planks. */
    static boolean sign(ServerLevel level, Villages.Village v) {
        return wooden(level, v, s -> s.is(ItemTags.SIGNS), 2);
    }

    /** A length of fence: one put by, or two planks. */
    static boolean fence(ServerLevel level, Villages.Village v) {
        return wooden(level, v, s -> s.is(ItemTags.WOODEN_FENCES), 2);
    }

    /**
     * A block of stone for a headstone or a plinth, paid for out of the stores, and what it is: chiselled
     * stone bricks if the masons' stone bricks run to it (two slabs a block, Masonry), else a plain block
     * of stone bricks, else a rough block of cobblestone. Null if the stores hold none of them.
     */
    @Nullable
    static Block masonry(ServerLevel level, Villages.Village v) {
        if (Masonry.take(level, v, Blocks.CHISELED_STONE_BRICKS)) return Blocks.CHISELED_STONE_BRICKS;
        if (take(level, v, s -> s.is(Items.STONE_BRICKS), 1)) return Blocks.STONE_BRICKS;
        if (take(level, v, s -> s.is(Items.COBBLESTONE), 1)) return Blocks.COBBLESTONE;
        return null;
    }

    /** So many of a thing taken down to make way (a roof stripped, a wall refaced, earth cut) back
     *  into the stores, in whole stacks, rather than thrown away. */
    static void giveBack(ServerLevel level, Villages.Village v, Item item, int n) {
        if (item == null || item == Items.AIR || n <= 0) return;
        int most = Math.max(1, new ItemStack(item).getMaxStackSize());
        while (n > 0) {
            int k = Math.min(n, most);
            store(level, v, new ItemStack(item, k));
            n -= k;
        }
    }

    private static int guards(Villages.Village v) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a.stationTask() == AssistantEntity.StationTask.GUARD) n++;
        return WarFooting.armsFor(v.id(), n);         // [war-prep] and the militia's, on a war footing
    }

    // ------------------------------------------------------------------ the blacksmith

    /** A piece of the smith's work: what, of how much of what metal, sticks and planks, and how many to keep in the stores. */
    private record Smithing(Item item, Item metal, int bars, int sticks, int planks, int keep) {
        Smithing(Item item, int iron, int sticks, int keep) { this(item, Items.IRON_INGOT, iron, sticks, 0, keep); }
    }

    @Nullable
    static String smith(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        // The anvil it brought, set down in the smithy where the drawing has it.
        if (f.countCarried(s -> s.is(ItemTags.ANVIL)) > 0) {
            Trades.workstation(f, level, v, Blocks.ANVIL, s -> s.is(ItemTags.ANVIL),
                com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.ANVIL);
        }
        // [emerald] A book the trader bought from the villagers, laid on the town's best tool at the anvil (EmeraldTrader).
        String laid = EmeraldTrader.layBook(level, v, f);
        if (laid != null) return laid;
        int watch = Math.max(1, guards(v));
        List<Smithing> wants = List.of(
            new Smithing(Items.IRON_PICKAXE, 3, 2, 2),
            new Smithing(Items.IRON_SWORD, 2, 1, Math.min(4, watch + 1)),
            new Smithing(Items.IRON_HELMET, 5, 0, Math.min(3, watch)),
            new Smithing(Items.IRON_CHESTPLATE, 8, 0, Math.min(3, watch)),
            new Smithing(Items.IRON_LEGGINGS, 7, 0, Math.min(3, watch)),
            new Smithing(Items.IRON_BOOTS, 4, 0, Math.min(3, watch)),
            new Smithing(Items.SHIELD, Items.IRON_INGOT, 1, 0, 6, Math.min(3, watch)),
            new Smithing(Items.SHEARS, 2, 0, 1),
            new Smithing(Items.BUCKET, 3, 0, 2),
            new Smithing(Items.IRON_AXE, 3, 2, 1),
            new Smithing(Items.IRON_HOE, 2, 2, 1),
            new Smithing(Items.IRON_SHOVEL, 1, 2, 1),
            // With the hand for it, and the diamonds to hand: the miners' best pick and the watch's best blade.
            new Smithing(Items.DIAMOND_PICKAXE, Items.DIAMOND, 3, 2, 0, 1),
            new Smithing(Items.DIAMOND_SWORD, Items.DIAMOND, 2, 1, 0, 1));
        // The watch's bows and arrows, turn about with the iron work: a guard on the wall with
        // nothing to shoot is no guard, and a miner with a broken pick is no miner.
        boolean fletchFirst = (level.getGameTime() / EVERY) % 2 == 0;
        if (fletchFirst) {
            String fletched = fletch(level, v, guards(v));
            if (fletched != null) return fletched;
        } else {
            String best = WatchKit.makeBest(level, v, f);     // [guard-kit] the watch's diamond, turn about with the forging
            if (best != null) return best;
        }
        // [nether] A gold charm for each Nether runner with no gold to wear; the leader's flint and steel (NetherRunners).
        String nether = NetherRunners.smith(level, v, f);
        if (nether != null) return nether;
        String forged = forge(level, v, f, wants);
        if (forged != null) return forged;
        // [guard-kit] Its forging seen to: the watch's diamond (the blade first) once the age, the diamonds and the
        // miners' pick allow, netherite in the Nether Age, and the leather when the town has no tailor (WatchKit).
        String kit = WatchKit.make(level, v, f);
        if (kit != null) return kit;
        if (!fletchFirst) {
            String fletched = fletch(level, v, guards(v));
            if (fletched != null) return fletched;
        }
        // [transport] The railway's rails, powered rails, torches, levers and carts, of the stores' iron and gold (Railways).
        String rails = Railways.smith(level, v, f);
        if (rails != null) return rails;
        // The tools and the watch seen to: the village's lights and pots, of the iron it can spare.
        return ironwork(level, v);
    }

    /** Is the village still putting iron by for its age (Villages.needs)? */
    static boolean savingIron(ServerLevel level, Villages.Village v) {
        for (Villages.Need n : Villages.needs(level, v.id())) {
            if (n.task() == Villages.Task.IRON) return true;
        }
        return false;
    }

    /** Bars the smith never beats into lanterns and pots: the next pick, the next blade. */
    static final int IRON_KEPT = 16;
    /** Lanterns the smith keeps in the stores, for the lamp posts and the buildings' lantern hooks. */
    static final int LANTERNS_KEPT = 8;

    /**
     * The smith's lighter work, out of iron the village can spare once its tools and armour are seen to
     * (never while it is putting iron by for its age, and never the last sixteen bars): lanterns for the
     * lamp posts and the buildings' lantern hooks (an ingot beaten into nine nuggets; eight and a torch
     * make a lantern, and the ninth waits for the next), and a cauldron (seven ingots) for a building
     * going up with a place for one. Until there are lanterns the village lights itself with torches.
     */
    @Nullable
    static String ironwork(ServerLevel level, Villages.Village v) {
        if (savingIron(level, v)) return null;
        int iron = stock(level, v, s -> s.is(Items.IRON_INGOT));
        if (stock(level, v, s -> s.is(Items.LANTERN)) < LANTERNS_KEPT && stock(level, v, s -> s.is(Items.TORCH)) >= 1) {
            if (stock(level, v, s -> s.is(Items.IRON_NUGGET)) < 8) {
                if (iron < 1 + IRON_KEPT || !take(level, v, s -> s.is(Items.IRON_INGOT), 1)) return null;
                store(level, v, new ItemStack(Items.IRON_NUGGET, 9));
                iron--;
            }
            if (take(level, v, s -> s.is(Items.IRON_NUGGET), 8)) {
                if (!take(level, v, s -> s.is(Items.TORCH), 1)) {
                    store(level, v, new ItemStack(Items.IRON_NUGGET, 8));
                    return null;
                }
                store(level, v, new ItemStack(Items.LANTERN));
                return "a lantern, of eight nuggets and a torch";
            }
        }
        if (stock(level, v, s -> s.is(Items.CAULDRON)) < 1 && iron >= 7 + IRON_KEPT && wantsCauldron(v)
                && take(level, v, s -> s.is(Items.IRON_INGOT), 7)) {
            store(level, v, new ItemStack(Items.CAULDRON));
            return "a cauldron";
        }
        return null;
    }

    /** Tests: the smith's lighter work, now. */
    @Nullable
    public static String ironworkForTests(ServerLevel level, Villages.Village v) {
        return ironwork(level, v);
    }

    /** Is a building going up that has a place for a cauldron (the smeltery, the brewery, the café)? */
    private static boolean wantsCauldron(Villages.Village v) {
        for (Map.Entry<String, Villages.Site> e : Villages.sitesOf(v.id()).entrySet()) {
            if (com.jrpetty.mcassistant.entity.goal.BuildGoal.partCounts(e.getKey(), e.getValue().radius())
                    .getOrDefault(com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.CAULDRON, 0) > 0) return true;
        }
        return false;
    }

    /**
     * The first thing on the list the village is short of that the smith has the hand for
     * (Craftsmanship: a beginner makes tools and blades, armour comes with the years, diamond
     * later still) and the metal for, made as well as its hand makes it.
     */
    @Nullable
    private static String forge(ServerLevel level, Villages.Village v, VillageFolkEntity f, List<Smithing> wants) {
        // While the village is still putting iron by for its age, the smith makes only what gets
        // more of it (picks for the mine) and what keeps it safe (a blade for the watch): the
        // armour and the buckets wait. It used to keep four bars back and forge the rest into
        // armour the guards then took out of the stores, and the age's iron never came. The
        // same for the diamonds the age asks for.
        boolean saving = false, savingDiamonds = false;
        for (Villages.Need n : Villages.needs(level, v.id())) {
            if (n.task() == Villages.Task.IRON) saving = true;
            if (n.task() == Villages.Task.DIAMOND) savingDiamonds = true;
        }
        if (WarFooting.ready(v.id())) saving = false;      // [war-prep] on a war footing the arms come before the age
        int skill = f.veteranLevel();
        for (Smithing w : wants) {
            Item it = w.item();
            // [guard-kit] The watch's armour, blades and shields wait on no smith's years: beyond its hand they come
            // out an apprentice's work (WatchKit.forTheWatch, hand); and the watch's iron armour, up to its share of
            // the age's iron, waits on no saving either (WatchKit.ironForTheWatch).
            if (!Craftsmanship.canMake(skill, it) && !WatchKit.forTheWatch(level, v, it)) continue;   // not the hand for it yet
            boolean diamond = w.metal() == Items.DIAMOND;
            if (diamond ? savingDiamonds : saving && it != Items.IRON_PICKAXE && it != Items.IRON_SWORD
                    && !WatchKit.ironForTheWatch(level, v, it)) continue;
            if (stock(level, v, s -> s.is(it)) >= w.keep()) continue;
            Item metal = w.metal();
            int bars = stock(level, v, s -> s.is(metal));
            if (bars < w.bars() + (diamond ? 2 : 4)) continue;             // a few kept back for the village
            int wood = w.planks() + (w.sticks() + 1) / 2;
            if (wood > 0 && !planks(level, v, wood)) continue;
            if (!take(level, v, s -> s.is(metal), w.bars())) return null;
            sticks(level, v, w.sticks());
            if (w.planks() > 0) take(level, v, s -> s.is(ItemTags.PLANKS), w.planks());
            ItemStack made = Craftsmanship.finish(level, new ItemStack(it), WatchKit.hand(skill, it), f.displayNameCap());   // [guard-kit]
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
        watch += NetherRunners.bows(v.id());                      // [nether] a bow, and its arrows, for each Nether runner too
        if (watch <= 0) return null;
        if (Fletchers.keeps(v.id())) return null;                      // [fletcher] the town's fletcher makes them (Fletchers)
        if (stock(level, v, s -> s.is(Items.BOW)) < watch && stock(level, v, s -> s.is(Items.STRING)) >= 3
                && planks(level, v, 2)) {
            if (!take(level, v, s -> s.is(Items.STRING), 3)) return null;
            sticks(level, v, 3);
            store(level, v, new ItemStack(Items.BOW));
            return "a bow for the watch";
        }
        if (stock(level, v, s -> s.is(Items.ARROW)) < 32 * watch && stock(level, v, s -> s.is(Items.FEATHER)) >= 1) {
            if (stock(level, v, s -> s.is(Items.FLINT)) < 1) {
                // [fletcher] Sifted out of the gravel as the game gives it: set down and broken, a flint one time in ten.
                return Fletchers.siftFor(level, v, 4);
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
        // What it can make, and how well, is its years at the loom (Craftsmanship).
        int skill = f.veteranLevel();
        if ((beds < 2 || (bedsFirst && beds < 4)) && have >= 3 && planks(level, v, 3)) {
            Item colour = woolColour(level, v);
            if (!take(level, v, s -> s.is(colour), 3) && !take(level, v, wool, 3)) return null;
            take(level, v, s -> s.is(ItemTags.PLANKS), 3);
            ItemStack bed = Craftsmanship.finish(level, new ItemStack(byColour(colour, "_bed", Items.RED_BED)), skill, f.displayNameCap());
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
        String ribbon = BigWorks.tailorRibbon(level, v);       // [civic] the opening ribbon for the great work under way
        if (ribbon != null) return ribbon;
        // [guard-kit] The watch's leather (WatchKit): a cap, a tunic, trousers and boots for every guard who wears
        // worse, out of the stores' leather, a little kept back for the books. Wool is for the beds; this is not.
        String kit = WatchKit.make(level, v, f);
        if (kit != null) return kit;
        String knotted = Fleet.makeNet(level, v, f);              // [fleet] a net for each of the fishing fleet's boats
        if (knotted != null) return knotted;
        String satchel = NetherRunners.tailor(level, v, f);        // [nether] a runner's satchel for each Nether runner without one
        if (satchel != null) return satchel;
        if (bedsFirst) return null;
        // [fashion] The fashion's garments, at the loom: the book's orders, dyed with the stores' dyes (Tailoring).
        String garment = Tailoring.work(level, v, f, loom);
        if (garment != null) return garment;
        // Books for the library's shelves (three to a bookshelf) and the enchanter's table: three paper
        // pressed from the farmers' cane and a piece of the rancher's leather. Shelves were only ever
        // made of books the enchanter happened to have bound, and the library stood with bare walls.
        if (stock(level, v, s -> s.is(Items.BOOK)) < booksWanted(level, v) && stock(level, v, s -> s.is(Items.LEATHER)) >= 1) {
            if (stock(level, v, s -> s.is(Items.PAPER)) < 3 && stock(level, v, s -> s.is(Items.SUGAR_CANE)) >= 3
                    && take(level, v, s -> s.is(Items.SUGAR_CANE), 3)) {
                store(level, v, new ItemStack(Items.PAPER, 3));
            }
            if (stock(level, v, s -> s.is(Items.PAPER)) >= 3 && take(level, v, s -> s.is(Items.PAPER), 3)) {
                if (take(level, v, s -> s.is(Items.LEATHER), 1)) {
                    store(level, v, new ItemStack(Items.BOOK));
                    return "a book bound, for the library's shelves";
                }
                store(level, v, new ItemStack(Items.PAPER, 3));
            }
        }
        // Boots of the rancher's leather: everybody's, a pair each. Plain leather from a beginner;
        // in the village's colour from a tailor of ten years and more.
        int barefoot = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!a.isBaby() && a.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET).isEmpty()) barefoot++;
        }
        if (barefoot > stock(level, v, s -> s.is(Items.LEATHER_BOOTS)) && stock(level, v, s -> s.is(Items.LEATHER)) >= 4
                && take(level, v, s -> s.is(Items.LEATHER), 4)) {
            ItemStack boots = new ItemStack(Items.LEATHER_BOOTS);
            boolean dyed = skill >= 10;
            if (dyed) {
                boots.set(net.minecraft.core.component.DataComponents.DYED_COLOR,
                    new net.minecraft.world.item.component.DyedItemColor(Villages.colour(v.id()), false));
            }
            store(level, v, Craftsmanship.finish(level, boots, skill, f.displayNameCap()));
            return dyed ? "a pair of boots in the village's colour" : "a pair of plain leather boots";
        }
        if (stock(level, v, s -> s.is(ItemTags.WOOL_CARPETS)) < 8 && have >= 2) {
            Item colour = woolColour(level, v);
            if (!take(level, v, s -> s.is(colour), 2) && !take(level, v, wool, 2)) return null;
            ItemStack rugs = new ItemStack(byColour(colour, "_carpet", Items.WHITE_CARPET), 3);
            store(level, v, rugs.copy());
            return name(rugs);
        }
        // Banners are woven on the loom, by a tailor with the hand for it; a better hand weaves a
        // border into them, and a master a stripe as well.
        if (loom != null && Craftsmanship.canMake(skill, Items.WHITE_BANNER) && stock(level, v, s -> s.is(ItemTags.BANNERS)) < 2
                && have >= 6 && planks(level, v, 1)) {
            Item colour = woolColour(level, v);
            if (!take(level, v, s -> s.is(colour), 6) && !take(level, v, wool, 6)) return null;
            sticks(level, v, 1);
            ItemStack banner = new ItemStack(byColour(colour, "_banner", Items.WHITE_BANNER));
            Craftsmanship.weave(level, banner, skill);
            store(level, v, banner.copy());
            return name(banner) + (skill >= 25 ? ", with a border woven in" : "");
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

    /**
     * The books the village would like put by: three for each bookshelf its library has room for and
     * no shelf in (fourteen for a library still to go up), and one for the enchanter.
     */
    static int booksWanted(ServerLevel level, Villages.Village v) {
        int shelves = 0;
        boolean standing = false;
        for (com.jrpetty.mcassistant.village.Ledger.Building b : com.jrpetty.mcassistant.village.Ledger.buildings(v.id())) {
            if (!b.structure().equals("library") || !level.isLoaded(b.anchor())) continue;
            standing = true;
            shelves += emptyShelves(level, v, b).size();
        }
        if (!standing && Villages.sitesOf(v.id()).containsKey("library")) shelves = 14;
        return 1 + 3 * Math.min(shelves, 14);
    }

    /** The places in a library for a bookshelf that have none. */
    static List<BlockPos> emptyShelves(ServerLevel level, Villages.Village v, com.jrpetty.mcassistant.village.Ledger.Building b) {
        List<BlockPos> out = new ArrayList<>();
        String plan = Grow.tall(v.id(), b.anchor()) ? "library" + com.jrpetty.mcassistant.entity.goal.Blueprints.TALL : "library";
        for (com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement p
                : com.jrpetty.mcassistant.entity.goal.BuildGoal.plan(plan, b.anchor(), b.facing(), 13)) {
            if (p.part() == com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.BOOKSHELF && level.getBlockState(p.pos()).isAir()) {
                out.add(p.pos());
            }
        }
        return out;
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
        // [fields] With a bee smoker, every full hive smoked and emptied on the one round, and more from each (FieldTools).
        String smoked = FieldTools.smokedHarvest(level, v, f, hives);
        if (smoked != null) return smoked;
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
                Economy.storesOut(v.id(), s, 1);
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
    static BlockPos wildFlower(ServerLevel level, BlockPos near, int r, @Nullable UUID village) {
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
            new Brew(Potions.FIRE_RESISTANCE, Items.MAGMA_CREAM, NetherHome.fireResistanceKept(v.id())),   // [nether] two a runner a day
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
        // [nether] The town's wart farm of the runners' soul sand first (NetherHome), else its own small patch.
        String patch = NetherHome.tend(level, v, f);
        if (patch == null) patch = wartPatch(level, v, f, at != null ? at : (f.workZone() != null ? f.workZone().center() : f.blockPosition()));
        if (at == null || !(level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity stand)) {
            return patch;
        }
        // Blaze powder ground from the rods the Nether parties bring (Nether): two to a rod.
        if (have(level, v, f, s -> s.is(Items.BLAZE_POWDER)) < 6 && take(level, v, s -> s.is(Items.BLAZE_ROD), 1)) {
            store(level, v, new ItemStack(Items.BLAZE_POWDER, 2));
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
        // [nether] Magma cream for fire resistance: a blaze powder (the runners' rods) and a slime ball, by the game's recipe.
        if (r == Items.MAGMA_CREAM) return NetherHome.magmaCream(level, v, use);
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
        // [emerald] A book the trader bought from the villagers, laid on the town's best tool (EmeraldTrader.layBook).
        String laid = EmeraldTrader.layBook(level, v, f);
        if (laid != null) return laid;
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
        // The shelves set how strong the work can be, and so does the enchanter's own hand: a
        // beginner lays the first rank only, however many books are round it (Craftsmanship).
        int skill = f.veteranLevel();
        int tier = Math.min(shelves >= 15 ? 3 : shelves >= 6 ? 2 : 1, Craftsmanship.enchantTier(skill));
        var reg = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || s.getCount() != 1 || !Craftsmanship.canEnchant(skill, s)) continue;
                List<Map.Entry<ResourceKey<Enchantment>, Integer>> spell = spellFor(s, tier, skill >= 30);
                // Something already as good (its own work done before; a master smith's tempering
                // is only a start on it) is left be.
                if (spell.isEmpty() || !betters(s, spell, reg)) continue;
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
    private static List<Map.Entry<ResourceKey<Enchantment>, Integer>> spellFor(ItemStack s, int tier, boolean bound) {
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        boolean good = path.startsWith("iron_") || path.startsWith("diamond_") || path.startsWith("netherite_") || s.is(Items.BOW);
        if (!good) return List.of();
        // An enchanter of thirty years binds its work to last: Unbreaking one better.
        int lasting = (tier >= 3 ? 2 : 1) + (bound ? 1 : 0);
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

    /** Would this work make the thing better: is any of it stronger than what is on it already? */
    private static boolean betters(ItemStack s, List<Map.Entry<ResourceKey<Enchantment>, Integer>> spell,
                                   net.minecraft.core.Registry<Enchantment> reg) {
        var on = s.getOrDefault(net.minecraft.core.component.DataComponents.ENCHANTMENTS,
            net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        for (Map.Entry<ResourceKey<Enchantment>, Integer> e : spell) {
            var h = reg.getHolder(e.getKey());
            if (h.isPresent() && on.getLevel(h.get()) < e.getValue()) return true;
        }
        return false;
    }
}
