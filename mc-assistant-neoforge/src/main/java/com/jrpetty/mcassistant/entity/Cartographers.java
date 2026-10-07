package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [cartographer] The cartographer: the town's maps, and the old places round it.
 *
 * <p><b>The trade.</b> A Stone Age town that has sent out scouts, or has thirty folk, wants a cartographer and a map
 * room for it to work in (blueprints/maproom.txt: the cartography table between two bookcases, the lectern, the
 * chests, a wall for the cartographer's own maps). Once the map room stands the town chooses one (appoint): a scout
 * who knows the land, else the most curious and patient of its folk, never the last hand of a trade the town is short
 * of. One, however big the town.
 *
 * <p><b>Its day.</b> At the map room's table (the station brain, work): paper made of the stores' sugar cane and a
 * compass of their iron and redstone when the town runs short, as the game makes them; a commission a player paid for
 * walked first; the hall's map when it is due (a week old, or the town grown), the region's every fortnight; and
 * between them the search for the old places round the town (MapFinds), a kind at a time. The walking itself is
 * MapSurveys': the sheets in its pack fill in as the game fills a map, from the ground it walks over.
 *
 * <p><b>Players.</b> It sells real explorer maps for coin, priced by how far and how rare (Prices.mapOf): the ocean
 * explorer map to a monument, the woodland explorer map to a mansion, a buried treasure map (one the town brought
 * home from a wreck, or treasure the town has found itself), a map to any place the town knows of, and a copy of the
 * hall's map of the town, every sheet. A player may commission a map ("map me the land to the east"): paid for when it
 * is asked, walked that day, left ready in the map room's chest with the player's name on it. A quest that sends a
 * player somewhere far comes with a map to it from the map room (QuestRun.accept).
 *
 * <p><b>The town knows.</b> Its card, its talk, the board, the gazette ("From the map room"), the chronicle, its trade
 * book, and the Maps page of the town's books (the region drawn as the cartographer drew it, every place found, the
 * archive of old walls).
 */
public final class Cartographers {

    private Cartographers() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** A Stone Age town of this many wants a cartographer (sooner, if it has scouts out). */
    public static final int FROM = 30;
    /** The map room. */
    public static final String STRUCTURE = "maproom";
    /** Ticks between pieces of work at the table. */
    static final int PACE = 200;
    /** Ticks between looks for the old places round the town. */
    static final int LOOK_EVERY = 2400;
    /** The stores keep this much paper and these compasses for the map room. */
    static final int PAPER_KEPT = 24, COMPASSES_KEPT = 2;

