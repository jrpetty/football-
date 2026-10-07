package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.properties.RotationSegment;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [batchF] The public works fund: a statue for the square, paid for by the town's own people.
 *
 * <p>From the Stone Age, in a town of six or more, a fund is open for a statue on the square (a stepped
 * plinth and a figure of banded stone with a lantern at each front corner: blueprints/statue.txt). The board
 * shows how much is raised of the sixty coins it needs. Folk with something put by give a coin or three
 * of their savings now and then (the generous and the contented the readiest); a player gives by
 * right-clicking the board with village coins in hand (one coin a click, the whole stack crouching) or with
 * {@code /village donate <coins>}, and the town thinks the better of them for it. The coin given is kept in
 * the fund, not spent, until the sixty are raised: then it goes into the treasury, the chronicle says so,
 * and the statue goes on the town's build list, at the head of it. When it stands, a hand on the town's
 * works puts a sign before it (a sign out of the stores, or two planks) with the names of those who gave
 * the most, and how many more.
 */
public final class PublicFund {

    private PublicFund() {}

    public static final String STRUCTURE = "statue";
    /** What the statue costs the town's purse: sixty coins. */
    public static final int NEEDED = 60;
    /** A town opens its fund from this many folk. */
    static final int FROM = 6;

    private static final Map<UUID, Long> GAVE = new ConcurrentHashMap<>();

    public static void resetForTests() {
        GAVE.clear();
    }

    static CompoundTag fund(UUID village) {
        return Civics.sub(Civics.town(village), "fund");
    }

    static String word(@Nullable UUID village) {
        return "statue";
    }

    /** Is the fund open: the Stone Age, folk enough, no statue up and the money not yet raised? */
    static boolean open(UUID village) {
        CompoundTag f = fund(village);
        return Villages.ageOf(village).ordinal() >= Villages.Age.STONE.ordinal() && Villages.headcount(village) >= FROM
            && f.getLong("funded") == 0 && Villages.builtStructure(village, STRUCTURE) == null;
    }

    /** Is the statue paid for and still to go up (Villages.projectsWantedInOrder)? */
    public static boolean wanted(UUID village) {
        return fund(village).getLong("funded") > 0 && Villages.builtStructure(village, STRUCTURE) == null
            && !Villages.hasBuilt(village, STRUCTURE);
    }

    /** Why it goes up (Villages.whyBuild). */
    public static String why(UUID village) {
        CompoundTag f = fund(village);
        return "a statue on the square, paid for by the town's own people: " + f.getInt("raised") + " coins from "
            + f.getCompound("donors").size() + " of them";
    }

    // ------------------------------------------------------------------ giving

    /** A player gives: so many coins out of their pack into the fund. What to tell them. */
    public static String donate(ServerLevel level, Villages.Village v, Player p, int coins) {
        UUID id = v.id();
        CompoundTag f = fund(id);
        if (!open(id)) {
            if (f.getLong("funded") > 0) return "The statue fund is raised already: the statue goes up next. Thank you all the same!";
            if (Villages.builtStructure(id, STRUCTURE) != null) return Villages.name(id) + "'s statue stands already, thanks to its people.";
            return "There's no fund open in " + Villages.name(id) + " yet: it opens in the Stone Age, with six folk or more.";
        }
        int left = NEEDED - f.getInt("raised");
        int n = Math.min(coins, left);
        int have = Market.coinsHeld(p);
        if (have <= 0) return "You've no village coins to give.";
        n = Math.min(n, have);
        if (!Civics.takeCoins(p, n)) return "You've only " + have + " coins.";
        give(level, v, p.getName().getString(), n);
        // The town thinks the better of whoever gives to it.
        for (VillageFolkEntity folk : Civics.grown(id)) folk.persona().feelFor(p.getUUID(), p.getName().getString(), Math.min(3, 1 + n / 10));
        Standing.stir(id, p.getUUID());
        CompoundTag after = fund(id);
        return after.getLong("funded") > 0
            ? "You gave " + n + (n == 1 ? " coin" : " coins") + " — and that's the sixty! The statue goes on " + Villages.name(id) + "'s build list. Thank you!"
            : "You gave " + n + (n == 1 ? " coin" : " coins") + " to " + Villages.name(id) + "'s statue fund: " + after.getInt("raised")
                + " of " + NEEDED + " raised. Thank you!";
    }

