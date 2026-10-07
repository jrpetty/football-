package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The town's banner and its motto [batchD] (Culture).
 *
 * <p><b>The banner.</b> Drawn once, when the town is founded (or the first morning after, its land looked
 * over), and the same for ever after, whoever leads it later and whatever it is renamed: its field (the
 * base colour) is its land's — blue for the coast, a pale blue for a river, green for the forest, white for
 * the snowfields, grey for the mountains, yellow for the desert — and on it two or three charges woven on the
 * loom: one for the land itself where the land has one (the sea's edge, a river's bend, a peak in snow, the
 * desert sun, icicles), one for its name (a fess of water for a ford or a brook, a pale for a tree, masonry
 * for a stone town, a roundel for the sun or a bell, a chevron for a fox or a wolf...) in the town's colours
 * (its watch's), and one for the nature of whoever led it then (a hard worker's border, a cheerful chief, a
 * curious lozenge...).
 *
 * <p>It is made the way a player makes one: the tailor's banner in the field's colour (six wool and a stick,
 * Bench, at its level of ten and over, or one put by in the stores) and a dye for each charge at the loom,
 * all out of the stores, never what the stores do not hold. A hand at the town's works hangs it, a piece
 * at a time: either side of the hall's door, on a post of each of the gates, on the market's front posts,
 * and on the theatre's back wall over the stage. The books say what it waits on.
 *
 * <p><b>A copy at the shop.</b> Once there is a shop, a sign goes up by its door ("Our banner"): a citizen
 * of the town right-clicks it and the tailor's copy is made there and then out of the stores, for the price
 * of its makings and a little for the work, into the treasury. Nobody else may fly it.
 *
 * <p><b>The motto.</b> Chosen with the banner, out of the land and what the town cares for (its leader's
 * heart, or most of its folk's): "By the river, for each other", "Out of the rock, the gate holds". Once the
 * hall stands it is carved on a sign over its door; the crier cries it on feast days (Traditions).
 */
public final class Heraldry {

    private Heraldry() {}

    /** The first line of the shop's sign for the banner (Culture.onUseBlock knows it by this). */
    static final String SHOP_SIGN = "Our banner";
    /** The town's works that hang the banner, carve the motto and put up the shop's sign: one hand for them all. */
    static final String WORKS = "banners";

    // ------------------------------------------------------------------ the design

    /** What the loom weaves on a banner, by the word the books use for it (only what takes no pattern item). */
    public enum Charge {
        BORDER(BannerPatterns.BORDER, "border", true), SCALLOPED(BannerPatterns.CURLY_BORDER, "scalloped border", true),
        SALTIRE(BannerPatterns.CROSS, "saltire", true), CROSS(BannerPatterns.STRAIGHT_CROSS, "cross", true),
        ROUNDEL(BannerPatterns.CIRCLE_MIDDLE, "roundel", true), LOZENGE(BannerPatterns.RHOMBUS_MIDDLE, "lozenge", true),
        PALE(BannerPatterns.STRIPE_CENTER, "pale", true), FESS(BannerPatterns.STRIPE_MIDDLE, "fess", true),
        BASE(BannerPatterns.STRIPE_BOTTOM, "base", true), CHIEF(BannerPatterns.STRIPE_TOP, "chief", true),
        CHEVRON(BannerPatterns.TRIANGLE_BOTTOM, "chevron", true), INDENTED(BannerPatterns.TRIANGLES_TOP, "indented chief", true),
        MASONRY(BannerPatterns.BRICKS, "masonry", false), FADE(BannerPatterns.GRADIENT, "fade", true),
        BEND(BannerPatterns.STRIPE_DOWNRIGHT, "bend", true), PALLETS(BannerPatterns.STRIPE_SMALL, "pallets", false);

        public final ResourceKey<BannerPattern> key;
        public final String noun;
        final boolean one;

        Charge(ResourceKey<BannerPattern> key, String noun, boolean one) {
            this.key = key;
            this.noun = noun;
            this.one = one;
        }
    }

    /** One charge in its dye. */
    public record Layer(Charge charge, DyeColor colour) {}

    /** A banner: its field and its charges, in the order the loom weaves them. */
    public record Design(DyeColor field, List<Layer> layers) {

        String encode() {
            StringBuilder sb = new StringBuilder(field.getName()).append('|');
            for (int i = 0; i < layers.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(layers.get(i).charge().name()).append(':').append(layers.get(i).colour().getName());
            }
            return sb.toString();
        }

        @Nullable
        static Design decode(@Nullable String s) {
            if (s == null || s.isEmpty()) return null;
            String[] parts = s.split("\\|", -1);
            DyeColor field = DyeColor.byName(parts[0], null);
            if (field == null) return null;
            List<Layer> layers = new ArrayList<>();
            if (parts.length > 1 && !parts[1].isEmpty()) {
                for (String l : parts[1].split(",")) {
                    String[] cc = l.split(":");
                    if (cc.length != 2) continue;
                    try {
                        DyeColor c = DyeColor.byName(cc[1], null);
                        if (c != null) layers.add(new Layer(Charge.valueOf(cc[0]), c));
                    } catch (IllegalArgumentException ignored) {
                        // a charge from a later build: left out
                    }
                }
            }
            return new Design(field, List.copyOf(layers));
        }

        /** "Blue, with a white fess and a black border". */
        public String blazon() {
            StringBuilder sb = new StringBuilder(Culture.capital(colourWord(field)));
            for (int i = 0; i < layers.size(); i++) {
                Layer l = layers.get(i);
                sb.append(i == 0 ? ", with " : i == layers.size() - 1 ? " and " : ", ");
                String c = colourWord(l.colour());
                sb.append(l.charge().one ? article(c) + " " : "").append(c).append(' ').append(l.charge().noun);
            }
            return sb.toString();
        }
    }

    static String colourWord(DyeColor c) {
        return c.getName().replace('_', ' ');
    }

    private static String article(String word) {
        return "aeiou".indexOf(Character.toLowerCase(word.charAt(0))) >= 0 ? "an" : "a";
    }

    /** The field each land's banner is: its sea, its river, its woods, its snow, its rock, its sand. */
    private static final Map<Homeland.Land, DyeColor> FIELD = new EnumMap<>(Homeland.Land.class);
    static {
        FIELD.put(Homeland.Land.COAST, DyeColor.BLUE);
        FIELD.put(Homeland.Land.RIVER, DyeColor.LIGHT_BLUE);
        FIELD.put(Homeland.Land.FOREST, DyeColor.GREEN);
        FIELD.put(Homeland.Land.TAIGA, DyeColor.CYAN);
        FIELD.put(Homeland.Land.SNOW, DyeColor.WHITE);
        FIELD.put(Homeland.Land.MOUNTAIN, DyeColor.GRAY);
        FIELD.put(Homeland.Land.DESERT, DyeColor.YELLOW);
        FIELD.put(Homeland.Land.SAVANNA, DyeColor.ORANGE);
        FIELD.put(Homeland.Land.JUNGLE, DyeColor.LIME);
        FIELD.put(Homeland.Land.SWAMP, DyeColor.BROWN);
        FIELD.put(Homeland.Land.BADLANDS, DyeColor.RED);
        FIELD.put(Homeland.Land.MEADOW, DyeColor.PINK);
        FIELD.put(Homeland.Land.PLAINS, DyeColor.LIME);
    }

    /**
     * The town's banner, drawn now: its land's field; its land's own charge, if the land has one; a charge
     * for its name in the town's colours; and one for the nature of whoever leads it (or the eldest).
     */
    static Design draw(UUID village) {
        Homeland.Land land = Homeland.of(village);
        DyeColor field = FIELD.getOrDefault(land, DyeColor.WHITE);
        List<Layer> layers = new ArrayList<>();
        Layer own = switch (land) {
            case COAST -> new Layer(Charge.BASE, DyeColor.WHITE);              // the sea's edge
            case RIVER -> new Layer(Charge.BEND, DyeColor.BLUE);               // the river across its land
            case MOUNTAIN -> new Layer(Charge.CHEVRON, DyeColor.WHITE);        // a peak in snow
            case DESERT -> new Layer(Charge.ROUNDEL, DyeColor.ORANGE);         // the sun
            case SNOW -> new Layer(Charge.INDENTED, DyeColor.LIGHT_BLUE);      // icicles
            default -> null;
        };
        if (own != null) layers.add(own);
        Charge named = forName(Villages.name(village));
        if (own != null && named == own.charge()) named = Charge.LOZENGE;
        layers.add(new Layer(named, contrast(MuseumFront.colour(village), field)));
        Social.Trait nature = leaderNature(village);
        Layer led = switch (nature) {
            case HARDWORKING -> new Layer(Charge.BORDER, DyeColor.BLACK);
            case EASYGOING -> new Layer(Charge.FADE, DyeColor.LIGHT_BLUE);
            case SOCIABLE -> new Layer(Charge.SALTIRE, DyeColor.RED);
            case SHY -> new Layer(Charge.BASE, DyeColor.GRAY);
            case CHEERFUL -> new Layer(Charge.CHIEF, DyeColor.YELLOW);
            case GRUMPY -> new Layer(Charge.BORDER, DyeColor.BROWN);
            case GENEROUS -> new Layer(Charge.SCALLOPED, DyeColor.MAGENTA);
            case CURIOUS -> new Layer(Charge.LOZENGE, DyeColor.PURPLE);
        };
        for (Layer l : layers) {
            if (l.charge() == led.charge()) led = new Layer(led.charge() == Charge.BORDER ? Charge.SCALLOPED : Charge.BORDER, led.colour());
        }
        layers.add(new Layer(led.charge(), contrast(led.colour(), field)));
        return new Design(field, List.copyOf(layers));
    }

    /** A charge for the town's name: what its first half or its last says of the place. */
    static Charge forName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String water : new String[]{ "ford", "brook", "mere", "well", "haven", "holm", "marsh", "rush", "mill" }) {
            if (n.contains(water)) return Charge.FESS;
        }
        for (String tree : new String[]{ "oak", "ash", "elm", "birch", "willow", "alder", "hazel", "linden", "rowan", "fern",
                "thorn", "bramble", "heather", "moss" }) {
            if (n.startsWith(tree)) return Charge.PALE;
        }
        for (String stone : new String[]{ "stone", "iron", "clay", "dun" }) if (n.startsWith(stone)) return Charge.MASONRY;
        for (String round : new String[]{ "sun", "bell", "swan", "mead" }) if (n.startsWith(round)) return Charge.ROUNDEL;
        for (String beast : new String[]{ "fox", "wolf", "hart", "raven", "ridge" }) if (n.contains(beast)) return Charge.CHEVRON;
        if (n.contains("cross")) return Charge.SALTIRE;
        for (String high : new String[]{ "kings", "high", "white", "red", "green", "black" }) if (n.startsWith(high)) return Charge.CROSS;
        Charge[] any = { Charge.LOZENGE, Charge.CHIEF, Charge.PALE, Charge.SALTIRE, Charge.PALLETS };
        return any[Math.floorMod(n.hashCode(), any.length)];
    }

    /** Whoever leads the town now (or the eldest of it), as its nature has it: its first trait. */
    static Social.Trait leaderNature(UUID village) {
        VillageFolkEntity who = Orders.elderOf(village);
        if (who == null) {
            UUID eldest = FoundingDay.eldest(village);
            for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && f.getUUID().equals(eldest)) who = f;
        }
        if (who != null && who.life().rolled()) {
            for (Social.Trait t : Social.Trait.values()) if (who.life().has(t)) return t;
        }
        return Social.Trait.HARDWORKING;
    }

    /** A colour that shows on the field: itself, unless it is the field's own. */
    static DyeColor contrast(DyeColor c, DyeColor field) {
        if (c != field) return c;
        return field == DyeColor.WHITE ? DyeColor.BLACK : DyeColor.WHITE;
    }

    /** The town's banner, once it has been drawn; null before. */
    @Nullable
    public static Design design(UUID village) {
        return Design.decode(Ledger.note(village, "culture.banner"));
    }

    /** The town's motto, once chosen; null before. */
    @Nullable
    public static String motto(UUID village) {
        String m = Ledger.note(village, "culture.motto");
        return m == null || m.isEmpty() ? null : m;
    }

    /**
     * The banner and the motto, chosen once: at the founding, its land looked over (or a day on, for a town
     * whose land never was). Into its history.
     */
    static void choose(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (design(id) != null && motto(id) != null) return;
        long day = level.getDayTime() / 24000L, founded = com.jrpetty.mcassistant.village.Chronicle.foundedOn(id);
        if (Homeland.known(id) == null && (founded < 0 || day <= founded)) return;      // its land not yet looked over
        if (Villages.headcount(id) < 1) return;
        Design d = design(id);
        if (d == null) {
            d = draw(id);
            Ledger.note(id, "culture.banner", d.encode());
            Ledger.note(id, "culture.banner.day", Long.toString(day));
        }
        String m = motto(id);
        if (m == null) {
            m = mottoFor(id);
            Ledger.note(id, "culture.motto", m);
        }
        Villages.tell(id, day, "the founders chose the town's banner (" + d.blazon().toLowerCase(Locale.ROOT) + ") and its motto, “"
            + m + "”");
    }

    /** The motto: the land, then what the town cares for. */
    static String mottoFor(UUID village) {
        String land = switch (Homeland.of(village)) {
            case COAST -> "By the sea";
            case RIVER -> "By the river";
            case FOREST -> "Under the oaks";
            case TAIGA -> "Among the pines";
            case SNOW -> "Through the long snow";
            case MOUNTAIN -> "Out of the rock";
            case DESERT -> "Round the well";
            case SAVANNA -> "On the wide grass";
            case JUNGLE -> "In the deep green";
            case SWAMP -> "Out of the fen";
            case BADLANDS -> "On the red earth";
            case MEADOW -> "Among the blossom";
            case PLAINS -> "On the open plain";
        };
        String[] heart = switch (caresFor(village)) {
            case FOOD -> new String[]{ "bread for all", "no one goes hungry" };
            case HOMES -> new String[]{ "for each other", "a hearth for all" };
            case PROGRESS -> new String[]{ "ever onward", "always higher" };
            case SAFETY -> new String[]{ "the gate holds", "we keep the watch" };
            case WEALTH -> new String[]{ "fair trade, full hands", "honest coin" };
            case LEISURE -> new String[]{ "glad of heart", "a song at day's end" };
            case TRADITION -> new String[]{ "as of old", "the old ways kept" };
        };
        return land + ", " + heart[Math.floorMod(Villages.name(village).hashCode(), heart.length)];
    }

    /** What the town cares for: its leader's heart, else what most of its folk care for most, else homes. */
    static Values.Value caresFor(UUID village) {
        VillageFolkEntity elder = Orders.elderOf(village);
        if (elder != null) return Values.top(elder);
        Map<Values.Value, Integer> n = new EnumMap<>(Values.Value.class);
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby()) n.merge(Values.top(f), 1, Integer::sum);
        }
        Values.Value best = Values.Value.HOMES;
        int most = 0;
        for (Map.Entry<Values.Value, Integer> e : n.entrySet()) if (e.getValue() > most) { most = e.getValue(); best = e.getKey(); }
        return best;
    }

    // ------------------------------------------------------------------ the banner as a thing

    static Item bannerItem(DyeColor c) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(c.getName() + "_banner"));
    }

    static Item dyeItem(DyeColor c) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(c.getName() + "_dye"));
    }

    static Block wallBanner(DyeColor c) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(c.getName() + "_wall_banner"));
    }

    /** The banner as it comes off the loom: the field's banner with every charge woven on it, and its name. */
    public static ItemStack item(ServerLevel level, UUID village, Design d) {
        ItemStack s = new ItemStack(bannerItem(d.field()));
        var reg = level.registryAccess().registryOrThrow(Registries.BANNER_PATTERN);
        BannerPatternLayers.Builder layers = new BannerPatternLayers.Builder();
        for (Layer l : d.layers()) reg.getHolder(l.charge().key).ifPresent(h -> layers.add(h, l.colour()));
        s.set(DataComponents.BANNER_PATTERNS, layers.build());
        s.set(DataComponents.ITEM_NAME, Component.literal("Banner of " + Villages.name(village)));
        return s;
    }

    /** What the banner is made of, as the stores must give it: the field's banner and a dye a charge. */
    static List<Bench.Want> wants(Design d) {
        List<Bench.Want> out = new ArrayList<>();
        out.add(Bench.Want.of(bannerItem(d.field()), 1));
        Map<Item, Integer> dyes = new LinkedHashMap<>();
        for (Layer l : d.layers()) dyes.merge(dyeItem(l.colour()), 1, Integer::sum);
        for (Map.Entry<Item, Integer> e : dyes.entrySet()) out.add(Bench.Want.of(e.getKey(), e.getValue()));
        return out;
    }

    /** The tailor (the best of them), whose banner it is; null in a town with none. */
    @Nullable
    static VillageFolkEntity tailor(UUID village) {
        VillageFolkEntity best = null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == AssistantEntity.StationTask.TAILOR && !f.isBaby()
                && (best == null || f.veteranLevel() > best.veteranLevel())) best = f;
        }
        return best;
    }

    /** The banner worked out at the tailor's bench (or a hand at the town's works, with none), out of the stores. */
    static Bench.Plan plan(ServerLevel level, Villages.Village v, Design d) {
        return Bench.plan(level, v, wants(d), Bench.handOf(level, v, tailor(v.id()), "workshop"));
    }

    /** What a copy costs a citizen: its makings at the price list's worth, and a little for the tailor's work. */
    static int price(Design d) {
        double c = Prices.each(bannerItem(d.field()));
        for (Layer l : d.layers()) c += Prices.each(dyeItem(l.colour()));
        return Math.max(3, (int) Math.ceil(c) + 2);
    }

    // ------------------------------------------------------------------ where it hangs

    /** A place the banner hangs: where, and what it is called there. */
    record Place(Culture.Spot spot, String where) {}

    /** Every place the town's banner hangs, as the town stands: its hall, its gates, its market, its theatre. */
    static List<Place> places(ServerLevel level, UUID village) {
        List<Place> out = new ArrayList<>();
        Ledger.Building hall = Culture.hall(village);
        if (hall != null) {
            String name = Villages.spoken(hall.structure());
            for (Culture.Spot s : two(Culture.front(level, hall, 2, new int[]{ -2, 2, -3, 3, -4, 4, -1, 1 }, true))) {
                out.add(new Place(s, name));
            }
        }
        for (Watch.Gate g : Watch.gates(level, village)) {
            if (g.doors().isEmpty()) continue;
            BlockPos mid = g.doors().get(g.doors().size() / 2);
            for (Direction side : new Direction[]{ g.out().getClockWise(), g.out().getCounterClockWise() }) {
                BlockPos post = mid.relative(side, 2).above(2);
                BlockPos at = post.relative(g.out());
                BlockState ps = level.getBlockState(post), as = level.getBlockState(at);
                if (!ps.isFaceSturdy(level, post, g.out())) continue;
                if (!as.isAir() && !(as.getBlock() instanceof WallBannerBlock)) continue;
                out.add(new Place(new Culture.Spot(at.immutable(), g.out()), "the " + g.out().getName() + " gate"));
                break;
            }
        }
        Ledger.Building market = Culture.building(village, "market");
        if (market != null) {
            for (Culture.Spot s : two(Culture.front(level, market, 2, new int[]{ -4, 4, 0, -3, 3 }, true))) out.add(new Place(s, "the market"));
        }
        Ledger.Building theatre = Culture.building(village, Theatre.STRUCTURE);
        if (theatre != null) {
            for (int[] c : Theatre.BANNERS) {
                out.add(new Place(new Culture.Spot(Culture.at(theatre, c[0], c[1], c[2]), theatre.facing().getOpposite()), "the theatre"));
            }
        }
        return out;
    }

    private static List<Culture.Spot> two(List<Culture.Spot> all) {
        return all.subList(0, Math.min(2, all.size()));
    }

    // ------------------------------------------------------------------ the town's works

    /** When each town's banners are next looked at: soon while there is work on them, else now and then. */
    private static final Map<UUID, Long> DUE = new ConcurrentHashMap<>();
    /** What the banner waits on, in words ("2 red dye"), while it waits. */
    private static final Map<UUID, String> SHORT = new ConcurrentHashMap<>();

    static void resetForTests() {
        DUE.clear();
        SHORT.clear();
    }

    /** The town's look at its banner and its motto (Culture, each second): chosen, carved, hung, a piece at a time. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long due = DUE.get(id);
        if (due != null && now < due) return;
        choose(level, v);
        long t = level.getDayTime() % 24000L;
        boolean day = t < 12500L || t >= 23500L;                         // the town's works are done by day
        boolean more = day && (carve(level, v, false) || hang(level, v, false) || shopSign(level, v, false));
        DUE.put(id, now + (more ? 100L : 1200L));
    }

    /** Tests: the banner and the motto chosen now, whatever the land; and every piece the stores run to put up. */
    public static String putForTests(ServerLevel level, Villages.Village v) {
        choose(level, v);
        List<String> did = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            if (carve(level, v, false)) { did.add("carved"); continue; }
            if (hang(level, v, false)) { did.add("hung"); continue; }
            if (shopSign(level, v, false)) { did.add("sign"); continue; }
            break;
        }
        String s = SHORT.get(v.id());
        return String.join(",", did) + (s == null ? "" : " (short of " + s + ")");
    }

    /** Tests: the banner and the motto chosen now. */
    public static void chooseForTests(ServerLevel level, Villages.Village v) {
        choose(level, v);
    }

    /** Tests: the banner drawn afresh from the town as it is (not kept). */
    public static Design drawForTests(UUID village) {
        return draw(village);
    }

    /** Tests: the places the banner hangs, as the town stands, and whether it hangs there. */
    public static List<String> placesForTests(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        for (Place p : places(level, village)) {
            out.add(p.where() + " " + p.spot().at().toShortString() + " "
                + (level.getBlockState(p.spot().at()).getBlock() instanceof WallBannerBlock ? "hung" : "empty"));
        }
        return out;
    }

    /** Tests: where the town's banner hangs now, place by place. */
    public static List<BlockPos> hungForTests(ServerLevel level, UUID village) {
        List<BlockPos> out = new ArrayList<>();
        for (Place p : places(level, village)) if (level.getBlockState(p.spot().at()).getBlock() instanceof WallBannerBlock) out.add(p.spot().at());
        return out;
    }

    /** The next banner hung where it is missing. True while a hand is on its way or there is more the stores run to. */
    static boolean hang(ServerLevel level, Villages.Village v, boolean free) {
        UUID id = v.id();
        Design d = design(id);
        if (d == null) return false;
        Block wall = wallBanner(d.field());
        if (!(wall instanceof WallBannerBlock)) return false;
        for (Place p : places(level, id)) {
            BlockPos at = p.spot().at();
            BlockState st = level.getBlockState(at);
            if (st.getBlock() instanceof WallBannerBlock) continue;
            if (!st.isAir() || !level.getBlockState(at.below()).isAir()) continue;
            if (!wall.defaultBlockState().setValue(WallBannerBlock.FACING, p.spot().facing()).canSurvive(level, at)) continue;
            VillageFolkEntity tailor = tailor(id);
            if (!free) {
                Bench.Plan plan = plan(level, v, d);
                if (!plan.ok()) {
                    SHORT.put(id, plan.shortOf + (plan.why.isEmpty() ? "" : " (" + plan.why + ")"));
                    return false;
                }
                SHORT.remove(id);
                if (!TownJobs.atWork(level, v, WORKS, at, "hanging the town's banner on " + p.where())) return true;
                if (!Bench.take(level, v, plan, tailor)) return false;
            }
            if (!Decor.hangBanner(level, at, p.spot().facing(), item(level, id, d))) return false;
            long day = level.getDayTime() / 24000L;
            int hung = (int) Culture.num(String.valueOf(Ledger.note(id, "culture.banner.hung")), 0) + 1;
            Ledger.note(id, "culture.banner.hung", Integer.toString(hung));
            if (hung == 1 && !free) {
                Villages.tell(id, day, "the town's banner was first hung, on " + p.where()
                    + (tailor != null ? ", woven by " + tailor.displayNameCap() + " the tailor" : ""));
                if (tailor != null) tailor.persona().remember(day, "I wove the town's banner", 3);
            }
            return true;
        }
        return false;
    }

    /** Where the motto is carved: over the hall's door, on the outside. Null before the hall stands. */
    @Nullable
    static Culture.Spot overTheDoor(ServerLevel level, UUID village) {
        Ledger.Building b = Culture.hall(village);
        if (b == null) return null;
        List<Blueprints.Cell> doors = new ArrayList<>();
        int low = Integer.MAX_VALUE, front = Integer.MAX_VALUE;
        for (Blueprints.Cell c : Blueprints.cells(Ages.drawing(village, b))) {
            if (c.key().part() != BuildGoal.Part.DOOR || c.h() < 0) continue;
            doors.add(c);
            low = Math.min(low, c.h());
        }
        for (Blueprints.Cell c : doors) if (c.h() == low) front = Math.min(front, c.dz());
        List<Integer> across = new ArrayList<>();
        for (Blueprints.Cell c : doors) if (c.h() == low && c.dz() == front && !across.contains(c.dx())) across.add(c.dx());
        if (across.isEmpty()) return null;
        across.sort(Integer::compare);
        List<Integer> tries = new ArrayList<>();
        int lo = across.get(0), hi = across.get(across.size() - 1);
        tries.add(Math.floorDiv(lo + hi, 2));                          // over the middle of a pair of doors
        for (int dx : across) if (!tries.contains(dx)) tries.add(dx);
        Direction out = b.facing().getOpposite();
        for (int h = low + 2; h <= low + 3; h++) {
            for (int dx : tries) {
                BlockPos w = Culture.at(b, dx, h, front);
                BlockState ws = level.getBlockState(w);
                if (!ws.isFaceSturdy(level, w, out)) continue;
                BlockPos s = w.relative(out);
                BlockState ss = level.getBlockState(s);
                if (ss.isAir() || ss.getBlock() instanceof WallSignBlock) return new Culture.Spot(s.immutable(), out);
            }
        }
        return null;
    }

    /** The motto carved over the hall's door (and kept right): a sign out of the stores, or two of their planks. */
    static boolean carve(ServerLevel level, Villages.Village v, boolean free) {
        UUID id = v.id();
        String motto = motto(id);
        Culture.Spot spot = motto == null ? null : overTheDoor(level, id);
        if (spot == null) return false;
        String[] lines = Culture.signLines(motto);
        if (level.getBlockEntity(spot.at()) instanceof SignBlockEntity s && level.getBlockState(spot.at()).getBlock() instanceof WallSignBlock) {
            TownLife.write(s, lines);
            return false;
        }
        if (!level.getBlockState(spot.at()).isAir()) return false;
        BlockState sign = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, spot.facing());
        if (!sign.canSurvive(level, spot.at())) return false;
        if (!free) {
            if (!signInStores(level, v)) return false;
            if (!TownJobs.atWork(level, v, WORKS, spot.at(), "carving the town's motto over the hall's door")) return true;
            if (!Crafts.sign(level, v)) return false;
        }
        level.setBlock(spot.at(), sign, 3);
        if (level.getBlockEntity(spot.at()) instanceof SignBlockEntity s) TownLife.write(s, lines);
        Ledger.Building hall = Culture.hall(id);
        if (!free && hall != null) {
            Villages.tell(id, level.getDayTime() / 24000L, "the town's motto was carved over the door of " + Villages.spoken(hall.structure())
                + ": “" + motto + "”");
        }
        return true;
    }

    /** Has the motto been carved over the hall's door? */
    static boolean carved(ServerLevel level, UUID village) {
        Culture.Spot spot = overTheDoor(level, village);
        return spot != null && level.getBlockState(spot.at()).getBlock() instanceof WallSignBlock;
    }

    /** Tests: the words carved over the hall's door, a line each, or null before they are. */
    @Nullable
    public static String[] carvedForTests(ServerLevel level, UUID village) {
        Culture.Spot spot = overTheDoor(level, village);
        if (spot == null || !(level.getBlockEntity(spot.at()) instanceof SignBlockEntity s)) return null;
        String[] out = new String[4];
        for (int i = 0; i < 4; i++) out[i] = s.getFrontText().getMessage(i, false).getString();
        return out;
    }

    /** A sign put by in the stores, or the planks (or a log) to make one. */
    static boolean signInStores(ServerLevel level, Villages.Village v) {
        return Crafts.stock(level, v, s -> s.is(ItemTags.SIGNS)) > 0 || Crafts.stock(level, v, s -> s.is(ItemTags.PLANKS)) >= 2
            || Crafts.stock(level, v, s -> s.is(ItemTags.LOGS)) > 0;
    }

    // ------------------------------------------------------------------ a copy at the shop

    /** Where the shop's sign for the banner goes: on its front, beside the door, at eye height. */
    @Nullable
    static Culture.Spot shopSpot(ServerLevel level, UUID village) {
        Ledger.Building shop = Culture.building(village, "shop");
        if (shop == null) return null;
        List<Culture.Spot> s = Culture.front(level, shop, 1, new int[]{ -3, 3, -2, 2 }, false);
        return s.isEmpty() ? null : s.get(0);
    }

    /** The shop's sign for the banner put up by its door (and its price kept right). True while a hand is on its way. */
    static boolean shopSign(ServerLevel level, Villages.Village v, boolean free) {
        UUID id = v.id();
        Design d = design(id);
        Culture.Spot spot = d == null ? null : shopSpot(level, id);
        if (spot == null) return false;
        String[] lines = { SHOP_SIGN, price(d) + " coins", "for citizens", "(right-click)" };
        if (level.getBlockEntity(spot.at()) instanceof SignBlockEntity s && level.getBlockState(spot.at()).getBlock() instanceof WallSignBlock) {
            if (SHOP_SIGN.equals(s.getFrontText().getMessage(0, false).getString())) TownLife.write(s, lines);
            return false;
        }
        if (!level.getBlockState(spot.at()).isAir()) return false;
        BlockState sign = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, spot.facing());
        if (!sign.canSurvive(level, spot.at())) return false;
        if (!free) {
            if (!signInStores(level, v)) return false;
            if (!TownJobs.atWork(level, v, WORKS, spot.at(), "putting up the shop's sign for the town's banner")) return true;
            if (!Crafts.sign(level, v)) return false;
        }
        level.setBlock(spot.at(), sign, 3);
        if (level.getBlockEntity(spot.at()) instanceof SignBlockEntity s) TownLife.write(s, lines);
        return true;
    }

    /** Tests: the shop's sign put up now (out of the stores). Where it went, or null. */
    @Nullable
    public static BlockPos shopSignForTests(ServerLevel level, Villages.Village v) {
        shopSign(level, v, false);
        Culture.Spot s = shopSpot(level, v.id());
        return s != null && level.getBlockState(s.at()).getBlock() instanceof WallSignBlock ? s.at() : null;
    }

    /**
     * A player at the shop's sign: a citizen buys a copy of the town's banner, made now out of the stores by
     * the tailor's hand, for its price in coin, into the treasury. Returns what to tell them.
     */
    public static String sell(ServerLevel level, Villages.Village v, Player p) {
        UUID id = v.id();
        String town = Villages.name(id);
        Design d = design(id);
        if (d == null) return town + " has no banner yet.";
        if (!Citizens.is(id, p.getUUID())) {
            return "The banner of " + town + " is for its citizens to fly. Ask any of its folk about living here.";
        }
        Bench.Plan plan = plan(level, v, d);
        if (!plan.ok()) return "The tailor can't make one just now: short of " + plan.shortOf + ".";
        int price = price(d);
        int coins = Market.coinsHeld(p);
        if (coins < price) return "A copy of the banner of " + town + " is " + price + " coins. You have " + coins + ".";
        VillageFolkEntity tailor = tailor(id);
        if (!Bench.take(level, v, plan, tailor)) return "The stores changed under the tailor's hands; try again.";
        Market.payOut(p, price);
        Ledger.addCoins(id, price);
        Economy.sold(id, price);
        ItemStack copy = item(level, id, d);
        if (!p.getInventory().add(copy)) p.drop(copy, false);
        long day = level.getDayTime() / 24000L;
        int sold = (int) Culture.num(String.valueOf(Ledger.note(id, "culture.banner.sold")), 0) + 1;
        Ledger.note(id, "culture.banner.sold", Integer.toString(sold));
        if (sold == 1) Villages.tell(id, day, p.getName().getString() + " bought the first copy of the town's banner");
        if (tailor != null) tailor.persona().feelFor(p.getUUID(), p.getName().getString(), 2);
        return "You bought a copy of the banner of " + town + " (" + d.blazon().toLowerCase(Locale.ROOT) + ") for " + price + " coins.";
    }

    // ------------------------------------------------------------------ what the player reads

    /** The board's line: "Our banner: ... Our motto: “...”". */
    @Nullable
    static String boardLine(UUID village) {
        Design d = design(village);
        String m = motto(village);
        if (d == null && m == null) return null;
        StringBuilder sb = new StringBuilder();
        if (d != null) sb.append("Our banner: ").append(d.blazon().toLowerCase(Locale.ROOT)).append('.');
        if (m != null) sb.append(sb.length() > 0 ? " " : "").append("Our motto: “").append(m).append("”.");
        return sb.toString();
    }

    /** The Culture page's banner: its field and charges (drawn on the page), in words, where it hangs, what it waits on, the motto. */
    static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        Design d = design(id);
        if (d != null) {
            out.putString("field", d.field().getName());
            ListTag layers = new ListTag();
            for (Layer l : d.layers()) {
                CompoundTag t = new CompoundTag();
                t.putString("pattern", l.charge().key.location().toString());
                t.putString("colour", l.colour().getName());
                layers.add(t);
            }
            out.put("layers", layers);
            out.putString("blazon", d.blazon());
            out.putInt("price", price(d));
        } else {
            out.putString("blazon", "not yet drawn: the founders choose it once the town's land has been looked over");
        }
        String m = motto(id);
        out.putString("motto", m == null ? "" : m);
        out.putBoolean("carved", carved(level, id));
        Ledger.Building hall = Culture.hall(id);
        out.putString("hall", hall == null ? "" : Villages.spoken(hall.structure()));
        List<String> where = new ArrayList<>();
        int hung = 0;
        List<Place> places = places(level, id);
        for (Place p : places) {
            boolean up = level.getBlockState(p.spot().at()).getBlock() instanceof WallBannerBlock;
            if (up) hung++;
            if (up && !where.contains(p.where())) where.add(p.where());
        }
        out.putInt("hung", hung);
        out.putInt("places", places.size());
        out.put("where", Culture.strings(where));
        String s = SHORT.get(id);
        out.putString("short", s == null ? "" : s);
        VillageFolkEntity tailor = tailor(id);
        out.putString("tailor", tailor == null ? "" : tailor.displayNameCap());
        out.putBoolean("shop", shopSpot(level, id) != null);
        out.putInt("sold", (int) Culture.num(String.valueOf(Ledger.note(id, "culture.banner.sold")), 0));
        return out;
    }
}
