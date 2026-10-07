package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Districts;
import com.jrpetty.mcassistant.village.Districts.District;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The town's quarters, as the village lives them (village/Districts has the arithmetic).
 * <ul>
 * <li><b>Where things go.</b> The market's trades round the square, the crafts' smoke and clangour
 *     on one side of the town (chosen once: where its smeltery already stands, else toward its
 *     mines, else the side next round from the fields), the farmland on its own, and the homes on
 *     the sides that are left. The builders are offered a building's own quarter's lots first.</li>
 * <li><b>Smoke and noise.</b> A home within sixteen blocks of a smeltery, a smithy, a workshop or
 *     a brewery that has been at work in the last two days (its furnaces lit, or its hands at it),
 *     or within twenty of a mine's head while the miner is working it, is a worse place to live:
 *     its folk are a little the less content for it, and say why ("the smithy's hammering keeps me
 *     up"), and the house sells and lets for a little less. A smith does not mind its own forge.</li>
 * <li><b>The park</b> (Park): a home within twenty-four blocks of it is the happier, and the dearer.</li>
 * </ul>
 * All of it shows: on a folk's card, on the Buildings page (each building's quarter, and a map of
 * them), on the Why page (the homes in the smoke, the homes by the park) and the Society page.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Quarters {

    private Quarters() {}

    /** How near a working smeltery, smithy, workshop or brewery a home is in its smoke and din. */
    public static final int SMOKE_REACH = 16;
    /** How near a mine's head a home hears the picks. */
    public static final int MINE_REACH = 20;
    /** How much the smoke and the din take off a folk's spirits. */
    static final int SMOKE_MOOD = 5;
    /** What a house in the smoke sells and lets for, and one by the park, in percent of the going price. */
    public static final int SMOKY_PERCENT = 85, PARK_PERCENT = 115;
    /** How many days after it was last seen at work a building still counts as a working one. */
    private static final long LATELY = 2L;
    /** Where the village keeps the side its crafts went on (TownPlan.NORTH..WEST). */
    private static final String NOTE = "crafts.side";

    /** A building or a mine at work near enough the town to trouble its homes. */
    public record Source(BlockPos at, String structure, boolean noise) {
        /** "the smithy's hammering", "the smeltery's smoke". */
        public String what() { return Quarters.what(structure); }
    }

    private static final Map<UUID, List<Source>> SOURCES = new ConcurrentHashMap<>();
    /** The day each industry building (by its anchor) or mine (by its middle) was last seen at work. */
    private static final Map<Long, Long> WORKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SCANNED = new ConcurrentHashMap<>();
    /** Today, as the clock last read it (for the chronicle, where there is no level to hand). */
    private static volatile long today;
    /** The furnaces (and the like) in each building's drawing, worked out once (by its anchor). */
    private static final Map<Long, List<BlockPos>> FIRES = new ConcurrentHashMap<>();

    public static void resetForTests() {
        SOURCES.clear();
        WORKED.clear();
        SCANNED.clear();
        FIRES.clear();
    }

    // ------------------------------------------------------------------ the sides

    /** The side of its farmland, as the village chose it (-1 if not yet). */
    public static int farmSide(@Nullable UUID village) {
        return Villages.fieldsSide(village);
    }

    /**
     * The side the village's crafts go on, chosen the first time it is asked and kept (in the ledger,
     * with the world): where an industry building already stands, else toward the mines, else the
     * side next round from the farmland. If the fields are later laid out on that side, the crafts
     * move round to the next (what stands stays where it is).
     */
    public static int craftSide(@Nullable UUID village) {
        if (village == null) return -1;
        int farm = Villages.fieldsSide(village);
        String note = Ledger.note(village, NOTE);
        int had = -1;
        if (note != null && !note.isEmpty()) {
            try {
                had = Integer.parseInt(note.trim());
            } catch (NumberFormatException ignored) {
                had = -1;
            }
        }
        if (had >= 0 && had <= 3 && had != farm) return had;
        Villages.Village v = Villages.get(village);
        if (v == null) return -1;
        int[] toward = toward(village, v.centre());
        int side = Districts.chooseCraftSide(farm, toward, homesBySide(village, v.centre()));
        // Kept once there is something to go by (the fields' side, a smeltery, a mine); till then, as it
        // stands today (the side with fewest homes), asked again each time.
        if (farm < 0 && toward == null) return side;
        Ledger.note(village, NOTE, Integer.toString(side));
        if (had >= 0 && had != side) {
            Villages.tell(village, today, "the craft quarter moved round to the " + Districts.sideWord(side)
                + " side: the fields took the " + Districts.sideWord(had));
        }
        return side;
    }

    /** How many homes stand on each side of the heart, past the lots round the square. */
    private static int[] homesBySide(UUID village, BlockPos heart) {
        int[] n = new int[4];
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (!Homes.isHome(b.structure())) continue;
            int dx = b.anchor().getX() - heart.getX(), dz = b.anchor().getZ() - heart.getZ();
            if (Math.max(Math.abs(dx), Math.abs(dz)) <= Districts.ROUND_THE_SQUARE) continue;
            int[] other = { -1 };
            n[Districts.sideOf(dx, dz, other)]++;
            if (other[0] >= 0) n[other[0]]++;
        }
        return n;
    }

    /** Where the crafts would most like to be: the first industry building that stands, else the nearest mine. */
    @Nullable
    private static int[] toward(UUID village, BlockPos heart) {
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (Districts.isIndustry(b.structure())) {
                return new int[]{ b.anchor().getX() - heart.getX(), b.anchor().getZ() - heart.getZ() };
            }
        }
        int[] best = null;
        long nearest = Long.MAX_VALUE;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.stationTask() != AssistantEntity.StationTask.MINE || a.workZone() == null) continue;
            BlockPos c = a.workZone().center();
            long d = (long) (c.getX() - heart.getX()) * (c.getX() - heart.getX()) + (long) (c.getZ() - heart.getZ()) * (c.getZ() - heart.getZ());
            if (d < nearest) { nearest = d; best = new int[]{ c.getX() - heart.getX(), c.getZ() - heart.getZ() }; }
        }
        return best;
    }

    /** Which quarter of the village this spot is in. */
    public static District districtOf(UUID village, BlockPos heart, BlockPos at) {
        return Districts.of(at.getX() - heart.getX(), at.getZ() - heart.getZ(), farmSide(village), craftSide(village));
    }

    /** Which quarter a building stands in (the wall is the square's). */
    public static District districtOf(UUID village, BlockPos heart, Ledger.Building b) {
        if (b.structure().equals("fortify")) return District.SQUARE;
        return districtOf(village, heart, b.anchor());
    }

    // ------------------------------------------------------------------ where a building goes

    /**
     * The plan's lots for a building (TownPlan.candidates), its own quarter's first (Districts.order):
     * what the builders choose a lot from (Villages.siteFor).
     */
    public static List<TownPlan.Lot> candidates(UUID village, String project) {
        List<TownPlan.Lot> plan = TownPlan.candidates(project);
        if (TownLook.byTheFields(project)) return TownLook.fieldLots(village, plan);   // [batchE] the mill, the orchard, the allotments
        if (Districts.forBuilding(project) == null) return plan;
        return Districts.order(plan, project, farmSide(village), craftSide(village));
    }

    /** What a lot outside a building's own quarter counts against it, in blocks of building work
     *  (Villages.siteFor weighs a lot by the work it wants): enough that a slope in its own quarter
     *  beats flat ground in the wrong one, not so much that a cliff does. */
    public static int misfit(UUID village, String project, TownPlan.Lot lot) {
        District want = Districts.forBuilding(project);
        if (want == null) return 0;
        int score = 40 * Districts.rank(lot, want, farmSide(village), craftSide(village));
        // A home (or the park) is kept out of the smoke of the crafts at work, while there is room elsewhere.
        Villages.Village v = Villages.get(village);
        if (want == District.HOMES && v != null && troubling(village, v.centre().offset(lot.x(), 0, lot.z()), null) != null) score += 30;
        return score;
    }

    // ------------------------------------------------------------------ smoke and noise

    /** Does this kind of building clang (or saw, or rattle) rather than smoke? */
    static boolean noisy(String structure) {
        String s = structure.toLowerCase(java.util.Locale.ROOT);
        return s.contains("smith") || s.contains("forge") || s.equals("workshop") || s.equals("mine") || s.contains("mill")
            || s.contains("quarry") || s.endsWith("works");
    }

    /** What troubles the neighbours about it, as they would say it. */
    static String what(String structure) {
        return switch (structure) {
            case "smeltery" -> "the smeltery's smoke";
            case "smithy" -> "the smithy's hammering";
            case "workshop" -> "the workshop's racket";
            case "brewery" -> "the brewery's reek";
            case "mine" -> "the picks in the mine";
            default -> "the " + structure + (noisy(structure) ? "'s din" : "'s smoke");
        };
    }

    /** The building itself, as the neighbours would point at it. */
    static String place(String structure) {
        return structure.equals("mine") ? "the mine's head" : "the " + structure;
    }

    /** Every so often: which of the village's industry buildings and mines are at work. */
    public static void scan(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        today = day;
        List<Source> out = new ArrayList<>();
        for (Ledger.Building b : Ledger.buildings(id)) {
            if (!Districts.isIndustry(b.structure())) continue;
            long key = b.anchor().asLong();
            if (Land.areaLoaded(level, b.anchor(), 6) && working(level, id, b)) WORKED.put(key, day);
            Long last = WORKED.get(key);
            if (last != null && day - last <= LATELY) out.add(new Source(b.anchor(), b.structure(), noisy(b.structure())));
        }
        // A mine's head, while its miner works it: only one near the town troubles anybody.
        int reach = Villages.townReach(id) + MINE_REACH;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a.stationTask() != AssistantEntity.StationTask.MINE || a.workZone() == null) continue;
            BlockPos c = a.workZone().center();
            if (Math.max(Math.abs(c.getX() - v.centre().getX()), Math.abs(c.getZ() - v.centre().getZ())) > reach) continue;
            long key = c.asLong() * 31L + 7L;
            boolean atIt = !(a instanceof VillageFolkEntity f) || !f.offWorkNow();
            if (atIt) WORKED.put(key, day);
            Long last = WORKED.get(key);
            if (last != null && day - last <= LATELY) out.add(new Source(c, "mine", true));
        }
        SOURCES.put(id, List.copyOf(out));
        SCANNED.put(id, level.getGameTime());
    }

    /** Is this industry building at work: a fire lit in it, or a hand of its trade at work by it? */
    static boolean working(ServerLevel level, UUID village, Ledger.Building b) {
        for (BlockPos p : FIRES.computeIfAbsent(b.anchor().asLong(), k -> fires(b))) {
            BlockState st = level.getBlockState(p);
            if (st.hasProperty(AbstractFurnaceBlock.LIT) && st.getValue(AbstractFurnaceBlock.LIT)) return true;
            if (st.getBlock() instanceof CampfireBlock && st.getValue(CampfireBlock.LIT)) return true;
        }
        int[] half = BuildGoal.footprint(b.structure());
        int r = Math.max(half[0], half[1]) + 6;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.offWorkNow()) continue;
            String works = VillageFolkEntity.buildingFor(f.stationTask());
            // Its own trade's hands; or, for a building no trade is kept for, whoever's work is in it.
            boolean ofIt = b.structure().equals(works)
                || (!tradeFor(b.structure()) && f.workZone() != null
                    && Math.max(Math.abs(f.workZone().center().getX() - b.anchor().getX()),
                                Math.abs(f.workZone().center().getZ() - b.anchor().getZ())) <= Math.max(half[0], half[1]) + 1);
            if (!ofIt) continue;
            if (Math.abs(f.getX() - b.anchor().getX()) <= r && Math.abs(f.getZ() - b.anchor().getZ()) <= r) return true;
        }
        return false;
    }

    /** Is there a trade that works in a building of this kind (VillageFolkEntity.buildingFor)? */
    private static boolean tradeFor(String structure) {
        for (AssistantEntity.StationTask t : AssistantEntity.StationTask.values()) {
            if (structure.equals(VillageFolkEntity.buildingFor(t))) return true;
        }
        return false;
    }

    /** The furnaces, smokers and hearths in a building's drawing. */
    private static List<BlockPos> fires(Ledger.Building b) {
        List<BlockPos> out = new ArrayList<>();
        for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
            if (p.part() == BuildGoal.Part.FURNACE || p.part() == BuildGoal.Part.SMOKER || p.part() == BuildGoal.Part.CAMPFIRE) {
                out.add(p.pos());
            }
        }
        return List.copyOf(out);
    }

    /** The industry at work round a village now (as last looked at). */
    public static List<Source> sources(@Nullable UUID village) {
        return village == null ? List.of() : SOURCES.getOrDefault(village, List.of());
    }

    /**
     * What smoke or din reaches a home here, the nearest, or null. {@code own}: the trade of whoever
     * lives there, whose own fire does not trouble it (null: the house itself, for its price).
     */
    @Nullable
    public static Source troubling(@Nullable UUID village, @Nullable BlockPos home, @Nullable AssistantEntity.StationTask own) {
        if (village == null || home == null) return null;
        String mine = own == null ? null : own == AssistantEntity.StationTask.MINE ? "mine" : VillageFolkEntity.buildingFor(own);
        Source best = null;
        double bestD = Double.MAX_VALUE;
        for (Source s : sources(village)) {
            if (s.structure().equals(mine)) continue;
            int reach = s.structure().equals("mine") ? MINE_REACH : SMOKE_REACH;
            double dx = s.at().getX() - home.getX(), dz = s.at().getZ() - home.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > reach || Math.abs(s.at().getY() - home.getY()) > 16) continue;
            if (d < bestD) { bestD = d; best = s; }
        }
        return best;
    }

    /**
     * A folk's spirits, as where it lives has them (VillageFolkEntity.refreshMood): the smoke or the
     * din of the crafts at work near its bed, a little less; by the park, a little more; an hour in
     * the park today, a little more again. Each with its reason, for it to give when asked.
     */
    public static int mood(VillageFolkEntity f, int m, List<Object[]> why) {
        UUID village = f.ownerId();
        BlockPos home = f.bedPos();
        if (village == null || home == null || f.isBaby()) return m;
        Source s = troubling(village, home, f.stationTask());
        if (s != null) {
            int hit = SMOKE_MOOD + (f.life().has(Social.Trait.GRUMPY) ? 2 : 0) - (f.life().has(Social.Trait.EASYGOING) ? 2 : 0);
            m -= hit;
            why.add(new Object[]{ s.noise() ? "noise" : "smoke", hit });
        }
        if (Park.near(village, home)) {
            m += Park.MOOD_BY;
            why.add(new Object[]{ "parkside", Park.MOOD_BY });
        }
        if (Park.visitedToday(f)) {
            m += Park.MOOD_VISIT;
            why.add(new Object[]{ "park", Park.MOOD_VISIT });
        }
        return m;
    }

    /** A folk's words for one of these reasons (FolkTalk.reason). */
    public static String words(VillageFolkEntity f, String why) {
        RandomSource r = f.getRandom();
        Source s = troubling(f.ownerId(), f.bedPos(), f.stationTask());
        String it = s == null ? "the works next door" : s.what();
        return switch (why) {
            case "noise" -> s != null && s.structure().equals("smithy")
                ? FolkTalk.pick(r, "The smithy's hammering keeps me up.", "Clang, clang, clang — that smithy never stops.")
                : FolkTalk.pick(r, cap(it) + " keeps me up.", "I can't hear myself think for " + it + ".");
            case "smoke" -> s != null && s.structure().equals("smeltery")
                ? FolkTalk.pick(r, "The smeltery's smoke gets into the washing.", "We're downwind of the smeltery. The smoke!")
                : FolkTalk.pick(r, cap(it) + " gets into everything.", "I wish we lived further from " + it + ".");
            case "parkside" -> FolkTalk.pick(r, "We live by the park — I can hear the fountain from my window.",
                "There's the park, right on our street. Lovely, it is.");
            case "park" -> FolkTalk.pick(r, "I sat by the fountain in the park a while.", "An hour in the park does a body good.");
            default -> "";
        };
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * What a house here sells and lets for, in percent of the going price (Homes.price, Homes.rent):
     * less in the smoke, more by the park (both: as it comes).
     */
    public static int homePercent(@Nullable UUID village, @Nullable BlockPos anchor) {
        if (village == null || anchor == null || anchor.equals(BlockPos.ZERO)) return 100;
        int p = 100;
        if (troubling(village, anchor, null) != null) p += SMOKY_PERCENT - 100;
        if (Park.near(village, anchor)) p += PARK_PERCENT - 100;
        return p;
    }

    /** A house's rent (Homes.rent), as where it stands has it: a rent of three coins or more is cut or
     *  raised; one of a coin or two is too small to shave. */
    public static int rent(@Nullable UUID village, @Nullable BlockPos anchor, int rent) {
        int p = homePercent(village, anchor);
        if (p == 100 || rent < 3) return rent;
        return Math.max(1, Math.round(rent * p / 100.0F));
    }

    // ------------------------------------------------------------------ what a player sees

    /** The card's line about where a folk lives: its quarter, the smoke, the park, the price. */
    public static String cardLine(VillageFolkEntity f) {
        UUID village = f.ownerId();
        Villages.Village v = Villages.get(village);
        BlockPos home = f.bedPos();
        if (v == null || home == null) return "";
        StringBuilder sb = new StringBuilder("In ").append(districtOf(village, v.centre(), home).words);
        Source s = troubling(village, home, f.stationTask());
        if (s != null) sb.append(", beside ").append(place(s.structure())).append(": ")
            .append(s.noise() ? "the noise keeps it up at night" : "the smoke gets into everything");
        if (Park.near(village, home)) sb.append(s != null ? "; but" : ",").append(" by the park, and the happier for it");
        int p = homePercent(village, Homes.homeOf(f) != null ? Homes.homeOf(f) : home);
        if (p != 100) sb.append(" (the house is worth ").append(p < 100 ? "less" : "more").append(" for it)");
        return sb.append('.').toString();
    }

    /** A building's quarter and neighbourhood, into its row of the Buildings page (Annals). */
    public static void describe(UUID village, BlockPos heart, Ledger.Building b, CompoundTag c) {
        District d = districtOf(village, heart, b);
        c.putString("district", d.label);
        c.putString("quarter", d.words);
        c.putInt("dx", b.anchor().getX() - heart.getX());
        c.putInt("dz", b.anchor().getZ() - heart.getZ());
        int[] half = BuildGoal.footprint(b.structure());
        boolean turned = b.facing().getAxis() == net.minecraft.core.Direction.Axis.X;
        c.putInt("hx", turned ? half[1] : half[0]);
        c.putInt("hz", turned ? half[0] : half[1]);
        if (Homes.isHome(b.structure())) {
            Source s = troubling(village, b.anchor(), null);
            if (s != null) c.putString("smoke", s.what());
            if (Park.near(village, b.anchor())) c.putBoolean("parkside", true);
        }
        if (Districts.isIndustry(b.structure())) c.putBoolean("working", isSource(village, b.anchor()));
    }

    private static boolean isSource(UUID village, BlockPos at) {
        for (Source s : sources(village)) if (s.at().equals(at)) return true;
        return false;
    }

    /**
     * The quarters for the town's books (Annals): how the town is laid out, the homes in the smoke and
     * the homes by the park, the park itself, and the mines' heads for the map.
     */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        int farm = farmSide(id), craft = craftSide(id);
        out.putInt("farm_side", farm);
        out.putInt("craft_side", craft);
        out.putString("plan", planLine(id));
        ListTag smoky = new ListTag(), parkside = new ListTag(), mines = new ListTag();
        Map<District, Integer> counts = new EnumMap<>(District.class);
        for (Ledger.Building b : Ledger.buildings(id)) {
            counts.merge(districtOf(id, v.centre(), b), 1, Integer::sum);
            if (!Homes.isHome(b.structure()) || b.structure().equals("townhall")) continue;
            String where = addressOf(id, v, b);
            String who = residents(id, b);
            Source s = troubling(id, b.anchor(), null);
            if (s != null && smoky.size() < 24) {
                int d = (int) Math.round(Math.sqrt(s.at().distSqr(b.anchor().atY(s.at().getY()))));
                smoky.add(StringTag.valueOf(where + (who.isEmpty() ? "" : " (" + who + ")") + ": " + s.what() + ", " + d + " blocks off"));
            }
            if (Park.near(id, b.anchor()) && parkside.size() < 24) {
                parkside.add(StringTag.valueOf(where + (who.isEmpty() ? "" : " (" + who + ")")));
            }
        }
        for (Source s : sources(id)) {
            if (!s.structure().equals("mine")) continue;
            CompoundTag m = new CompoundTag();
            m.putInt("dx", s.at().getX() - v.centre().getX());
            m.putInt("dz", s.at().getZ() - v.centre().getZ());
            mines.add(m);
        }
        out.put("smoky", smoky);
        out.put("parkside", parkside);
        out.put("mines", mines);
        CompoundTag c = new CompoundTag();
        for (Map.Entry<District, Integer> e : counts.entrySet()) c.putInt(e.getKey().label, e.getValue());
        out.put("counts", c);
        out.putString("park", Park.status(level, v));
        return out;
    }

    /** How the town is laid out, in a line. */
    public static String planLine(UUID village) {
        int farm = farmSide(village), craft = craftSide(village);
        List<String> homes = new ArrayList<>();
        for (int s = 0; s < 4; s++) if (s != farm && s != craft) homes.add(Districts.sideWord(s));
        return "The market round the square" + (farm >= 0 ? " and out toward the fields" : "")
            + "; the crafts to the " + Districts.sideWord(craft)
            + (farm >= 0 ? "; the farmland to the " + Districts.sideWord(farm) : "")
            + "; the homes to the " + String.join(" and ", homes) + ".";
    }

    private static String addressOf(UUID village, Villages.Village v, Ledger.Building b) {
        String[] a = TownLife.address(village, v.centre(), b);
        return a == null ? "the " + b.structure() + " at " + b.anchor().getX() + ", " + b.anchor().getZ() : a[0] + ", " + a[1];
    }

    private static String residents(UUID village, Ledger.Building b) {
        List<String> names = new ArrayList<>();
        int[] half = BuildGoal.footprint(b.structure());
        int r = Math.max(half[0], half[1]);
        for (AssistantEntity a : Villages.folkOf(village)) {
            BlockPos bed = a.bedPos();
            if (bed == null || Math.abs(bed.getX() - b.anchor().getX()) > r || Math.abs(bed.getZ() - b.anchor().getZ()) > r
                || Math.abs(bed.getY() - b.anchor().getY()) > 6) continue;
            names.add(a.displayNameCap());
            if (names.size() >= 3) break;
        }
        return String.join(", ", names);
    }

    /** How many of the folk live in the smoke, and how many by the park, and how many are there now (Society). */
    public static void society(UUID village, List<VillageFolkEntity> folk, CompoundTag c) {
        int smoky = 0, parkside = 0, there = 0;
        for (VillageFolkEntity f : folk) {
            BlockPos bed = f.bedPos();
            if (bed != null && troubling(village, bed, f.stationTask()) != null) smoky++;
            if (bed != null && Park.near(village, bed)) parkside++;
            if (Park.doing(f) != null) there++;
        }
        c.putInt("smoky", smoky);
        c.putInt("parkside", parkside);
        c.putInt("at_park", there);
    }

    /** Tests: what a plain house standing here would sell for, and its day's rent, as the village's books
     *  have them (Homes.price, Homes.rent): {price, rent}. */
    public static int[] termsForTests(UUID village, BlockPos anchor) {
        Homes.Home h = new Homes.Home(anchor, "house");
        return new int[]{ Homes.price(village, h), Homes.rent(village, h) };
    }

    // ------------------------------------------------------------------ the command

    /**
     * /village districts: the nearest town's quarters, the homes in the smoke and by the park, and its
     * park. For operators: /village districts park now (the park put up at once on its lot, as the
     * showcase does, and everybody off work sent to it: for the photographs), /village districts map
     * (the books opened at the Buildings page's map).
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("districts").executes(Quarters::tellDistricts)
            .then(Commands.literal("map").executes(Quarters::openMap))
            .then(Commands.literal("park").requires(src -> src.hasPermission(2))
                .then(Commands.literal("now").executes(ctx -> Park.now(ctx)))
                .then(Commands.literal("visit").executes(ctx -> Park.callEveryone(ctx))));
    }

    @Nullable
    static Villages.Village near(CommandContext<CommandSourceStack> ctx) {
        BlockPos here = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(ctx.getSource().getLevel(), here, Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        return v;
    }

    private static int tellDistricts(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        scan(level, v);
        CompoundTag r = report(level, v);
        StringBuilder sb = new StringBuilder("DISTRICTS " + Villages.name(v.id()) + ": " + r.getString("plan"));
        sb.append(" | buildings by quarter ").append(r.getCompound("counts"));
        sb.append(" | at work: ");
        List<String> at = new ArrayList<>();
        for (Source s : sources(v.id())) at.add(s.structure() + " " + s.at().getX() + " " + s.at().getZ());
        sb.append(at.isEmpty() ? "nothing" : String.join(", ", at));
        ListTag smoky = r.getList("smoky", net.minecraft.nbt.Tag.TAG_STRING), park = r.getList("parkside", net.minecraft.nbt.Tag.TAG_STRING);
        sb.append(" | in the smoke and din: ").append(smoky.size());
        for (int i = 0; i < Math.min(6, smoky.size()); i++) sb.append("; ").append(smoky.getString(i));
        sb.append(" | by the park: ").append(park.size());
        for (int i = 0; i < Math.min(6, park.size()); i++) sb.append("; ").append(park.getString(i));
        sb.append(" | ").append(r.getString("park"));
        String text = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int openMap(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null || !(ctx.getSource().getEntity() instanceof ServerPlayer p)) {
            ctx.getSource().sendFailure(Component.literal("No village yet, or nobody to show it to."));
            return 0;
        }
        scan(ctx.getSource().getLevel(), v);
        CompoundTag books = Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putInt("tab", 10);                                          // the Buildings page (CityScreen.TABS)
        books.putBoolean("map", true);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    // ------------------------------------------------------------------ the clock

    /** Every second, a fifth of the villages: what is at work (every ten seconds each), and the park tended. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % 20 != 13) return;
        int phase = (tick / 20) % 5;
        com.jrpetty.mcassistant.Guard.run("quarters", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    ParkGround.watch(level, v);                    // every second: a fountain that would not hold, emptied
                    if (Math.floorMod(v.id().hashCode() >> 3, 5) != phase) continue;
                    if (level.getGameTime() - SCANNED.getOrDefault(v.id(), -100000L) >= 200L) scan(level, v);
                    Park.tend(level, v);
                }
            }
        });
    }
}
