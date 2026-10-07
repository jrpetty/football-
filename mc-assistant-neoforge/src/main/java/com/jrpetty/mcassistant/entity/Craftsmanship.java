package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;

import javax.annotation.Nullable;

/**
 * A maker's hand, as its years at the trade make it. A smith, a tailor or an enchanter learns
 * what it can make a rung at a time, and makes it better the longer it has been at it.
 *
 * <h2>What it can make</h2>
 * <ul>
 * <li><b>The smith.</b> From the first day, iron tools and blades, shears and buckets; iron
 *     helmets and boots at level 5, the chestplate and leggings at 10; shields and crossbows
 *     at 15; diamond tools and diamond armour at 25 (only with the diamonds to hand);
 *     netherite at 40.</li>
 * <li><b>The tailor.</b> Boots and caps of plain leather, beds and rugs from the first day;
 *     jerkins and leggings at 5; clothes in the village's colour, and banners on the loom, at
 *     10; banners with a border and a stripe woven in at 25.</li>
 * <li><b>The enchanter.</b> The first rank of enchantment on iron things and bows; the
 *     second rank, and diamond things, at 10; the third at 25 (the bookshelves still have
 *     their say, as they do for a player); netherite at 25; and from 30 its work is bound
 *     to last (Unbreaking one better).</li>
 * </ul>
 *
 * <h2>How well</h2>
 * What it makes comes out as good as its hand: a beginner's tools wear through sooner than
 * they should (about a seventh fewer uses in them), sound work from level 5, a sixth
 * longer-lasting from 15, a third from 25, and from 40 half as long again and tempered
 * (Unbreaking, and an edge or a good fit with it). Each piece carries its maker's mark and
 * how good it is, and sells for as much more (or less) at the village's counters. A player's
 * order gets the hand of whoever took it, and an order above that hand is turned down.
 *
 * <p>[guard-kit] <b>The watch's kit first.</b> The town's armour, blades and shields for its
 * watch wait on nobody's years (WatchKit.forTheWatch): the smith and the tailor make them once
 * the age has come to them, whatever their level, and what is beyond their hand comes out an
 * apprentice's work. A real town's smith was at level five or six after weeks at the anvil.
 */
public final class Craftsmanship {

    private Craftsmanship() {}

    /** Where the mark is kept on a thing. */
    static final String MARK = "mca_made";

    /** How good a piece is: what the maker's hand makes of it. */
    public enum Grade {
        ROUGH("an apprentice's work", 0.85, 0.85),
        PLAIN("sound work", 1.0, 1.0),
        GOOD("good work", 1.17, 1.15),
        FINE("fine work", 1.33, 1.35),
        MASTER("a master's work", 1.5, 1.6);

        /** In a few words, for the mark. */
        public final String words;
        /** How long it lasts, against the usual. */
        public final double lasts;
        /** What it fetches, against the usual. */
        public final double worth;

        Grade(String words, double lasts, double worth) {
            this.words = words;
            this.lasts = lasts;
            this.worth = worth;
        }
    }

    /** The grade a maker of this level turns out. */
    public static Grade grade(int level) {
        return level >= 40 ? Grade.MASTER : level >= 25 ? Grade.FINE : level >= 15 ? Grade.GOOD
            : level >= 5 ? Grade.PLAIN : Grade.ROUGH;
    }

    // ------------------------------------------------------------------ the ladders

    private static boolean armour(String path) {
        return path.endsWith("_helmet") || path.endsWith("_chestplate") || path.endsWith("_leggings") || path.endsWith("_boots");
    }

    /** The level a maker needs to make this (nought: anybody at the trade). */
    public static int rung(Item item) {
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        // The smith's.
        if (path.startsWith("netherite_")) return 40;
        if (path.startsWith("diamond_")) return 25;              // [guard-kit] diamond armour with the diamond tools
        if (path.startsWith("chainmail_") || item == Items.SHIELD || item == Items.CROSSBOW) return 15;
        if (path.equals("reinforced_pickaxe")) return Lessons.MASTER;   // [player-civic] a master smith's own (TradeGoods)
        if (item == Items.ANVIL) return 20;
        if ((path.startsWith("iron_") || path.startsWith("golden_")) && armour(path)) {
            return path.endsWith("_helmet") || path.endsWith("_boots") ? 5 : 10;
        }
        // The tailor's.
        if (path.startsWith("leather_")) {
            if (path.equals("leather_horse_armor")) return 15;
            return path.endsWith("_chestplate") || path.endsWith("_leggings") ? 5 : 0;
        }
        if (item instanceof BannerItem) return 10;
        // The enchanter's.
        if (item == Items.ENCHANTING_TABLE) return 20;
        return 0;
    }

