package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [culture-wonders] The town's players as a company: who they are, how well the town thinks of each, their wardrobe,
 * and the evenings they rehearse.
 *
 * <p><b>The company.</b> The folk who love a story, a tune or a carving (Theatre.player) are the town's players. Each
 * night it plays, an actor's name is made a little: two for the evening, and the night's stars on top (Playbill's
 * review). The town's favourite is whoever stands highest; the books and its card say so, and an actor of name is cast
 * first.
 *
 * <p><b>The wardrobe.</b> The company keeps its own costumes, made once out of the stores and kept for good (a note of
 * the town's, not a chest anybody could empty): wooden swords of two planks and a stick, helmets of five leather and
 * tunics of eight, dyed in the town's colour when the stores have a dye, a flower for a bride, a book for the teller.
 * For a play each actor is dressed out of it for its part (a copy for show, never dropped: Leisure.isProp), and
 * undressed after the bows; a costume left on across a restart, or on a folk the play has done with, comes off.
 *
 * <p><b>Rehearsals.</b> On two evenings of the week that are not the day of rest the cast of the coming play goes to
 * the stage and runs its lines with nobody watching (Theatre: a rehearsal is a play without an audience, tickets or a
 * review). Each run makes the night better; the review says so.
 *
 * <p><b>A player lost.</b> An actor who dies is written into the company's book; the next play is given in its memory,
 * and an understudy takes its part the night it is missing.
 */
@EventBusSubscriber(modid = com.jrpetty.mcassistant.McAssistantMod.MODID)
public final class Troupe {

    private Troupe() {}

    static final String FAME = "troupe.fame", WARDROBE = "troupe.wardrobe", REHEARSED = "troupe.rehearsed", LOST = "troupe.lost";
    static final String COSTUME = "mca_costume";

    // ------------------------------------------------------------------ the company's name

    /** Each actor's name with the town: {uuid, name, fame, plays}. */
    static Map<UUID, int[]> fame(UUID village) {
        Map<UUID, int[]> out = new LinkedHashMap<>();
        for (String[] r : Culture.rows(village, FAME)) {
            if (r.length < 4) continue;
            UUID u = WarBooks.id(r[0]);
            if (u != null) out.put(u, new int[]{ (int) Culture.num(r[2], 0), (int) Culture.num(r[3], 0) });
        }
        return out;
    }

    static String fameName(UUID village, UUID actor) {
        for (String[] r : Culture.rows(village, FAME)) if (r.length >= 2 && r[0].equals(actor.toString())) return r[1];
        return "";
    }

    /** An actor's fame with the town (0 for one never on the stage). */
    public static int fameOf(UUID village, UUID actor) {
        int[] f = fame(village).get(actor);
        return f == null ? 0 : f[0];
    }

    /** Fame added for a night on the stage. */
    static void applaud(UUID village, UUID actor, String name, int add) {
        List<String[]> rows = Culture.rows(village, FAME);
        boolean found = false;
        for (String[] r : rows) {
            if (r.length >= 4 && r[0].equals(actor.toString())) {
                r[1] = name;
                r[2] = Long.toString(Culture.num(r[2], 0) + add);
                r[3] = Long.toString(Culture.num(r[3], 0) + 1);
                found = true;
            }
        }
        if (!found) rows.add(new String[]{ actor.toString(), name, Integer.toString(add), "1" });
        rows.sort(Comparator.comparingLong((String[] r) -> -Culture.num(r.length > 2 ? r[2] : "0", 0)));
        while (rows.size() > 16) rows.remove(rows.size() - 1);
        Culture.rows(village, FAME, rows);
    }

    /** The town's favourite player: {name, fame}, or null with none. */
    @Nullable
    static String[] favourite(UUID village) {
        String[] best = null;
        long most = 0;
        for (String[] r : Culture.rows(village, FAME)) {
            if (r.length < 4) continue;
            long f = Culture.num(r[2], 0);
            if (f > most) { most = f; best = new String[]{ r[1], Long.toString(f) }; }
        }
        return best;
    }

    // ------------------------------------------------------------------ the wardrobe

    /** The wardrobe's pieces. */
    enum Piece {
        SWORD("wooden swords"), HELMET("helmets"), TUNIC("tunics"), FLOWER("flowers"), BOOK("a teller's book");

        final String words;

        Piece(String words) { this.words = words; }
    }

    static Map<Piece, Integer> wardrobe(UUID village) {
        Map<Piece, Integer> out = new LinkedHashMap<>();
        for (Piece p : Piece.values()) out.put(p, 0);
        String n = Ledger.note(village, WARDROBE);
        if (n == null || n.isEmpty()) return out;
        for (String kv : n.split(";")) {
            String[] p = kv.split("=", 2);
            if (p.length < 2) continue;
            try {
                out.put(Piece.valueOf(p[0]), (int) Culture.num(p[1], 0));
            } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    static void wardrobe(UUID village, Map<Piece, Integer> w) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Piece, Integer> e : w.entrySet()) parts.add(e.getKey().name() + "=" + e.getValue());
        Ledger.note(village, WARDROBE, String.join(";", parts));
    }

    /** Is the company's kit dyed in the town's colour? */
    static boolean dyed(UUID village) {
        return "1".equals(Ledger.note(village, WARDROBE + ".dyed"));
    }

    /** What a play wants of the wardrobe: so many of each piece. */
    static Map<Piece, Integer> wants(Playwright.Play p) {
        Map<Piece, Integer> out = new HashMap<>();
        for (Playwright.Part part : p.parts()) {
            switch (part.kit()) {
                case SOLDIER, HERO -> {
                    out.merge(Piece.SWORD, 1, Integer::sum);
                    out.merge(Piece.HELMET, 1, Integer::sum);
                    out.merge(Piece.TUNIC, 1, Integer::sum);
                }
                case FOE -> {
                    out.merge(Piece.SWORD, 1, Integer::sum);
                    out.merge(Piece.HELMET, 1, Integer::sum);
                }
                case BRIDE -> out.merge(Piece.FLOWER, 1, Integer::sum);
                case TELLER -> out.put(Piece.BOOK, 1);
                default -> { }
            }
        }
        return out;
    }

    /**
     * The wardrobe made up for a play out of the stores, piece by piece as far as they run to it: a sword of two planks
     * and a stick (or a wooden sword the stores hold), a helmet of five leather and a tunic of eight (or the stores'
     * own), a small flower, a book. The first made with a dye in the stores are dyed in the town's colour, and the
     * rest after them. Returns what was made, in words ("" for nothing).
     */
    static String stock(ServerLevel level, Villages.Village v, Playwright.Play p) {
        UUID id = v.id();
        Map<Piece, Integer> have = wardrobe(id), want = wants(p);
        List<String> made = new ArrayList<>();
        boolean colour = dyed(id);
        for (Map.Entry<Piece, Integer> w : want.entrySet()) {
            Piece piece = w.getKey();
            int got = 0;
            while (have.get(piece) < w.getValue()) {
                boolean ok = switch (piece) {
                    case SWORD -> TownWork.take(level, v, s -> s.is(Items.WOODEN_SWORD), 1) || twoAndOne(level, v);
                    case HELMET -> TownWork.take(level, v, s -> s.is(Items.LEATHER_HELMET), 1) || TownWork.take(level, v, s -> s.is(Items.LEATHER), 5);
                    case TUNIC -> TownWork.take(level, v, s -> s.is(Items.LEATHER_CHESTPLATE), 1) || TownWork.take(level, v, s -> s.is(Items.LEATHER), 8);
                    case FLOWER -> TownWork.take(level, v, s -> s.is(ItemTags.SMALL_FLOWERS), 1);
                    case BOOK -> TownWork.take(level, v, s -> s.is(Items.BOOK), 1);
                };
                if (!ok) break;
                have.put(piece, have.get(piece) + 1);
                got++;
            }
            if (got > 0) made.add(got + " " + (got == 1 ? single(piece) : piece.words));
        }
        if (!colour && (have.get(Piece.HELMET) > 0 || have.get(Piece.TUNIC) > 0)
                && TownWork.take(level, v, s -> s.getItem() instanceof DyeItem, 1)) {
            Ledger.note(id, WARDROBE + ".dyed", "1");
            made.add("the soldiers' kit dyed in the town's colour");
        }
        if (!made.isEmpty()) wardrobe(id, have);
        return String.join(", ", made);
    }

    private static String single(Piece p) {
        return switch (p) {
            case SWORD -> "wooden sword";
            case HELMET -> "helmet";
            case TUNIC -> "tunic";
            case FLOWER -> "flower";
            case BOOK -> "teller's book";
        };
    }

    /** Two planks and a stick out of the stores, for a wooden sword. */
    private static boolean twoAndOne(ServerLevel level, Villages.Village v) {
        if (Market.stock(level, v.id(), s -> s.is(ItemTags.PLANKS)) < 2 || Market.stock(level, v.id(), s -> s.is(Items.STICK)) < 1) return false;
        if (!TownWork.take(level, v, s -> s.is(ItemTags.PLANKS), 2)) return false;
        if (!TownWork.take(level, v, s -> s.is(Items.STICK), 1)) {
            TownWork.give(level, v, new ItemStack(Items.OAK_PLANKS, 2));
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ dressing

    /** A costume piece for show: the wardrobe's, marked so it is never dropped and comes off after. */
    static ItemStack costume(Item item, String name, int colour) {
        ItemStack s = new ItemStack(item);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("mca_prop", true);
        tag.putBoolean(COSTUME, true);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        s.set(DataComponents.ITEM_NAME, Component.literal(name));
        if (colour >= 0) s.set(DataComponents.DYED_COLOR, new DyedItemColor(colour, false));
        return s;
    }

    static boolean isCostume(ItemStack s) {
        return !s.isEmpty() && s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getBoolean(COSTUME);
    }

    private static final EquipmentSlot[] SLOTS = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND };

    /** On only where the slot is empty: its own armour, its own tool, are never taken off for a costume. */
    private static void wear(VillageFolkEntity f, EquipmentSlot slot, ItemStack s) {
        if (!f.getItemBySlot(slot).isEmpty()) return;
        f.setItemSlot(slot, s);
        if (slot == EquipmentSlot.OFFHAND) f.propInHand = true;
    }

    /**
     * An actor dressed for its part out of the wardrobe, as far as the wardrobe has the pieces: a soldier of the town
     * in a helmet and a tunic of the town's colour with a sword, the enemy in a plain helmet with a sword, the bride
     * with a flower, the teller with a book. Returns how many pieces it put on.
     */
    static int dress(VillageFolkEntity f, UUID village, Playwright.Kit kit, Map<Piece, Integer> lent) {
        Map<Piece, Integer> have = wardrobe(village);
        int colour = dyed(village) ? Villages.colour(village) : -1;
        int on = 0;
        String town = Villages.name(village);
        for (Piece p : pieces(kit)) {
            if (lent.getOrDefault(p, 0) >= have.getOrDefault(p, 0)) continue;     // the wardrobe has no more of it
            ItemStack s = switch (p) {
                case SWORD -> costume(Items.WOODEN_SWORD, "A player's wooden sword", -1);
                case HELMET -> costume(Items.LEATHER_HELMET, kit == Playwright.Kit.FOE ? "The enemy's helmet" : "A soldier's helmet",
                    kit == Playwright.Kit.FOE ? -1 : colour);
                case TUNIC -> costume(Items.LEATHER_CHESTPLATE, "A tunic of " + town, colour);
                case FLOWER -> costume(Items.OXEYE_DAISY, "The bride's flowers", -1);
                case BOOK -> costume(Items.BOOK, "The teller's book", -1);
            };
            EquipmentSlot slot = switch (p) {
                case SWORD, FLOWER -> f.getMainHandItem().isEmpty() ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
                case HELMET -> EquipmentSlot.HEAD;
                case TUNIC -> EquipmentSlot.CHEST;
                case BOOK -> EquipmentSlot.OFFHAND;
            };
            if (!f.getItemBySlot(slot).isEmpty()) continue;
            wear(f, slot, s);
            lent.merge(p, 1, Integer::sum);
            on++;
        }
        return on;
    }

    static List<Piece> pieces(Playwright.Kit kit) {
        return switch (kit) {
            case SOLDIER, HERO -> List.of(Piece.HELMET, Piece.TUNIC, Piece.SWORD);
            case FOE -> List.of(Piece.HELMET, Piece.SWORD);
            case BRIDE -> List.of(Piece.FLOWER);
            case TELLER -> List.of(Piece.BOOK);
            default -> List.of();
        };
    }

    /** Every costume piece off. Returns how many came off. */
    public static int undress(VillageFolkEntity f) {
        int n = 0;
        for (EquipmentSlot s : SLOTS) {
            if (isCostume(f.getItemBySlot(s))) {
                f.setItemSlot(s, ItemStack.EMPTY);
                n++;
            }
        }
        return n;
    }

    /** A costume on a folk the play is done with (or across a restart): off. Cheap; every few ticks for each folk. */
    static void tidy(VillageFolkEntity f) {
        if (f.tickCount % 40 != 7 || Theatre.inCast(f)) return;
        undress(f);
    }

    /** A folk dying in its costume: the costume off first, so nothing of the wardrobe's is dropped; an actor lost is remembered. */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity() instanceof VillageFolkEntity f) || !(f.level() instanceof ServerLevel level)) return;
        undress(f);
        UUID id = f.ownerId();
        if (id == null || f.isShowcase()) return;
        if (fameOf(id, f.getUUID()) > 0 || Theatre.inCast(f)) {
            long day = level.getDayTime() / 24000L;
            Ledger.note(id, LOST, f.displayNameCap() + "\t" + day);
            Theatre.actorLost(level, id, f);
        }
    }

    /** The actor the company lost and has not yet played for, {name, day}, or null. */
    @Nullable
    static String[] lost(UUID village) {
        String n = Ledger.note(village, LOST);
        if (n == null || n.isEmpty()) return null;
        String[] p = n.split("\t", 2);
        return p.length == 2 ? p : null;
    }

    static void lostHonoured(UUID village) {
        Ledger.forget(village, LOST);
    }

    // ------------------------------------------------------------------ rehearsals

    /** How often the coming play has been rehearsed. */
    static int rehearsed(UUID village, String key) {
        String n = Ledger.note(village, REHEARSED);
        if (n == null || !n.startsWith(key + "=")) return 0;
        return (int) Culture.num(n.substring(key.length() + 1), 0);
    }

    static void rehearsedOnce(UUID village, String key) {
        Ledger.note(village, REHEARSED, key + "=" + (rehearsed(village, key) + 1));
    }

    /** Is this evening one of the company's two rehearsal evenings (never the day of rest)? */
    static boolean rehearsalDay(UUID village, long day) {
        if (RestDay.today(village, day)) return false;
        int k = Math.floorMod(village.hashCode(), 7);
        long m = Math.floorMod(day, 7L);
        return m == k || m == Math.floorMod(k + 3, 7);
    }

    // ------------------------------------------------------------------ what the player reads

    /** Its card: its name as an actor. */
    @Nullable
    static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return null;
        int[] me = fame(id).get(f.getUUID());
        if (me == null) return null;
        String[] fav = favourite(id);
        boolean best = fav != null && fav[0].equals(f.displayNameCap());
        return (best ? "the town's favourite player" : "a player of name") + ": " + me[1] + (me[1] == 1 ? " night" : " nights")
            + " on the stage, fame " + me[0];
    }

    /** The books' lines about the company: its stars, its wardrobe. */
    static List<String> lines(UUID village) {
        List<String> out = new ArrayList<>();
        List<String> stars = new ArrayList<>();
        for (String[] r : Culture.rows(village, FAME)) {
            if (r.length >= 4 && stars.size() < 5) stars.add(r[1] + " (fame " + r[2] + ", " + r[3] + " nights)");
        }
        if (!stars.isEmpty()) out.add("the company: " + String.join(", ", stars));
        List<String> kit = new ArrayList<>();
        for (Map.Entry<Piece, Integer> e : wardrobe(village).entrySet()) {
            if (e.getValue() > 0) kit.add(e.getValue() + " " + (e.getValue() == 1 ? single(e.getKey()) : e.getKey().words));
        }
        if (!kit.isEmpty()) out.add("the wardrobe: " + String.join(", ", kit) + (dyed(village) ? ", the soldiers' in the town's colour" : ""));
        String[] lost = lost(village);
        if (lost != null) out.add("the next play is given in memory of " + lost[0]);
        return out;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the wardrobe as "SWORD=1;HELMET=2...". */
    public static String wardrobeForTests(UUID village) {
        return Ledger.note(village, WARDROBE) == null ? "" : Ledger.note(village, WARDROBE);
    }

    /** Tests: is this a costume piece of the company's? */
    public static boolean costumeForTests(ItemStack s) {
        return isCostume(s);
    }
}