    private static final Map<UUID, Integer> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LOOKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> TICKED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LAST.clear();
        LOOKED.clear();
        TICKED.clear();
        MapSurveys.resetForTests();
    }

    // ------------------------------------------------------------------ the trade opening

    /** Does the town want a cartographer: the Stone Age or later, and scouts out or thirty folk? */
    public static boolean wanted(@Nullable UUID village) {
        if (village == null) return false;
        return opens(Villages.ageOf(village), Villages.headcount(village), hasScouts(village));
    }

    /** The rule itself: from the Stone Age, once the town has scouts out or thirty folk. */
    public static boolean opens(Villages.Age age, int folk, boolean scouts) {
        return age.ordinal() >= Villages.Age.STONE.ordinal() && (folk >= FROM || scouts);
    }

    static boolean hasScouts(UUID village) {
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == StationTask.SCOUT) return true;
        return !Scouts.atlas(village).isEmpty();
    }

    /** The map room wanted on the town's list: the trade wanted, and no map room yet. */
    public static boolean wantsMapRoom(@Nullable UUID village) {
        return village != null && wanted(village) && mapRoom(village) == null;
    }

    /** Can a cartographer work: wanted, and its map room stands? */
    public static boolean ready(@Nullable UUID village) {
        return village != null && wanted(village) && mapRoom(village) != null;
    }

    /** Why the town builds a map room (Villages.whyBuild). */
    public static String why(UUID village) {
        return "a map room for a cartographer: the town's map walked and drawn for the hall, the country round it, and"
            + (hasScouts(village) ? " the old places the scouts' land holds" : " the old places round it") + " found and put on explorer maps";
    }

    @Nullable
    public static Ledger.Building mapRoom(UUID village) {
        return Visitors.building(village, STRUCTURE);
    }

    /** The town's cartographer, or null. */
    @Nullable
    public static VillageFolkEntity cartographer(@Nullable UUID village) {
        if (village == null) return null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && !f.isBaby() && f.stationTask() == StationTask.CARTOGRAPHER) return f;
        }
        return null;
    }

    /** Does the cartographer keep the hall's map (so the clerk's waits: MapRoom)? */
    public static boolean keepsTheHallMap(UUID village) {
        return cartographer(village) != null;
    }

    /** The cartography table in the map room, or null. */
    @Nullable
    public static BlockPos tableAt(ServerLevel level, UUID village) {
        Ledger.Building b = mapRoom(village);
        return b == null ? null : Trades.find(level, b.anchor(), 5, Blocks.CARTOGRAPHY_TABLE);
    }

    /** The map room's chests: the first for the players' maps waiting, the last the archive. */
    static List<BlockPos> chests(ServerLevel level, UUID village) {
        List<BlockPos> out = new ArrayList<>();
        Ledger.Building b = mapRoom(village);
        if (b == null || !Land.areaLoaded(level, b.anchor(), 6)) return out;
        for (BlockPos p : BlockPos.betweenClosed(b.anchor().offset(-4, -1, -4), b.anchor().offset(4, 2, 4))) {
            if (level.getBlockState(p).is(Blocks.CHEST)) out.add(p.immutable());
        }
        out.sort((a, c) -> Long.compare(a.asLong(), c.asLong()));
        return out;
    }

    @Nullable
    static BlockPos archiveChest(ServerLevel level, UUID village) {
        List<BlockPos> c = chests(level, village);
        return c.isEmpty() ? null : c.get(c.size() - 1);
    }

    @Nullable
    static BlockPos readyChest(ServerLevel level, UUID village) {
        List<BlockPos> c = chests(level, village);
        return c.isEmpty() ? null : c.get(0);
    }

    // ------------------------------------------------------------------ the town chooses its cartographer

    /** Each village, every half a minute (VillageFolkEntity, beside Bank.tick): a cartographer appointed once it can work. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = TICKED.get(id);
        if (last != null && now - last < 600L && now >= last) return;
        TICKED.put(id, now);
        if (!ready(id) || cartographer(id) != null) return;
        appoint(level, v, level.getDayTime() / 24000L);
    }

    /**
     * Who the town would make its cartographer, the best first: a grown folk free of a trade the town is short of, by
     * its knack for it (score). [cartographer] Interviews: when the town interviews for its posts (a later day's
     * Interviews), the cartographer's post goes through them: this list is who it would interview, in its order.
     */
    public static List<VillageFolkEntity> candidates(Villages.Village v) {
        UUID id = v.id();
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.isHired() || !f.isAlive()) continue;
            if (f.trip() != null || f.expedition() != null) continue;
            StationTask t = f.stationTask();
            if (t == StationTask.CARTOGRAPHER || t == StationTask.BANK || t == StationTask.CAVE || t == StationTask.FERRY) continue;
            if (t == StationTask.STORE && hands(id, t) < 2) continue;
            // Never the last of a trade the town is short of; a scout leaves the scouting to the others.
            if (t != StationTask.NONE && t != StationTask.SCOUT && !Villages.overStaffed(id, t) && hands(id, t) < 3) continue;
            if (t == StationTask.SCOUT && hands(id, t) < 2 && Villages.headcount(id) >= Scouts.FROM) continue;
            out.add(f);
        }
        out.sort((a, b) -> Integer.compare(score(b), score(a)));
        return out;
    }

    static int hands(UUID village, StationTask t) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == t) n++;
        return n;
    }

    /** A folk's knack for maps: years scouting, a curious mind, patience, a steady hand; and the scouts' land in its head. */
    public static int score(VillageFolkEntity f) {
        int s = f.tradeLevel(StationTask.SCOUT) * 4 + f.tradeLevel(StationTask.CARTOGRAPHER) * 6;
        if (f.stationTask() == StationTask.SCOUT) s += 20;
        if (f.stationTask() == StationTask.NONE) s += 6;
        if (f.life().has(Social.Trait.CURIOUS)) s += 14;
        if (f.life().has(Social.Trait.SHY)) s += 4;
        if (f.life().has(Social.Trait.HARDWORKING)) s += 4;
        if (f.life().has(Social.Trait.EASYGOING)) s -= 4;
        s += Math.min(10, f.ageYears() / 8);
        return s;
    }

    /** The best of the candidates made the town's cartographer, its post at the map room. Who, or null. */
    @Nullable
    static VillageFolkEntity appoint(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Ledger.Building b = mapRoom(id);
        if (b == null) return null;
        List<VillageFolkEntity> c = candidates(v);
        if (c.isEmpty()) return null;
        VillageFolkEntity best = c.get(0);
        StationTask was = best.stationTask();
        best.setStation(b.anchor(), StationTask.CARTOGRAPHER);
        best.assignPlot(WorkZone.around(b.anchor(), 4, WorkZone.DEFAULT_DEPTH), "The Map Room");
        String why = was == StationTask.SCOUT ? "a scout who knows the land" : best.life().has(Social.Trait.CURIOUS) ? "the most curious of us"
            : "a steady hand";
        Villages.tell(id, day, best.displayNameCap() + " (" + why + ") " + (was == StationTask.NONE ? "took up" : "gave up " + was.label + " for")
            + " map-making, at the new map room");
        best.persona().remember(day, "I became the town's cartographer", 6);
        FolkTalk.speak(best, FolkTalk.pick(level.getRandom(), "The town's maps are mine to draw. Every street of it, and everything round it.",
            "A cartographer! I'll walk every inch of this place, and put it on paper.", "Fresh paper, a steady hand and a long walk. I'll take it."));
        log(id, day, best.displayNameCap() + " became the town's cartographer");
        LOG.info("[MCA-CARTO] {} appoints {} its cartographer ({}, was {}; score {})", Villages.name(id), best.displayNameCap(), why, was,
            score(best));
        return best;
    }

    // ------------------------------------------------------------------ its day

    /** What a cartographer keeps in its pack and never banks: its sheets and maps, paper, compasses, panes, banners. */
    static int keeps(ItemStack s) {
        if (s.is(Items.FILLED_MAP) || s.is(Items.MAP)) return 64;
        if (s.is(Items.PAPER)) return 16;
        if (s.is(Items.COMPASS)) return 2;
        if (s.is(Items.GLASS_PANE)) return 9;
        if (s.get(DataComponents.FOOD) != null) return 8;
        return 0;
    }

    /** The aiStep's look (VillageFolkEntity): out on a survey, a round of it. */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        return MapSurveys.hold(f, level);
    }

    /** Out on a survey (the plot does not call it back, the rest of its day waits). */
    public static boolean surveying(VillageFolkEntity f) {
        return MapSurveys.surveying(f);
    }

    /**
     * The cartographer's day at the map room (its station brain): to the table; its table set; paper and a compass
     * made when the stores run short; then, in turn, a commission paid for, the hall's map when due, the region's
     * when due, and a look for the old places round the town. Returns whether it did something.
     */
    static boolean work(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return false;
        if (MapSurveys.of(id) != null) return MapSurveys.daylight(level.getDayTime());
        BlockPos table = tableAt(level, id);
        if (table == null) table = setTable(level, v, f);
        if (table != null && f.blockPosition().distSqr(table) > 3.5 * 3.5) {
            if (f.getNavigation().isDone()) f.walkTo(table, 0.8D);
            f.hobbyNow = "on its way to the map room";
            return true;
        }
        if (table != null) f.getLookControl().setLookAt(table.getX() + 0.5, table.getY() + 1.0, table.getZ() + 0.5);
        int last = LAST.getOrDefault(f.getUUID(), -100000);
        if (f.tickCount - last < PACE && f.tickCount >= last) return true;
        LAST.put(f.getUUID(), f.tickCount);
        long day = level.getDayTime() / 24000L;
        String did = next(level, v, f, day);
        if (did != null) {
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            level.playSound(null, f.blockPosition(), SoundEvents.VILLAGER_WORK_CARTOGRAPHER, SoundSource.NEUTRAL, 0.8F, 1.0F);
            f.brain(did);
            return true;
        }
        f.hobbyNow = FolkTalk.pick(f.getRandom(), "at the cartography table, going over the town's sheets", "inking the town's book of maps",
            "at the map room, matching the scouts' notes to the sheets");
        return true;
    }

    /** The next piece of the day's work, done: what, or null for none to do now. */
    @Nullable
    static String next(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day) {
        UUID id = v.id();
        long t = level.getDayTime() % 24000L;
        String made = makings(level, v, f);
        if (made != null) return made;
        // A walk is begun in the morning or the early afternoon, never with dusk coming on.
        boolean time = t >= 1400L && t < 8000L;
        if (time) {
            Commission c = open(id);
            if (c != null && MapSurveys.beginCommission(level, v, f, c, day) != null) {
                setState(id, c.player(), "WALKING", -1);
                return "set out to map the land " + c.way() + " for " + c.name();
            }
            String due = MapSurveys.wallDue(level, id, day);
            if (due != null && MapRoom.hall(id) != null && MapSurveys.beginWall(level, v, f, day) != null) return "set out to walk the town (" + due + ")";
            if (regionDue(id, day) && t < 4000L && MapSurveys.beginRegion(level, v, f, day) != null) return "set out round the country for the region's map";
        }
        int looked = LOOKED.getOrDefault(f.getUUID(), -100000);
        if (f.tickCount - looked >= LOOK_EVERY || f.tickCount < looked) {
            LOOKED.put(f.getUUID(), f.tickCount);
            String found = MapFinds.lookOnce(level, v, f, day);
            if (found != null) {
                log(id, day, "found " + found);
                return "found " + found;
            }
        }
        return null;
    }

    /** Is the region's map due: never drawn (and the town has somewhere to draw), or a fortnight old? */
    static boolean regionDue(UUID village, long day) {
        long drawn = MapSurveys.regionDay(village);
        if (drawn < 0) return MapRoom.hall(village) != null && MapSurveys.wall(village) != null;
        return day - drawn >= MapSurveys.REGION_EVERY;
    }

    /** The cartography table set down at the map room's spot for it, out of the stores (or made of two paper and four planks). */
    @Nullable
    static BlockPos setTable(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (mapRoom(v.id()) == null) return null;
        if (Crafts.stock(level, v, s -> s.is(Items.CARTOGRAPHY_TABLE)) == 0 && f.countCarried(s -> s.is(Items.CARTOGRAPHY_TABLE)) == 0) {
            if (Crafts.stock(level, v, s -> s.is(Items.PAPER)) < 2 || !Crafts.planks(level, v, 4)) return null;
            Economy.openCraft(v.id(), StationTask.CARTOGRAPHER);
            try {
                if (!Crafts.take(level, v, s -> s.is(Items.PAPER), 2)) return null;
                if (!Crafts.usePlanks(level, v, 4)) {
                    Crafts.store(level, v, new ItemStack(Items.PAPER, 2));
                    return null;
                }
                Crafts.store(level, v, new ItemStack(Items.CARTOGRAPHY_TABLE));
            } finally {
                Economy.closeCraft();
            }
        }
        return Trades.workstation(f, level, v, Blocks.CARTOGRAPHY_TABLE, s -> s.is(Items.CARTOGRAPHY_TABLE),
            com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.CARTOGRAPHY);
    }

    /**
     * Paper made of the stores' sugar cane (three cane, three sheets) when the map room's paper runs short, and a compass
     * of four iron and a redstone when it has none to spare: the game's own recipes, out of the stores and into them.
     * What was made, or null.
     */
    @Nullable
    static String makings(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        UUID id = v.id();
        String out = null;
        Economy.openCraft(id, StationTask.CARTOGRAPHER);
        try {
            int paper = Crafts.stock(level, v, s -> s.is(Items.PAPER));
            int cane = Crafts.stock(level, v, s -> s.is(Items.SUGAR_CANE));
            if (paper < PAPER_KEPT && cane >= 3) {
                int batches = Math.min((PAPER_KEPT - paper + 2) / 3, Math.min(cane / 3, 4));
                if (batches > 0 && Crafts.take(level, v, s -> s.is(Items.SUGAR_CANE), batches * 3)) {
                    Crafts.store(level, v, new ItemStack(Items.PAPER, batches * 3));
                    count(id, "paper", batches * 3);
                    out = batches * 3 + " paper of the stores' sugar cane";
                }
            }
            // A compass is four iron: the first the map room has is made whatever the age is putting by; a second only
            // when the town is not saving its iron.
            int compasses = Crafts.stock(level, v, s -> s.is(Items.COMPASS));
            if (out == null && Villages.ageOf(id).ordinal() >= Villages.Age.STONE.ordinal() && compasses < COMPASSES_KEPT
                    && Crafts.stock(level, v, s -> s.is(Items.IRON_INGOT)) >= 4 && Crafts.stock(level, v, s -> s.is(Items.REDSTONE)) >= 1
                    && (compasses == 0 || !Crafts.savingIron(level, v))) {
                if (Crafts.take(level, v, s -> s.is(Items.IRON_INGOT), 4)) {
                    if (Crafts.take(level, v, s -> s.is(Items.REDSTONE), 1)) {
                        Crafts.store(level, v, new ItemStack(Items.COMPASS));
                        count(id, "compasses", 1);
                        out = "a compass of the stores' iron and redstone";
                    } else {
                        Crafts.store(level, v, new ItemStack(Items.IRON_INGOT, 4));
                    }
                }
            }
        } finally {
            Economy.closeCraft();
        }
        if (out != null) {
            f.note(AssistantEntity.Deed.THINGS_MADE, 1);
            if (f.getRandom().nextInt(3) == 0) FolkTalk.speak(f, out.startsWith("a compass") ? "A new compass for the explorer maps. It points true."
                : "Fresh paper, pressed from the cane. Smell that!");
        }
        return out;
    }

    // ------------------------------------------------------------------ commissions

    /** A map a player paid for: who, which way, when, what it paid, how it stands (OPEN, WALKING, READY, DONE), its map. */
    public record Commission(UUID player, String name, int bearing, String way, long day, int paid, String state, int map) {
        String encode() {
            return player + "|" + name.replace('|', ' ') + "|" + bearing + "|" + way + "|" + day + "|" + paid + "|" + state + "|" + map;
        }

        @Nullable
        static Commission decode(String s) {
            String[] p = s.split("\\|", -1);
            if (p.length < 8) return null;
            try {
                return new Commission(UUID.fromString(p[0]), p[1], Integer.parseInt(p[2]), p[3], Long.parseLong(p[4]), Integer.parseInt(p[5]), p[6],
                    Integer.parseInt(p[7]));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    public static List<Commission> commissions(UUID village) {
        List<Commission> out = new ArrayList<>();
        String s = Ledger.note(village, "carto.commissions");
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            Commission c = Commission.decode(line);
            if (c != null) out.add(c);
        }
        return out;
    }

    static void saveCommissions(UUID village, List<Commission> all) {
        while (all.size() > 12) all.remove(0);
        StringBuilder sb = new StringBuilder();
        for (Commission c : all) sb.append(sb.length() == 0 ? "" : "\n").append(c.encode());
        Ledger.note(village, "carto.commissions", sb.toString());
    }

    @Nullable
    static Commission open(UUID village) {
        for (Commission c : commissions(village)) if (c.state().equals("OPEN")) return c;
        return null;
    }

    static void setState(UUID village, UUID player, String state, int map) {
        List<Commission> all = commissions(village);
        for (int i = 0; i < all.size(); i++) {
            Commission c = all.get(i);
            if (!c.player().equals(player) || c.state().equals("DONE")) continue;
            all.set(i, new Commission(c.player(), c.name(), c.bearing(), c.way(), c.day(), c.paid(), state, map >= 0 ? map : c.map()));
            break;
        }
        saveCommissions(village, all);
    }

    static final String[] WAYS = { "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west" };

    /** The way asked for, 0 (north) to 7 (north-west) clockwise; -1 for none. */
    static int bearing(String t) {
        for (int i = 1; i < 8; i += 2) if (t.contains(WAYS[i]) || t.contains(WAYS[i].replace("-", " ")) || t.contains(WAYS[i].replace("-", ""))) return i;
        for (int i = 0; i < 8; i += 2) if (t.contains(WAYS[i])) return i;
        return -1;
    }

    /** What a commission costs: a sheet and a pane at their worth, and the day's walk out and back. */
    public static int commissionPrice() {
        return Math.max(4, (int) Math.round(Prices.each(Items.PAPER) + Prices.each(Items.GLASS_PANE)) + 4);
    }

    /** "Map me the land to the east": paid for now, walked today, left ready in the map room. */
    static String commission(VillageFolkEntity f, Player p, String t) {
        UUID id = f.ownerId();
        long day = f.level().getDayTime() / 24000L;
        for (Commission c : commissions(id)) {
            if (!c.player().equals(p.getUUID())) continue;
            if (c.state().equals("OPEN") || c.state().equals("WALKING")) return "I'm already mapping the land " + c.way() + " for you. Patience — it's a day's walk.";
        }
        int b = bearing(t);
        if (b < 0) return "Gladly — which way? Say \"map me the land to the east\", or north, south-west, any way you like.";
        int price = commissionPrice();
        int coins = Market.coinsHeld(p);
        if (coins < price) return "A map of the land " + "to the " + WAYS[b] + " is " + price + " coins — a day's walk and a sheet under glass. You've " + coins + ".";
        Market.payOut(p, price);
        f.earn(price - 1);
        Ledger.addCoins(id, 1);
        Economy.sold(id, price);
        List<Commission> all = commissions(id);
        all.add(new Commission(p.getUUID(), p.getName().getString(), b, "to the " + WAYS[b], day, price, "OPEN", -1));
        saveCommissions(id, all);
        count(id, "commissions", 1);
        f.persona().remember(day, p.getName().getString() + " asked me to map the land to the " + WAYS[b], 2);
        log(id, day, p.getName().getString() + " commissioned a map of the land to the " + WAYS[b]);
        LOG.info("[MCA-CARTO] {} commissions {} for a map of the land to the {} ({} coins)", p.getName().getString(), f.displayNameCap(), WAYS[b], price);
        return "The land to the " + WAYS[b] + " it is: " + price + " coins, thank you. I'll walk it "
            + (f.level().getDayTime() % 24000L < 8000L ? "today" : "first thing tomorrow") + ", and it'll be in the chest at the map room with your name on it.";
    }

    /** A commission's sheet done (MapSurveys.finish): named for its player, into the map room's chest, the player told. */
    static String commissionReady(ServerLevel level, Villages.Village v, VillageFolkEntity f, MapSurveys.Survey s, long day) {
        UUID id = v.id();
        ItemStack sheet = MapSurveys.takeSheet(f, s.sheets.get(0));
        if (sheet.isEmpty()) return "a commission's sheet was lost";
        String[] who = s.forWhom.split(";", 2);
        String name = who.length > 1 ? who[1] : "a traveller";
        sheet.set(DataComponents.CUSTOM_NAME, Component.literal("For " + name + ": " + MapFinds.capital(s.what)));
        int map = sheet.get(DataComponents.MAP_ID).id();
        BlockPos chest = readyChest(level, id);
        boolean in = false;
        if (chest != null && level.getBlockEntity(chest) instanceof Container c) {
            for (int i = 0; i < c.getContainerSize() && !in; i++) if (c.getItem(i).isEmpty()) { c.setItem(i, sheet); in = true; }
            c.setChanged();
        }
        if (!in) MapSurveys.give(f, sheet);
        try {
            UUID player = UUID.fromString(who[0]);
            setState(id, player, "READY", map);
            MapSurveys.tell(level, player, "Your map of " + s.what + " of " + Villages.name(id) + " is ready" + (in ? " in the chest at the map room." : ": ask "
                + f.displayNameCap() + " for it."));
        } catch (IllegalArgumentException ignored) {
            // nobody to tell
        }
        Villages.tell(id, day, f.displayNameCap() + " walked and drew " + s.what + " for " + name);
        log(id, day, "drew " + s.what + " for " + name);
        return "the commission for " + name + " (" + s.what + ") is ready";
    }

    /** A ready commission handed over: out of the map room's chest (or the cartographer's pack). What it says, or null for none. */
    @Nullable
    static String collect(ServerLevel level, VillageFolkEntity f, Player p) {
        UUID id = f.ownerId();
        for (Commission c : commissions(id)) {
            if (!c.player().equals(p.getUUID()) || !c.state().equals("READY")) continue;
            ItemStack got = ItemStack.EMPTY;
            for (BlockPos at : chests(level, id)) {
                if (!(level.getBlockEntity(at) instanceof Container box)) continue;
                for (int i = 0; i < box.getContainerSize(); i++) {
                    if (MapSurveys.isSheet(box.getItem(i), c.map())) {
                        got = box.getItem(i).copy();
                        box.setItem(i, ItemStack.EMPTY);
                        box.setChanged();
                        break;
                    }
                }
                if (!got.isEmpty()) break;
            }
            if (got.isEmpty()) got = MapSurveys.takeSheet(f, c.map());
            setState(id, p.getUUID(), "DONE", c.map());
            if (got.isEmpty()) return "You've had it already — it went out of the chest at the map room.";
            if (!p.getInventory().add(got)) p.drop(got, false);
            return "Here it is: the land " + c.way() + ", every step of it walked. Locked under glass, so it'll never fade.";
        }
        return null;
    }

    // ------------------------------------------------------------------ selling maps

    /** Is a line said to the cartographer about its maps? */
    public static boolean meant(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("map") || t.contains("chart") || t.contains("commission");
    }

    /** What a player says to the cartographer about maps: a sale, a commission, a collection, or the list. */
    public static String talk(VillageFolkEntity f, Player p, String text) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null || !(f.level() instanceof ServerLevel level)) return "Maps? Of where? There's no town here.";
        if (f.stationTask() != StationTask.CARTOGRAPHER) {
            VillageFolkEntity c = cartographer(id);
            return c == null ? (ready(id) ? "We've a map room, but nobody to draw in it yet." : "We've no cartographer. The storekeeper might draw you a rough map of the town.")
                : "Maps are " + c.displayNameCap() + "'s — our cartographer. Find " + c.displayNameCap() + " at the map room.";
        }
        long day = level.getDayTime() / 24000L;
        Standing.Title title = Standing.of(id, p.getUUID(), level.getGameTime()).title();
        if (Laws.banished(id, p.getUUID(), day) || title == Standing.Title.OUTCAST) return "Maps for you? So you can find your way back in? No.";
        String ready = collect(level, f, p);
        if (ready != null) return ready;
        String t = " " + text.toLowerCase(Locale.ROOT) + " ";
        if (t.contains("map me") || t.contains("map the land") || t.contains("land to the") || t.contains("commission")) return commission(f, p, t);
        if (t.contains("ocean") || t.contains("monument") || t.contains(" sea")) return sellExplorer(level, v, f, p, MapFinds.Place.MONUMENT);
        if (t.contains("woodland") || t.contains("mansion") || t.contains("forest")) return sellExplorer(level, v, f, p, MapFinds.Place.MANSION);
        if (t.contains("treasure") || t.contains("buried")) return sellTreasure(level, v, f, p);
        if (t.contains("of the town") || t.contains("town's map") || t.contains("town map") || t.contains("the hall") || t.contains("copy")) {
            return sellTownCopy(level, v, f, p);
        }
        MapFinds.Found x = MapFinds.knownByWords(id, t);
        if (x != null) return sellFound(level, v, f, p, x);
        return list(level, v, f);
    }

    /** What maps it has, and what they cost: the board over its table, in words. */
    static String list(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        MapRoom.Record r = MapRoom.record(id);
        int sheets = r == null ? 0 : r.frames().size();
        if (sheets > 0) out.add("a copy of the hall's map of the town (" + sheets + (sheets == 1 ? " sheet" : " sheets") + ", " + townPrice(sheets) + " coins)");
        out.add("an ocean explorer map, a woodland explorer map or a treasure map (by how far, if the town's been far enough)");
        List<String> known = new ArrayList<>();
        for (MapFinds.Found x : MapFinds.found(id)) if (!known.contains(x.label())) known.add(x.label());
        if (!known.isEmpty()) out.add("a map to any place we know of: " + JobMarket.join(known.subList(Math.max(0, known.size() - 5), known.size())));
        out.add("or a map made to order: \"map me the land to the east\", " + commissionPrice() + " coins, ready by nightfall");
        return "Maps, fresh from the table: " + String.join("; ", out) + ". Which will it be?";
    }

    /** The price of a copy of the hall's map: a sheet of paper each, and a coin each for the copying. */
    static int townPrice(int sheets) {
        return Math.max(2, (int) Math.ceil(sheets * (Prices.each(Items.PAPER) + 1.0)));
    }

    /** Coin paid for a map: the makings' worth into the treasury, the rest (the work) the cartographer's. */
    static void paid(Villages.Village v, VillageFolkEntity f, Player p, int price, double makings) {
        Market.payOut(p, price);
        int treasury = Math.min(price, (int) Math.round(makings));
        if (treasury > 0) Ledger.addCoins(v.id(), treasury);
        f.earn(price - treasury);
        Economy.sold(v.id(), price);
        count(v.id(), "sold", 1);
        count(v.id(), "coin", price);
    }

    static double explorerMakings() {
        return Prices.each(Items.PAPER) * 8 + Prices.each(Items.COMPASS);
    }

    /** An explorer map to the nearest of a place the town knows of (or finds now, as far as it has been), for coin. */
    static String sellExplorer(ServerLevel level, Villages.Village v, VillageFolkEntity f, Player p, MapFinds.Place place) {
        UUID id = v.id();
        MapFinds.Found known = null;
        for (MapFinds.Found x : MapFinds.found(id)) {
            if (x.place() == place && (known == null || Scouts.flat(x.at(), v.centre()) < Scouts.flat(known.at(), v.centre()))) known = x;
        }
        MapFinds.Spot s = known != null ? new MapFinds.Spot(known.at(), MapFinds.structureAt(level, known)) : MapFinds.search(level, v, place, v.centre());
        if (s == null) {
            return place == MapFinds.Place.MONUMENT ? "No monument that I know of. The scouts have not been far enough out to sea — or not that far yet. Ask me again when they have."
                : "No " + place.bare() + " in any land the town has seen. When the scouts have been further, ask me again.";
        }
        return sell(level, v, f, p, place, s, known == null);
    }

    static String sellFound(ServerLevel level, Villages.Village v, VillageFolkEntity f, Player p, MapFinds.Found x) {
        return sell(level, v, f, p, x.place(), new MapFinds.Spot(x.at(), MapFinds.structureAt(level, x)), false);
    }

    /** The sale itself: the price by how far and how rare, the makings out of the stores, the map made and handed over. */
    static String sell(ServerLevel level, Villages.Village v, VillageFolkEntity f, Player p, MapFinds.Place place, MapFinds.Spot s, boolean fresh) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        int dist = (int) Math.sqrt(Scouts.flat(v.centre(), s.at()));
        int price = Prices.mapOf(dist, place.rarity);
        int coins = Market.coinsHeld(p);
        String what = place == MapFinds.Place.MONUMENT ? "An ocean explorer map" : place == MapFinds.Place.MANSION ? "A woodland explorer map"
            : place == MapFinds.Place.TREASURE ? "A treasure map" : "A map to " + MapFinds.label(place, s);
        if (coins < price) return what + " — " + dist + " blocks " + Guide.direction(v.centre(), s.at()) + " — is " + price + " coins. You've " + coins + ".";
        if (!MapFinds.payForExplorer(level, v)) return "I'd draw it gladly, but the stores haven't the makings: eight paper and a compass for a map that points the way.";
        ItemStack map = MapFinds.explorerMap(level, v, place, s, f.displayNameCap(), day);
        paid(v, f, p, price, explorerMakings());
        if (!p.getInventory().add(map)) p.drop(map, false);
        if (fresh) MapFinds.record(id, new MapFinds.Found(place, s.at(), day, MapFinds.Hands.TOWN, true, MapFinds.label(place, s)));
        f.persona().remember(day, "I sold " + p.getName().getString() + " " + what.toLowerCase(Locale.ROOT), 1);
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 1);
        log(id, day, "sold " + p.getName().getString() + " " + what.toLowerCase(Locale.ROOT));
        level.playSound(null, f.blockPosition(), SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.NEUTRAL, 1.0F, 1.0F);
        LOG.info("[MCA-CARTO] {} sold {} {} ({} blocks {}, {} coins)", f.displayNameCap(), p.getName().getString(), place, dist,
            Guide.direction(v.centre(), s.at()), price);
        return what + ", " + price + " coins. " + MapFinds.capital(MapFinds.label(place, s)) + " lies " + dist + " blocks "
            + Guide.direction(v.centre(), s.at()) + " — follow the mark. Mind how you go.";
    }

    /** A buried treasure map: one the town brought home (a wreck's), else to treasure the town found itself. */
    static String sellTreasure(ServerLevel level, Villages.Village v, VillageFolkEntity f, Player p) {
        ItemStack kept = Crafts.takeOne(level, v, Cartographers::treasureMap);
        if (!kept.isEmpty()) {
            BlockPos at = MapFinds.target(kept);
            int dist = at == null ? 200 : (int) Math.sqrt(Scouts.flat(v.centre(), at));
            int price = Prices.mapOf(dist, MapFinds.Place.TREASURE.rarity);
            int coins = Market.coinsHeld(p);
            if (coins < price) {
                Crafts.store(level, v, kept);
                return "We've a treasure map from a wreck: " + price + " coins. You've " + coins + ".";
            }
            paid(v, f, p, price, 0);
            if (!p.getInventory().add(kept)) p.drop(kept, false);
            log(v.id(), level.getDayTime() / 24000L, "sold " + p.getName().getString() + " a wreck's treasure map");
            return "A treasure map out of a wreck, " + price + " coins. The cross is where it's buried. Bring a shovel.";
        }
        return sellExplorer(level, v, f, p, MapFinds.Place.TREASURE);
    }

    static boolean treasureMap(ItemStack s) {
        if (!s.is(Items.FILLED_MAP)) return false;
        Component n = s.get(DataComponents.ITEM_NAME);
        if (n != null && n.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc && tc.getKey().equals("filled_map.buried_treasure")) return true;
        return MapFinds.marks(s).contains(MapDecorationTypes.RED_X) && n != null && n.getString().toLowerCase(Locale.ROOT).contains("treasure");
    }

    /** A copy of the hall's map, every sheet (the same maps, locked: they never fade), a sheet of the stores' paper each. */
    static String sellTownCopy(ServerLevel level, Villages.Village v, VillageFolkEntity f, Player p) {
        UUID id = v.id();
        List<ItemStack> wall = MapRoom.mapsForTests(level, id);
        wall.removeIf(ItemStack::isEmpty);
        if (wall.isEmpty() || MapSurveys.wall(id) == null) return "I've not walked the town for the hall yet. Give me a day or two.";
        int price = townPrice(wall.size());
        int coins = Market.coinsHeld(p);
        if (coins < price) return "A copy of the hall's map — " + wall.size() + " sheets — is " + price + " coins. You've " + coins + ".";
        Economy.openCraft(id, StationTask.CARTOGRAPHER);
        try {
            if (!Crafts.take(level, v, s -> s.is(Items.PAPER), wall.size())) return "Not paper enough in the stores to copy it: a sheet each.";
        } finally {
            Economy.closeCraft();
        }
        paid(v, f, p, price, Prices.each(Items.PAPER) * wall.size());
        for (ItemStack s : wall) {
            ItemStack copy = new ItemStack(Items.FILLED_MAP);
            copy.set(DataComponents.MAP_ID, s.get(DataComponents.MAP_ID));
            if (s.get(DataComponents.ITEM_NAME) != null) copy.set(DataComponents.ITEM_NAME, s.get(DataComponents.ITEM_NAME));
            copy.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("A copy of the hall's map, by " + f.displayNameCap()).withStyle(ChatFormatting.GRAY))));
            Economy.tally(id, StationTask.CARTOGRAPHER, copy, 1, true);
            if (!p.getInventory().add(copy)) p.drop(copy, false);
        }
        log(id, level.getDayTime() / 24000L, "copied the hall's map for " + p.getName().getString());
        return "There — the town on " + wall.size() + " sheets, copied from the hall's. Hang them " + (wall.size() == 9 ? "three by three" : "two by two")
            + ", north-west at the top left, and they'll meet up as they do in the hall.";
    }

    // ------------------------------------------------------------------ quests, caravans and the cave team

    /**
     * A quest taken on that sends a player somewhere well away from the town (QuestRun.accept): a map to it from the
     * map room, a locator map centred on the place with a cross on it, out of the stores' paper and a compass. The
     * player is given it there and then; nothing if the town has no cartographer, no makings, or the place is close.
     */
    public static void questMap(ServerLevel level, QuestBook.Quest q, Player p) {
        try {
            UUID id = q.village;
            Villages.Village v = id == null ? null : Villages.get(id);
            VillageFolkEntity c = cartographer(id);
            if (v == null || c == null) return;
            QuestBook.Step to = null;
            for (QuestBook.Step s : q.steps) {
                if (s.at == null || s.done || !s.dim.equals(level.dimension().location().toString())) continue;
                if (s.type == QuestBook.StepType.GO || s.type == QuestBook.StepType.FIND || s.type == QuestBook.StepType.KILL
                        || s.type == QuestBook.StepType.SEAL || s.type == QuestBook.StepType.LIGHT) { to = s; break; }
            }
            if (to == null) return;
            int dist = (int) Math.sqrt(Scouts.flat(v.centre(), to.at));
            if (dist < 48 || !MapFinds.payForExplorer(level, v)) return;
            ItemStack map = MapItem.create(level, to.at.getX(), to.at.getZ(), (byte) (dist > 300 ? 2 : 1), true, true);
            MapItem.renderBiomePreviewMap(level, map);
            MapItemSavedData.addTargetDecoration(map, to.at, "+", MapDecorationTypes.TARGET_X);
            map.set(DataComponents.ITEM_NAME, Component.literal("A map for: " + q.title));
            map.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Drawn by " + c.displayNameCap() + ", cartographer of " + Villages.name(id)).withStyle(ChatFormatting.GRAY),
                Component.literal(to.text).withStyle(ChatFormatting.GRAY))));
            Economy.tally(id, StationTask.CARTOGRAPHER, map, 1, true);
            if (!p.getInventory().add(map)) p.drop(map, false);
            p.sendSystemMessage(Component.literal(c.displayNameCap() + " the cartographer sends you a map to it: " + dist + " blocks "
                + Guide.direction(v.centre(), to.at) + ", the place marked with a cross.").withStyle(ChatFormatting.YELLOW));
            count(id, "questmaps", 1);
            log(id, level.getDayTime() / 24000L, "drew a map for " + p.getName().getString() + "'s quest (" + q.title + ")");
        } catch (RuntimeException e) {
            LOG.debug("[MCA-CARTO] no map for a quest: {}", e.toString());
        }
    }

    /**
     * A caravan or an envoy on the road (Caravans.drive, once a second): setting out, it is given a copy of the region's
     * map from the map room; on the road, its copy fills in as it goes, wherever the ground about it is loaded.
     */
    public static void onTheRoad(VillageFolkEntity f, ServerLevel level, Caravans.Trip t) {
        if (f.tickCount % 20 != 0) return;
        UUID home = f.ownerId();
        Villages.Village v = home == null ? null : Villages.get(home);
        if (v == null || !t.from.equals(home)) return;
        if (!t.back && t.at <= 1 && MapSurveys.roadCopy(level, v, f)) {
            FolkTalk.speak(f, "A copy of the country's map for the road — the cartographer's. I'll fill the roads in for it.");
        }
        MapSurveys.walkWith(level, f);
    }

    /** The next place the cartographer found for the cave team that the team has not yet been led to, nearest first; or null. */
    @Nullable
    public static BlockPos caveLead(UUID village, BlockPos home) {
        MapFinds.Found best = null;
        for (MapFinds.Found x : MapFinds.found(village)) {
            if (x.hands() != MapFinds.Hands.CAVE_TEAM || Ledger.note(village, "carto.led/" + x.at().asLong()) != null) continue;
            if (best == null || Scouts.flat(x.at(), home) < Scouts.flat(best.at(), home)) best = x;
        }
        return best == null ? null : best.at();
    }

    /** The cave team led to it (CaveDwellers.setOut): not again. */
    public static void ledTo(UUID village, BlockPos at, long day) {
        Ledger.note(village, "carto.led/" + at.asLong(), Long.toString(day));
    }

    // ------------------------------------------------------------------ the town's books, the board, the talk

    /** A tally of the map room's work, kept with the town ("sheets", "walls", "found", "sold", "coin"...). */
    static void count(UUID village, String key, int n) {
        if (n == 0) return;
        Ledger.note(village, "carto.n/" + key, Integer.toString(counted(village, key) + n));
    }

    public static int counted(UUID village, String key) {
        try {
            String s = Ledger.note(village, "carto.n/" + key);
            return s == null || s.isEmpty() ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The map room's day book: the last twenty things done, for the gazette and the Maps page. */
    static void log(UUID village, long day, String text) {
        String s = Ledger.note(village, "carto.log");
        List<String> lines = new ArrayList<>();
        if (s != null && !s.isEmpty()) lines.addAll(List.of(s.split("\n")));
        lines.add(day + "|" + text.replace('\n', ' ').replace('|', ' '));
        while (lines.size() > 20) lines.remove(0);
        Ledger.note(village, "carto.log", String.join("\n", lines));
    }

    static List<String[]> logLines(UUID village) {
        List<String[]> out = new ArrayList<>();
        String s = Ledger.note(village, "carto.log");
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            String[] p = line.split("\\|", 2);
            if (p.length == 2) out.add(p);
        }
        return out;
    }

    /** What it is doing, in its own words (FolkTalk.doing). */
    public static String doing(VillageFolkEntity f, RandomSource r) {
        UUID id = f.ownerId();
        MapSurveys.Survey s = id == null ? null : MapSurveys.of(id);
        if (s != null && MapSurveys.daylight(f.level().getDayTime())) {
            return switch (s.kind()) {
                case WALL -> FolkTalk.pick(r, "Walking the town with the hall's sheets. Watch — it fills in as I go, street by street.",
                    "Out with the new map of the town. Every street walked, or it's not a map.");
                case REGION -> "Out round the country with the region's sheet. It's a long walk, but the map's worth it.";
                case COMMISSION -> "Mapping " + s.what + " — a commission. It'll be ready by tonight.";
            };
        }
        return FolkTalk.pick(r, "At the cartography table. The town on paper, and everything round it.",
            "Matching the scouts' notes to my sheets. There's a lot out there nobody's drawn.",
            "Drawing. Ask me for a map — ocean, woodland, treasure, or the land any way you like.");
    }

    /** Its card's line: the survey under way, or the hall's map and what it has found and sold. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        if (f.stationTask() != StationTask.CARTOGRAPHER || f.ownerId() == null) return null;
        UUID id = f.ownerId();
        long day = f.level().getDayTime() / 24000L;
        List<String> out = new ArrayList<>();
        MapSurveys.Survey s = MapSurveys.of(id);
        if (s != null) out.add("out on a survey: " + s.kind().words + ", stop " + Math.min(s.stops(), s.stop() + 1) + " of " + s.stops());
        MapSurveys.Wall w = MapSurveys.wall(id);
        if (w != null) {
            long next = Math.max(0, w.day() + MapSurveys.WALL_EVERY - day);
            out.add("the hall's map drawn day " + (w.day() + 1) + " (" + w.across() + " by " + w.across() + ", 1:" + (1 << w.scale()) + "), the next "
                + (next == 0 ? "due" : "in " + next + (next == 1 ? " day" : " days")));
        }
        int found = MapFinds.found(id).size(), sold = counted(id, "sold");
        if (found > 0) out.add(found + (found == 1 ? " place found" : " places found"));
        if (sold > 0) out.add(sold + (sold == 1 ? " map sold" : " maps sold"));
        String waits = MapSurveys.WANTS.get(id);
        if (waits != null) out.add("waits on " + waits);
        return out.isEmpty() ? "new to the map room" : MapFinds.capital(String.join("; ", out)) + ".";
    }

    /** The board's line: the hall's map, the region's, and the latest find. Null for a town with no cartographer and no maps. */
    @Nullable
    public static String boardLine(UUID village) {
        VillageFolkEntity c = cartographer(village);
        MapSurveys.Wall w = MapSurveys.wall(village);
        List<MapFinds.Found> found = MapFinds.found(village);
        if (c == null && w == null && found.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("Maps: ");
        sb.append(w == null ? "the hall's map not yet walked" : "the hall's map of day " + (w.day() + 1) + " (" + w.across() * w.across() + " sheets)");
        long region = MapSurveys.regionDay(village);
        if (region >= 0) sb.append("; the country's, day ").append(region + 1);
        if (!found.isEmpty()) {
            MapFinds.Found x = found.get(found.size() - 1);
            Villages.Village v = Villages.get(village);
            String where = v == null ? "" : " " + (int) Math.sqrt(Scouts.flat(v.centre(), x.at())) + " " + Guide.direction(v.centre(), x.at());
            sb.append("; ").append(found.size()).append(" found, latest ").append(x.label()).append(where).append(" (").append(x.hands().words).append(")");
        }
        return sb.append('.').toString();
    }

    /** The gazette's "From the map room": yesterday's maps, finds and sales. Null for nothing. */
    @Nullable
    public static String gazette(UUID village, long day) {
        List<String> got = new ArrayList<>();
        for (String[] l : logLines(village)) {
            try {
                if (Long.parseLong(l[0]) == day - 1) got.add(MapFinds.capital(l[1]) + ".");
            } catch (NumberFormatException ignored) {
                // an unreadable line
            }
        }
        if (got.isEmpty()) return null;
        VillageFolkEntity c = cartographer(village);
        return "§lFrom the map room§r\n" + (c == null ? "" : c.displayNameCap() + ", cartographer: ") + String.join(" ", got.subList(0, Math.min(4, got.size())));
    }

    /** The trade book's notes: what the map room has learnt, and its numbers (TradeBooks.notes). */
    public static List<String> bookNotes(UUID village) {
        List<String> out = new ArrayList<>();
        int sheets = counted(village, "sheets"), walls = counted(village, "walls"), found = counted(village, "found"), sold = counted(village, "sold");
        int coin = counted(village, "coin"), paper = counted(village, "paper");
        out.add("Walk every sheet you draw. A map fills in a hundred and twenty-odd blocks round whoever carries it, and only what you walk near: "
            + "stand at each station till the sheet's done there, and lock it under glass when it's finished, or it'll go on changing on the wall.");
        if (walls > 0) out.add("We've hung " + com.jrpetty.mcassistant.village.Quill.count(walls, "map of the town", "maps of the town") + " in the hall, "
            + com.jrpetty.mcassistant.village.Quill.count(sheets, "sheet", "sheets") + " walked in all. The old ones are in the archive: look how we've grown.");
        if (found > 0) out.add("Between us we've found " + com.jrpetty.mcassistant.village.Quill.count(found, "old place", "old places")
            + " round the town. The cave team gets the mineshafts and the dungeons, the scouts the villages and the temples: a map each, to follow.");
        if (sold > 0) out.add("We've sold " + com.jrpetty.mcassistant.village.Quill.count(sold, "map", "maps") + " to travellers, for " + coin
            + " coins. Price a map by how far and how rare; never sell what the scouts haven't seen.");
        if (paper > 0) out.add("We've pressed " + paper + " sheets of paper from the town's cane. Keep two dozen by; a wall of nine eats them.");
        return out;
    }

    // ------------------------------------------------------------------ the Maps page

    /**
     * The Maps page of the town's books: the picture (the region's sheet as drawn, or the hall's map put together), what
     * it covers, the places found on it, the hall's map and its archive, and the map room's numbers.
     */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        VillageFolkEntity c = cartographer(id);
        out.putString("by", c == null ? "" : c.displayNameCap());
        out.putBoolean("wanted", wanted(id));
        out.putBoolean("room", mapRoom(id) != null);
        out.putInt("hx", v.centre().getX());
        out.putInt("hz", v.centre().getZ());
        // The picture.
        int region = MapSurveys.regionMap(id);
        MapItemSavedData rd = region < 0 ? null : level.getMapData(new MapId(region));
        if (rd != null) {
            picture(out, rd.colors, rd.centerX, rd.centerZ, MapSurveys.width(rd.scale), "The country round " + Villages.name(id));
        } else {
            MapRoom.Record r = MapRoom.record(id);
            MapSurveys.Wall w = MapSurveys.wall(id);
            if (r != null && w != null && r.frames().size() == w.across() * w.across()) {
                int across = w.across(), sw = MapSurveys.width(w.scale());
                byte[] big = new byte[128 * 128];
                List<ItemStack> maps = MapRoom.mapsForTests(level, id);
                for (int i = 0; i < maps.size(); i++) {
                    MapItemSavedData d = MapItem.getSavedData(maps.get(i), level);
                    if (d == null) continue;
                    int ox = (i % across) * 128 / across, oz = (i / across) * 128 / across;
                    for (int y = 0; y < 128 / across; y++) for (int x = 0; x < 128 / across; x++) {
                        big[(ox + x) + (oz + y) * 128] = d.colors[(x * across) + (y * across) * 128];
                    }
                }
                picture(out, big, v.centre().getX(), v.centre().getZ(), sw * across, "The town as the hall's map has it");
            }
        }
        // The places found.
        ListTag found = new ListTag();
        for (MapFinds.Found x : MapFinds.found(id)) {
            CompoundTag t = new CompoundTag();
            t.putString("what", x.label());
            t.putString("kind", x.place().name());
            t.putInt("x", x.at().getX());
            t.putInt("z", x.at().getZ());
            t.putLong("day", x.day());
            t.putString("to", x.hands().words);
            t.putBoolean("mapped", x.mapped());
            found.add(t);
        }
        out.put("found", found);
        // The lines.
        ListTag lines = new ListTag();
        long day = level.getDayTime() / 24000L;
        MapSurveys.Wall w = MapSurveys.wall(id);
        lines.add(StringTag.valueOf(c == null ? (ready(id) ? "The map room stands, and waits on a cartographer." : wanted(id) ? "The town wants a map room."
            : "No cartographer yet: a Stone Age town with scouts, or of thirty, takes one up.") : c.displayNameCap() + " keeps the map room."));
        if (w != null) lines.add(StringTag.valueOf("The hall's map: " + w.across() + " by " + w.across() + " sheets at 1:" + (1 << w.scale()) + ", walked by "
            + w.by() + " and hung on day " + (w.day() + 1) + "; the next " + (Math.max(0, w.day() + MapSurveys.WALL_EVERY - day) == 0 ? "is due"
            : "in " + Math.max(0, w.day() + MapSurveys.WALL_EVERY - day) + " days") + "."));
        long rday = MapSurveys.regionDay(id);
        if (rday >= 0) lines.add(StringTag.valueOf("The country's map: drawn on day " + (rday + 1) + "; the caravans and envoys carry copies ("
            + counted(id, "roadcopies") + " given)."));
        MapSurveys.Survey s = MapSurveys.of(id);
        if (s != null) lines.add(StringTag.valueOf("Out now: " + s.kind().words + ", stop " + Math.min(s.stops(), s.stop() + 1) + " of " + s.stops() + "."));
        for (MapArchive.Kept k : MapArchive.kept(id)) {
            lines.add(StringTag.valueOf("Archive: " + Villages.name(id) + " in its " + MapArchive.ageWords(k.age()) + ", drawn day " + (k.drawn() + 1)
                + ", taken down day " + (k.down() + 1) + " (" + k.sheets() + " sheets): " + k.where() + "."));
        }
        for (Commission cm : commissions(id)) {
            if (cm.state().equals("DONE")) continue;
            lines.add(StringTag.valueOf("Commission: the land " + cm.way() + " for " + cm.name() + ", " + switch (cm.state()) {
                case "OPEN" -> "to be walked";
                case "WALKING" -> "being walked";
                default -> "ready at the map room";
            } + "."));
        }
        lines.add(StringTag.valueOf("Drawn " + counted(id, "sheets") + " sheets; found " + counted(id, "found") + " places ("
            + counted(id, "explorer") + " explorer maps given); sold " + counted(id, "sold") + " maps for " + counted(id, "coin") + " coins; "
            + counted(id, "commissions") + " commissions; " + counted(id, "questmaps") + " quest maps; " + counted(id, "paper") + " paper and "
            + counted(id, "compasses") + " compasses made."));
        String waits = MapSurveys.WANTS.get(id);
        if (waits != null) lines.add(StringTag.valueOf("Waits on " + waits + "."));
        for (String[] l : logLines(id)) {
            try {
                lines.add(StringTag.valueOf("Day " + (Long.parseLong(l[0]) + 1) + ": " + l[1] + "."));
            } catch (NumberFormatException ignored) {
                // an unreadable line
            }
        }
        out.put("lines", lines);
        return out;
    }

    private static void picture(CompoundTag out, byte[] colours, int cx, int cz, int span, String title) {
        out.putByteArray("colours", colours.clone());
        out.putInt("cx", cx);
        out.putInt("cz", cz);
        out.putInt("span", span);
        out.putString("title", title);
    }

    // ------------------------------------------------------------------ /village maps

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("maps")
            .executes(Cartographers::cmdPage)
            .then(Commands.literal("books").executes(Cartographers::cmdBooks))
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                ServerLevel level = ctx.getSource().getLevel();
                VillageFolkEntity f = cartographer(v.id());
                if (f == null) {
                    ctx.getSource().sendFailure(Component.literal("No cartographer in " + Villages.name(v.id()) + "."));
                    return 0;
                }
                String did = next(level, v, f, level.getDayTime() / 24000L);
                ctx.getSource().sendSuccess(() -> Component.literal(did == null ? "Nothing to do just now." : did), false);
                return 1;
            }))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                List<String> out = MapStage.stage(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()), v,
                    ctx.getSource().getEntity() instanceof ServerPlayer sp ? sp : null);
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                return out.size();
            }).then(Commands.literal("walk").executes(ctx -> {
                // The trade at work: the cartographer sent out on the region's walk now, for the pictures.
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                List<String> out = MapStage.walk(ctx.getSource().getLevel(), v);
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                return out.size();
            })));
    }

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int cmdPage(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        CompoundTag r = report(ctx.getSource().getLevel(), v);
        List<String> out = new ArrayList<>();
        out.add("The maps of " + Villages.name(v.id()) + ":");
        for (net.minecraft.nbt.Tag t : r.getList("lines", net.minecraft.nbt.Tag.TAG_STRING)) out.add(" " + t.getAsString());
        for (net.minecraft.nbt.Tag t : r.getList("found", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            out.add(" FOUND " + c.getString("what") + " at " + c.getInt("x") + " " + c.getInt("z") + " (day " + (c.getLong("day") + 1) + ", "
                + c.getString("to") + (c.getBoolean("mapped") ? ", mapped" : "") + ")");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
        return out.size();
    }

    private static int cmdBooks(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return cmdPage(ctx);
        CompoundTag books = Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "Maps");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    // ------------------------------------------------------------------ tests

    public static VillageFolkEntity appointForTests(ServerLevel level, Villages.Village v) {
        return appoint(level, v, level.getDayTime() / 24000L);
    }

    public static String makingsForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        return makings(level, v, f);
    }

    @Nullable
    public static MapSurveys.Survey beginWallForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        return MapSurveys.beginWall(level, v, f, level.getDayTime() / 24000L);
    }

    @Nullable
    public static MapSurveys.Survey beginRegionForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        return MapSurveys.beginRegion(level, v, f, level.getDayTime() / 24000L);
    }

    @Nullable
    public static MapSurveys.Survey beginCommissionForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        Commission c = open(v.id());
        if (c == null) return null;
        MapSurveys.Survey s = MapSurveys.beginCommission(level, v, f, c, level.getDayTime() / 24000L);
        if (s != null) setState(v.id(), c.player(), "WALKING", -1);
        return s;
    }

    @Nullable
    public static String setTableForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        BlockPos at = setTable(level, v, f);
        return at == null ? null : at.toShortString();
    }
}
