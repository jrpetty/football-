package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.Villages;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * [fashion] The tailor's garments: what a folk buys to wear over its trade's clothes, and how each is worn.
 *
 * <p>Every one is a real item of real cloth, made at the tailor's loom (or at a crafting table by a player) by an
 * ordinary recipe (data/mc_assistant/recipe), and dyed by the game's own dyeing (the garment and a dye in a crafting
 * grid, as leather armour is: they are all in the game's dyeable tag but the brooch). Which age makes it follows from
 * what goes into it, as for every item (entity/Tiers); it is written here too, and the tests hold the two together:
 * <ul>
 * <li>Wood Age, of wool and string: the long coat, the shawl, the flat cap, the scarf, and the show's rosette (wool,
 *     paper and string);</li>
 * <li>Stone Age, with leather: the leather jacket, and the felt hat (its band);</li>
 * <li>Iron Age, with gold: the waistcoat (its buttons), the top hat (its buckle) and the brooch (gold and lapis).</li>
 * </ul>
 * A folk wears one of each place at a time (Slot): a coat, jacket, shawl or waistcoat on its body; a hat on its head
 * (off work: at work it wears its trade's); a scarf; a brooch; a rosette it won. How grand a thing is ({@link #rank})
 * is what a folk of each standing runs to: a poor folk's scarf, a wealthy one's top hat.
 */
public enum Garment {
    LONG_COAT("long_coat", Slot.BODY, 1, "long coat", Cloth.WOOL, Villages.Age.WOOD, 3),
    LEATHER_JACKET("leather_jacket", Slot.BODY, 2, "leather jacket", Cloth.LEATHER, Villages.Age.STONE, 2),
    WOOL_SHAWL("wool_shawl", Slot.BODY, 3, "shawl", Cloth.WOOL, Villages.Age.WOOD, 1),
    WAISTCOAT("waistcoat", Slot.BODY, 4, "waistcoat", Cloth.WOOL, Villages.Age.IRON, 3),
    FELT_HAT("felt_hat", Slot.HEAD, 1, "felt hat", Cloth.WOOL, Villages.Age.STONE, 2),
    FLAT_CAP("flat_cap", Slot.HEAD, 2, "flat cap", Cloth.WOOL, Villages.Age.WOOD, 1),
    TOP_HAT("top_hat", Slot.HEAD, 3, "top hat", Cloth.WOOL, Villages.Age.IRON, 4),
    WOOL_SCARF("wool_scarf", Slot.NECK, 1, "scarf", Cloth.WOOL, Villages.Age.WOOD, 0),
    BROOCH("brooch", Slot.PIN, 1, "brooch", Cloth.GOLD, Villages.Age.IRON, 3),
    ROSETTE("rosette", Slot.RIBBON, 1, "rosette", Cloth.WOOL, Villages.Age.WOOD, 0);

    /** Where it is worn: one of each at a time. */
    public enum Slot { BODY, HEAD, NECK, PIN, RIBBON }

    /** What it is made of: what an undyed one looks like, and whether a dye takes. */
    public enum Cloth {
        WOOL(0xE9E1CC), LEATHER(0xA06540), GOLD(-1);

        /** An undyed one's colour (the fleece's, the tanned hide's); -1: never dyed. */
        public final int natural;

        Cloth(int natural) {
            this.natural = natural;
        }
    }

    /** Its item's name (mc_assistant:long_coat). */
    public final String id;
    public final Slot slot;
    /** Which shape of its place it is, for the model (one to seven; nought is none). */
    public final int shape;
    /** "long coat". */
    public final String noun;
    public final Cloth cloth;
    /** The age whose makings it takes (the rule entity/Tiers reads off its recipe gives the same). */
    public final Villages.Age age;
    /** How grand it is, nought to four: who runs to it (the poor a scarf, the wealthy a top hat). */
    public final int rank;

    Garment(String id, Slot slot, int shape, String noun, Cloth cloth, Villages.Age age, int rank) {
        this.id = id;
        this.slot = slot;
        this.shape = shape;
        this.noun = noun;
        this.cloth = cloth;
        this.age = age;
        this.rank = rank;
    }

    /** The colour code of one that is not dyed (its cloth's own colour). */
    public static final int NATURAL = 16;

    public boolean dyeable() {
        return cloth != Cloth.GOLD;
    }

    /** Its item (looked up by name, so this table never waits on the registry). */
    public Item item() {
        Item it = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, id));
        return it == null ? Items.AIR : it;
    }

    /** Which garment this is, or null. */
    @Nullable
    public static Garment of(ItemStack s) {
        if (s.isEmpty()) return null;
        if (s.getItem() instanceof GarmentItem g) return g.garment();
        return null;
    }

    @Nullable
    public static Garment of(Item it) {
        return it instanceof GarmentItem g ? g.garment() : null;
    }

    /** The garment of this shape worn in this place, or null (nought, or none such). */
    @Nullable
    public static Garment byShape(Slot slot, int shape) {
        for (Garment g : values()) if (g.slot == slot && g.shape == shape) return g;
        return null;
    }

    /** The garments a folk can be seen in for each place, plain words: "long coats". */
    public String plural() {
        return noun.endsWith("s") ? noun : noun + "s";
    }

    /** "a long coat", "an ivory scarf" when coloured. */
    public String a(int colour) {
        String w = colour >= 0 && colour < NATURAL ? colourWord(colour) + " " + noun : colour == NATURAL && dyeable() ? "plain " + noun : noun;
        return (w.matches("^[aeiou].*") ? "an " : "a ") + w;
    }

    /** Its age, if this item is one of the garments: the age its makings belong to. */
    @Nullable
    public static Villages.Age ageOf(Item it) {
        Garment g = of(it);
        return g == null ? null : g.age;
    }

    // ------------------------------------------------------------------ colour

    /** The colour of a garment as a dye (0 to 15), NATURAL for an undyed one, -1 for a thing that is not dyeable. */
    public static int colourOf(ItemStack s) {
        Garment g = of(s);
        if (g == null || !g.dyeable()) return -1;
        DyedItemColor c = s.get(DataComponents.DYED_COLOR);
        return c == null ? NATURAL : nearest(c.rgb()).getId();
    }

    /** The dye nearest a colour: a garment a player dyed with two dyes reads as the nearer of the sixteen. */
    public static DyeColor nearest(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        DyeColor best = DyeColor.WHITE;
        long bestD = Long.MAX_VALUE;
        for (DyeColor d : DyeColor.values()) {
            int c = d.getTextureDiffuseColor();
            int dr = ((c >> 16) & 255) - r, dg = ((c >> 8) & 255) - g, db = (c & 255) - b;
            long dist = 2L * dr * dr + 4L * dg * dg + 3L * db * db;
            if (dist < bestD) { bestD = dist; best = d; }
        }
        return best;
    }

    /** One of these, new, dyed by the game's own dyeing with this dye (or left plain for NATURAL). */
    public ItemStack dyed(int colour) {
        ItemStack plain = new ItemStack(item());
        if (colour < 0 || colour >= NATURAL || !dyeable()) return plain;
        ItemStack out = DyedItemColor.applyDyes(plain, List.of(DyeItem.byColor(DyeColor.byId(colour))));
        return out.isEmpty() ? plain : out;
    }

    /** The colour a dye code is drawn in (RGB): a dye's own, or the cloth's when undyed. */
    public int rgb(int colour) {
        if (colour >= 0 && colour < NATURAL) return DyeColor.byId(colour).getTextureDiffuseColor() & 0xFFFFFF;
        return cloth.natural < 0 ? 0xFFFFFF : cloth.natural;
    }

    /**
     * A dye colour as the town says it of a fashion: red is "crimson", light blue "sky blue", cyan "teal". The dye
     * that makes it is still the red dye.
     */
    public static String colourWord(int colour) {
        if (colour == NATURAL) return "undyed";
        if (colour < 0 || colour > 15) return "plain";
        return switch (DyeColor.byId(colour)) {
            case RED -> "crimson";
            case LIGHT_BLUE -> "sky blue";
            case CYAN -> "teal";
            case GRAY -> "grey";
            case LIGHT_GRAY -> "pale grey";
            case LIME -> "lime green";
            default -> DyeColor.byId(colour).getName().replace('_', ' ').toLowerCase(Locale.ROOT);
        };
    }

    /** "Crimson", for the start of a sentence or a name. */
    public static String colourCap(int colour) {
        String w = colourWord(colour);
        return Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }
}
