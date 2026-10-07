package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paintings [batchD] (Culture).
 *
 * <p>A folk with an artist's hands (a whittler) or an eye for colour (a gardener) paints of an evening, one
 * evening in three, instead of its usual pastime: it takes a painting's makings, eight sticks and a wool, as
 * a player's recipe has them, out of its own pack or else the town's stores (no makings, no painting), sets up
 * by its door and works at it a while, brush in hand, the wool's colour flecking off it, until it is done. It
 * names the picture after what it painted — the well at dusk, the town from the hill, a portrait of its
 * sweetheart, the sea, the new smithy — signs it, and the town buys it off it for the price list's price, out
 * of the treasury into its purse, for the stores. From the stores it is sold at the shop like any painting
 * (and a comfortable folk buys one for its wall: Luxuries); and the town's works hang two in each of its
 * public rooms, the hall, the museum and the tavern, on a wall where one fits.
 */
public final class Paintings {

    private Paintings() {}

    /** The painting evening: after supper, and not too late. */
    static final long FROM = 12800L, TO = 16500L;
    /** Strokes to a picture, one every two seconds. */
    static final int STROKES = 15;
    /** Pictures in each public room. */
    static final int EACH = 2;
    /** The tag a picture of the town's own carries: its title, its painter and its day. */
    static final String MARK = "mca_painted";

    /** A painter's evening at its easel: the day, where, how far on, what of, and whether it had the makings. */
    static final class Easel {
        final long day;
        @Nullable BlockPos spot;
        int strokes;
        long lastStroke;
        boolean makings, none, done;
        String title = "";
        ItemStack wool = ItemStack.EMPTY;

        Easel(long day) { this.day = day; }
    }

    private static final Map<UUID, Easel> EASELS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> DUE = new ConcurrentHashMap<>();

    static void resetForTests() {
        EASELS.clear();
        DUE.clear();
    }

    /** An artist: grown, a whittler or a gardener. */
    static boolean artist(VillageFolkEntity f) {
        if (f.isBaby() || !f.persona().rolled()) return false;
        Persona.Hobby h = f.persona().hobby();
        return h == Persona.Hobby.WHITTLING || h == Persona.Hobby.GARDENING;
    }

    /** Is tonight one of its painting evenings (one in three)? */
    static boolean tonight(VillageFolkEntity f, long day) {
        return Math.floorMod((int) (day * 13L) + f.getUUID().hashCode(), 3) == 0;
    }

    // ------------------------------------------------------------------ at the easel

    /** A painter's evening: the makings, its spot by its door, a stroke at a time, and the picture into the stores. */
    static boolean easel(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        if (t < FROM || t >= TO || !artist(f) || !tonight(f, day) || Assemblies.attending(f)) return false;
        return paint(f, level, v, day, false);
    }