    /** Can a maker of this level make this? */
    public static boolean canMake(int level, Item item) {
        return level >= rung(item);
    }

    /** The rank of enchantment an enchanter of this level can lay (the bookshelves may hold it lower). */
    public static int enchantTier(int level) {
        return level >= 25 ? 3 : level >= 10 ? 2 : 1;
    }

    /** Can an enchanter of this level work this thing? Diamond from 10, netherite from 25. */
    public static boolean canEnchant(int level, ItemStack s) {
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (path.startsWith("netherite_")) return level >= 25;
        if (path.startsWith("diamond_")) return level >= 10;
        return true;
    }

    // ------------------------------------------------------------------ the work

    /**
     * A thing fresh from a maker's hands: lasting as long as its hand makes it, a master's piece
     * tempered, and the maker's mark on it. What comes by the stack (rugs, string, arrows) is no
     * one's work in particular and is left as it is.
     */
    public static ItemStack finish(@Nullable ServerLevel level, ItemStack s, int skill, String maker) {
        if (s.isEmpty() || s.getMaxStackSize() > 1) return s;
        Grade g = grade(skill);
        if (s.isDamageableItem()) {
            int usual = s.getMaxDamage();
            int lasts = Math.max(1, (int) Math.round(usual * g.lasts));
            if (lasts != usual) s.set(DataComponents.MAX_DAMAGE, lasts);
            if (g == Grade.MASTER && level != null) temper(level, s);
        }
        CompoundTag mark = new CompoundTag();
        mark.putInt("grade", g.ordinal());
        mark.putString("by", maker);
        CustomData.update(DataComponents.CUSTOM_DATA, s, tag -> tag.put(MARK, mark));
        ItemLore lore = s.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        s.set(DataComponents.LORE, lore.withLineAdded(
            Component.literal(capital(g.words) + ", by " + maker).withStyle(ChatFormatting.GRAY)));
        return s;
    }

    /** A master's piece: tempered (Unbreaking), and an edge on a tool or a blade, a good fit on armour. */
    private static void temper(ServerLevel level, ItemStack s) {
        var reg = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        reg.getHolder(Enchantments.UNBREAKING).ifPresent(h -> s.enchant(h, 1));
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        ResourceKey<Enchantment> edge = path.endsWith("_sword") ? Enchantments.SHARPNESS
            : path.endsWith("_pickaxe") || path.endsWith("_axe") || path.endsWith("_shovel") || path.endsWith("_hoe") ? Enchantments.EFFICIENCY
            : armour(path) ? Enchantments.PROTECTION : null;
        if (edge != null) reg.getHolder(edge).ifPresent(h -> s.enchant(h, 1));
    }

    /** A banner woven by a hand of 25 and more: a border in it, and from 40 a stripe down the middle. */
    public static void weave(ServerLevel level, ItemStack banner, int skill) {
        if (skill < 25 || !(banner.getItem() instanceof BannerItem b)) return;
        DyeColor ink = b.getColor() == DyeColor.BLACK ? DyeColor.WHITE : DyeColor.BLACK;
        var reg = level.registryAccess().registryOrThrow(Registries.BANNER_PATTERN);
        BannerPatternLayers.Builder layers = new BannerPatternLayers.Builder();
        reg.getHolder(BannerPatterns.BORDER).ifPresent(h -> layers.add(h, ink));
        if (skill >= 40) reg.getHolder(BannerPatterns.STRIPE_CENTER).ifPresent(h -> layers.add(h, ink));
        banner.set(DataComponents.BANNER_PATTERNS, layers.build());
    }

