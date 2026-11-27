package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.item.Garment;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.AnimalArmorItem;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.BrushItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.GameMasterBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [uses16] What a thing is for, who in the town has a use for it now, and the using of it once bought.
 *
 * <p><b>Knowing things.</b> Any item, the game's own, this mod's or another mod's, is read for what it is for (kinds): food
 * to eat, a tool of a trade, a weapon or armour for the watch and the hunters, furniture, a light or a comfort for a home,
 * building stuff, a maker's makings, seed and saplings, feed for the pens, a remedy, a book, clothes and dye, a keepsake,
 * fuel, a luxury, music, tack for a horse; and what nobody here would do anything with (spawn eggs, the game master's
 * blocks). A thing it has not heard of it reads by its class (armour, a tiered tool, a block, something edible) and by the
 * shared tags every mod tags its things with (ingots, gems, seeds, crops, dyes, foods, tools...).
 *
 * <p><b>Wanting things.</b> Each folk (and through it its household, the firm it works for and the town's departments it
 * does the town's buying for) has a list of needs (needsOf), each with what will do, how many it would use and why: the
 * tool of its trade when its own is gone or nearly worn through, armour the watch's guard lacks, food when its larder is
 * low, fuel, candles and a remedy for the cupboard when the household runs short, the pieces its home's plan still wants
 * (a light for a dark home, a chest, a stove...), a comfort while its standing runs to more and its home has room, seed for
 * bare furrows, saplings to replant, feed when the troughs run low, arrows, a book for a reader, a remedy for the ill, a
 * keepsake to its taste, a luxury for the well-off, the makings its firm's bench is short of, the builders' timber and
 * stone. A thing is weighed against the list (want): bought only for a real use, in a sensible number (never past what
 * the use takes), at a price within what the folk will pay (its means, its nature, how pressing the need), the better
 * value for the use preferred; anything else it turns down with a reason the player sees on the stall's books.
 *
 * <p><b>Using things.</b> Bought, a thing is put to its use (use): a tool or a blade taken up (the old one to the stores),
 * armour and clothes put on, food and fuel put by at home, a piece of furniture or a light carried home and set out where
 * its home's plan has room for it (HomeMaking, Luxuries), a remedy drunk by the ill, a book read, seed sown in the bare
 * furrows, feed and makings and timber into the stores for the troughs, the firm's bench and the builders. Each use is
 * remembered and shows on the folk's card.
 */
public final class ItemUses {

    private ItemUses() {}

    // ------------------------------------------------------------------ what a thing is for

    /** What a thing can be used for. */
    public enum Kind {
        FOOD("food", "to eat"),
        TOOL("a tool", "for its work"),
        WEAPON("a weapon", "to arm itself"),
        ARMOUR("armour", "to wear on its rounds"),
        AMMO("arrows", "for its bow"),
        FURNITURE("furniture", "for its home"),
        LIGHT("a light", "to light its home"),
        COMFORT("a comfort", "for its home"),
        HOUSEHOLD("a household thing", "for the house"),
        BUILDING("building stuff", "for the builders"),
        INPUT("makings", "for a maker's bench"),
        SEED("seed", "to sow"),
        SAPLING("a sapling", "to plant"),
        FEED("feed", "for the pens"),
        MEDICINE("a remedy", "for the ill"),
        BOOK("a book", "to read"),
        CLOTHING("clothes", "to wear"),
        DYE("dye", "for the dyer"),
        GIFT("a keepsake", "to keep"),
        FUEL("fuel", "for the stove"),
        LUXURY("a luxury", "for the pleasure of it"),
        MUSIC("music", "to play"),
        TACK("tack", "for a horse");

        public final String words, forWhat;

        Kind(String words, String forWhat) {
            this.words = words;
            this.forWhat = forWhat;
        }

        /** How nobody in a town would put it, when nobody needs it. */
        String nobody() {
            return switch (this) {
                case FOOD -> "the larders are stocked";
                case TOOL -> "nobody's trade wants it, or their own are good yet";
                case WEAPON, ARMOUR, AMMO -> "the watch is kitted out";
                case FURNITURE, HOUSEHOLD -> "no home has call or room for it";
                case LIGHT -> "the homes are lit already";
                case COMFORT -> "the homes have all of that they want";
                case BUILDING -> "the builders have enough";
                case INPUT -> "no maker is short of it";
                case SEED -> "no bare furrows to sow";
                case SAPLING -> "no woodcutter wants for saplings";
                case FEED -> "the troughs are full";
                case MEDICINE -> "nobody is ill, and the cupboards have remedies";
                case BOOK -> "no reader wants another book just now";
                case CLOTHING, DYE -> "everybody is dressed";
                case GIFT -> "nobody fancies it";
                case FUEL -> "the woodpiles are stacked";
                case LUXURY -> "nobody here is that well off";
                case MUSIC -> "nobody here plays it";
                case TACK -> "nobody here rides";
            };
        }
    }

    /** Never anybody's to use: the game master's things. */
    static boolean nobodys(ItemStack s) {
        Item it = s.getItem();
        return it instanceof SpawnEggItem || it instanceof GameMasterBlockItem || s.is(Items.KNOWLEDGE_BOOK) || s.is(Items.DEBUG_STICK)
            || s.is(Items.BARRIER) || s.is(Items.LIGHT) || s.is(Items.BEDROCK) || s.is(Items.STRUCTURE_VOID) || s.is(Items.COMMAND_BLOCK_MINECART)
            || s.is(Items.END_PORTAL_FRAME) || s.is(Items.REINFORCED_DEEPSLATE) || s.is(Items.SPAWNER) || s.is(Items.TRIAL_SPAWNER)
            || s.is(Items.VAULT) || s.is(Items.PETRIFIED_OAK_SLAB);
    }

    /**
     * What a thing is for, every use it has (none: what would anybody do with it?). Known things by what they are; a thing
     * of another mod by its class (armour, a tool of a tier, a block, a food) and the shared tags (c:ingots, c:seeds...).
     */
    public static EnumSet<Kind> kinds(ItemStack s) {
        EnumSet<Kind> k = EnumSet.noneOf(Kind.class);
        if (s.isEmpty() || Market.isCoin(s) || nobodys(s)) return k;
        Item it = s.getItem();
        if (PlayerStalls.food(s)) k.add(Kind.FOOD);
        if (medicine(s)) k.add(Kind.MEDICINE);
        if (it instanceof ArmorItem a) {
            k.add(Kind.ARMOUR);
            if (a.getMaterial().equals(ArmorMaterials.LEATHER)) k.add(Kind.CLOTHING);
        }
        if (Garment.of(s) != null) k.add(Kind.CLOTHING);
        if (it instanceof ShieldItem || s.is(Tags.Items.TOOLS_SHIELD) || s.is(Tags.Items.ARMORS)) k.add(Kind.ARMOUR);
        if (it instanceof SwordItem || it instanceof BowItem || it instanceof CrossbowItem || it instanceof TridentItem || s.is(ItemTags.SWORDS)
            || s.is(Tags.Items.MELEE_WEAPON_TOOLS) || s.is(Tags.Items.RANGED_WEAPON_TOOLS)) k.add(Kind.WEAPON);
        if (it instanceof ArrowItem || s.is(ItemTags.ARROWS)) k.add(Kind.AMMO);
        if (it instanceof DiggerItem || it instanceof ShearsItem || it instanceof FishingRodItem || it instanceof FlintAndSteelItem
            || it instanceof BrushItem || s.is(ItemTags.PICKAXES) || s.is(ItemTags.AXES) || s.is(ItemTags.HOES) || s.is(ItemTags.SHOVELS)
            || s.is(Tags.Items.MINING_TOOL_TOOLS) || s.is(Tags.Items.TOOLS_SHEAR) || s.is(Tags.Items.TOOLS_FISHING_ROD)) k.add(Kind.TOOL);
        for (StationTask t : WORK_ITEM_TRADES) if (WorkTools.keeps(t, s) > 0) k.add(Kind.TOOL);   // this mod's: a prop, a rope, a sack, a saw, a crate
        if (s.is(Items.SADDLE) || it instanceof AnimalArmorItem || s.is(Items.LEAD) || s.is(Items.NAME_TAG)) k.add(Kind.TACK);
        if (s.is(Items.WRITTEN_BOOK) || s.is(Items.WRITABLE_BOOK) || s.is(Items.ENCHANTED_BOOK) || s.is(Items.BOOK)) k.add(Kind.BOOK);
        if (s.has(DataComponents.JUKEBOX_PLAYABLE) || s.is(Tags.Items.MUSIC_DISCS) || s.is(Items.GOAT_HORN)) k.add(Kind.MUSIC);
        if (it instanceof DyeItem || s.is(Tags.Items.DYES)) k.add(Kind.DYE);
        if (s.is(ItemTags.SAPLINGS)) k.add(Kind.SAPLING);
        if (seed(s)) k.add(Kind.SEED);
        if (feed(s)) k.add(Kind.FEED);
        if (fuel(s)) k.add(Kind.FUEL);
        if (it instanceof BucketItem || s.is(Tags.Items.BUCKETS)) k.add(Kind.HOUSEHOLD);
        if (it instanceof BlockItem bi) blockKinds(s, bi.getBlock(), k);
        if (s.is(Items.PAINTING) || s.is(Items.ITEM_FRAME) || s.is(Items.GLOW_ITEM_FRAME) || s.is(Items.ARMOR_STAND)) k.add(Kind.COMFORT);
        if (input(s)) k.add(Kind.INPUT);
        if (FolkTalk.kindOf(s) != null) k.add(Kind.GIFT);
        if (PlayerStalls.luxury(s)) k.add(Kind.LUXURY);
        // Nothing it knows of: by its class.
        if (k.isEmpty()) {
            if (it instanceof TieredItem) k.add(Kind.TOOL);
            else if (s.has(DataComponents.FOOD)) k.add(Kind.FOOD);
            else if (s.is(Tags.Items.FOODS)) k.add(Kind.FOOD);
        }
        return k;
    }

    /** The trades that keep this mod's work items (WorkTools.keeps). */
    private static final StationTask[] WORK_ITEM_TRADES = { StationTask.MINE, StationTask.CAVE, StationTask.WOOD, StationTask.HAUL };

    /** A block's uses: a light, a piece of furniture, a comfort, building stuff. */
    private static void blockKinds(ItemStack s, Block b, EnumSet<Kind> k) {
        BlockState st = b.defaultBlockState();
        if (b instanceof LanternBlock || b instanceof TorchBlock || b instanceof CandleBlock || (st.getLightEmission() > 0 && !(b instanceof EntityBlock)
            && !s.is(Items.MAGMA_BLOCK))) k.add(Kind.LIGHT);
        if (b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof BedBlock || b instanceof CraftingTableBlock || b instanceof AbstractFurnaceBlock
            || b instanceof AbstractCauldronBlock || b instanceof AnvilBlock || b instanceof ComposterBlock || b instanceof LecternBlock
            || b instanceof NoteBlock || b instanceof JukeboxBlock || s.is(Items.BOOKSHELF) || s.is(Tags.Items.CHESTS) || s.is(Tags.Items.BARRELS)
            || s.is(Tags.Items.VILLAGER_JOB_SITES) || s.is(Tags.Items.PLAYER_WORKSTATIONS_CRAFTING_TABLES)
            || s.is(Tags.Items.PLAYER_WORKSTATIONS_FURNACES)) k.add(Kind.FURNITURE);
        // A fence post and a board make a table; a wooden stair a seat (HomeMaking).
        if (s.is(ItemTags.WOODEN_FENCES) || s.is(ItemTags.WOODEN_PRESSURE_PLATES) || s.is(ItemTags.WOODEN_STAIRS)) k.add(Kind.FURNITURE);
        if (b instanceof CarpetBlock || b instanceof FlowerPotBlock || b instanceof AbstractBannerBlock || s.is(Items.GLASS_PANE)
            || s.is(ItemTags.SMALL_FLOWERS) || s.is(Items.FLOWER_POT) || Luxuries.kindOf(s) != null) k.add(Kind.COMFORT);
        if (b instanceof EntityBlock || b instanceof CropBlock || b instanceof StemBlock) return;
        boolean whole = st.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if ((whole || b instanceof SlabBlock || b instanceof StairBlock || b instanceof WallBlock || s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS)
            || s.is(Tags.Items.GLASS_BLOCKS)) && !s.is(Tags.Items.ORES) && !s.is(Tags.Items.STORAGE_BLOCKS) && !k.contains(Kind.LIGHT)) {
            k.add(Kind.BUILDING);
        }
    }

    /** A remedy: what the healer gives, a potion that does a body good, milk for what ails it. */
    static boolean medicine(ItemStack s) {
        if (Health.isRemedy(s) || s.is(Items.MILK_BUCKET)) return true;
        if (!(s.getItem() instanceof PotionItem)) return false;
        PotionContents pc = s.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        for (MobEffectInstance e : pc.getAllEffects()) if (e.getEffect().value().isBeneficial()) return true;
        return false;
    }

    /** Seed to sow: the seeds, and the crops that are sown as they are (a potato, a carrot). */
    static boolean seed(ItemStack s) {
        if (s.is(Tags.Items.SEEDS)) return true;
        return s.getItem() instanceof BlockItem bi && (bi.getBlock() instanceof CropBlock || bi.getBlock() instanceof StemBlock);
    }

    /** Feed for the pens. */
    static boolean feed(ItemStack s) {
        return s.is(Items.WHEAT) || s.is(Items.HAY_BLOCK) || s.is(Items.CARROT) || s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS)
            || s.is(Tags.Items.ANIMAL_FOODS) && !s.is(ItemTags.FLOWERS);
    }

    /** Fuel for a stove: what the households burn (coal, logs), and anything else that burns well. */
    static boolean fuel(ItemStack s) {
        if (Housekeeping.FUEL.test(s)) return true;
        int burn;
        try {
            burn = s.getBurnTime(RecipeType.SMELTING);
        } catch (RuntimeException e) {
            burn = 0;
        }
        return burn >= 1600 && !(s.getItem() instanceof BucketItem);
    }

    /** The makings of a trade: metal, gems, string, leather, hides, crops, wool, timber and the like. */
    static boolean input(ItemStack s) {
        return s.is(Tags.Items.INGOTS) || s.is(Tags.Items.NUGGETS) || s.is(Tags.Items.GEMS) || s.is(Tags.Items.GEMS_PRISMARINE)
            || s.is(Items.PRISMARINE_SHARD) || s.is(Items.PRISMARINE_CRYSTALS) || s.is(Tags.Items.DUSTS)
            || s.is(Tags.Items.RAW_MATERIALS) || s.is(Tags.Items.STRINGS) || s.is(Tags.Items.LEATHERS) || s.is(Tags.Items.CROPS)
            || s.is(Tags.Items.FEATHERS) || s.is(Tags.Items.BONES) || s.is(Tags.Items.SLIME_BALLS) || s.is(Tags.Items.GUNPOWDERS)
            || s.is(Tags.Items.RODS) || s.is(Tags.Items.ORES) || s.is(Tags.Items.EGGS) || s.is(Tags.Items.MUSHROOMS)
            || s.is(ItemTags.WOOL) || s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS) || s.is(Tags.Items.DYES) || s.is(Tags.Items.SANDS)
            || s.is(Items.PAPER) || s.is(Items.SUGAR) || s.is(Items.HONEYCOMB) || s.is(Items.WHEAT) || s.is(Items.SUGAR_CANE) || s.is(Items.FLINT)
            || s.is(Items.CLAY_BALL) || s.is(Items.CLAY) || s.is(Items.BOOK) || s.is(Items.GLASS_BOTTLE) || s.is(Items.NETHER_WART)
            || s.is(Items.BLAZE_POWDER) || s.is(Items.COBBLESTONE) || s.is(Items.COAL) || s.is(Items.CHARCOAL);
    }

    /** "food, a light" — a thing's uses in words. */
    public static String kindsWords(ItemStack s) {
        EnumSet<Kind> k = kinds(s);
        if (k.isEmpty()) return "no use anybody here knows of";
        List<String> w = new ArrayList<>();
        for (Kind x : k) w.add(x.words);
        return String.join(", ", w);
    }

    /** A thing's name as folk say it, from its own name in the game (the server has no translations): "iron hoe". */
    static String name(ItemStack s) {
        if (s.isEmpty()) return "nothing";
        if (s.is(Items.WRITTEN_BOOK)) {
            var wb = s.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (wb != null) return "the book \"" + wb.title().raw() + "\"";
        }
        Market.Good g = Market.goodFor(s.copyWithCount(1));
        if (g != null && g.bundle() > 1) return g.name().toLowerCase(Locale.ROOT);
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().replace('_', ' ');
    }

    /** "a lantern", "an iron hoe", "8 bread". */
    static String counted(ItemStack s, int n) {
        String w = name(s);
        if (w.startsWith("the book")) return w;
        if (n > 1) return n + " " + w;
        return ("aeiou".indexOf(w.isEmpty() ? 'x' : w.charAt(0)) >= 0 ? "an " : "a ") + w;
    }

    // ------------------------------------------------------------------ what the town has a use for

    /** Who pays for a need: the folk (for itself and its household), the firm it works for, or the town. */
    public enum Payer { OWN, FIRM, TOWN }

    /**
     * One use a folk has now (its own, its household's, its firm's or the town's through it): what kind, what will do, one
     * that would (for the books), how many it would use, how pressing (nought to one), why, who pays and what it is called.
     */
    public record Need(Kind kind, Predicate<ItemStack> what, ItemStack sample, int qty, double urgency, String reason, Payer payer,
                       @Nullable String firm, @Nullable Accounts.Dept dept, String key) {}

    /** A thing as one folk weighs it: what for, how many it would take, how much it is worth to it now (nought to one), the
     *  most it would pay for one, and why (or why not). */
    public record Want(@Nullable Kind kind, int qty, double value, int mostEach, String reason, @Nullable Need need) {
        public boolean yes() {
            return qty > 0 && need != null;
        }
    }

    /** A thing put to its use: done or not, and what was done, in words ("hung it in its home"). */
    public record Used(boolean done, String words) {}

    /** A thing weighed by the whole town: who would buy it and why (the keenest), how many folk would, how many in all and
     *  the most one would pay; or, nobody, why not. */
    public record Verdict(boolean yes, String who, String why, int buyers, int qty, int mostEach) {
        /** "Tom, farmer: its hoe is nearly worn through (and 2 more)", or "Nobody: the homes are lit already". */
        public String line() {
            if (!yes) return why.contains("can't afford") ? why : "Nobody needs it: " + why;
            return who + ": " + why + (buyers > 1 ? " (and " + (buyers - 1) + " more)" : "");
        }
    }

    private static final long NEEDS_TTL = 100L;
    private static final Map<UUID, Object[]> NEEDS = new ConcurrentHashMap<>();
    private static final Map<String, Object[]> VERDICTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Object[]> TOWN = new ConcurrentHashMap<>();
    /** The day each folk last bought a book, a keepsake, a luxury (no more than one a few days). */
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();
    /** Each folk's last few uses, for its card. */
    private static final Map<UUID, Deque<String>> LOG = new ConcurrentHashMap<>();
    /** The books each folk has read (by title). */
    private static final Map<UUID, List<String>> READ = new ConcurrentHashMap<>();
    /** What the folk turned down at each stall as no use to them: village|owner -> key -> {day, who, reason}. */
    private static final Map<String, LinkedHashMap<String, String[]>> REFUSED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        NEEDS.clear();
        VERDICTS.clear();
        TOWN.clear();
        LAST.clear();
        LOG.clear();
        READ.clear();
        REFUSED.clear();
    }

    /** Looked at again now (it bought something, or a test changed its world). */
    public static void forget(VillageFolkEntity f) {
        NEEDS.remove(f.getUUID());
        UUID v = f.ownerId();
        if (v != null) {
            TOWN.remove(v);
            VERDICTS.keySet().removeIf(k -> k.startsWith(v + "|"));
        }
    }

    /** Every folk of the town looked at again (tests). */
    public static void forgetTown(UUID village) {
        for (AssistantEntity a : Villages.folkOf(village)) NEEDS.remove(a.getUUID());
        TOWN.remove(village);
        VERDICTS.keySet().removeIf(k -> k.startsWith(village + "|"));
    }

    private static long now(VillageFolkEntity f) {
        return f.level().getGameTime();
    }

    private static long day(VillageFolkEntity f) {
        return f.level().getDayTime() / 24000L;
    }

    /** What this folk has a use for now, most pressing first (looked at afresh every few seconds). */
    @SuppressWarnings("unchecked")
    public static List<Need> needsOf(VillageFolkEntity f) {
        Object[] c = NEEDS.get(f.getUUID());
        long t = now(f);
        if (c != null && t - (long) c[0] < NEEDS_TTL && t >= (long) c[0]) return (List<Need>) c[1];
        List<Need> out = f.level() instanceof ServerLevel level ? reckon(level, f) : List.of();
        NEEDS.put(f.getUUID(), new Object[]{ t, out });
        return out;
    }

    /** Has it any use for anything just now? */
    public static boolean hasNeeds(VillageFolkEntity f) {
        return !needsOf(f).isEmpty();
    }

    private static List<Need> reckon(ServerLevel level, VillageFolkEntity f) {
        List<Need> out = new ArrayList<>();
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null || f.isBaby() || !f.isAlive()) return out;
        long day = day(f);
        StationTask job = f.stationTask();
        Wealth.Tier tier = Wealth.tier(f);
        // The tool of its trade (and the watch's and the hunter's blade).
        Toolrack.Tool tool = Toolrack.of(job);
        if (tool != null) toolNeed(f, tool, tier, job == StationTask.GUARD || job == StationTask.HUNT ? Kind.WEAPON : Kind.TOOL, out);
        // This mod's work items its trade keeps (a miner's props, rope and sack; a woodcutter's saw; a courier's crates).
        for (ItemStack kit : workKit()) {
            int keep = WorkTools.keeps(job, kit);
            if (keep <= 0) continue;
            Item ki = kit.getItem();
            int has = f.countCarried(x -> x.is(ki));
            if (has * 2 > keep) continue;
            out.add(new Need(Kind.TOOL, x -> x.is(ki) && WorkTools.keeps(job, x) > 0, kit.copy(), keep - has, 0.4,
                "its work wants " + name(kit) + (has > 0 ? " and it has only " + has : " and it has none"), Payer.OWN, null, null, "kit." + name(kit)));
        }
        // The watch's armour, the hunter's, the cave team's and the nether runners'.
        if (job == StationTask.GUARD || job == StationTask.HUNT || job == StationTask.CAVE || job == StationTask.NETHER) armourNeeds(f, out);
        // Arrows for a bow it carries.
        if ((job == StationTask.GUARD || job == StationTask.HUNT)
            && f.countCarried(s -> s.getItem() instanceof BowItem || s.getItem() instanceof CrossbowItem) > 0
            && f.countCarried(s -> s.getItem() instanceof ArrowItem) < 16) {
            out.add(new Need(Kind.AMMO, s -> s.getItem() instanceof ArrowItem && !s.is(Items.TIPPED_ARROW), new ItemStack(Items.ARROW),
                16, 0.4, "its quiver is nearly empty", Payer.OWN, null, null, "ammo"));
        }
        // Boots for anybody barefoot.
        if (f.getItemBySlot(EquipmentSlot.FEET).isEmpty()) {
            out.add(new Need(Kind.CLOTHING, s -> s.getItem() instanceof ArmorItem a && a.getEquipmentSlot() == EquipmentSlot.FEET
                && a.getMaterial().equals(ArmorMaterials.LEATHER), new ItemStack(Items.LEATHER_BOOTS), 1, 0.3, "it goes barefoot",
                Payer.OWN, null, null, "boots"));
        }
        // Ill or hurt: a remedy now.
        Health.State hs = f.health();
        boolean ill = hs.cold > 0, hurt = hs.wounded || f.getHealth() < f.getMaxHealth() * 0.6F;
        if (ill || hurt) {
            out.add(new Need(Kind.MEDICINE, ItemUses::medicine, potion(), 1, 0.9,
                ill ? "it is laid up with a cold" : "it is hurt", Payer.OWN, null, null, "remedy"));
        }
        // Its home: the household's stocks and the pieces its plan still wants.
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        boolean home = h != null && !Homes.lodging(f) && !Homes.seat(h) && level.isLoaded(h.anchor);
        if (home) homeNeeds(level, v, f, h, tier, day, out);
        else if (f.countCarried(PlayerStalls::food) < 3) {
            out.add(new Need(Kind.FOOD, PlayerStalls::food, new ItemStack(Items.BREAD), 4, Market.hungry(village) ? 0.9 : 0.5,
                "it has nothing to eat by it", Payer.OWN, null, null, "food"));
        }
        // Its work: seed for bare furrows, saplings to replant, the pens' feed.
        if (job == StationTask.FARM) {
            int bare = bareFurrows(level, f.blockPosition(), 10);
            int carried = f.countCarried(ItemUses::seed);
            if (bare > carried) {
                out.add(new Need(Kind.SEED, ItemUses::seed, new ItemStack(Items.WHEAT_SEEDS), Math.min(16, bare - carried), 0.5,
                    bare + (bare == 1 ? " furrow lies" : " furrows lie") + " bare in its field", Payer.OWN, null, null, "seed"));
            }
        }
        if (job == StationTask.WOOD && f.countCarried(s -> s.is(ItemTags.SAPLINGS)) < 4) {
            out.add(new Need(Kind.SAPLING, s -> s.is(ItemTags.SAPLINGS), new ItemStack(Items.OAK_SAPLING), 4, 0.3,
                "it has no saplings to replant what it fells", Payer.OWN, null, null, "sapling"));
        }
        if (job == StationTask.RANCH) {
            int feed = Market.stock(level, village, ItemUses::feed);
            if (feed < 32) {
                out.add(new Need(Kind.FEED, ItemUses::feed, new ItemStack(Items.WHEAT), 16, 0.4,
                    "the troughs are running low (" + feed + " feed in the stores)", Payer.TOWN, null, Accounts.Dept.UPKEEP_ANIMALS, "feed"));
            }
        }
        // A reader's book; a keepsake to its taste; a luxury for the well-off.
        Persona p = f.persona();
        boolean reader = p.rolled() && (p.hobby() == Persona.Hobby.READING || p.loves() == Persona.Gift.BOOKS);
        if (reader && day - LAST.getOrDefault(f.getUUID() + "/book", -100L) >= 3) {
            List<String> read = READ.getOrDefault(f.getUUID(), List.of());
            out.add(new Need(Kind.BOOK, s -> s.is(Items.WRITTEN_BOOK) && !read.contains(title(s)), new ItemStack(Items.WRITTEN_BOOK), 1, 0.35,
                "it loves a good book and has read all of its own", Payer.OWN, null, null, "book"));
        }
        if (p.rolled() && tier.ordinal() >= Wealth.Tier.COMFORTABLE.ordinal() && day - LAST.getOrDefault(f.getUUID() + "/gift", -100L) >= 5) {
            Persona.Gift loves = p.loves();
            if (loves != Persona.Gift.BOOKS && loves != Persona.Gift.TOOLS) {
                out.add(new Need(Kind.GIFT, s -> FolkTalk.kindOf(s) == loves && !PlayerStalls.food(s), sampleOf(loves), 1, 0.2,
                    "it loves " + loves.name().toLowerCase(Locale.ROOT).replace('_', ' '), Payer.OWN, null, null, "gift"));
            }
        }
        if (tier.ordinal() >= Wealth.Tier.WELL_OFF.ordinal() && day - LAST.getOrDefault(f.getUUID() + "/luxury", -100L) >= 3) {
            out.add(new Need(Kind.LUXURY, PlayerStalls::luxury, new ItemStack(Items.CAKE), 1, 0.15, "it is well off and likes nice things",
                Payer.OWN, null, null, "luxury"));
        }
        // Its firm's bench: the makings it is short of.
        Business firm = Enterprise.employerOf(village, f);
        if (firm != null) firmNeeds(level, village, firm, out);
        // The town's buying, by its storekeeper and its couriers: the builders' timber and stone.
        if (job == StationTask.STORE || job == StationTask.HAUL) townNeeds(level, village, out);
        out.sort(Comparator.comparingDouble((Need n) -> -n.urgency()));
        return out;
    }

    /** This mod's work items, one of each, for the needs (WorkItems). */
    private static volatile List<ItemStack> workKit;

    private static List<ItemStack> workKit() {
        if (workKit == null) {
            List<ItemStack> l = new ArrayList<>();
            try {
                l.add(new ItemStack(com.jrpetty.mcassistant.item.WorkItems.PIT_PROP_ITEM.get()));
                l.add(new ItemStack(com.jrpetty.mcassistant.item.WorkItems.ROPE_COIL.get()));
            } catch (RuntimeException e) {
                // not registered (a unit test): no work items to want
            }
            workKit = l;
        }
        return workKit;
    }

    private static ItemStack potion() {
        ItemStack s = new ItemStack(Items.POTION);
        s.set(DataComponents.POTION_CONTENTS, new PotionContents(net.minecraft.world.item.alchemy.Potions.HEALING));
        return s;
    }

    private static ItemStack sampleOf(Persona.Gift g) {
        return new ItemStack(switch (g) {
            case FLOWERS -> Items.POPPY;
            case SWEETS -> Items.COOKIE;
            case GEMS -> Items.AMETHYST_SHARD;
            case MUSIC -> Items.MUSIC_DISC_CAT;
            case FISH -> Items.COD;
            case WOOL -> Items.WHITE_WOOL;
            case GOLD -> Items.GOLD_NUGGET;
            default -> Items.POPPY;
        });
    }

    /** How good a tool is, for choosing: its metal, then the wear left in it. */
    private static int toolScore(ItemStack s) {
        return s.isEmpty() ? -1 : Toolrack.metal(s) * 200 + Toolrack.left(s);
    }

    /** The best of this kind of tool it has (in its pack or in its hand), or empty. */
    static ItemStack ownTool(VillageFolkEntity f, Toolrack.Tool tool) {
        ItemStack best = ItemStack.EMPTY;
        for (ItemStack s : f.getInventoryItems()) if (Toolrack.is(tool, s) && toolScore(s) > toolScore(best)) best = s;
        ItemStack hand = f.getMainHandItem();
        if (Toolrack.is(tool, hand) && toolScore(hand) > toolScore(best)) best = hand;
        return best;
    }

    private static void toolNeed(VillageFolkEntity f, Toolrack.Tool tool, Wealth.Tier tier, Kind kind, List<Need> out) {
        ItemStack own = ownTool(f, tool);
        int left = own.isEmpty() ? 0 : Toolrack.left(own);
        int metal = own.isEmpty() ? -1 : Toolrack.metal(own);
        String word = tool.word;
        if (own.isEmpty()) {
            out.add(new Need(kind, s -> Toolrack.is(tool, s) && Toolrack.left(s) > 50 && f.mayUseTier(s), plain(tool), 1, 1.0,
                "it has no " + word + " for its work", Payer.OWN, null, null, "tool"));
        } else if (left <= 35) {
            out.add(new Need(kind, s -> Toolrack.is(tool, s) && Toolrack.left(s) > Math.max(50, left) && f.mayUseTier(s), plain(tool), 1, 0.75,
                "its " + name(own) + " is nearly worn through (" + left + "% left)", Payer.OWN, null, null, "tool"));
        } else if (metal < 3 && tier.ordinal() >= Wealth.Tier.COMFORTABLE.ordinal()) {
            out.add(new Need(kind, s -> Toolrack.is(tool, s) && Toolrack.metal(s) > metal && Toolrack.left(s) > 50 && f.mayUseTier(s),
                better(tool), 1, 0.3, "a better " + word + " than its " + name(own), Payer.OWN, null, null, "tool"));
        }
    }

    private static ItemStack plain(Toolrack.Tool t) {
        return new ItemStack(switch (t) {
            case PICKAXE -> Items.STONE_PICKAXE;
            case AXE -> Items.STONE_AXE;
            case HOE -> Items.STONE_HOE;
            case SWORD -> Items.STONE_SWORD;
            case ROD -> Items.FISHING_ROD;
            case SHEARS -> Items.SHEARS;
        });
    }

    private static ItemStack better(Toolrack.Tool t) {
        return new ItemStack(switch (t) {
            case PICKAXE -> Items.IRON_PICKAXE;
            case AXE -> Items.IRON_AXE;
            case HOE -> Items.IRON_HOE;
            case SWORD -> Items.IRON_SWORD;
            case ROD -> Items.FISHING_ROD;
            case SHEARS -> Items.SHEARS;
        });
    }

    private static final EquipmentSlot[] ARMOUR = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

    private static void armourNeeds(VillageFolkEntity f, List<Need> out) {
        for (EquipmentSlot slot : ARMOUR) {
            ItemStack cur = f.getItemBySlot(slot);
            int has = cur.getItem() instanceof ArmorItem a ? a.getDefense() : 0;
            if (has > 0 && !Toolrack.worn(cur)) continue;
            String piece = switch (slot) {
                case HEAD -> "helmet";
                case CHEST -> "breastplate";
                case LEGS -> "leggings";
                default -> "boots";
            };
            boolean watch = f.stationTask() != StationTask.HUNT;                 // [econ32b] the watch's kit is not the hunter's
            out.add(new Need(Kind.ARMOUR, s -> s.getItem() instanceof ArmorItem a && a.getEquipmentSlot() == slot && a.getDefense() > has
                && f.mayUseTier(s) && (watch || !WatchKit.issued(s)), new ItemStack(slot == EquipmentSlot.HEAD ? Items.IRON_HELMET : slot == EquipmentSlot.CHEST ? Items.IRON_CHESTPLATE
                : slot == EquipmentSlot.LEGS ? Items.IRON_LEGGINGS : Items.IRON_BOOTS), 1, 0.6,
                has > 0 ? "its " + piece + " is nearly worn through" : "it goes on its rounds with no " + piece, Payer.OWN, null, null, "armour." + piece));
        }
    }

    private static void homeNeeds(ServerLevel level, Villages.Village v, VillageFolkEntity f, Homes.Home h, Wealth.Tier tier, long day, List<Need> out) {
        UUID village = v.id();
        Housekeeping.Levels lv = Housekeeping.levels(level, village, h);
        boolean hungry = Market.hungry(village);
        int food = lv.have().getOrDefault(Housekeeping.Stock.FOOD, 0), foodWant = lv.want().getOrDefault(Housekeeping.Stock.FOOD, 0);
        int carried = f.countCarried(PlayerStalls::food);
        if (food + carried < foodWant || hungry && carried < 3) {
            int n = Math.max(2, Math.min(16, foodWant - food - carried));
            out.add(new Need(Kind.FOOD, PlayerStalls::food, new ItemStack(Items.BREAD), n, hungry || lv.days() < 1 ? 0.85 : 0.45,
                lv.days() < 1 ? "its larder is all but bare" : "its larder is low (" + lv.days() + (lv.days() == 1 ? " day" : " days") + " of food)",
                Payer.OWN, null, null, "food"));
        }
        stockNeed(lv, f, Housekeeping.Stock.FUEL, Kind.FUEL, Housekeeping.FUEL, new ItemStack(Items.CHARCOAL), 16, 0.5, "its woodpile is low", out);
        stockNeed(lv, f, Housekeeping.Stock.LIGHT, Kind.LIGHT, Housekeeping.LIGHTS, new ItemStack(Items.CANDLE), 8, 0.3, "it is short of candles", out);
        stockNeed(lv, f, Housekeeping.Stock.MEDICINE, Kind.MEDICINE, Housekeeping.REMEDY, new ItemStack(Items.HONEY_BOTTLE), 1, 0.3,
            "there is no remedy in its cupboard", out);
        // The pieces its home's plan still wants: a light for a dark home, a chest, the stove...
        for (HomeMaking.Wanted w : HomeMaking.wanting(f)) {
            Kind kind = w.key().equals("light") ? Kind.LIGHT : w.key().startsWith("taste.") || w.key().startsWith("colour.") ? Kind.COMFORT : Kind.FURNITURE;
            String why = w.key().equals("light") ? "its home has no light" : "its home wants " + w.words();
            out.add(new Need(kind, w.what(), new ItemStack(w.sample()), w.n(), w.basic() ? 0.7 : 0.35, why, Payer.OWN, null, null, "piece." + w.key()));
        }
        // The comforts its standing runs to, while its home has fewer of a kind than a house takes.
        if (tier.ordinal() >= Wealth.Tier.COMFORTABLE.ordinal() && f.comforts() < tier.comforts) {
            Map<String, Long> book = Decor.book(village, h.anchor);
            for (Luxuries.Kind k : Luxuries.Kind.values()) {
                if (tier.ordinal() < k.from.ordinal()) continue;
                int have = 0;
                for (String key : book.keySet()) if (key.startsWith("lux." + k.key + ".")) have++;
                if (k == Luxuries.Kind.LANTERN) {                             // the home's light hung as a lantern counts
                    BlockPos lit = HomeMaking.pieceAt(village, h.anchor, "light");
                    if (lit != null && level.getBlockState(lit).getBlock() instanceof LanternBlock) have++;
                }
                if (have >= k.most) continue;
                out.add(new Need(k == Luxuries.Kind.LANTERN ? Kind.LIGHT : Kind.COMFORT, k.what, sample(k), 1, 0.25,
                    "its home has " + (have == 0 ? "no " : "room for another ") + k.key + (have == 0 ? "" : " (it has " + have + ")"),
                    Payer.OWN, null, null, "lux." + k.key));
            }
        }
    }

    private static ItemStack sample(Luxuries.Kind k) {
        return new ItemStack(switch (k) {
            case RUG -> Items.WHITE_CARPET;
            case PAINTING -> Items.PAINTING;
            case PANE -> Items.GLASS_PANE;
            case CANDLE -> Items.CANDLE;
            case POT -> Items.FLOWER_POT;
            case LANTERN -> Items.LANTERN;
            case BANNER -> Items.WHITE_BANNER;
            case BOOKSHELF -> Items.BOOKSHELF;
        });
    }

    private static void stockNeed(Housekeeping.Levels lv, VillageFolkEntity f, Housekeeping.Stock stock, Kind kind, Predicate<ItemStack> what,
                                  ItemStack sample, int most, double urgency, String why, List<Need> out) {
        int have = lv.have().getOrDefault(stock, 0) + f.countCarried(what);
        int want = lv.want().getOrDefault(stock, 0);
        if (have >= want) return;
        out.add(new Need(kind, what, sample, Math.max(1, Math.min(most, want - have)), urgency, why, Payer.OWN, null, null, "stock." + stock.name()));
    }

    /** What its firm's bench is short of (InputWants: the word a firm short of its makings sends out), past what the stores hold. */
    private static void firmNeeds(ServerLevel level, UUID village, Business b, List<Need> out) {
        CompoundTag r = Enterprise.rec(village, b.id);
        String shortOf = r == null ? "" : r.getString("short").toLowerCase(Locale.ROOT);
        for (Map.Entry<Item, Integer> e : InputWants.all(village).entrySet()) {
            Item it = e.getKey();
            ItemStack one = new ItemStack(it);
            String key = Stockroom.key(one);
            Business.Line l = b.allTime.lines.get(key);
            String nm = name(one);
            if ((l == null || l.used <= 0) && !shortOf.contains(nm) && !shortOf.contains(nm.replace(" ingot", "")) && !firmUses(level, village, b, it)) continue;
            int stock = Market.stock(level, village, s -> s.is(it));
            int n = Math.min(16, e.getValue() - stock);
            if (n <= 0) continue;
            out.add(new Need(Kind.INPUT, s -> s.is(it) && !s.isEnchanted(), one, n, 0.6, b.name + " is short of " + nm + " for its bench",
                Payer.FIRM, b.id, null, "input." + key));
        }
    }

    private static final Map<String, Boolean> USES = new ConcurrentHashMap<>();

    /** Does a firm of this kind make any of its wares of this (a toolsmith's picks of iron)? By the game's recipes. */
    static boolean firmUses(ServerLevel level, UUID village, Business b, Item it) {
        BusinessTypes.BusinessType t = Enterprise.kindOf(village, b);
        if (t == null) return false;
        return USES.computeIfAbsent(t.key() + "|" + BuiltInRegistries.ITEM.getKey(it), k -> {
            ItemStack probe = new ItemStack(it);
            for (String ware : t.wares()) {
                ItemStack out = Stockroom.sampleOf(ware);
                if (out.isEmpty()) continue;
                for (net.minecraft.world.item.crafting.RecipeHolder<?> r : level.getRecipeManager().getRecipes()) {
                    ItemStack res = r.value().getResultItem(level.registryAccess());
                    if (res.isEmpty() || !res.is(out.getItem())) continue;
                    for (net.minecraft.world.item.crafting.Ingredient ing : r.value().getIngredients()) if (ing.test(probe)) return true;
                }
            }
            return false;
        });
    }

    /** The builders' working stock, short in the stores. */
    private static void townNeeds(ServerLevel level, UUID village, List<Need> out) {
        int timber = Market.stock(level, village, s -> s.is(ItemTags.PLANKS)) + 4 * Market.stock(level, village, s -> s.is(ItemTags.LOGS));
        if (timber < 48) {
            out.add(new Need(Kind.BUILDING, s -> s.is(ItemTags.PLANKS) || s.is(ItemTags.LOGS), new ItemStack(Items.OAK_PLANKS), 32, 0.4,
                "the builders are short of timber (" + timber + " planks' worth in the stores)", Payer.TOWN, null, Accounts.Dept.WORKS, "timber"));
        }
        int stone = Market.stock(level, village, s -> s.is(Items.COBBLESTONE) || s.is(Items.STONE));
        if (stone < Masonry.BUILDERS_STONE) {
            out.add(new Need(Kind.BUILDING, s -> s.is(Items.COBBLESTONE) || s.is(Items.STONE), new ItemStack(Items.COBBLESTONE), 32, 0.4,
                "the builders are short of stone (" + stone + " in the stores)", Payer.TOWN, null, Accounts.Dept.WORKS, "stone"));
        }
    }

    /** Bare tilled ground near here, nothing growing on it. */
    static int bareFurrows(ServerLevel level, BlockPos at, int r) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-r, -2, -r), at.offset(r, 1, r))) {
            if (level.getBlockState(p).getBlock() instanceof FarmBlock && level.getBlockState(p.above()).isAir()) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ one thing weighed

    /** What one of this is worth here, coin: the village's going price, else its worth by the price list (another mod's
     *  thing too: by its recipe, or its rarity). */
    public static double worthEach(ServerLevel level, UUID village, ItemStack s) {
        ItemStack one = s.copyWithCount(1);
        int lot = PlayerStalls.lot(one);
        int going = Budget.goodFor(one) != null ? PlayerStalls.going(level, village, one) : 0;
        if (going > 0) return going / (double) lot;
        double p = Math.max(1.0, Prices.of(one));
        if (MarketMaker.live(village)) p *= MarketMaker.priceLevel(village);
        return p;
    }

    /** [uses16] PlayerStalls.going for a thing the village's counter has no price for: its worth by the list, a lot of it,
     *  so a useful thing of another mod can be bought too. Nought for what nobody here has a use for. */
    static int fallbackLot(ServerLevel level, UUID village, ItemStack one) {
        if (kinds(one).isEmpty()) return 0;
        double p = Math.max(1.0, Prices.of(one.copyWithCount(1)));         // no price on the list: worth a coin to whoever has a use for it
        if (MarketMaker.live(village)) p *= MarketMaker.priceLevel(village);
        return Math.max(1, (int) Math.round(p * PlayerStalls.lot(one)));
    }

    /** How far over the going price this folk goes for this need: its means and its nature (PlayerStalls.willing), a tenth
     *  more when it is pressing; a firm and the town pay the going price. */
    static double keen(VillageFolkEntity f, Need n) {
        if (n.payer() != Payer.OWN) return 1.0;
        double k = PlayerStalls.willing(f, false);
        if (n.urgency() >= 0.8) k = Math.max(1.0, k) + 0.1;
        return Math.min(PlayerStalls.FAR_OVER, k);
    }

    /**
     * [uses16] The API: this thing, as this folk weighs it now: what it would use it for, how many (never past what the use
     * takes), how much it is worth to it, the most it would pay for one, and why; or no, and why not ("what would I do with
     * a saddle?", "my home's lit already").
     */
    public static Want want(VillageFolkEntity f, ItemStack s) {
        if (s.isEmpty() || Market.isCoin(s)) return new Want(null, 0, 0, 0, "nothing", null);
        if (s.isDamageableItem() && Toolrack.left(s) < 50) {
            return new Want(null, 0, 0, 0, "it's half worn out", null);
        }
        EnumSet<Kind> kinds = kinds(s);
        if (kinds.isEmpty()) return new Want(null, 0, 0, 0, "what would I do with " + counted(s, 1) + "?", null);
        for (Need n : needsOf(f)) {
            if (!n.what().test(s)) continue;
            if (n.kind() == Kind.FOOD && !kinds.contains(Kind.FOOD)) continue;
            int room = n.payer() == Payer.OWN ? roomFor(f, s) : n.qty();
            int qty = Math.min(n.qty(), room);
            if (qty <= 0) return new Want(n.kind(), 0, 0, 0, "my pack's full", null);
            ServerLevel level = (ServerLevel) f.level();
            double each = worthEach(level, f.ownerId(), s);
            int most = Math.max(1, (int) Math.floor(each * keen(f, n) + 1e-9));
            if (keen(f, n) >= 1.0) most = Math.max(most, (int) Math.ceil(each - 1e-9));
            if (!canAfford(f, n, most)) return new Want(n.kind(), 0, n.urgency(), most, "I can't afford " + counted(s, 1) + " just now", null);
            return new Want(n.kind(), qty, n.urgency(), most, n.reason(), n);
        }
        return new Want(kinds.iterator().next(), 0, 0, 0, refusal(f, s, kinds), null);
    }

    /** Has it a real use for this now? */
    public static boolean wants(VillageFolkEntity f, ItemStack s) {
        return want(f, s).yes();
    }

    /** How many of this its pack has room for. */
    static int roomFor(VillageFolkEntity f, ItemStack s) {
        int room = 0;
        for (ItemStack x : f.getInventoryItems()) {
            if (x.isEmpty()) room += s.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(x, s)) room += Math.max(0, x.getMaxStackSize() - x.getCount());
        }
        return room;
    }

    /** The most whoever pays for this need could pay now, coin: its purse, its firm's till, the treasury. */
    static int means(VillageFolkEntity f, Need n) {
        return switch (n.payer()) {
            case OWN -> f.purse();
            case FIRM -> {
                Business b = n.firm() == null || f.ownerId() == null ? null : Businesses.of(f.ownerId(), n.firm());
                yield b == null ? 0 : (int) Math.min(Integer.MAX_VALUE, b.till / 100);
            }
            case TOWN -> f.ownerId() == null ? 0 : Ledger.coins(f.ownerId());
        };
    }

    private static boolean canAfford(VillageFolkEntity f, Need n, int price) {
        return switch (n.payer()) {
            case OWN -> f.purse() >= price;
            case FIRM -> {
                Business b = n.firm() == null || f.ownerId() == null ? null : Businesses.of(f.ownerId(), n.firm());
                yield b != null && b.till >= price * 100L;
            }
            case TOWN -> f.ownerId() != null && Ledger.coins(f.ownerId()) >= price;
        };
    }

    /** Why this folk would not buy this, in its own words. */
    private static String refusal(VillageFolkEntity f, ItemStack s, EnumSet<Kind> kinds) {
        String a = counted(s, 1);
        Kind k = kinds.iterator().next();
        if (kinds.contains(Kind.TACK)) return "what would I do with " + a + "? I've no horse";
        if (kinds.contains(Kind.LIGHT)) {
            UUID village = f.ownerId();
            Homes.Home h = village == null ? null : Homes.homeOf(village, f.getUUID());
            Luxuries.Kind lk = Luxuries.kindOf(s);
            if (h != null && lk != null) {
                int have = 0;
                for (String key : Decor.book(village, h.anchor).keySet()) if (key.startsWith("lux." + lk.key + ".")) have++;
                if (have >= lk.most) return "too many " + lk.key + "s already";
            }
            return h == null ? "I've no home to light" : "my home's lit already";
        }
        if (kinds.contains(Kind.TOOL) || kinds.contains(Kind.WEAPON)) {
            Toolrack.Tool tool = Toolrack.of(f.stationTask());
            if (tool == null || !Toolrack.is(tool, s)) return "that's no tool of my trade";
            if (!f.mayUseTier(s)) return "that's beyond me — I'd not know how to use it";
            return "my own " + tool.word + " is good yet";
        }
        if (kinds.contains(Kind.ARMOUR)) return f.stationTask() == StationTask.GUARD ? "I'm kitted out already" : "I'm no soldier";
        if (kinds.contains(Kind.FOOD)) return "our larder's full";
        if (kinds.contains(Kind.FURNITURE) || kinds.contains(Kind.COMFORT)) return "there's no call or room for " + a + " in my home";
        if (kinds.contains(Kind.MEDICINE)) return "I'm well, thank you";
        if (kinds.contains(Kind.SEED)) return f.stationTask() == StationTask.FARM ? "my furrows are sown" : "I've nowhere to sow it";
        if (kinds.contains(Kind.BOOK)) {
            Persona p = f.persona();
            if (!(p.rolled() && (p.hobby() == Persona.Hobby.READING || p.loves() == Persona.Gift.BOOKS))) return "I'm no great reader";
            return READ.getOrDefault(f.getUUID(), List.of()).contains(title(s)) ? "I've read that one already" : "I've a book on the go — another in a day or two";
        }
        if (kinds.contains(Kind.INPUT) || kinds.contains(Kind.BUILDING)) return "nobody I work for is short of it";
        if (kinds.contains(Kind.FUEL)) return "our woodpile's stacked";
        return "I've no use for " + a + " (" + k.words + ")";
    }

    /** The value of one of this for its use, against another that would do: the work in a tool, the cover in armour, the
     *  bellyful in food; else one for one. For choosing the better value among what is on offer. */
    public static double fit(ItemStack s, Want w) {
        if (w.kind() == null) return 0;
        return switch (w.kind()) {
            case TOOL, WEAPON -> Purchases.toolWork(s) / 1000.0;
            case ARMOUR -> s.getItem() instanceof ArmorItem a ? a.getDefense() * Math.max(0.1, Toolrack.left(s) / 100.0) : 1.0;
            case FOOD -> Purchases.bellyful(s);
            default -> 1.0;
        };
    }

    // ------------------------------------------------------------------ paying

    /** [uses16] The coin for it, out of whoever pays for this need: its own purse, its firm's till, the town's treasury (the
     *  works or the pens' upkeep). False, and nothing paid, if they can't. */
    public static boolean pay(ServerLevel level, VillageFolkEntity f, Want w, int coins, String what) {
        Need n = w.need();
        UUID village = f.ownerId();
        if (n == null || village == null || coins <= 0) return false;
        switch (n.payer()) {
            case OWN -> {
                return f.spend(coins);
            }
            case FIRM -> {
                Business b = n.firm() == null ? null : Businesses.of(village, n.firm());
                if (b == null || b.till < coins * 100L) return false;
                b.till -= coins * 100L;
                b.note(level.getDayTime() / 24000L, "paid " + coins + "c for " + what);
                Businesses.dirty();
                return true;
            }
            case TOWN -> {
                if (Ledger.coins(village) < coins || n.dept() == null) return false;
                long paid = Accounts.debit(level, village, Accounts.Account.dept(n.dept()), coins * 100L, Accounts.Purpose.WORK, what);
                return paid >= coins * 100L;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the using of it

    /** [uses16] The API: one of these, just come by (bought at a player's shop, say), weighed and put to its use; nothing if
     *  it has none (the caller keeps it). */
    public static Used use(VillageFolkEntity f, ItemStack got, String from) {
        Want w = want(f, got);
        if (!w.yes()) return new Used(false, w.reason());
        return use(f, got, w, from);
    }

    /**
     * [uses16] What it bought, put to the use it bought it for: a tool or a blade taken up, armour and clothes put on, food
     * and fuel put by at home, furniture and a light carried home to be set out, a remedy drunk, a book read, seed sown,
     * feed, makings and timber into the stores for the troughs, its firm's bench and the builders. Remembered, and on its
     * card. The stack is used up (what is over goes into its pack, or the stores).
     */
    public static Used use(VillageFolkEntity f, ItemStack got, Want w, String from) {
        if (got.isEmpty() || !(f.level() instanceof ServerLevel level)) return new Used(false, "nothing");
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        Need n = w.need();
        if (v == null || n == null) {
            stash(level, v, f, got);
            return new Used(false, "kept it");
        }
        long day = level.getDayTime() / 24000L;
        String what = counted(got, got.getCount());
        String did;
        switch (n.kind()) {
            case TOOL, WEAPON -> did = takeUp(level, v, f, got);
            case ARMOUR -> did = putOn(level, v, f, got);
            case CLOTHING -> {
                if (Garment.of(got) != null) {
                    Fashion.wear(level, v, f, got.split(1), "bought");
                    did = "put it on";
                } else did = putOn(level, v, f, got);
            }
            case MEDICINE -> did = n.key().equals("remedy") ? takeRemedy(level, f, got) : putBy(level, v, f, got, "put it in the cupboard at home");
            case FOOD -> did = putBy(level, v, f, got, Homes.homeOf(village, f.getUUID()) != null ? "put it by in its larder" : "keeps it to eat");
            case FUEL -> did = putBy(level, v, f, got, "stacked it by its stove");
            case LIGHT, FURNITURE, COMFORT -> did = forHome(level, v, f, got, n, from);
            case BOOK -> did = read(level, f, got, day);
            case SEED -> did = sow(level, v, f, got);
            case SAPLING, AMMO -> {
                stash(level, v, f, got);
                did = n.kind() == Kind.AMMO ? "filled its quiver" : "keeps them to replant what it fells";
            }
            case FEED -> {
                Crafts.store(level, v, got.copy());
                got.setCount(0);
                did = "put it in the stores for the troughs";
            }
            case INPUT -> did = toFirm(level, v, f, got, n);
            case BUILDING -> {
                Crafts.store(level, v, got.copy());
                got.setCount(0);
                did = "took it to the stores for the builders";
            }
            case GIFT, LUXURY, MUSIC -> {
                Homes.keepsake(got, f, Homes.Source.BOUGHT);
                stash(level, v, f, got);
                did = n.kind() == Kind.GIFT ? "keeps it, a treasure of its own" : "keeps it for the pleasure of it";
            }
            default -> {
                stash(level, v, f, got);
                did = "keeps it";
            }
        }
        if (n.kind() == Kind.BOOK) LAST.put(f.getUUID() + "/book", day);
        if (n.kind() == Kind.GIFT) LAST.put(f.getUUID() + "/gift", day);
        if (n.kind() == Kind.LUXURY) LAST.put(f.getUUID() + "/luxury", day);
        String line = "bought " + what + (from.isEmpty() ? "" : " from " + from) + "; " + did;
        Deque<String> log = LOG.computeIfAbsent(f.getUUID(), k -> new ArrayDeque<>());
        log.addFirst("day " + day + ": " + line);
        while (log.size() > 3) log.removeLast();
        f.brain(line);
        f.persona().remember(day, "I " + line.replace("; ", " and ").replace(" its ", " my "), n.urgency() >= 0.7 ? 2 : 1);
        com.mojang.logging.LogUtils.getLogger().info("[MCA-USES] {} {} (for {}: {})", f.displayNameCap(), line, n.kind().words, n.reason());
        forgetTown(village);                                                  // its household's needs, and the town's, looked at again
        return new Used(true, did);
    }

    /** Into its pack; what it can't carry, into the stores. */
    private static void stash(ServerLevel level, @Nullable Villages.Village v, VillageFolkEntity f, ItemStack got) {
        if (got.isEmpty()) return;
        ItemStack left = f.insertItem(got.copy());
        got.setCount(0);
        if (!left.isEmpty() && v != null) Crafts.store(level, v, left);
    }

    /** The household's own, to be put by at home (Housekeeping sees to it). */
    private static String putBy(ServerLevel level, Villages.Village v, VillageFolkEntity f, ItemStack got, String did) {
        Homes.keepsake(got, f, Homes.Source.BOUGHT);
        stash(level, v, f, got);
        return did;
    }

    /** A tool or a blade, taken up: in its hand and its pack, the one it replaces (the worse) to the stores. */
    private static String takeUp(ServerLevel level, Villages.Village v, VillageFolkEntity f, ItemStack got) {
        Toolrack.Tool tool = Toolrack.of(f.stationTask());
        if (tool == null || !Toolrack.is(tool, got)) {                     // a work item (a rope, props), a tool beside its own
            stash(level, v, f, got);
            return "keeps it in its pack for its work";
        }
        ItemStack one = got.split(1);
        String old = "";
        if (tool != null) {
            ItemStack hand = f.getMainHandItem();
            if (Toolrack.is(tool, hand)) {
                old = name(hand);
                Crafts.store(level, v, hand.copy());
                f.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            } else {
                for (ItemStack s : f.getInventoryItems()) {
                    if (Toolrack.is(tool, s) && toolScore(s) < toolScore(one)) {
                        old = name(s);
                        Crafts.store(level, v, s.split(1));
                        break;
                    }
                }
            }
        }
        if (f.getMainHandItem().isEmpty()) f.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, one);
        else stash(level, v, f, one);
        stash(level, v, f, got);
        level.playSound(null, f.blockPosition(), SoundEvents.ARMOR_EQUIP_GENERIC.value(), SoundSource.NEUTRAL, 0.6F, 1.0F);
        return "took it up for its work" + (old.isEmpty() ? "" : ", its old " + old + " to the stores");
    }

    /** Armour or boots, put on; what it had on there to the stores. */
    private static String putOn(ServerLevel level, Villages.Village v, VillageFolkEntity f, ItemStack got) {
        ItemStack one = got.split(1);
        EquipmentSlot slot = one.getItem() instanceof ArmorItem a ? a.getEquipmentSlot() : EquipmentSlot.FEET;
        ItemStack old = f.getItemBySlot(slot);
        if (!old.isEmpty()) Crafts.store(level, v, old.copy());
        f.setItemSlot(slot, one);
        stash(level, v, f, got);
        level.playSound(null, f.blockPosition(), SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.NEUTRAL, 0.6F, 1.0F);
        return "put it on" + (old.isEmpty() ? "" : ", its old " + name(old) + " to the stores");
    }

    /** A remedy, taken there and then: a potion drunk, honey or a carrot eaten; the cold eased, the hurt healed. */
    private static String takeRemedy(ServerLevel level, VillageFolkEntity f, ItemStack got) {
        ItemStack one = got.split(1);
        Health.State s = f.health();
        if (one.getItem() instanceof PotionItem) Health.drink(f, one);
        else f.heal(one.is(Items.GOLDEN_CARROT) || one.is(Items.GOLDEN_APPLE) ? 6.0F : 3.0F);
        if (s.cold > 0) {
            s.cold -= one.getItem() instanceof PotionItem ? Health.SOME : Health.REMEDY;
            if (s.cold <= 0) Health.recovered(level, f, s, level.getDayTime() / 24000L);
        }
        if (one.getItem() instanceof PotionItem || one.is(Items.HONEY_BOTTLE)) {
            ItemStack bottle = new ItemStack(Items.GLASS_BOTTLE);
            if (!f.insertItem(bottle).isEmpty()) Crafts.store(level, Villages.get(f.ownerId()), bottle);
        }
        stash(level, Villages.get(f.ownerId()), f, got);
        level.playSound(null, f.blockPosition(), one.getItem() instanceof PotionItem ? SoundEvents.GENERIC_DRINK : SoundEvents.GENERIC_EAT,
            SoundSource.NEUTRAL, 0.7F, 1.0F);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, f.getX(), f.getY() + 1.0, f.getZ(), 5, 0.3, 0.3, 0.3, 0.0);
        return one.getItem() instanceof PotionItem ? "drank it there and then" : "took it there and then";
    }

    /** A piece for its home: the plan's piece (HomeMaking sets it out where the plan has room), else a comfort (Luxuries
     *  carries it home and sets it out). */
    private static String forHome(ServerLevel level, Villages.Village v, VillageFolkEntity f, ItemStack got, Need n, String from) {
        String said = null;
        if (n.key().startsWith("piece.")) said = HomeMaking.bought(f, got.copyWithCount(1), from.isEmpty() ? "a stall" : from);
        Homes.keepsake(got, f, Homes.Source.BOUGHT);
        String words = n.key().equals("piece.light") || n.key().equals("lux.lantern") ? "to hang in its home"
            : n.kind() == Kind.LIGHT ? "to light its home" : "to set out in its home";
        stash(level, v, f, got);
        if (said != null) FolkTalk.speak(f, said);
        return "carrying it home " + words;
    }

    private static String title(ItemStack s) {
        var wb = s.get(DataComponents.WRITTEN_BOOK_CONTENT);
        return wb == null ? BuiltInRegistries.ITEM.getKey(s.getItem()).toString() : wb.title().raw();
    }

    /** A book, read: it sits a while with it, and remembers it. Kept, to read again. */
    private static String read(ServerLevel level, VillageFolkEntity f, ItemStack got, long day) {
        String t = title(got);
        READ.computeIfAbsent(f.getUUID(), k -> new ArrayList<>()).add(t);
        f.persona().remember(day, "I read \"" + t + "\"", 2);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "\"" + t + "\"! I'll start it tonight.", "A new book — I couldn't wait. What a read!",
            "I read it straight through. Well worth the coin."));
        level.playSound(null, f.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 0.7F, 1.0F);
        Homes.keepsake(got, f, Homes.Source.BOUGHT);
        stash(level, Villages.get(f.ownerId()), f, got);
        return "read it through";
    }

    /** Seed, sown: in the bare furrows near it, a plant a furrow; what is left in its pack for the next. */
    private static String sow(ServerLevel level, Villages.Village v, VillageFolkEntity f, ItemStack got) {
        int sown = 0;
        Block crop = got.getItem() instanceof BlockItem bi ? bi.getBlock() : null;
        if (crop instanceof CropBlock || crop instanceof StemBlock) {
            BlockPos at = f.blockPosition();
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-10, -2, -10), at.offset(10, 1, 10))) {
                if (got.isEmpty()) break;
                if (!(level.getBlockState(p).getBlock() instanceof FarmBlock) || !level.getBlockState(p.above()).isAir()) continue;
                BlockState plant = crop.defaultBlockState();
                if (!plant.canSurvive(level, p.above())) continue;
                level.setBlock(p.above(), plant, 3);
                got.shrink(1);
                sown++;
            }
        }
        if (sown > 0) {
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            level.playSound(null, f.blockPosition(), SoundEvents.CROP_PLANTED, SoundSource.NEUTRAL, 0.7F, 1.0F);
        }
        stash(level, v, f, got);
        return sown > 0 ? "sowed " + sown + (sown == 1 ? " furrow" : " furrows") + " with it" : "keeps it to sow";
    }

    /** Makings for its firm's bench: into the stores, bonded in the firm's name (its own stock: its bench draws on it first,
     *  not paying the market for it again). */
    private static String toFirm(ServerLevel level, Villages.Village v, VillageFolkEntity f, ItemStack got, Need n) {
        Business b = n.firm() == null ? null : Businesses.of(v.id(), n.firm());
        int k = got.getCount();
        Crafts.store(level, v, got.copy());
        got.setCount(0);
        if (b != null) {
            String key = Stockroom.key(n.sample());
            b.stock.computeIfAbsent(key, x -> new Business.Lot()).add(k, Math.round(LAST_PRICE.getOrDefault(f.getUUID(), 0) * 100L));
            Businesses.dirty();
            return "took it to the stores for " + b.name + "'s bench";
        }
        return "took it to the stores";
    }

    /** The coin the last thing it paid for cost (for the firm's books). */
    private static final Map<UUID, Integer> LAST_PRICE = new ConcurrentHashMap<>();

    /** [uses16] What it just paid for the thing it is about to use (the firm's books keep the cost). */
    public static void paid(VillageFolkEntity f, int coins) {
        LAST_PRICE.put(f.getUUID(), coins);
    }

    // ------------------------------------------------------------------ the town's view

    /** [uses16] Who in the town would buy this now and why (the keenest), or why nobody would. */
    public static Verdict judge(ServerLevel level, UUID village, ItemStack s) {
        String key = village + "|" + PlayerStalls.key(s);
        Object[] c = VERDICTS.get(key);
        long t = level.getGameTime();
        if (c != null && t - (long) c[0] < NEEDS_TTL && t >= (long) c[0]) return (Verdict) c[1];
        Verdict v = judgeNow(level, village, s);
        VERDICTS.put(key, new Object[]{ t, v });
        return v;
    }

    private static Verdict judgeNow(ServerLevel level, UUID village, ItemStack s) {
        EnumSet<Kind> kinds = kinds(s);
        if (kinds.isEmpty()) return new Verdict(false, "", "what would anybody here do with " + counted(s, 1) + "?", 0, 0, 0);
        int buyers = 0, qty = 0, most = 0;
        Want best = null;
        VillageFolkEntity bestF = null, broke = null;
        int brokeN = 0;
        Map<String, Integer> why = new HashMap<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive()) continue;
            Want w = want(f, s);
            if (!w.yes()) {
                why.merge(w.reason(), 1, Integer::sum);
                if (w.reason().startsWith("I can't afford")) {             // wanted, but its purse won't run to it
                    brokeN++;
                    if (broke == null) broke = f;
                }
                continue;
            }
            buyers++;
            qty += w.qty();
            most = Math.max(most, w.mostEach());
            if (best == null || w.value() > best.value()) {
                best = w;
                bestF = f;
            }
        }
        if (best == null && broke != null) {
            String who = broke.displayNameCap() + (broke.stationTask() == StationTask.NONE ? "" : ", " + broke.stationTask().name().toLowerCase(Locale.ROOT));
            return new Verdict(false, who, who + " needs it but can't afford it just now" + (brokeN > 1 ? " (and " + (brokeN - 1) + " more)" : "")
                + ": priced lower, it would sell", 0, 0, 0);
        }
        if (best == null) {
            Kind k = kinds.iterator().next();
            if (kinds.contains(Kind.TACK)) k = Kind.TACK;
            String top = k.nobody();
            return new Verdict(false, "", top, 0, 0, 0);
        }
        String who = bestF.displayNameCap() + (bestF.stationTask() == StationTask.NONE ? "" : ", " + bestF.stationTask().name().toLowerCase(Locale.ROOT));
        String for_ = best.need().payer() == Payer.FIRM ? " (for its firm)" : best.need().payer() == Payer.TOWN ? " (for the town)" : "";
        return new Verdict(true, who + for_, best.reason(), buyers, qty, most);
    }

    /** [uses16] The verdict as a tag, for a screen (a stall's row, a player's shop): wanted or not, who and why, how many folk,
     *  how many in all, the most one would pay for one. */
    public static CompoundTag verdictTag(ServerLevel level, UUID village, ItemStack s) {
        Verdict v = judge(level, village, s);
        CompoundTag t = new CompoundTag();
        t.putBoolean("useful", v.yes());
        t.putString("who", v.who());
        t.putString("why", v.why());
        t.putString("line", v.line());
        t.putInt("buyers", v.buyers());
        t.putInt("wantQty", v.qty());
        t.putInt("most", v.mostEach());
        t.putString("uses", kindsWords(s));
        return t;
    }

    /** One line of the town's needs: what kind, one that would do, how many folk, how many in all, the most one would pay,
     *  the keenest's reason, and how pressing. */
    public record TownNeed(Kind kind, ItemStack sample, int folk, int qty, int mostEach, String reason, double urgency, String who) {}

    /** [uses16] What the town's folk, households, firms and departments want now and would pay, most pressing first. */
    @SuppressWarnings("unchecked")
    public static List<TownNeed> townNeeds(ServerLevel level, UUID village) {
        Object[] c = TOWN.get(village);
        long t = level.getGameTime();
        if (c != null && t - (long) c[0] < 200L && t >= (long) c[0]) return (List<TownNeed>) c[1];
        Map<String, TownNeed> by = new LinkedHashMap<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive()) continue;
            for (Need n : needsOf(f)) {
                double each = worthEach(level, village, n.sample());
                int most = Math.max(1, (int) Math.floor(each * keen(f, n) + 1e-9));
                if (keen(f, n) >= 1.0) most = Math.max(most, (int) Math.ceil(each - 1e-9));
                most = Math.min(most, means(f, n));                            // no more than whoever pays has
                String key = n.key().startsWith("piece.seat") ? "piece.seat" : n.key().startsWith("armour.") || n.key().startsWith("input.")
                    ? n.key() : n.key().startsWith("tool") ? "tool." + PlayerStalls.key(n.sample()) : n.key();
                TownNeed was = by.get(key);
                String who = f.displayNameCap();
                if (was == null) by.put(key, new TownNeed(n.kind(), n.sample(), 1, n.qty(), most, n.reason(), n.urgency(), who));
                else by.put(key, new TownNeed(was.kind(), was.sample(), was.folk() + 1, was.qty() + n.qty(), Math.max(was.mostEach(), most),
                    n.urgency() > was.urgency() ? n.reason() : was.reason(), Math.max(was.urgency(), n.urgency()), was.who()));
            }
        }
        List<TownNeed> out = new ArrayList<>(by.values());
        out.sort(Comparator.comparingDouble((TownNeed n) -> -(n.urgency() * (1 + 0.25 * Math.min(8, n.folk())))));
        TOWN.put(village, new Object[]{ t, out });
        return out;
    }

    /** [uses16] The town's needs for a screen (the city books' Shops page, a stall, a player's shop): up to {@code most} rows. */
    public static ListTag needsTag(ServerLevel level, UUID village, int most) {
        ListTag out = new ListTag();
        for (TownNeed n : townNeeds(level, village)) {
            if (out.size() >= most) break;
            CompoundTag t = new CompoundTag();
            t.putString("kind", n.kind().words);
            t.putString("item", BuiltInRegistries.ITEM.getKey(n.sample().getItem()).toString());
            t.putString("name", name(n.sample()));
            t.putInt("folk", n.folk());
            t.putInt("qty", n.qty());
            t.putInt("most", n.mostEach());
            t.putString("why", n.reason());
            t.putString("who", n.who());
            t.putDouble("urgency", n.urgency());
            out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------ turned down, on a stall's books

    /** [uses16] A folk at a stall turned this down as no use to it: on the stall's books (one line a ware, the latest). */
    public static void turnedDown(ServerLevel level, UUID village, UUID owner, String key, VillageFolkEntity f, String reason) {
        LinkedHashMap<String, String[]> book = REFUSED.computeIfAbsent(village + "|" + owner, k -> new LinkedHashMap<>());
        book.remove(key);
        book.put(key, new String[]{ Long.toString(level.getDayTime() / 24000L), f.displayNameCap(), reason });
        while (book.size() > 8) book.remove(book.keySet().iterator().next());
    }

    /** [uses16] What the folk turned down at this stall as no use to them, the latest first, for its screen. */
    public static ListTag refusedTag(UUID village, UUID owner) {
        ListTag out = new ListTag();
        LinkedHashMap<String, String[]> book = REFUSED.get(village + "|" + owner);
        if (book == null) return out;
        List<Map.Entry<String, String[]>> all = new ArrayList<>(book.entrySet());
        for (int i = all.size() - 1; i >= 0; i--) {
            CompoundTag t = new CompoundTag();
            String[] x = all.get(i).getValue();
            t.putString("key", all.get(i).getKey());
            t.putLong("day", Long.parseLong(x[0]));
            t.putString("who", x[1]);
            t.putString("why", x[2]);
            t.putString("what", name(PlayerStalls.sampleOf(all.get(i).getKey())));
            out.add(t);
        }
        return out;
    }

    /** How many things the folk have turned down at this stall as no use (tests). */
    public static int refusedCount(UUID village, UUID owner) {
        LinkedHashMap<String, String[]> book = REFUSED.get(village + "|" + owner);
        return book == null ? 0 : book.size();
    }

    // ------------------------------------------------------------------ what the player reads

    /** [uses16] Its card's line: what it last bought and what it did with it; what it has a use for now. */
    public static String cardLine(VillageFolkEntity f) {
        if (f.isBaby()) return "";
        StringBuilder sb = new StringBuilder();
        Deque<String> log = LOG.get(f.getUUID());
        if (log != null && !log.isEmpty()) sb.append(cap(log.peekFirst())).append('.');
        List<Need> needs = needsOf(f);
        if (!needs.isEmpty()) {
            List<String> w = new ArrayList<>();
            for (Need n : needs) {
                if (w.size() >= 3) break;
                w.add(name(n.sample()) + (n.qty() > 1 ? " ×" + n.qty() : "") + " (" + n.reason() + ")");
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append("Would buy: ").append(String.join("; ", w)).append('.');
        }
        return sb.toString();
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Tests: its tastes set (a reader, a lover of gems...), its persona taken as rolled. */
    public static void tasteForTests(VillageFolkEntity f, Persona.Hobby hobby, Persona.Gift loves) {
        Persona p = f.persona();
        p.hobby = hobby;
        p.loves = loves;
        if (p.quirk.isEmpty()) p.quirk = "a quiet one";
        forget(f);
    }

    /** Tests: the ticks of its cold still to run. */
    public static int coldForTests(VillageFolkEntity f) {
        return f.health().cold;
    }

    /** Tests: the best of its trade's tool it has. */
    public static ItemStack ownToolForTests(VillageFolkEntity f) {
        Toolrack.Tool t = Toolrack.of(f.stationTask());
        return t == null ? ItemStack.EMPTY : ownTool(f, t);
    }

    /** Its last uses, newest first (tests, the card). */
    public static List<String> usesOf(VillageFolkEntity f) {
        Deque<String> log = LOG.get(f.getUUID());
        return log == null ? List.of() : List.copyOf(log);
    }

    /** [uses16] Purchases.decide's check for what it can do without (a treat, a luxury, a thing for its home): false for a
     *  thing nobody could use, or a comfort its home already has as many of as a house takes. */
    static boolean mayBuy(VillageFolkEntity f, ItemStack one) {
        EnumSet<Kind> kinds = kinds(one);
        if (kinds.isEmpty()) return false;
        Luxuries.Kind lk = Luxuries.kindOf(one);
        UUID village = f.ownerId();
        if (lk != null && lk != Luxuries.Kind.PANE && village != null) {
            Homes.Home h = Homes.homeOf(village, f.getUUID());
            if (h != null) {
                int have = 0;
                for (String key : Decor.book(village, h.anchor).keySet()) if (key.startsWith("lux." + lk.key + ".")) have++;
                if (have >= lk.most) return false;
            }
        }
        return true;
    }
}