    private static boolean paint(VillageFolkEntity f, ServerLevel level, Villages.Village v, long day, boolean force) {
        Easel e = EASELS.get(f.getUUID());
        if (e == null || e.day != day) {
            e = new Easel(day);
            EASELS.put(f.getUUID(), e);
        }
        if (e.done || e.none) return false;
        if (!e.makings) {
            if (!makings(f, level, v, e)) {
                e.none = true;
                return false;
            }
            e.makings = true;
            e.title = title(f, v.id(), day);
        }
        if (e.spot == null) e.spot = spot(f, v);
        Culture.mark(f, Culture.Role.PAINTER, "painting “" + e.title + "”");
        if (!force && !Culture.arrive(f, e.spot, 1.2, 0.8)) return true;
        Culture.prop(f, Items.BRUSH);
        long now = level.getGameTime();
        if (!force && now - e.lastStroke < 40L) return true;
        e.lastStroke = now;
        e.strokes++;
        RandomSource r = f.getRandom();
        f.swing(net.minecraft.world.InteractionHand.OFF_HAND);
        f.getLookControl().setLookAt(f.getX() + f.getLookAngle().x, f.getEyeY() - 0.2, f.getZ() + f.getLookAngle().z);
        if (!e.wool.isEmpty()) {
            level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, e.wool), f.getX(), f.getEyeY() - 0.3, f.getZ(), 3, 0.2, 0.1, 0.2, 0.02);
        }
        if (r.nextInt(5) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(r, "A touch more blue...", "Hold still, sky.", "There — the light's just right.",
                "Hmm. Is that a cow or a cart?", "Nearly..."));
        }
        if (e.strokes >= STROKES) finish(level, v, f, e, day);
        return true;
    }

    /** Eight sticks and a wool: out of its own pack, else the town's stores. False, and nothing taken, with neither. */
    private static boolean makings(VillageFolkEntity f, ServerLevel level, Villages.Village v, Easel e) {
        boolean ownSticks = f.countCarried(s -> s.is(Items.STICK)) >= 8;
        boolean ownWool = f.countCarried(s -> s.is(ItemTags.WOOL)) >= 1;
        if (!ownSticks && Crafts.stock(level, v, s -> s.is(Items.STICK)) < 8) return false;
        if (!ownWool && Crafts.stock(level, v, s -> s.is(ItemTags.WOOL)) < 1) return false;
        ItemStack wool = ItemStack.EMPTY;
        if (ownWool) {
            for (ItemStack s : f.getInventoryItems()) if (s.is(ItemTags.WOOL)) { wool = s.copyWithCount(1); break; }
            f.removeMatching(s -> s.is(ItemTags.WOOL), 1);
        } else {
            wool = Crafts.takeOne(level, v, s -> s.is(ItemTags.WOOL));
            if (wool.isEmpty()) return false;
        }
        boolean sticks = ownSticks ? f.removeMatching(s -> s.is(Items.STICK), 8) == 8 : Crafts.take(level, v, s -> s.is(Items.STICK), 8);
        if (!sticks) {                                                 // the stores changed under it: the wool put back
            if (ownWool) {
                ItemStack left = f.insertItem(wool);
                if (!left.isEmpty()) Crafts.store(level, v, left);
            } else {
                Crafts.store(level, v, wool);
            }
            return false;
        }
        e.wool = wool;
        return true;
    }

    /** Where it paints: by its own door, else at the heart of the town. */
    private static BlockPos spot(VillageFolkEntity f, Villages.Village v) {
        BlockPos home = f.bedPos() != null ? f.bedPos() : v.centre();
        int h = f.getUUID().hashCode();
        BlockPos s = f.surfaceAt(home.getX() + 3 + Math.floorMod(h, 3), home.getZ() + 3 - Math.floorMod(h >> 3, 3));
        return s != null ? s : v.centre();
    }

    /** What it paints: something of the town's, its land's, or somebody dear to it. */
    static String title(VillageFolkEntity f, UUID village, long day) {
        String town = Villages.name(village);
        List<String> subjects = new ArrayList<>();
        subjects.add(town + " from the Hill");
        subjects.add("Night over " + town);
        subjects.add("Still Life with Bread");
        if (Villages.hasBuilt(village, "well")) subjects.add("The Well at Dusk");
        if (Villages.hasBuilt(village, "market")) subjects.add("Market Day on the Square");
        if (Villages.hasBuilt(village, "tavern")) subjects.add("An Evening at the Tavern");
        String land = switch (Homeland.of(village)) {
            case COAST -> "The Sea at " + town;
            case RIVER -> "The River at Evening";
            case FOREST, TAIGA, JUNGLE -> "In the Woods";
            case MOUNTAIN -> "The Mountain";
            case SNOW -> "Snow on the Roofs";
            case DESERT -> "The Desert at Noon";
            case SWAMP -> "Mist on the Fen";
            case BADLANDS -> "The Red Hills";
            case MEADOW -> "Blossom Time";
            default -> "Harvest Time";
        };
        subjects.add(land);
        String dear = f.life().partner() != null ? f.life().partnerName() : null;
        if (dear == null || dear.isEmpty()) {
            for (Social.Bond b : f.life().friends()) if (b.name != null && !b.name.isEmpty()) { dear = b.name; break; }
        }
        if (dear != null && !dear.isEmpty()) subjects.add("Portrait of " + dear);
        List<Ledger.Building> built = Ledger.buildings(village);
        if (!built.isEmpty()) {
            String last = Villages.spoken(built.get(built.size() - 1).structure());
            if (last.startsWith("the ")) subjects.add("The New " + Culture.capital(last.substring(4)));
        }
        return subjects.get(Math.floorMod((int) (day * 7L) + f.getUUID().hashCode(), subjects.size()));
    }

    /** The picture done: signed, into the stores, bought by the town off its painter; remembered, and told. */
    private static void finish(ServerLevel level, Villages.Village v, VillageFolkEntity f, Easel e, long day) {
        e.done = true;
        UUID id = v.id();
        ItemStack p = picture(e.title, f.displayNameCap(), day, Villages.name(id));
        Crafts.store(level, v, p);
        int price = Math.max(1, (int) Math.round(Prices.each(Items.PAINTING)));
        boolean paid = Ledger.coins(id) >= price;
        if (paid) {
            Ledger.addCoins(id, -price);
            f.earn(price);
        }
        f.persona().remember(day, "I painted “" + e.title + "”", 3);
        f.persona().enjoyedHobby(day);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There — finished!", "Done. I'll call it “" + e.title + "”.",
            "Not bad, if I say so myself."));
        level.playSound(null, f.blockPosition(), SoundEvents.VILLAGER_WORK_CARTOGRAPHER, net.minecraft.sounds.SoundSource.NEUTRAL, 0.8F, 1.0F);
        List<String[]> rows = Culture.rows(id, "culture.paintings");
        rows.add(new String[]{ Long.toString(day), e.title, f.displayNameCap(), paid ? Integer.toString(price) : "0" });
        while (rows.size() > 16) rows.remove(0);
        Culture.rows(id, "culture.paintings", rows);
        long n = Culture.num(String.valueOf(Ledger.note(id, "culture.paintings.n")), 0) + 1;
        Ledger.note(id, "culture.paintings.n", Long.toString(n));
        if (n == 1) {
            Villages.tell(id, day, f.displayNameCap() + " painted the town's first picture, “" + e.title + "”"
                + (paid ? ", and the town bought it for the shop" : ""));
        }
    }

    /** A painting of the town's own: its title on it, and who painted it and when. */
    static ItemStack picture(String title, String painter, long day, String town) {
        ItemStack p = new ItemStack(Items.PAINTING);
        p.set(DataComponents.ITEM_NAME, Component.literal("“" + title + "”"));
        p.set(DataComponents.LORE, ItemLore.EMPTY.withLineAdded(
            Component.literal("Painted by " + painter + " of " + town + ", day " + (day + 1)).withStyle(ChatFormatting.GRAY)));
        CompoundTag tag = new CompoundTag();
        tag.putString(MARK, title + "\t" + painter + "\t" + day);
        p.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return p;
    }

    /** What a picture of the town's says of itself: {title, painter}; null for any other painting. */
    @Nullable
    static String[] about(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        if (d == null || !d.contains(MARK)) return null;
        String[] f = d.copyTag().getString(MARK).split("\t", -1);
        return f.length >= 2 ? f : null;
    }

    // ------------------------------------------------------------------ on the walls

    /** The public rooms that are hung with pictures: the hall, the museum, the tavern. */
    static List<Ledger.Building> rooms(UUID village) {
        List<Ledger.Building> out = new ArrayList<>();
        Ledger.Building hall = Culture.hall(village);
        if (hall != null) out.add(hall);
        for (String s : new String[]{ "museum", "tavern" }) {
            Ledger.Building b = Culture.building(village, s);
            if (b != null) out.add(b);
        }
        return out;
    }

    /** How many pictures hang in this building now. */
    static int hanging(ServerLevel level, UUID village, Ledger.Building b) {
        Decor.Room room = Decor.room(village, b);
        if (room.floor().isEmpty()) return 0;
        AABB box = new AABB(b.anchor()).inflate(14, 10, 14);
        int n = 0;
        for (Painting p : level.getEntitiesOfClass(Painting.class, box)) {
            BlockPos at = p.getPos();
            for (int k = 0; k <= 3; k++) if (room.floorSet().contains(at.below(k))) { n++; break; }
        }
        return n;
    }

    /** The town's look at its pictures (Culture, each second, by day): the next one hung where a public room wants one. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long due = DUE.get(id);
        if (due != null && now < due) return;
        long t = level.getDayTime() % 24000L;
        boolean day = t < 12500L || t >= 23500L;
        DUE.put(id, now + (day && hang(level, v, false) ? 100L : 1200L));
    }

    /** A picture out of the stores hung in the next public room short of its two. True while a hand is on its way. */
    static boolean hang(ServerLevel level, Villages.Village v, boolean free) {
        UUID id = v.id();
        if (!free && Crafts.stock(level, v, s -> s.is(Items.PAINTING)) <= 0) return false;
        for (Ledger.Building b : rooms(id)) {
            if (!level.isLoaded(b.anchor()) || hanging(level, id, b) >= EACH) continue;
            Decor.Room room = Decor.room(id, b);
            Set<BlockPos> taken = Decor.reserved(level, id, b);
            String where = Villages.spoken(b.structure());
            for (Decor.Spot s : Decor.wallSpots(level, room, b.anchor(), 1, taken)) {
                if (Painting.create(level, s.at(), s.facing()).isEmpty()) continue;
                if (!free && !TownJobs.atWork(level, v, "paintings", s.at(), "hanging a painting in " + where)) return true;
                ItemStack p = free ? picture("A View of " + Villages.name(id), "a townsman", 0, Villages.name(id))
                    : Crafts.takeOne(level, v, st -> st.is(Items.PAINTING) && about(st) != null);
                if (p.isEmpty()) p = Crafts.takeOne(level, v, st -> st.is(Items.PAINTING));
                if (p.isEmpty()) return false;
                Optional<Painting> made = Painting.create(level, s.at(), s.facing());
                if (made.isEmpty()) {
                    Crafts.store(level, v, p);
                    return false;
                }
                level.addFreshEntity(made.get());
                made.get().playPlacementSound();
                String[] of = about(p);
                long day = level.getDayTime() / 24000L;
                List<String[]> rows = Culture.rows(id, "culture.hung");
                rows.add(new String[]{ where, of != null ? of[0] : "a painting", of != null ? of[1] : "", Long.toString(day) });
                while (rows.size() > 12) rows.remove(0);
                Culture.rows(id, "culture.hung", rows);
                if (!free && of != null) {
                    Villages.tell(id, day, f(of) + " was hung in " + where);
                }
                return true;
            }
        }
        return false;
    }

    private static String f(String[] of) {
        return "“" + of[0] + "”, painted by " + of[1] + ",";
    }

    // ------------------------------------------------------------------ the tests

    /** Tests: this folk's painting evening done at once (its makings out of its pack or the stores, every stroke now). */
    public static boolean paintForTests(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        EASELS.remove(f.getUUID());
        for (int i = 0; i < STROKES + 2; i++) {
            if (!paint(f, level, v, day, true)) break;
        }
        Easel e = EASELS.get(f.getUUID());
        Culture.release(f);
        return e != null && e.done;
    }

    /** Tests: every picture the stores run to hung now, where the public rooms want them; how many hang in this building. */
    public static int hangForTests(ServerLevel level, Villages.Village v, String structure) {
        for (int i = 0; i < EACH * 3 + 1; i++) if (!hang(level, v, false)) break;
        Ledger.Building b = Culture.building(v.id(), structure);
        return b == null ? -1 : hanging(level, v.id(), b);
    }

    /** Tests: is this an artist (a whittler or a gardener), and is tonight one of its painting evenings? */
    public static boolean tonightForTests(VillageFolkEntity f, long day) {
        return artist(f) && tonight(f, day);
    }

    /** Tests: a picture's title and painter, as it carries them; null for any other painting. */
    @Nullable
    public static String[] aboutForTests(ItemStack s) {
        return about(s);
    }

    // ------------------------------------------------------------------ what the player reads

    /** The folk's card: the pictures it has painted. */
    @Nullable
    static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || !artist(f)) return null;
        int n = 0;
        String last = null;
        for (String[] r : Culture.rows(id, "culture.paintings")) {
            if (r.length >= 3 && r[2].equals(f.displayNameCap())) {
                n++;
                last = r[1];
            }
        }
        if (n == 0) return "paints of an evening, when it has the sticks and the wool";
        return "has painted " + n + (n == 1 ? " picture" : " pictures") + ", the last “" + last + "”";
    }

    /** The Culture page's pictures: the artists, what they painted, and what hangs where. */
    static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        List<String> artists = new ArrayList<>(), painted = new ArrayList<>(), hung = new ArrayList<>(), lines = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f && artist(f)) artists.add(f.displayNameCap());
        List<String[]> rows = Culture.rows(id, "culture.paintings");
        for (int i = rows.size() - 1; i >= 0; i--) {
            String[] r = rows.get(i);
            if (r.length >= 3) painted.add("“" + r[1] + "” by " + r[2] + ", day " + (Culture.num(r[0], 0) + 1));
        }
        for (String[] r : Culture.rows(id, "culture.hung")) {
            if (r.length >= 3) hung.add((r[2].isEmpty() ? r[1] : "“" + r[1] + "” by " + r[2]) + ", in " + r[0]);
        }
        int stores = Crafts.stock(level, v, s -> s.is(Items.PAINTING));
        out.put("artists", Culture.strings(artists));
        out.put("painted", Culture.strings(painted));
        out.put("hung", Culture.strings(hung));
        out.putInt("stores", stores);
        out.putLong("total", Culture.num(String.valueOf(Ledger.note(id, "culture.paintings.n")), 0));
        lines.add(artists.isEmpty() ? "no artists yet (a whittler or a gardener paints of an evening)"
            : "artists: " + String.join(", ", artists) + "; " + out.getLong("total") + " pictures painted, " + stores + " in the stores");
        if (!painted.isEmpty()) lines.add("latest: " + painted.get(0));
        for (String h : hung) lines.add("hangs: " + h);
        out.put("lines", Culture.strings(lines));
        return out;
    }
}
