package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.item.Garment;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [fashion] The fashion show: at the May dance, the fair and the harvest festival (Festivals), once the festival's
 * own words are said, the elder calls the parade and judges the best-dressed by rules anybody can check.
 * <ul>
 * <li><b>Who.</b> The town's grown folk at the gathering, and any player stood with them in dyed leather.</li>
 * <li><b>The rules.</b> Two points for each thing of its own it wears (a coat, a hat, a scarf, a brooch) and one for
 *     a feather; three for the season's colour, and one more for the season's very thing in it; two if its colours go
 *     together (its second colour with its coat's); up to three for the work in its best piece (Craftsmanship: a
 *     master's coat); one for something new this season. A player: two a piece of dyed leather, three for the
 *     season's colour, two for a matched suit (three pieces of a colour).</li>
 * <li><b>The rosette.</b> The tailor makes a blue one the day before (wool, paper and string: Tailoring), and the
 *     winner wears it pinned on (a player is handed it). With none made, a paper ribbon from the stores, as the
 *     fair gives. Second and third are named. It goes into the chronicle and the gazette.</li>
 * </ul>
 */
public final class FashionShow {

    private FashionShow() {}

    static final String NOTE = "fashion.show";
    /** The festivals with a show: the May dance (spring), the fair (summer), the harvest festival (autumn). */
    static final Festivals.Feast[] SHOWS = { Festivals.Feast.MAYPOLE, Festivals.Feast.FAIR, Festivals.Feast.HARVEST };

    /** A show's result: the day, the festival, and the best-dressed three with what they wore. */
    record Result(long day, String feast, String winner, String words, String second, String third) {}

    /** One in the parade: a folk or a player, its points and what it wore. */
    record Entry(@Nullable VillageFolkEntity folk, @Nullable Player player, String name, double score, String words) {}

    private static final Map<UUID, Result> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> AWARDED = new ConcurrentHashMap<>();

    static void resetForTests() {
        LAST.clear();
        AWARDED.clear();
    }

    static boolean shows(@Nullable Festivals.Feast f) {
        for (Festivals.Feast s : SHOWS) if (s == f) return true;
        return false;
    }

    // ------------------------------------------------------------------ the parade (Festivals.script)

    /**
     * The show's lines, after the festival's own (Festivals.script): the parade called, the season's colour named,
     * third and second, and the rosette to the best-dressed. At the fair, before its last word.
     */
    static void script(ServerLevel level, Assemblies.Assembly a, @Nullable Festivals.Feast feast, List<Assemblies.Line> s, RandomSource r) {
        if (!shows(feast)) return;
        Villages.Village v = Villages.get(a.village);
        if (v == null) return;
        long day = level.getDayTime() / 24000L;
        List<Entry> ranked = judge(level, v, a.focus);
        if (ranked.isEmpty()) return;
        List<Assemblies.Line> lines = new ArrayList<>();
        String town = Villages.name(a.village), season = Seasons.season(a.village, day).word;
        lines.add(new Assemblies.Line(null, "And now — the parade! Who is the best-dressed in " + town + " this " + season + "?", '!', null));
        int colour = Fashion.trendColour(a.village);
        if (colour >= 0) {
            int[] n = Fashion.counts(a.village);
            lines.add(new Assemblies.Line(null, Garment.colourCap(colour) + "'s the colour this " + season + " — " + n[0]
                + (n[0] == 1 ? " of you is" : " of you are") + " wearing it!", '!', null));
        }
        if (ranked.size() > 2) lines.add(new Assemblies.Line(null, "Third: " + ranked.get(2).name() + ", in " + ranked.get(2).words() + ".", '?', null));
        if (ranked.size() > 1) lines.add(new Assemblies.Line(null, "Second: " + ranked.get(1).name() + ", in " + ranked.get(1).words() + ".", '?', null));
        Entry w = ranked.get(0);
        String second = ranked.size() > 1 ? ranked.get(1).name() : "", third = ranked.size() > 2 ? ranked.get(2).name() : "";
        String feastWords = feast.words;
        lines.add(new Assemblies.Line(null, "And the rosette for the best-dressed goes to " + w.name() + ", in " + w.words() + "!", '!',
            () -> award(level, v, w, feastWords, day, second, third)));
        if (feast == Festivals.Feast.FAIR && !s.isEmpty()) s.addAll(s.size() - 1, lines);
        else s.addAll(lines);
    }

    /** Everybody in the parade, the best-dressed first: the town's grown folk near the gathering, and players among them. */
    static List<Entry> judge(ServerLevel level, Villages.Village v, @Nullable BlockPos focus) {
        UUID id = v.id();
        List<Entry> out = new ArrayList<>();
        int colour = Fashion.trendColour(id);
        Garment kind = Fashion.trendKind(id);
        long since = Fashion.trend(id).since;
        for (VillageFolkEntity f : Fashion.grown(id)) {
            if (focus != null && f.blockPosition().distSqr(focus) > 32 * 32) continue;
            Style s = f.style();
            double score = score(s, colour, kind, since);
            if (score <= 0) continue;
            String words = Fashion.wearing(s);
            out.add(new Entry(f, null, f.displayNameCap(), score, words.isEmpty() ? "its own colours" : words));
        }
        if (focus != null) {
            for (ServerPlayer p : level.players()) {
                if (p.isSpectator() || p.blockPosition().distSqr(focus) > 16 * 16) continue;
                int[] count = new int[16];
                int pieces = 0;
                for (EquipmentSlot slot : new EquipmentSlot[]{ EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
                    ItemStack st = p.getItemBySlot(slot);
                    DyedItemColor c = st.is(ItemTags.DYEABLE) ? st.get(DataComponents.DYED_COLOR) : null;
                    if (c == null) continue;
                    count[Garment.nearest(c.rgb()).getId()]++;
                    pieces++;
                }
                if (pieces == 0) continue;
                int best = 0;
                for (int i = 1; i < 16; i++) if (count[i] > count[best]) best = i;
                double score = 2.0 * pieces + (colour >= 0 && count[colour] > 0 ? 3 : 0) + (count[best] >= 3 ? 2 : 0);
                out.add(new Entry(null, p, p.getName().getString(), score, Garment.colourWord(best) + " leather"));
            }
        }
        out.sort(Comparator.comparingDouble(Entry::score).reversed().thenComparing(Entry::name));
        return out;
    }

    /** A folk's points (see the class comment). */
    static double score(Style s, int colour, @Nullable Garment kind, long since) {
        double score = 0;
        for (Garment.Slot slot : new Garment.Slot[]{ Garment.Slot.BODY, Garment.Slot.HEAD, Garment.Slot.NECK, Garment.Slot.PIN }) {
            if (!s.worn(slot).isEmpty()) score += 2;
        }
        if (!s.feather.isEmpty() && s.garment(Garment.Slot.HEAD) == Garment.FELT_HAT) score += 1;
        if (colour >= 0 && s.wears(colour)) {
            score += 3;
            if (kind != null && s.garment(kind.slot) == kind && s.colour(kind.slot) == colour) score += 1;
        }
        int body = s.colour(Garment.Slot.BODY);
        if (Fashion.goes(body >= 0 && body < Garment.NATURAL ? body : s.main, s.accent)) score += 2;
        double work = 0;
        for (Garment.Slot slot : Garment.Slot.values()) {
            ItemStack st = s.worn(slot);
            if (!st.isEmpty()) work = Math.max(work, Craftsmanship.worth(st) - 1.0);
        }
        score += Math.min(3.0, work * 6.0);
        if (since >= 0 && s.newOn >= since) score += 1;
        return score;
    }

    /**
     * The rosette to the best-dressed, once a day: the tailor's (the stores' or the store's), pinned on a folk or handed
     * to a player; with none made, a paper ribbon from the stores. Into the chronicle and the books.
     */
    static String award(ServerLevel level, Villages.Village v, Entry w, String feast, long day, String second, String third) {
        UUID id = v.id();
        Long done = AWARDED.get(id);
        if (done != null && done == day) return "";
        AWARDED.put(id, day);
        int year = Seasons.year(id, day);
        String title = feast + ", year " + year;
        ItemStack prize = rosette(level, v);
        if (prize.isEmpty()) {
            ItemStack paper = Crafts.takeOne(level, v, s -> s.is(Items.PAPER) && !s.has(DataComponents.CUSTOM_NAME));
            if (!paper.isEmpty()) {
                prize = paper.copyWithCount(1);
                prize.set(DataComponents.CUSTOM_NAME, Component.literal("Ribbon: best-dressed, " + title).withStyle(ChatFormatting.BLUE));
            }
        }
        if (!prize.isEmpty()) {
            ItemLore lore = prize.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
            prize.set(DataComponents.LORE, lore.withLineAdded(Component.literal("Best-dressed at " + Villages.name(id) + "'s " + title)
                .withStyle(ChatFormatting.GRAY)));
        }
        if (w.folk() != null && w.folk().isAlive()) {
            VillageFolkEntity f = w.folk();
            if (Garment.of(prize) == Garment.ROSETTE) {
                Fashion.wear(level, v, f, prize, "won");
            } else if (!prize.isEmpty()) {
                Homes.keepsake(prize, f);
                ItemStack left = f.insertGiven(prize);
                if (!left.isEmpty()) Crafts.store(level, v, left);
            }
            f.style().rosette = title;
            f.persona().remember(day, "I was the best-dressed at " + title, 5);
            f.sayLater(FolkTalk.pick(f.getRandom(), "Me? The best-dressed? Oh, thank you!", "A rosette! I'll wear it with pride.",
                "Worth every stitch!"), 30);
            f.showStyle(f.style().pack(Fashion.inFashion(f)));
        } else if (w.player() instanceof ServerPlayer p) {
            if (!prize.isEmpty() && !p.getInventory().add(prize)) p.drop(prize, false);
            p.sendSystemMessage(Component.literal("You were the best-dressed at " + Villages.name(id) + "'s " + feast + ", in " + w.words()
                + "!" + (prize.isEmpty() ? "" : " The rosette is yours.")).withStyle(ChatFormatting.GOLD));
        } else if (!prize.isEmpty()) {
            Crafts.store(level, v, prize);
        }
        Result r = new Result(day, feast, w.name(), w.words(), second, third);
        LAST.put(id, r);
        Ledger.note(id, NOTE, String.join("|", Long.toString(day), feast, w.name(), w.words().replace('|', '/'), second, third));
        Villages.tell(id, day, w.name() + " was the best-dressed at " + feast + ", in " + w.words()
            + (second.isEmpty() ? "" : "; then " + second + (third.isEmpty() ? "" : " and " + third)));
        return w.name() + (prize.isEmpty() ? "" : ", " + prize.getHoverName().getString().toLowerCase(Locale.ROOT));
    }

    /** The show's rosette out of the stock: a blue one first, else any (never a second-hand one). Nothing if none. */
    static ItemStack rosette(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        for (Predicate<ItemStack> what : List.<Predicate<ItemStack>>of(
                s -> Garment.of(s) == Garment.ROSETTE && Garment.colourOf(s) == DyeColor.BLUE.getId() && !Fashion.secondHand(s),
                s -> Garment.of(s) == Garment.ROSETTE && !Fashion.secondHand(s))) {
            ItemStack one = Fashion.sampleOf(level, id, what);
            if (one.isEmpty()) continue;
            Predicate<ItemStack> exact = s -> ItemStack.isSameItemSameComponents(s, one);
            if (TownWork.take(level, v, exact, 1)) return one;
            List<ItemStack> got = Store.take(level, id, exact, 1, true);
            if (!got.isEmpty()) return got.get(0).copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    /** Before a show (today's or tomorrow's festival), a blue rosette on the tailor's book if the town has none (Tailoring.tick). */
    static void rosetteDue(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        boolean soon = false;
        for (Festivals.Feast f : SHOWS) {
            long next = Festivals.next(id, day, f);
            if (next >= day && next - day <= 1) soon = true;
        }
        if (!soon) return;
        for (Tailoring.Order o : Tailoring.book(id)) if (o.kind == Garment.ROSETTE) return;
        if (Fashion.stockOf(level, id, s -> Garment.of(s) == Garment.ROSETTE && !Fashion.secondHand(s), false) > 0) return;
        Tailoring.orderFor(id, "the fashion show", Garment.ROSETTE, DyeColor.BLUE.getId(), day, true);
    }

    // ------------------------------------------------------------------ now, by hand

    /** The show held now, whatever the day (/village fashion show): everybody judged, the rosette given. What happened. */
    static String holdNow(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        List<Entry> ranked = judge(level, v, null);
        if (ranked.isEmpty()) return "nobody wears anything of their own yet";
        AWARDED.remove(v.id());
        String second = ranked.size() > 1 ? ranked.get(1).name() : "", third = ranked.size() > 2 ? ranked.get(2).name() : "";
        String won = award(level, v, ranked.get(0), "the fashion show", day, second, third);
        StringBuilder sb = new StringBuilder("best-dressed: " + won + " in " + ranked.get(0).words());
        for (int i = 1; i < Math.min(3, ranked.size()); i++) sb.append("; ").append(i + 1).append(": ").append(ranked.get(i).name());
        return sb.toString();
    }

    /** Tests: the show held now (as /village fashion show). */
    public static String holdNowForTests(ServerLevel level, Villages.Village v) {
        return holdNow(level, v);
    }

    // ------------------------------------------------------------------ the books

    /** The last show, for the gazette within the week: "Best-dressed at the fair (day 12): Ada, in ...; then Tom and Bea." */
    @Nullable
    static String lastLine(UUID village, long day) {
        Result r = last(village);
        if (r == null || day - r.day() > 7) return null;
        return lineOf(r);
    }

    /** The last show ever held, in a line, or null. */
    @Nullable
    static String lastEver(UUID village) {
        Result r = last(village);
        return r == null ? null : lineOf(r);
    }

    private static String lineOf(Result r) {
        return "Best-dressed at " + r.feast() + " (day " + r.day() + "): " + r.winner() + ", in " + r.words()
            + (r.second().isEmpty() ? "" : "; then " + r.second() + (r.third().isEmpty() ? "" : " and " + r.third())) + ".";
    }

    @Nullable
    private static Result last(UUID village) {
        Result r = LAST.get(village);
        if (r != null) return r;
        String s = Ledger.note(village, NOTE);
        if (s == null || s.isEmpty()) return null;
        try {
            String[] p = s.split("\\|", -1);
            r = new Result(Long.parseLong(p[0]), p[1], p[2], p[3], p[4], p[5]);
            LAST.put(village, r);
            return r;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** When the next show is: "The next fashion show: the fair, day 12 (in 3 days)". */
    static String nextLine(ServerLevel level, UUID village, long day) {
        Festivals.Feast best = null;
        long when = Long.MAX_VALUE;
        for (Festivals.Feast f : SHOWS) {
            long next = Festivals.next(village, day, f);
            if (next < when) { when = next; best = f; }
        }
        if (best == null) return "";
        long in = when - day;
        return "The next fashion show: " + best.words + ", day " + when + (in <= 0 ? " (today)" : in == 1 ? " (tomorrow)" : " (in " + in + " days)") + ".";
    }

    // ------------------------------------------------------------------ the pictures

    /** The tag on the stage's folk (/kill @e[tag=fashion_lineup] clears them). */
    static final String LINEUP = "fashion_lineup";

    /**
     * The pictures' stage (/village fashion stage): on the ground where it is asked, a crowd of nine folk of different
     * trades three rows deep, six in the season's colour (a long coat and a felt hat with a feather, a leather jacket
     * and a flat cap, a waistcoat, a top hat and a brooch, a shawl, a scarf...) and three holding out in their own;
     * and a few steps off, a tailor at a loom. Showcase folk, dressed for nothing, hats on. Returns "VIEW name x y z ax
     * ay az" lines: the feet of the one looking, and what it looks at.
     */
    static List<String> stage(ServerLevel level, @Nullable Villages.Village v, BlockPos at) {
        List<Entity> old = new ArrayList<>();
        for (Entity e : level.getAllEntities()) if (e.getTags().contains(LINEUP)) old.add(e);
        for (Entity e : old) e.discard();
        int colour = v != null && Fashion.trendColour(v.id()) >= 0 ? Fashion.trendColour(v.id()) : DyeColor.RED.getId();
        AssistantEntity.StationTask[] trades = { AssistantEntity.StationTask.FARM, AssistantEntity.StationTask.SMITH,
            AssistantEntity.StationTask.STORE, AssistantEntity.StationTask.COOK, AssistantEntity.StationTask.SHOP,
            AssistantEntity.StationTask.MINE, AssistantEntity.StationTask.FISH, AssistantEntity.StationTask.RANCH,
            AssistantEntity.StationTask.BREW };
        String[] names = { "Ada", "Bram", "Cora", "Dell", "Edie", "Finn", "Gwen", "Hal", "Ivy" };
        // What each wears: {body, hat, scarf?, brooch?, feather?}; the last three hold out in their own colours.
        Garment[][] looks = {
            { Garment.LONG_COAT, Garment.FELT_HAT }, { Garment.LEATHER_JACKET, Garment.FLAT_CAP }, { Garment.WAISTCOAT, Garment.TOP_HAT },
            { Garment.WOOL_SHAWL, null }, { Garment.LONG_COAT, Garment.TOP_HAT }, { null, Garment.FLAT_CAP },
            { Garment.LONG_COAT, Garment.FELT_HAT }, { Garment.WOOL_SHAWL, null }, { Garment.LEATHER_JACKET, null } };
        int[] own = { DyeColor.BLUE.getId(), DyeColor.GREEN.getId(), DyeColor.YELLOW.getId() };
        int x0 = at.getX(), z0 = at.getZ();
        int stood = 0;
        double feetY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0 + 2, z0 + 2);
        for (int i = 0; i < 9; i++) {
            int row = i / 3, col = i % 3;
            double x = x0 + 0.5 + col * 1.6 + (row % 2) * 0.5, z = z0 + 0.5 + (2 - row) * 1.6;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
            VillageFolkEntity f = com.jrpetty.mcassistant.McAssistantMod.VILLAGE_FOLK.get().create(level);
            if (f == null) continue;
            f.moveTo(x, y, z, 0.0F, 0.0F);
            f.setYHeadRot(0.0F);
            f.setYBodyRot(0.0F);
            f.makeShowcase(trades[i]);
            f.rename(names[i]);
            Style s = f.style();
            boolean holdout = i >= 6;
            int c = holdout ? own[i - 6] : colour;
            s.main = holdout ? c : (colour + 3 + i * 2) % 16;
            s.accent = Fashion.GOES[c][i % Fashion.GOES[c].length];
            Garment[] look = looks[i];
            if (look[0] != null) s.worn[Garment.Slot.BODY.ordinal()] = look[0].dyed(c);
            if (look[1] != null) s.worn[Garment.Slot.HEAD.ordinal()] = look[1].dyed(c);
            if (i == 3 || i == 5 || i == 7) s.worn[Garment.Slot.NECK.ordinal()] = Garment.WOOL_SCARF.dyed(holdout ? s.accent : c);
            if (i == 2) s.worn[Garment.Slot.PIN.ordinal()] = new ItemStack(Garment.BROOCH.item());
            if (i == 0) {
                s.feather = new ItemStack(Items.FEATHER);
                s.worn[Garment.Slot.RIBBON.ordinal()] = Garment.ROSETTE.dyed(DyeColor.BLUE.getId());
            }
            s.dressed = true;
            f.showStyle(s.pack(!holdout));
            f.addTag(LINEUP);
            if (level.addFreshEntity(f)) stood++;
        }
        // The tailor at its loom, a few steps off: the loom on its right hand, the tailor turned to it.
        int tx = x0 + 8, tz = z0 + 2;
        int ty = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tx, tz);
        BlockPos loom = new BlockPos(tx + 1, ty, tz);
        if (level.getBlockState(loom).canBeReplaced()) {
            level.setBlock(loom, Blocks.LOOM.defaultBlockState().setValue(LoomBlock.FACING, Direction.WEST), 3);
        }
        VillageFolkEntity tailor = com.jrpetty.mcassistant.McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (tailor != null) {
            tailor.moveTo(tx + 0.5, ty, tz + 0.5, -90.0F, 0.0F);
            tailor.setYHeadRot(-90.0F);
            tailor.setYBodyRot(-90.0F);
            tailor.makeShowcase(AssistantEntity.StationTask.TAILOR);
            tailor.rename("Tam the tailor");
            Style s = tailor.style();
            s.main = colour;
            s.accent = Fashion.GOES[colour][0];
            s.worn[Garment.Slot.NECK.ordinal()] = Garment.WOOL_SCARF.dyed(colour);
            tailor.setItemSlot(EquipmentSlot.MAINHAND, Garment.LONG_COAT.dyed(colour));
            tailor.showStyle(s.pack(true));
            tailor.addTag(LINEUP);
            if (level.addFreshEntity(tailor)) stood++;
        }
        List<String> out = new ArrayList<>();
        out.add("FASHION STAGE " + stood + " stood up in " + Garment.colourWord(colour) + ", three holding out; /kill @e[tag=" + LINEUP + "] clears them");
        double mx = x0 + 2.1, mz = z0 + 2.1;
        out.add(String.format(Locale.ROOT, "VIEW fashion-1-crowd %.2f %.2f %.2f %.2f %.2f %.2f", mx, feetY + 0.9, mz + 7.2, mx, feetY + 1.3, mz));
        out.add(String.format(Locale.ROOT, "VIEW fashion-2-tailor %.2f %.2f %.2f %.2f %.2f %.2f", tx + 0.6, ty + 0.4, tz + 3.4, tx + 0.8, ty + 1.2, tz + 0.5));
        out.add("LOOM " + loom.getX() + " " + loom.getY() + " " + loom.getZ());
        return out;
    }
}
