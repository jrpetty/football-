package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.item.Garment;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * [fashion] One folk's style: its own two colours, what it wears of its own, and how it stands with the town's
 * fashion. Kept with the folk (VillageFolkEntity's save), and packed into one number for the client to draw
 * (client/FashionLayer).
 * <ul>
 * <li><b>Its colours.</b> Its main colour is the one its trade's dyed cloth is in (the farmer's neckerchief, the
 *     tailor's waistcoat, the storekeeper's), at first its favourite (Decor.colour); its accent is the second, on
 *     the trimmings of whatever it wears: a coat's cuffs and lapels, a hat's band, a scarf's stripes.</li>
 * <li><b>What it wears.</b> Real things it owns, bought, given or won, one in each place (Garment.Slot): its coat,
 *     jacket, shawl or waistcoat, its hat (put on off work, its trade's at work), its scarf, a brooch, a rosette, and
 *     a feather in a felt hat's band. Nothing here was made from nothing: each came out of the stores, a shop, a
 *     friend's back or a player's hand.</li>
 * <li><b>The fashion.</b> How far it has come round to the season's look (its pull, Fashion.spread), the garment it
 *     wants in the season's colour and whether the tailor has it on order, and when it took the look up.</li>
 * </ul>
 */
public final class Style {

    /** Its own colours (dye ids), -1 until chosen. */
    int main = -1, accent = -1;
    /** What it wears, by Garment.Slot. */
    final ItemStack[] worn = { ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY };
    /** A feather (the game's own, out of the stores) in its felt hat's band. */
    ItemStack feather = ItemStack.EMPTY;
    /** The day it last put something new on, and what, in words ("a crimson scarf"). */
    long newOn = -100;
    String newWhat = "";
    /** What it wants for the season's look, in which colour, since when, and whether the tailor has it on order. */
    @Nullable Garment want;
    int wantColour = -1;
    long wantSince = -1;
    boolean ordered;
    /** How far it has come round to the season's look (adds up day by day: at one, it wants it), and which look. */
    double pull;
    String pullFor = "";
    /** The season's look it took up (the trend's key), and the day. */
    String followed = "";
    long followedOn = -1;
    /** The rosette's festival and year, in words ("the May dance, year 2"). */
    String rosette = "";
    /** Off work, at a gathering or on its rest day: its own hat on (not saved: looked at every few seconds). */
    boolean dressed;
    /** The day it last looked for a feather for its hat (not saved). */
    long featherTry = -1;

    public int main() { return main; }
    public int accent() { return accent; }
    public boolean rolled() { return main >= 0; }

    public ItemStack worn(Garment.Slot slot) { return worn[slot.ordinal()]; }

    /** What it wears in this place, or null. */
    @Nullable
    public Garment garment(Garment.Slot slot) { return Garment.of(worn[slot.ordinal()]); }

    /** The colour of what it wears in this place (Garment.colourOf), or -1 for nothing. */
    public int colour(Garment.Slot slot) {
        ItemStack s = worn[slot.ordinal()];
        return s.isEmpty() ? -1 : Garment.colourOf(s);
    }

    /** Does it wear anything of this colour? */
    public boolean wears(int colour) {
        if (colour < 0) return false;
        for (Garment.Slot slot : Garment.Slot.values()) if (slot != Garment.Slot.RIBBON && colour(slot) == colour) return true;
        return false;
    }

    /** How many things of its own it wears. */
    public int count() {
        int n = 0;
        for (ItemStack s : worn) if (!s.isEmpty()) n++;
        return n + (feather.isEmpty() ? 0 : 1);
    }

    @Nullable public Garment wants() { return want; }
    public int wantColour() { return wantColour; }
    public boolean ordered() { return ordered; }

    // ------------------------------------------------------------------ for the client

    /** No dye: a colour field's empty value (five bits). */
    static final int NONE = 31;

    /**
     * Everything the client draws, in one number: its two colours, then each place's shape and colour, a feather,
     * whether its hat is on, and whether it is in the season's fashion.
     * <pre>
     *  0-3 main   4-7 accent   8 has colours
     *  9-11 body shape   12-16 body colour      17-19 hat shape   20-24 hat colour
     *  25-29 scarf colour (31: none)   30 brooch   31-35 rosette colour (31: none)
     *  36 feather   37 hat on   38 in fashion
     * </pre>
     */
    long pack(boolean inFashion) {
        long p = 0;
        if (main >= 0) p |= (main & 15L) | ((Math.max(0, accent) & 15L) << 4) | (1L << 8);
        Garment body = garment(Garment.Slot.BODY), hat = garment(Garment.Slot.HEAD);
        if (body != null) p |= ((long) body.shape << 9) | ((long) code(colour(Garment.Slot.BODY)) << 12);
        if (hat != null) p |= ((long) hat.shape << 17) | ((long) code(colour(Garment.Slot.HEAD)) << 20);
        p |= (long) (worn[Garment.Slot.NECK.ordinal()].isEmpty() ? NONE : code(colour(Garment.Slot.NECK))) << 25;
        if (!worn[Garment.Slot.PIN.ordinal()].isEmpty()) p |= 1L << 30;
        p |= (long) (worn[Garment.Slot.RIBBON.ordinal()].isEmpty() ? NONE : code(colour(Garment.Slot.RIBBON))) << 31;
        if (!feather.isEmpty() && hat == Garment.FELT_HAT) p |= 1L << 36;
        if (dressed) p |= 1L << 37;
        if (inFashion) p |= 1L << 38;
        return p;
    }

    private static int code(int colour) {
        return colour < 0 ? Garment.NATURAL : Math.min(colour, Garment.NATURAL);
    }

    // The client's reading of the number (client/FashionLayer).
    public static boolean hasColours(long p) { return (p >> 8 & 1L) != 0; }
    public static int mainOf(long p) { return (int) (p & 15L); }
    public static int accentOf(long p) { return (int) (p >> 4 & 15L); }
    public static int bodyShape(long p) { return (int) (p >> 9 & 7L); }
    public static int bodyColour(long p) { return (int) (p >> 12 & 31L); }
    public static int hatShape(long p) { return (int) (p >> 17 & 7L); }
    public static int hatColour(long p) { return (int) (p >> 20 & 31L); }
    public static int scarfColour(long p) { return (int) (p >> 25 & 31L); }
    public static boolean brooch(long p) { return (p >> 30 & 1L) != 0; }
    public static int rosetteColour(long p) { return (int) (p >> 31 & 31L); }
    public static boolean feather(long p) { return (p >> 36 & 1L) != 0; }
    public static boolean hatOn(long p) { return (p >> 37 & 1L) != 0; }
    public static boolean inFashion(long p) { return (p >> 38 & 1L) != 0; }
    /** "None" in a colour field. */
    public static boolean none(int colourField) { return colourField == NONE; }

    // ------------------------------------------------------------------ kept with the folk

    CompoundTag save(HolderLookup.Provider reg) {
        CompoundTag t = new CompoundTag();
        t.putInt("main", main);
        t.putInt("accent", accent);
        for (Garment.Slot slot : Garment.Slot.values()) {
            ItemStack s = worn[slot.ordinal()];
            if (!s.isEmpty()) t.put(slot.name(), s.save(reg));
        }
        if (!feather.isEmpty()) t.put("feather", feather.save(reg));
        t.putLong("newOn", newOn);
        t.putString("newWhat", newWhat);
        if (want != null) {
            t.putString("want", want.name());
            t.putInt("wantColour", wantColour);
            t.putLong("wantSince", wantSince);
            t.putBoolean("ordered", ordered);
        }
        t.putDouble("pull", pull);
        t.putString("pullFor", pullFor);
        t.putString("followed", followed);
        t.putLong("followedOn", followedOn);
        t.putString("rosette", rosette);
        return t;
    }

    void load(CompoundTag t, HolderLookup.Provider reg) {
        main = t.contains("main") ? t.getInt("main") : -1;
        accent = t.contains("accent") ? t.getInt("accent") : -1;
        for (Garment.Slot slot : Garment.Slot.values()) {
            worn[slot.ordinal()] = t.contains(slot.name(), Tag.TAG_COMPOUND)
                ? ItemStack.parse(reg, t.getCompound(slot.name())).orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
        }
        feather = t.contains("feather", Tag.TAG_COMPOUND) ? ItemStack.parse(reg, t.getCompound("feather")).orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
        newOn = t.contains("newOn") ? t.getLong("newOn") : -100;
        newWhat = t.getString("newWhat");
        want = null;
        if (t.contains("want")) {
            try {
                want = Garment.valueOf(t.getString("want"));
            } catch (IllegalArgumentException e) {
                want = null;
            }
            wantColour = t.getInt("wantColour");
            wantSince = t.getLong("wantSince");
            ordered = t.getBoolean("ordered");
        }
        pull = t.getDouble("pull");
        pullFor = t.getString("pullFor");
        followed = t.getString("followed");
        followedOn = t.contains("followedOn") ? t.getLong("followedOn") : -1;
        rosette = t.getString("rosette");
    }
}