    /** Coin into the fund, by name; at sixty, the fund is raised. */
    static void give(ServerLevel level, Villages.Village v, String who, int n) {
        if (n <= 0) return;
        CompoundTag f = fund(v.id());
        f.putInt("raised", f.getInt("raised") + n);
        CompoundTag donors = Civics.sub(f, "donors");
        donors.putInt(who, donors.getInt(who) + n);
        Civics.changed();
        if (f.getInt("raised") >= NEEDED) raised(level, v);
    }

    /** Once a day, of an evening: folk with savings give a little, the generous and the contented first; ten coins a day at most. */
    static void folkGive(ServerLevel level, Villages.Village v, long day) {
        int given = 0;
        for (VillageFolkEntity f : Civics.grown(v.id())) {
            if (given >= 10 || !open(v.id())) return;
            boolean generous = f.life().has(Social.Trait.GENEROUS);
            int keep = generous ? 20 : 40;                                    // what it keeps by, whatever the fund wants
            if (f.purse() < keep + 1 || !generous && f.persona().mood() < 60) continue;
            if (Math.floorMod(f.getUUID().hashCode() + day, generous ? 2 : 4) != 0) continue;     // not every day
            int n = Math.min(generous ? 3 : 1, f.purse() - keep);
            if (n <= 0 || !f.spend(n)) continue;
            give(level, v, f.displayNameCap(), n);
            CompoundTag me = Civics.folk(f.getUUID());
            me.putInt("gave", me.getInt("gave") + n);
            given += n;
            if (!f.isSleeping()) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "A coin for the statue fund.", "There — my bit for the statue."));
        }
    }

    /** Sixty raised: into the treasury, onto the build list, and into the chronicle. */
    static void raised(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag f = fund(id);
        if (f.getLong("funded") > 0) return;
        long day = Civics.day(level);
        int coins = f.getInt("raised");
        Ledger.addCoins(id, coins);
        f.putLong("funded", day + 1);
        Civics.changed();
        Villages.request(id, STRUCTURE);
        Villages.tell(id, day, "the statue fund was raised: " + coins + " coins from " + f.getCompound("donors").size()
            + " givers went into the treasury, and the statue goes up next");
        for (Player p : level.players()) {
            if (p.blockPosition().distSqr(v.centre()) < 160 * 160) {
                p.displayClientMessage(Component.literal(Villages.name(id) + "'s statue fund is raised: the statue goes up next!"), false);
            }
        }
    }

    // ------------------------------------------------------------------ the round of the town

    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = Civics.day(level), t = level.getDayTime() % 24000L;
        if (t >= 11000 && t < 13000 && GAVE.getOrDefault(id, -1L) != day) {         // of an evening, the day's wages in hand
            GAVE.put(id, day);
            if (open(id)) folkGive(level, v, day);
        }
        Ledger.Building statue = Villages.builtStructure(id, STRUCTURE);
        if (statue != null && !fund(id).getBoolean("plaque")) plaque(level, v, statue, false);
    }

    /**
     * The sign before the statue with its givers' names (those who gave most, and how many more), out of the
     * stores (a sign, or two planks) by a hand on the town's works; for nothing when {@code free} (the stage).
     */
    static boolean plaque(ServerLevel level, Villages.Village v, Ledger.Building statue, boolean free) {
        Direction front = statue.facing().getOpposite();
        BlockPos at = statue.anchor().relative(front, 3);
        if (!level.isLoaded(at)) return false;
        boolean up = level.getBlockState(at).getBlock() instanceof StandingSignBlock;
        if (!up) {
            if (!Petitions.clear(level, at)) return false;
            if (!free) {
                if (!TownJobs.atWork(level, v, "signs", at, "putting up the givers' names before the statue")) return false;
                if (!Crafts.sign(level, v)) return false;
            }
            level.setBlock(at, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, RotationSegment.convertToSegment(front)), 3);
        }
        if (!(level.getBlockEntity(at) instanceof SignBlockEntity sign)) return false;
        List<String> lines = plaqueLines(v.id());
        SignText face = new SignText(), back = new SignText();
        for (int i = 0; i < 4; i++) face = face.setMessage(i, Component.literal(lines.get(i)));
        for (int i = 0; i < 4; i++) back = back.setMessage(i, Component.literal(lines.get(4 + i)));
        sign.setText(face, true);
        sign.setText(back, false);
        sign.setWaxed(true);
        fund(v.id()).putBoolean("plaque", true);
        fund(v.id()).putLong("plaqueAt", at.asLong());
        Civics.changed();
        if (!free) Villages.tell(v.id(), Civics.day(level), "the names of those who paid for the statue were put up before it");
        return true;
    }

    /** The sign's eight lines, face then back: who gave most, and how many more. */
    static List<String> plaqueLines(UUID village) {
        CompoundTag donors = fund(village).getCompound("donors");
        List<String> names = new ArrayList<>(donors.getAllKeys());
        names.sort((a, b) -> donors.getInt(b) != donors.getInt(a) ? Integer.compare(donors.getInt(b), donors.getInt(a)) : a.compareTo(b));
        List<String> out = new ArrayList<>();
        out.add("Raised by");
        out.add("the people of");
        out.add(Villages.name(village));
        long funded = fund(village).getLong("funded");
        out.add(funded > 0 ? "day " + funded : "");
        // The back: the names, two a line, the most generous first.
        for (int line = 0; line < 4; line++) {
            int i = line * 2;
            if (line == 3 && names.size() > 8) {
                out.add("& " + (names.size() - 6) + " more");
                break;
            }
            String a = i < names.size() ? names.get(i) : "", b = i + 1 < names.size() ? names.get(i + 1) : "";
            out.add(b.isEmpty() ? a : a + ", " + b);
        }
        while (out.size() < 8) out.add("");
        return out;
    }

    // ------------------------------------------------------------------ where the player sees it

    static List<String> board(ServerLevel level, UUID village) {
        CompoundTag f = fund(village);
        if (open(village)) {
            return List.of("RN|The statue fund: " + f.getInt("raised") + " of " + NEEDED + " coins raised"
                + (f.getCompound("donors").isEmpty() ? "" : " (" + f.getCompound("donors").size() + " givers)")
                + ". Give with coins in hand here, or /village donate.");
        }
        if (wanted(village)) return List.of("RG|The statue fund is raised: the statue goes up next.");
        return List.of();
    }

    /** The town meeting's line on it, or null. */
    @Nullable
    static String meetingLine(UUID village) {
        CompoundTag f = fund(village);
        if (open(village)) return "The statue fund stands at " + f.getInt("raised") + " of " + NEEDED + " coins.";
        if (wanted(village)) return "The statue fund is raised, thanks to all of you; the statue goes up next.";
        return null;
    }

    static List<String> book(ServerLevel level, UUID village) {
        CompoundTag f = fund(village);
        if (!open(village) && f.getInt("raised") == 0) return List.of();
        CompoundTag donors = f.getCompound("donors");
        List<String> names = new ArrayList<>(donors.getAllKeys());
        names.sort((a, b) -> Integer.compare(donors.getInt(b), donors.getInt(a)));
        List<String> top = new ArrayList<>();
        for (String n : names.subList(0, Math.min(5, names.size()))) top.add(n + " " + donors.getInt(n));
        String state = f.getLong("funded") > 0 ? (Villages.builtStructure(village, STRUCTURE) != null ? "raised, and the statue stands" : "raised: the statue goes up next")
            : "open";
        return List.of("The statue fund (" + state + "): " + f.getInt("raised") + " of " + NEEDED + " coins"
            + (top.isEmpty() ? "" : "; the givers: " + String.join(", ", top) + (names.size() > 5 ? " and " + (names.size() - 5) + " more" : "")) + ".");
    }

    // ------------------------------------------------------------------ the tests

    /** Tests: {raised, needed, givers, funded (0/1)}. */
    public static int[] stateForTests(UUID village) {
        CompoundTag f = fund(village);
        return new int[]{ f.getInt("raised"), NEEDED, f.getCompound("donors").size(), f.getLong("funded") > 0 ? 1 : 0 };
    }

    /** Tests: the folk's daily giving, now. */
    public static void folkGiveForTests(ServerLevel level, Villages.Village v) {
        folkGive(level, v, Civics.day(level));
    }

    /** Tests: the plaque before the statue, put up now by the town's works (true if it stands). */
    public static boolean plaqueForTests(ServerLevel level, Villages.Village v) {
        Ledger.Building statue = Villages.builtStructure(v.id(), STRUCTURE);
        return statue != null && plaque(level, v, statue, false);
    }

    /** Tests: where the plaque stands, or null. */
    @Nullable
    public static BlockPos plaqueAtForTests(UUID village) {
        CompoundTag f = fund(village);
        return f.contains("plaqueAt") ? BlockPos.of(f.getLong("plaqueAt")) : null;
    }
}