    /** How good a piece is, by its mark; null for a thing nobody here made. */
    @Nullable
    public static Grade gradeOf(ItemStack s) {
        CustomData data = s.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(MARK)) return null;
        int g = data.copyTag().getCompound(MARK).getInt("grade");
        Grade[] all = Grade.values();
        return g >= 0 && g < all.length ? all[g] : null;
    }

    /** What a piece fetches against the usual, by its maker's hand (one for an unmarked thing). */
    public static double worth(ItemStack s) {
        Grade g = gradeOf(s);
        return g == null ? 1.0 : g.worth;
    }

    // ------------------------------------------------------------------ telling

    /** Its own bench, for its own sayings. */
    private static String bench(StationTask t) {
        return switch (t) {
            case SMITH -> "the anvil";
            case TAILOR -> "the loom";
            case ENCHANT -> "the table";
            case SMELT -> "the forge";
            case SHOP -> "the counter";
            default -> "the bench";
        };
    }

    /** A player asked for something above its hand. */
    public static String beyond(VillageFolkEntity f, Item want) {
        int lv = f.veteranLevel(), needs = rung(want);
        String what = new ItemStack(want).getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
        return "A " + what + "? That's beyond me yet — it's level " + needs + " work, and I'm level " + lv
            + ". Ask me again when I've more years at " + bench(f.stationTask()) + ".";
    }

    /** How long its things last, in a few words, for its talk. */
    private static String lasting(Grade g, String things, String bench) {
        return switch (g) {
            case ROUGH -> "my " + things + " wear through sooner than a master's — I'm still learning";
            case PLAIN -> "sound work, no more";
            case GOOD -> "my " + things + " last a sixth longer than most";
            case FINE -> "my " + things + " last a third longer than most";
            case MASTER -> "my " + things + " last half as long again, and come off " + bench + " tempered";
        };
    }

    /**
     * What a maker says of its own hand, asked about its trade: what it can make now, how well,
     * and what comes next. Empty for a trade that makes nothing by hand.
     */
    public static String line(VillageFolkEntity f) {
        StationTask t = f.stationTask();
        int lv = f.veteranLevel();
        Grade g = grade(lv);
        return switch (t) {
            case SMITH -> {
                String can = lv >= 40 ? "anything a forge can make, diamond and all"
                    : lv >= 25 ? "diamond tools and diamond armour, given the diamonds, and iron of every kind"
                    : lv >= 15 ? "shields and crossbows, and a full suit of iron"
                    : lv >= 10 ? "iron tools and blades, and a full suit of iron"
                    : lv >= 5 ? "iron tools and blades, and iron helmets and boots"
                    : "iron tools and blades";
                String next = lv >= 40 ? "" : lv >= 25 ? " A master's hand comes at 40."
                    : lv >= 15 ? " Diamond comes at 25." : lv >= 10 ? " Shields come at 15." : lv >= 5 ? " Chestplates come at 10."
                    : " Helmets and boots come at 5.";
                // [guard-kit] The watch's kit it makes whatever its years.
                String watch = lv < 25 ? " The watch's armour I make all the same, as well as my hand allows." : "";
                yield "I can forge " + can + "; " + lasting(g, "picks", "the anvil") + "." + next + watch;
            }
            case TAILOR -> {
                String can = lv >= 25 ? "banners with a border woven in, clothes in the village's colour, beds and rugs"
                    : lv >= 10 ? "banners on the loom, clothes in the village's colour, beds and rugs"
                    : lv >= 5 ? "leather jerkins and leggings, boots, beds and rugs"
                    : "boots and caps of plain leather, beds and rugs";
                String next = lv >= 25 ? "" : lv >= 10 ? " Patterned banners come at 25." : lv >= 5 ? " Dyed clothes and banners come at 10."
                    : " Jerkins come at 5.";
                String watch = lv < 5 ? " The watch's leather I cut all the same, as well as my hand allows." : "";   // [guard-kit]
                yield "I can make " + can + "; " + lasting(g, "leathers", "the loom") + "." + next + watch;
            }
            case ENCHANT -> {
                int tier = enchantTier(lv);
                String rank = tier == 3 ? "the third rank" : tier == 2 ? "the second rank" : "the first rank";
                String on = lv >= 25 ? "on anything, netherite too" : lv >= 10 ? "on iron and diamond things" : "on iron things and bows, not diamond yet";
                String next = lv >= 30 ? "" : lv >= 25 ? " My work will be bound to last at 30." : lv >= 10 ? " The third rank comes at 25."
                    : " The second rank, and diamond, come at 10.";
                yield "I can lay enchantments of " + rank + " " + on + ", the bookshelves willing"
                    + (lv >= 30 ? ", and bind them to last" : "") + "." + next;
            }
            default -> "";
        };
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
